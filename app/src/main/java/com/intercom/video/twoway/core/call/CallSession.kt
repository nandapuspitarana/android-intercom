package com.intercom.video.twoway.core.call

import com.intercom.video.twoway.core.crypto.CallKeys
import com.intercom.video.twoway.core.crypto.StrictCounterWindow

enum class MediaPath { DIRECT, VIA_HUB }

/** Mutable data for one call (data-model: Call). Owned and only touched by the engine thread. */
class CallSession(
    /** 16 random bytes as hex, chosen by the caller. */
    val callId: String,
    val role: CallRole,
    val peerId: String,
    var peerName: String,
    /** Address of the peer's signaling endpoint (caller side) or the remote address of the connection (callee). */
    val peerHost: String,
    val peerPort: Int,
    /** Pairing secret PS for this peer; zeroed on [wipe]. */
    private val pairingSecret: ByteArray,
    val startedWallMs: Long,
) {
    lateinit var nonceCaller: String
    var nonceCallee: String? = null
    var keys: CallKeys? = null
    var localMediaPort: Int = 0
    var remoteMediaPort: Int = 0
    var path: MediaPath = MediaPath.DIRECT
    var connectedAtMs: Long = 0
    var muted: Boolean = false
    var peerMuted: Boolean = false
    var route: com.intercom.video.twoway.core.media.AudioRoute = com.intercom.video.twoway.core.media.AudioRoute.EARPIECE
    var endReason: EndReason? = null

    /** Next counter for an encrypted signaling frame we send; strictly increasing. */
    private var sendCounter = 0L
    val recvWindow = StrictCounterWindow()

    fun secret(): ByteArray = pairingSecret

    fun nextCounter(): Long = sendCounter++

    /** 32-bit media session tag = first 4 bytes of the call id. */
    val sessionTag: Int
        get() = callId.take(8).toLong(16).toInt()

    fun wipe() {
        keys?.wipe()
        keys = null
        pairingSecret.fill(0)
    }
}
