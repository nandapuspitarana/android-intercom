package com.intercom.video.twoway.functional

import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PairingUiState
import com.intercom.video.twoway.service.PeerAddress
import com.intercom.video.twoway.testing.FakeAudioFactory
import com.intercom.video.twoway.testing.TestDevice

/**
 * "The other phone" for functional tests: a complete second device (own identity, trust store, fake
 * audio, real CallEngine on localhost sockets) with scriptable behaviour for incoming calls.
 */
class FakePeerDevice(
    val name: String = "Peer Phone",
    @Volatile var behavior: Behavior = Behavior.ACCEPT,
    /** Time the peer "rings" before it reacts, so tests can observe the calling/ringing UI. */
    @Volatile var answerDelayMs: Long = 0,
) {
    enum class Behavior { ACCEPT, REJECT, IGNORE }

    /** How the peer reacts to a pairing handshake. */
    enum class PairingBehavior {
        /** Accept an incoming request and press "Matches". */
        AUTO,

        /** Accept an incoming request but press "Does not match". */
        CONFIRM_MISMATCH,

        /** Decline an incoming request. */
        DECLINE,

        /** Do nothing; the test drives the peer's PairingManager itself. */
        MANUAL,
    }

    @Volatile
    var pairingBehavior = PairingBehavior.AUTO

    val device = TestDevice(name)
    val engine get() = device.engine
    val audio: FakeAudioFactory get() = device.audio
    val state: CallUiState get() = device.state
    val deviceId: String get() = device.deviceId

    lateinit var address: PeerAddress
        private set

    @Volatile
    private var running = false
    private var watcher: Thread? = null

    /** Number of incoming calls that rang on this phone. */
    @Volatile
    var ringCount = 0
        private set

    fun start(): PeerAddress {
        address = device.start()
        running = true
        watcher = Thread({ watch() }, "fake-peer-watcher").apply {
            isDaemon = true
            start()
        }
        return address
    }

    fun stop() {
        running = false
        watcher?.interrupt()
        device.stop()
    }

    /** The peer places a call to the app under test. */
    fun callApp(app: PeerAddress) = engine.call(app)

    fun hangup() = engine.hangup()

    private var pairingHandledFor: Any? = null

    private fun handlePairing() {
        val p = device.pairing.state.value
        if (p === pairingHandledFor) return
        when (p) {
            is PairingUiState.IncomingRequest -> when (pairingBehavior) {
                PairingBehavior.DECLINE -> {
                    pairingHandledFor = p
                    device.pairing.declineIncoming()
                }
                PairingBehavior.MANUAL -> Unit
                else -> {
                    pairingHandledFor = p
                    device.pairing.acceptIncoming()
                }
            }
            is PairingUiState.Verify -> if (!p.awaitingPeer) {
                when (pairingBehavior) {
                    PairingBehavior.AUTO -> {
                        pairingHandledFor = p
                        device.pairing.confirmMatches(true)
                    }
                    PairingBehavior.CONFIRM_MISMATCH -> {
                        pairingHandledFor = p
                        device.pairing.confirmMatches(false)
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
    }

    private fun watch() {
        var handledRing = false
        while (running) {
            try {
                when (state) {
                    is CallUiState.Incoming -> if (!handledRing) {
                        handledRing = true
                        ringCount++
                        val behavior = behavior
                        val delay = answerDelayMs
                        if (delay > 0) Thread.sleep(delay)
                        when (behavior) {
                            Behavior.ACCEPT -> engine.accept()
                            Behavior.REJECT -> engine.reject()
                            Behavior.IGNORE -> Unit
                        }
                    }
                    is CallUiState.Ended -> {
                        engine.dismissEnded()
                        handledRing = false
                    }
                    CallUiState.Idle -> handledRing = false
                    else -> Unit
                }
                handlePairing()
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
