package com.intercom.video.twoway.service

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.NoopLogger
import com.intercom.video.twoway.core.pairing.PairingException
import com.intercom.video.twoway.core.pairing.PairingRole
import com.intercom.video.twoway.core.pairing.PairingSession
import com.intercom.video.twoway.core.pairing.VerifiedPeer
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.Message
import com.intercom.video.twoway.core.protocol.PairAccept
import com.intercom.video.twoway.core.protocol.PairConfirm
import com.intercom.video.twoway.core.protocol.PairDecline
import com.intercom.video.twoway.core.protocol.PairRequest
import com.intercom.video.twoway.core.util.NameSanitizer
import com.intercom.video.twoway.net.transport.SignalingChannel
import com.intercom.video.twoway.net.transport.SocketProvider
import com.intercom.video.twoway.net.transport.TcpSignalingClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** This device's identity for pairing: id, name, long-term public key and signing. */
interface LocalIdentityOps {
    val deviceId: String
    val name: String
    val publicKey: ByteArray
    fun sign(data: ByteArray): ByteArray
}

enum class PairingSaveResult { SAVED, IDENTITY_CHANGED }

/** Persists a successful pairing. Implementations refuse a known device that now presents a different identity key. */
fun interface PairingStore {
    fun savePaired(deviceId: String, name: String, identityPublicKey: ByteArray, secret: ByteArray): PairingSaveResult
}

/** Receives PAIR_* frames that arrive on the signaling port (the call engine hands them over). */
interface PairingRouter {
    fun onPairingMessage(conn: SignalingChannel, msg: Message)
    fun onPairingConnectionClosed(conn: SignalingChannel)
}

enum class PairingFailure { DECLINED, MISMATCH, TIMEOUT, UNREACHABLE, IDENTITY_CHANGED, QR_MISMATCH, ERROR }

sealed interface PairingUiState {
    data object Idle : PairingUiState

    /** We asked [peerName] to pair and wait for their answer. */
    data class Requesting(val peerName: String) : PairingUiState

    /** [peerName] asks to pair with us. */
    data class IncomingRequest(val peerId: String, val peerName: String) : PairingUiState

    /** Both users compare [code]; [awaitingPeer] is true after we pressed "Matches". */
    data class Verify(val peerName: String, val code: String, val awaitingPeer: Boolean) : PairingUiState

    data class Done(val peerName: String) : PairingUiState
    data class Failed(val peerName: String, val reason: PairingFailure) : PairingUiState
}

/** At most one pending request per source address at a time, and at most [maxPerMinute] requests per source per minute. */
class PairRateLimiter(private val clock: Clock, private val maxPerMinute: Int = 3) {
    private val recent = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(source: String): Boolean {
        val now = clock.nowMs()
        val times = recent.getOrPut(source) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= 60_000) times.removeFirst()
        if (times.size >= maxPerMinute) return false
        times.addLast(now)
        return true
    }
}

/**
 * Runs pairing handshakes (contracts/pairing.md) over the signaling port: this phone can start one with a nearby
 * device or answer one. Everything is on one thread; a handshake never lasts longer than [TIMEOUT_MS]. Only the
 * pairing secret and the peer's identity fingerprint are stored, and only after both users confirmed the 6-digit
 * code. No android.* imports, so loopback tests run it on the JVM.
 */
class PairingManager(
    private val identity: LocalIdentityOps,
    private val store: PairingStore,
    sockets: SocketProvider,
    private val clock: Clock,
    private val logger: Logger = NoopLogger,
) : PairingRouter {
    private class Active(val session: PairingSession, val conn: SignalingChannel, val startedMs: Long) {
        var peerName: String = ""
        var pendingRequest: PairRequest? = null
        var myConfirmed = false
        var peerVerified: VerifiedPeer? = null
        var expectedFingerprint: ByteArray? = null
        var finished = false
    }

    private val client = TcpSignalingClient(sockets, logger)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pairing-manager").apply { isDaemon = true } }
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "pairing-io").apply { isDaemon = true } }
    private val limiter = PairRateLimiter(clock)

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state

    private var active: Active? = null

    init {
        executor.scheduleWithFixedDelay({ safely { checkTimeout() } }, 1, 1, TimeUnit.SECONDS)
    }

    // --- public API ----------------------------------------------------------------------------------------

    /** Starts pairing with a nearby device. [expectedFingerprint] (16 bytes, from a scanned QR code) is checked at the end. */
    fun start(peer: PeerAddress, expectedFingerprint: ByteArray? = null) = post {
        if (active != null) return@post
        _state.value = PairingUiState.Requesting(NameSanitizer.sanitizeOr(peer.name, peer.deviceId.take(8)))
        val session = PairingSession(PairingRole.INITIATOR, identity.deviceId, identity.name)
        io.execute {
            val conn = client.connect(peer.host, peer.port)
            post {
                if (conn == null) {
                    _state.value = PairingUiState.Failed(peer.name, PairingFailure.UNREACHABLE)
                    return@post
                }
                val a = Active(session, conn, clock.nowMs()).also {
                    it.peerName = NameSanitizer.sanitizeOr(peer.name, peer.deviceId.take(8))
                    it.expectedFingerprint = expectedFingerprint
                }
                active = a
                conn.startReading({ body -> post { handleOutboundFrame(conn, body) } }, { post { onPairingConnectionClosed(conn) } })
                conn.send(JsonMessageCodec.encodeBytes(session.createRequest()))
            }
        }
    }

    /** The user accepts an incoming pairing request. */
    fun acceptIncoming() = post {
        val a = active ?: return@post
        val req = a.pendingRequest ?: return@post
        a.pendingRequest = null
        try {
            val reply = a.session.acceptRequest(req)
            a.conn.send(JsonMessageCodec.encodeBytes(reply))
            _state.value = PairingUiState.Verify(a.peerName, a.session.sas!!, awaitingPeer = false)
        } catch (e: PairingException) {
            fail(a, PairingFailure.ERROR)
        }
    }

    fun declineIncoming() = post {
        val a = active ?: return@post
        if (a.pendingRequest == null) return@post
        a.conn.send(JsonMessageCodec.encodeBytes(PairDecline("declined")))
        end(a)
        _state.value = PairingUiState.Idle
    }

    /** The user compared the codes: [matches] true = they are identical. */
    fun confirmMatches(matches: Boolean) = post {
        val a = active ?: return@post
        if (a.session.sas == null || a.myConfirmed) return@post
        if (!matches) {
            a.conn.send(JsonMessageCodec.encodeBytes(a.session.createConfirm(false, ByteArray(0)) { ByteArray(0) }))
            fail(a, PairingFailure.MISMATCH)
            return@post
        }
        a.myConfirmed = true
        a.conn.send(JsonMessageCodec.encodeBytes(a.session.createConfirm(true, identity.publicKey, identity::sign)))
        _state.value = PairingUiState.Verify(a.peerName, a.session.sas!!, awaitingPeer = true)
        if (a.peerVerified != null) finish(a)
    }

    /** Aborts whatever pairing is in progress (waiting for an answer, comparing codes...). */
    fun cancel() = post {
        active?.let { end(it) }
        _state.value = PairingUiState.Idle
    }

    /** Leaves a Done / Failed screen. */
    fun dismiss() = post {
        val s = _state.value
        if (s is PairingUiState.Done || s is PairingUiState.Failed) _state.value = PairingUiState.Idle
    }

    fun shutdown() {
        post { active?.let { end(it) } }
        executor.shutdown()
        io.shutdownNow()
    }

    // --- PairingRouter (called from the engine thread) --------------------------------------------------------

    override fun onPairingMessage(conn: SignalingChannel, msg: Message) = post {
        val a = active
        if (msg is PairRequest) {
            onRequest(conn, msg)
        } else if (a != null && a.conn === conn) {
            onReply(a, msg)
        } else {
            conn.close()
        }
    }

    override fun onPairingConnectionClosed(conn: SignalingChannel) = post {
        val a = active ?: return@post
        if (a.conn !== conn || a.finished) return@post
        val awaitingAnswer = _state.value is PairingUiState.Requesting
        fail(a, if (awaitingAnswer) PairingFailure.UNREACHABLE else PairingFailure.ERROR)
    }

    // --- handshake steps ------------------------------------------------------------------------------------------

    private fun handleOutboundFrame(conn: SignalingChannel, body: ByteArray) {
        val msg = try {
            JsonMessageCodec.decode(body)
        } catch (e: JsonException) {
            null
        } ?: return
        val a = active
        if (a != null && a.conn === conn) onReply(a, msg)
    }

    private fun onRequest(conn: SignalingChannel, req: PairRequest) {
        val source = conn.remoteHost
        if (active != null) {
            conn.send(JsonMessageCodec.encodeBytes(PairDecline("busy")))
            conn.close()
            return
        }
        if (!limiter.allow(source)) {
            conn.close()
            return
        }
        val session = PairingSession(PairingRole.RESPONDER, identity.deviceId, identity.name)
        val a = Active(session, conn, clock.nowMs())
        a.pendingRequest = req
        a.peerName = NameSanitizer.sanitizeOr(req.name, req.id.take(8))
        active = a
        _state.value = PairingUiState.IncomingRequest(req.id, a.peerName)
    }

    private fun onReply(a: Active, msg: Message) {
        when (msg) {
            is PairAccept -> {
                if (a.session.role != PairingRole.INITIATOR || a.session.sas != null) return
                try {
                    a.session.handleAccept(msg)
                    a.peerName = NameSanitizer.sanitizeOr(msg.name, msg.id.take(8))
                    _state.value = PairingUiState.Verify(a.peerName, a.session.sas!!, awaitingPeer = false)
                } catch (e: PairingException) {
                    fail(a, PairingFailure.ERROR)
                }
            }
            is PairDecline -> fail(a, PairingFailure.DECLINED)
            is PairConfirm -> {
                if (a.session.sas == null) return
                try {
                    a.peerVerified = a.session.verifyConfirm(msg)
                } catch (e: PairingException) {
                    // "does not match" from the peer, or a confirm that does not verify
                    fail(a, if (!msg.ok) PairingFailure.MISMATCH else PairingFailure.ERROR)
                    return
                }
                if (a.myConfirmed) finish(a)
            }
            else -> Unit
        }
    }

    private fun finish(a: Active) {
        if (a.finished) return
        val peer = a.peerVerified ?: return
        val expected = a.expectedFingerprint
        if (expected != null && !peer.fingerprint.copyOf(expected.size).contentEquals(expected)) {
            fail(a, PairingFailure.QR_MISMATCH)
            return
        }
        val peerId = a.session.peerId ?: return
        val result = try {
            store.savePaired(peerId, a.peerName, peer.identityPublicKey, a.session.pairingSecret())
        } catch (e: RuntimeException) {
            logger.e(TAG, "saving pairing failed", e)
            fail(a, PairingFailure.ERROR)
            return
        }
        if (result == PairingSaveResult.IDENTITY_CHANGED) {
            fail(a, PairingFailure.IDENTITY_CHANGED)
            return
        }
        val name = a.peerName
        end(a)
        _state.value = PairingUiState.Done(name)
    }

    private fun fail(a: Active, reason: PairingFailure) {
        if (a.finished) return
        val name = a.peerName
        end(a)
        _state.value = PairingUiState.Failed(name, reason)
    }

    private fun end(a: Active) {
        a.finished = true
        a.session.wipe()
        a.conn.close()
        if (active === a) active = null
    }

    private fun checkTimeout() {
        val a = active ?: return
        if (clock.nowMs() - a.startedMs >= TIMEOUT_MS) {
            if (a.pendingRequest != null) {
                end(a)
                _state.value = PairingUiState.Idle // an unanswered incoming request simply expires
            } else {
                fail(a, PairingFailure.TIMEOUT)
            }
        }
    }

    private fun post(block: () -> Unit) {
        try {
            executor.execute { safely(block) }
        } catch (_: RejectedExecutionException) {
            // shut down
        }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            logger.e(TAG, "pairing error", e)
        }
    }

    companion object {
        /** Overall time allowed for a handshake. */
        const val TIMEOUT_MS = 120_000L
        private const val TAG = "PairingManager"
    }
}
