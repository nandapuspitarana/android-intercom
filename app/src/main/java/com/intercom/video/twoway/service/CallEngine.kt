package com.intercom.video.twoway.service

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.NoopLogger
import com.intercom.video.twoway.core.call.CallRole
import com.intercom.video.twoway.core.call.CallSession
import com.intercom.video.twoway.core.call.CallState
import com.intercom.video.twoway.core.call.CallStateMachine
import com.intercom.video.twoway.core.call.Effect
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.core.call.MediaPath
import com.intercom.video.twoway.core.crypto.AesGcm
import com.intercom.video.twoway.core.crypto.CallKeys
import com.intercom.video.twoway.core.crypto.Direction
import com.intercom.video.twoway.core.crypto.Envelope
import com.intercom.video.twoway.core.media.AudioFactory
import com.intercom.video.twoway.core.media.AudioFormat
import com.intercom.video.twoway.core.protocol.Accept
import com.intercom.video.twoway.core.protocol.Busy
import com.intercom.video.twoway.core.protocol.Cancel
import com.intercom.video.twoway.core.protocol.Hangup
import com.intercom.video.twoway.core.protocol.Hello
import com.intercom.video.twoway.core.protocol.HubRegister
import com.intercom.video.twoway.core.protocol.HubRelayClose
import com.intercom.video.twoway.core.protocol.HubRelayOpen
import com.intercom.video.twoway.core.protocol.Invite
import com.intercom.video.twoway.core.protocol.InviteAuth
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.Message
import com.intercom.video.twoway.core.protocol.Mute
import com.intercom.video.twoway.core.protocol.PairAccept
import com.intercom.video.twoway.core.protocol.PairConfirm
import com.intercom.video.twoway.core.protocol.PairDecline
import com.intercom.video.twoway.core.protocol.PairRequest
import com.intercom.video.twoway.core.protocol.PairRequired
import com.intercom.video.twoway.core.protocol.Ping
import com.intercom.video.twoway.core.protocol.Pong
import com.intercom.video.twoway.core.protocol.Reject
import com.intercom.video.twoway.core.protocol.RelayFrame
import com.intercom.video.twoway.core.protocol.Ringing
import com.intercom.video.twoway.core.util.B64
import com.intercom.video.twoway.core.util.Hex
import com.intercom.video.twoway.core.util.NameSanitizer
import com.intercom.video.twoway.net.network.HubRouter
import com.intercom.video.twoway.net.network.RelayChannel
import com.intercom.video.twoway.net.network.RelayProvider
import com.intercom.video.twoway.net.transport.SignalingChannel
import com.intercom.video.twoway.net.transport.SignalingConnection
import com.intercom.video.twoway.net.transport.SocketProvider
import com.intercom.video.twoway.net.transport.TcpSignalingClient
import com.intercom.video.twoway.net.transport.TcpSignalingServer
import com.intercom.video.twoway.net.transport.UdpMediaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Where to reach a peer. Identity is proven later by the pairing secret, not by this address. */
data class PeerAddress(val deviceId: String, val name: String, val host: String, val port: Int)

data class LocalDevice(val deviceId: String, val name: String)

/** What the UI shows. Ended stays until [CallEngine.dismissEnded] or the next call. */
sealed interface CallUiState {
    data object Idle : CallUiState
    data class Calling(val peerName: String, val ringing: Boolean) : CallUiState
    data class Incoming(val peerId: String, val peerName: String) : CallUiState
    data class Connecting(val peerName: String) : CallUiState
    data class InCall(
        val peerName: String,
        val connectedAtMs: Long,
        val muted: Boolean,
        val peerMuted: Boolean,
        /** No voice packets from the peer for 3 s: the call is still up but the connection is poor. */
        val poorConnection: Boolean = false,
        /** The microphone is being captured and sent right now (drives the on-screen indicator). */
        val micLive: Boolean = false,
        val route: com.intercom.video.twoway.core.media.AudioRoute = com.intercom.video.twoway.core.media.AudioRoute.EARPIECE,
        /** Routes the user can pick from (Bluetooth only with a headset connected). */
        val availableRoutes: Set<com.intercom.video.twoway.core.media.AudioRoute> = emptySet(),
    ) : CallUiState
    data class Ended(val peerName: String, val reason: EndReason) : CallUiState
}

/** Pairing secrets. Returns null when the device is unknown or revoked, so no call can be made or received. */
fun interface TrustStore {
    fun pairingSecret(deviceId: String): ByteArray?
}

class CallRecord(val peerId: String, val peerName: String, val outgoing: Boolean, val reason: EndReason, val startedWallMs: Long, val connectedMs: Long)

fun interface CallEventSink {
    fun onCallEnded(record: CallRecord)

    companion object {
        val None = CallEventSink { }
    }
}

/**
 * Owns call state and wires signaling, crypto, UDP media and audio together. All state changes happen on
 * one engine thread; network reader threads only post events to it. It lives in [ListenerService] on
 * Android and runs unchanged on the JVM for loopback tests (no android.* imports).
 */
class CallEngine(
    private val self: () -> LocalDevice,
    private val trust: TrustStore,
    private val sockets: SocketProvider,
    private val audio: AudioFactory,
    private val clock: Clock,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = NoopLogger,
    private val sink: CallEventSink = CallEventSink.None,
    private val random: SecureRandom = SecureRandom(),
) {
    private class ActiveCall(val session: CallSession) {
        var conn: SignalingChannel? = null
        var udp: UdpMediaSocket? = null
        var media: MediaSession? = null
        var invite: Invite? = null
        var sendCipher: AesGcm? = null
        var recvCipher: AesGcm? = null
        var badFrames = 0
    }

    private val machine = CallStateMachine(clock)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "call-engine").apply { isDaemon = true } }
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "call-engine-io").apply { isDaemon = true } }
    private val client = TcpSignalingClient(sockets, logger)

    /** Inbound connection limits; a hub raises the concurrent limit because every registered phone keeps a connection. */
    val connectionLimiter = com.intercom.video.twoway.net.transport.ConnectionLimiter(clock)
    private val server = TcpSignalingServer(sockets, clock, logger, ::onInboundConnection, connectionLimiter)

    private val _state = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val state: StateFlow<CallUiState> = _state

    private var call: ActiveCall? = null

    @Volatile
    private var started = false

    /**
     * When false (the user turned off background listening and the app is not on screen) a verified INVITE is
     * answered with REJECT("unavailable") instead of ringing.
     */
    @Volatile
    var acceptingCalls = true

    /** Where PAIR_* frames go (set by the runtime). Without a router they are dropped and the connection closed. */
    @Volatile
    var pairingRouter: PairingRouter? = null

    /** When true, INVITEs from unknown/unpaired/revoked devices are dropped silently instead of answered PAIR_REQUIRED. */
    @Volatile
    var autoRejectUnknown = false

    /** Where HUB_* and RELAY frames go when this phone is the hotspot hub. Without a router they are refused. */
    @Volatile
    var hubRouter: HubRouter? = null

    /** The hub client of a phone that joined a hotspot: used when a direct connection to the callee is impossible. */
    @Volatile
    var relayProvider: RelayProvider? = null

    /** "h" while this phone is the hotspot hub, otherwise "p"; answered to HELLO probes. */
    @Volatile
    var roleProvider: () -> String = { "p" }

    /** Port the signaling server is bound to; -1 before [start]. */
    val signalingPort: Int get() = server.port

    /** Live voice counters of the current call, for tests and diagnostics. */
    val currentMedia: MediaSession? get() = call?.media

    // --- lifecycle ------------------------------------------------------------------------------

    /** Starts listening; returns the bound signaling port (45678 when free). */
    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        if (started) return server.port
        val port = server.start(preferredPort)
        started = true
        executor.scheduleWithFixedDelay({ safely { onTick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        return port
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        try {
            executor.submit { safely { perform(machine.userHangup()) } }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // best effort: the call is torn down below either way
        }
        server.stop()
        executor.shutdownNow()
        io.shutdownNow()
        call?.let { cleanup(it) }
    }

    // --- public API (callable from any thread) -----------------------------------------------------

    fun call(peer: PeerAddress) = post { placeCall(peer) }

    fun accept() = post { perform(machine.userAccept()) }

    fun reject() = post { perform(machine.userReject()) }

    /** Cancels a ringing outgoing call, rejects an incoming one, or hangs up an established call. */
    fun hangup() = post { perform(machine.userHangup()) }

    fun dismissEnded() = post {
        if (_state.value is CallUiState.Ended) _state.value = CallUiState.Idle
    }

    /** Switches the call audio between earpiece, speaker and Bluetooth. */
    fun setRoute(route: com.intercom.video.twoway.core.media.AudioRoute) = post {
        val c = call ?: return@post
        if (route !in audio.availableRoutes()) return@post // e.g. Bluetooth without a headset
        audio.setRoute(route)
        c.session.route = route
        publishInCall(c)
    }

    fun setMuted(muted: Boolean) = post {
        val c = call ?: return@post
        c.session.muted = muted
        c.media?.muted = muted
        if (c.session.keys != null) sendEncrypted(c, Mute(c.session.callId, muted))
        publishInCall(c)
    }

    // --- placing a call ------------------------------------------------------------------------------

    private fun placeCall(peer: PeerAddress) {
        if (call != null || !machine.isIdle) return
        val ps = trust.pairingSecret(peer.deviceId)
        if (ps == null) {
            _state.value = CallUiState.Ended(peer.name, EndReason.NOT_PAIRED)
            return
        }
        val udp = try {
            UdpMediaSocket(sockets.datagramSocket(0))
        } catch (e: java.io.IOException) {
            logger.w(TAG, "cannot open media socket: ${e.message}")
            _state.value = CallUiState.Ended(peer.name, EndReason.UNAVAILABLE)
            return
        }
        val session = CallSession(
            callId = Hex.encode(randomBytes(16)),
            role = CallRole.CALLER,
            peerId = peer.deviceId,
            peerName = NameSanitizer.sanitizeOr(peer.name, peer.deviceId.take(8)),
            peerHost = peer.host,
            peerPort = peer.port,
            pairingSecret = ps.copyOf(),
            startedWallMs = wallClock(),
        )
        session.nonceCaller = B64.encode(randomBytes(16))
        session.localMediaPort = udp.localPort
        val active = ActiveCall(session).also { it.udp = udp }
        call = active
        perform(machine.placeCall())
    }

    private fun sendInvite(c: ActiveCall) {
        val s = c.session
        val invite = InviteAuth.sign(
            Invite(s.callId, self().deviceId, self().name, s.nonceCaller, s.localMediaPort, wallClock(), ""),
            s.secret(),
        )
        c.invite = invite
        io.execute {
            // 1. direct connection; 2. through the hotspot hub when the network blocks phone-to-phone traffic.
            var channel: SignalingChannel? = client.connect(s.peerHost, s.peerPort, DIRECT_CONNECT_TIMEOUT_MS)
            var viaHub = false
            var blocked = false
            if (channel == null) {
                val provider = relayProvider
                if (provider != null && provider.available) {
                    val relay = provider.openRelay(s.peerId, s.callId, RELAY_OPEN_TIMEOUT_MS)
                    if (relay != null) {
                        channel = relay
                        viaHub = true
                    } else {
                        blocked = true // neither a direct path nor the relay works (FR-021)
                    }
                }
            }
            post {
                if (call !== c) {
                    channel?.close()
                    return@post
                }
                if (channel == null) {
                    perform(machine.failLocally(if (blocked) EndReason.NETWORK_BLOCKED else EndReason.UNAVAILABLE))
                    return@post
                }
                c.conn = channel
                if (viaHub) {
                    s.path = MediaPath.VIA_HUB
                    (channel as RelayChannel).setListener({ body -> post { handleFrame(channel, body) } }, { post { onConnectionClosed(channel) } })
                } else {
                    (channel as SignalingConnection).startReading({ body -> post { handleFrame(channel, body) } }, { post { onConnectionClosed(channel) } })
                }
                channel.send(JsonMessageCodec.encodeBytes(invite))
            }
        }
    }

    /** A relayed channel for an incoming call arrived through the hub (we are the callee). */
    fun onRelayedChannel(channel: RelayChannel) {
        channel.setListener({ body -> post { handleFrame(channel, body) } }, { post { onConnectionClosed(channel) } })
    }

    // --- inbound connections and frames --------------------------------------------------------------

    private fun onInboundConnection(conn: SignalingConnection) {
        conn.startReading(
            { body -> post { handleFrame(conn, body) } },
            {
                server.connectionClosed()
                post { onConnectionClosed(conn) }
            },
        )
    }

    private fun handleFrame(conn: SignalingChannel, body: ByteArray) {
        val text = String(body, Charsets.UTF_8)
        try {
            val envelope = Envelope.decode(text)
            if (envelope != null) {
                handleEncrypted(conn, envelope)
            } else {
                val msg = JsonMessageCodec.decode(text) ?: return // unknown type: ignored
                handlePlain(conn, msg)
            }
        } catch (e: JsonException) {
            logger.d(TAG, "malformed frame: ${e.message}")
            // A bad frame on an active call's connection is dropped; on any other connection it is closed.
            if (call?.conn !== conn) conn.close()
        }
    }

    private fun handlePlain(conn: SignalingChannel, msg: Message) {
        val c = call
        when (msg) {
            is Hello -> {
                if (c?.conn !== conn) {
                    conn.send(JsonMessageCodec.encodeBytes(Hello(self().deviceId, self().name, roleProvider())))
                    conn.close()
                }
            }
            is Invite -> onInvite(conn, msg)
            is HubRegister, is HubRelayOpen, is HubRelayClose, is RelayFrame -> {
                val router = hubRouter
                if (router != null) router.onHubMessage(conn, msg) else conn.close()
            }
            is PairRequest, is PairAccept, is PairDecline, is PairConfirm -> {
                val router = pairingRouter
                if (router != null) router.onPairingMessage(conn, msg) else conn.close()
            }
            is Ringing -> if (c != null && c.conn === conn && c.session.role == CallRole.CALLER) onRinging(c, msg)
            is Busy -> if (c != null && c.conn === conn && preRinging(c)) perform(machine.rxBusy())
            is Reject -> if (c != null && c.conn === conn && preRinging(c) && msg.reason != "declined") {
                perform(machine.rxReject(msg.reason))
            }
            is PairRequired -> if (c != null && c.conn === conn && preRinging(c)) perform(machine.rxPairRequired())
            else -> Unit
        }
    }

    /** Plaintext BUSY/REJECT/PAIR_REQUIRED are only accepted before the callee's RINGING (no keys exist yet). */
    private fun preRinging(c: ActiveCall) = c.session.keys == null && machine.state is CallState.Calling

    private fun onInvite(conn: SignalingChannel, inv: Invite) {
        val ps = trust.pairingSecret(inv.from)
        if (ps == null || !InviteAuth.verify(inv, ps, wallClock())) {
            if (!autoRejectUnknown) conn.send(JsonMessageCodec.encodeBytes(PairRequired(inv.callId)))
            conn.close() // never rings (SC-010)
            return
        }
        val nonceCaller = B64.decode(inv.nonce)
        if (nonceCaller == null || nonceCaller.size != 16 || inv.mediaPort !in 1..65535 || Hex.decode(inv.callId)?.size != 16) {
            conn.close()
            return
        }
        if (!acceptingCalls) {
            conn.send(JsonMessageCodec.encodeBytes(Reject(inv.callId, "unavailable")))
            conn.close()
            return
        }
        // Glare: both phones called each other at the same moment. The call from the LOWER deviceId wins: the
        // higher side cancels its own call and rings for the other one; the lower side keeps its call and
        // answers BUSY to the crossing INVITE (which the higher side has already given up on).
        val current = call
        if (current != null &&
            current.session.role == CallRole.CALLER &&
            current.session.peerId == inv.from &&
            machine.state is CallState.Calling
        ) {
            if (self().deviceId < inv.from) {
                conn.send(JsonMessageCodec.encodeBytes(Busy(inv.callId)))
                conn.close()
                return
            }
            perform(machine.userCancel())
        }
        if (call != null || !machine.isIdle) {
            conn.send(JsonMessageCodec.encodeBytes(Busy(inv.callId)))
            conn.close()
            return
        }
        val session = CallSession(
            callId = inv.callId,
            role = CallRole.CALLEE,
            peerId = inv.from,
            peerName = NameSanitizer.sanitizeOr(inv.fromName, inv.from.take(8)),
            peerHost = conn.remoteHost,
            peerPort = 0,
            pairingSecret = ps.copyOf(),
            startedWallMs = wallClock(),
        )
        session.nonceCaller = inv.nonce
        val nonceCallee = randomBytes(16)
        session.nonceCallee = B64.encode(nonceCallee)
        session.remoteMediaPort = inv.mediaPort
        session.keys = CallKeys.derive(ps, nonceCaller, nonceCallee)
        if (conn is RelayChannel) session.path = MediaPath.VIA_HUB
        val active = ActiveCall(session).also { it.conn = conn }
        initCiphers(active)
        call = active
        perform(machine.incomingInvite())
    }

    private fun onRinging(c: ActiveCall, r: Ringing) {
        val s = c.session
        if (r.callId != s.callId || !InviteAuth.verifyRinging(r, s.secret(), s.nonceCaller)) return
        val nonceCallee = B64.decode(r.nonce)
        val nonceCaller = B64.decode(s.nonceCaller)
        if (nonceCallee == null || nonceCallee.size != 16 || nonceCaller == null) return
        s.nonceCallee = r.nonce
        s.keys = CallKeys.derive(s.secret(), nonceCaller, nonceCallee)
        initCiphers(c)
        perform(machine.rxRinging())
    }

    private fun handleEncrypted(conn: SignalingChannel, env: Envelope.Parsed) {
        val c = call ?: return
        if (c.conn !== conn) return
        val recv = c.recvCipher ?: return
        val dir = peerDirection(c.session)
        val plain = recv.open(env.counter, aad(c.session, dir), env.data)
        if (plain == null || !c.session.recvWindow.accept(env.counter)) {
            if (++c.badFrames >= MAX_BAD_FRAMES) conn.close()
            return
        }
        val msg = try {
            JsonMessageCodec.decode(plain)
        } catch (e: JsonException) {
            return
        } ?: return
        if (msg.callIdOrNull() != c.session.callId) return // frames for another call are ignored without a reply
        machine.rxValid()
        when (msg) {
            is Accept -> {
                if (c.session.role == CallRole.CALLER && msg.mediaPort in 1..65535) {
                    c.session.remoteMediaPort = msg.mediaPort
                    perform(machine.rxAccept())
                }
            }
            is Reject -> perform(machine.rxReject(msg.reason))
            is Cancel -> perform(machine.rxCancel())
            is Hangup -> perform(machine.rxHangup())
            is Ping -> perform(machine.rxPing())
            is Pong -> Unit
            is Mute -> {
                c.session.peerMuted = msg.muted
                publishInCall(c)
            }
            else -> Unit
        }
    }

    private fun onConnectionClosed(conn: SignalingChannel) {
        pairingRouter?.onPairingConnectionClosed(conn)
        hubRouter?.onHubChannelClosed(conn)
        val c = call ?: return
        if (c.conn !== conn) return
        when (machine.state) {
            CallState.Ringing -> perform(machine.rxCancel()) // caller gave up before we answered
            is CallState.Calling -> if (c.session.keys == null) perform(machine.rxReject("unavailable"))
            else -> Unit // established calls rely on the heartbeat (contracts/signaling.md)
        }
    }

    // --- effects -------------------------------------------------------------------------------------

    private fun perform(effects: List<Effect>) {
        for (e in effects) {
            val c = call
            when (e) {
                Effect.SendInvite -> c?.let { sendInvite(it) }
                Effect.ResendInvite -> c?.let { cc -> cc.invite?.let { cc.conn?.send(JsonMessageCodec.encodeBytes(it)) } }
                Effect.SendRinging -> c?.let { sendRinging(it) }
                Effect.SendAccept -> c?.let { sendAccept(it) }
                is Effect.SendReject -> c?.let { sendEncrypted(it, Reject(it.session.callId, e.reason)) }
                Effect.SendCancel -> c?.let { sendCancel(it) }
                is Effect.SendHangup -> c?.let { sendEncrypted(it, Hangup(it.session.callId, e.reason)) }
                Effect.SendPing -> c?.let { sendEncrypted(it, Ping(it.session.callId, clock.nowMs())) }
                Effect.SendPong -> c?.let { sendEncrypted(it, Pong(it.session.callId, clock.nowMs())) }
                Effect.StartMedia -> c?.let { startMedia(it) }
                Effect.StopMedia -> c?.media?.let { it.stop() }
                is Effect.StateChanged -> publish(e.state, c)
                is Effect.CallEnded -> c?.let { finish(it, e) }
            }
        }
    }

    private fun sendRinging(c: ActiveCall) {
        val s = c.session
        val nonce = s.nonceCallee ?: return
        c.conn?.send(
            JsonMessageCodec.encodeBytes(Ringing(s.callId, nonce, InviteAuth.ringingMac(s.secret(), s.callId, s.nonceCaller, nonce))),
        )
    }

    private fun sendAccept(c: ActiveCall) {
        if (c.udp == null) {
            c.udp = try {
                UdpMediaSocket(sockets.datagramSocket(0))
            } catch (e: java.io.IOException) {
                logger.w(TAG, "cannot open media socket: ${e.message}")
                null
            }
        }
        val udp = c.udp ?: return
        c.session.localMediaPort = udp.localPort
        sendEncrypted(c, Accept(c.session.callId, udp.localPort, AudioFormat.CODEC_NAME))
    }

    private fun sendCancel(c: ActiveCall) {
        if (c.session.keys != null) sendEncrypted(c, Cancel(c.session.callId))
        // Before RINGING there are no keys: closing the connection tells the callee (it treats that as a cancel).
    }

    private fun startMedia(c: ActiveCall) {
        val s = c.session
        val keys = s.keys ?: return
        val udp = c.udp ?: return
        val sendDir = if (s.role == CallRole.CALLER) Direction.CALLER_TO_CALLEE else Direction.CALLEE_TO_CALLER
        val recvDir = if (sendDir == Direction.CALLER_TO_CALLEE) Direction.CALLEE_TO_CALLER else Direction.CALLER_TO_CALLEE
        val relay = c.conn as? RelayChannel
        val host = try {
            InetAddress.getByName(if (relay != null) relay.remoteHost else s.peerHost)
        } catch (e: java.net.UnknownHostException) {
            return
        }
        val media = MediaSession(
            socket = udp,
            remoteHost = host,
            // through the hub, both phones send to the hub's relay port; directly, to the peer's own media port
            remotePort = relay?.relayPort ?: s.remoteMediaPort,
            sendCipher = keys.mediaCipher(sendDir),
            recvCipher = keys.mediaCipher(recvDir),
            sessionTag = s.sessionTag,
            audio = audio,
            clock = clock,
            logger = logger,
            onFirstValidPacket = { post { perform(machine.mediaFlowing()) } },
        )
        media.muted = s.muted
        c.media = media
        media.start()
    }

    private fun sendEncrypted(c: ActiveCall, msg: Message) {
        val cipher = c.sendCipher ?: return
        val s = c.session
        val counter = s.nextCounter()
        val sealed = cipher.seal(counter, aad(s, ownDirection(s)), JsonMessageCodec.encodeBytes(msg))
        c.conn?.send(Envelope.encode(counter, sealed).toByteArray(Charsets.UTF_8))
    }

    private fun initCiphers(c: ActiveCall) {
        val keys = c.session.keys ?: return
        val own = ownDirection(c.session)
        val peer = peerDirection(c.session)
        c.sendCipher = keys.signalingCipher(own)
        c.recvCipher = keys.signalingCipher(peer)
    }

    // --- state publishing and cleanup -------------------------------------------------------------------

    private fun publish(state: CallState, c: ActiveCall?) {
        val name = c?.session?.peerName ?: ""
        _state.value = when (state) {
            CallState.Idle -> CallUiState.Idle
            is CallState.Calling -> CallUiState.Calling(name, state.remoteRinging)
            CallState.Ringing -> CallUiState.Incoming(c?.session?.peerId ?: "", name)
            CallState.Connecting -> CallUiState.Connecting(name)
            CallState.InCall -> {
                c?.session?.connectedAtMs = wallClock()
                inCallState(c)
            }
            is CallState.Ended -> CallUiState.Ended(name, state.reason)
        }
    }

    private fun publishInCall(c: ActiveCall) {
        if (_state.value is CallUiState.InCall) {
            val next = inCallState(c)
            if (next != _state.value) _state.value = next
        }
    }

    private fun inCallState(c: ActiveCall?): CallUiState.InCall {
        val s = c?.session
        val media = c?.media
        val last = media?.lastValidMs ?: -1L
        val now = clock.nowMs()
        // 3 s without any voice/keep-alive packet from the peer = poor connection (the call only ends by the heartbeat rule)
        val poor = media != null && last >= 0 && now - last >= POOR_CONNECTION_MS
        return CallUiState.InCall(
            peerName = s?.peerName ?: "",
            connectedAtMs = s?.connectedAtMs ?: 0L,
            muted = s?.muted ?: false,
            peerMuted = s?.peerMuted ?: false,
            poorConnection = poor,
            micLive = media?.isMicLive ?: false,
            route = s?.route ?: com.intercom.video.twoway.core.media.AudioRoute.EARPIECE,
            availableRoutes = audio.availableRoutes(),
        )
    }

    private fun finish(c: ActiveCall, ended: Effect.CallEnded) {
        sink.onCallEnded(
            CallRecord(
                peerId = c.session.peerId,
                peerName = c.session.peerName,
                outgoing = c.session.role == CallRole.CALLER,
                reason = ended.reason,
                startedWallMs = c.session.startedWallMs,
                connectedMs = ended.connectedMs,
            ),
        )
        cleanup(c)
        machine.reset()
    }

    private fun cleanup(c: ActiveCall) {
        c.media?.stop()
        c.udp?.close()
        c.conn?.close()
        c.session.wipe()
        if (call === c) call = null
    }

    private fun onTick() {
        val st = machine.state
        if (st is CallState.InCall || st is CallState.Connecting) {
            val last = call?.media?.lastValidMs ?: -1L
            if (last >= 0 && clock.nowMs() - last < MEDIA_ALIVE_MS) machine.rxValid()
        }
        perform(machine.tick())
        call?.let { publishInCall(it) } // poor-connection and microphone indicators follow the media
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private fun ownDirection(s: CallSession) = if (s.role == CallRole.CALLER) Direction.CALLER_TO_CALLEE else Direction.CALLEE_TO_CALLER

    private fun peerDirection(s: CallSession) = if (s.role == CallRole.CALLER) Direction.CALLEE_TO_CALLER else Direction.CALLER_TO_CALLEE

    /** Associated data: call id bytes followed by the direction byte. */
    private fun aad(s: CallSession, d: Direction): ByteArray = s.callId.toByteArray(Charsets.UTF_8) + d.aad

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun Message.callIdOrNull(): String? = when (this) {
        is Accept -> callId
        is Reject -> callId
        is Cancel -> callId
        is Hangup -> callId
        is Ping -> callId
        is Pong -> callId
        is Mute -> callId
        is Busy -> callId
        else -> null
    }

    private fun post(block: () -> Unit) {
        try {
            executor.execute { safely(block) }
        } catch (_: RejectedExecutionException) {
            // engine stopped
        }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            logger.e(TAG, "engine error", e)
        }
    }

    companion object {
        const val DEFAULT_PORT = 45678
        private const val TAG = "CallEngine"
        private const val TICK_MS = 100L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 1_500
        private const val RELAY_OPEN_TIMEOUT_MS = 2_000L
        private const val MEDIA_ALIVE_MS = 500L
        private const val POOR_CONNECTION_MS = 3_000L
        private const val MAX_BAD_FRAMES = 5
    }
}
