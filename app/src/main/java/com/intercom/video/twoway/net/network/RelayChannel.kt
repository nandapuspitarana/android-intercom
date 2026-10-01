package com.intercom.video.twoway.net.network

import com.intercom.video.twoway.core.protocol.HubRelayClose
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.RelayFrame
import com.intercom.video.twoway.net.transport.SignalingChannel

/**
 * Signaling to one peer through the hotspot hub. Every frame is wrapped as RELAY(sessionId, frame) and sent over the
 * client's connection to the hub, which forwards it unchanged. [relayPort] is where media for this session goes.
 */
class RelayChannel(val sessionId: String, private val hub: SignalingChannel, var relayPort: Int, private val onClosedByUs: (RelayChannel) -> Unit = {}) :
    SignalingChannel {
    override val remoteHost: String get() = hub.remoteHost

    @Volatile
    private var closed = false
    private var onFrame: ((ByteArray) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null
    private val early = ArrayList<ByteArray>()

    override val isClosed: Boolean get() = closed || hub.isClosed

    override fun send(body: ByteArray): Boolean {
        if (isClosed) return false
        return hub.send(JsonMessageCodec.encodeBytes(RelayFrame(sessionId, body)))
    }

    /** Frames that arrived before a listener was attached are delivered when it is. */
    @Synchronized
    fun setListener(onFrame: (ByteArray) -> Unit, onClosed: () -> Unit) {
        this.onFrame = onFrame
        this.onClosed = onClosed
        early.forEach(onFrame)
        early.clear()
        if (closed) onClosed()
    }

    /** Called by the hub client when the hub delivers a frame for this session. */
    @Synchronized
    fun deliver(frame: ByteArray) {
        if (closed) return
        val cb = onFrame
        if (cb == null) early.add(frame) else cb(frame)
    }

    /** The hub (or the peer) ended the session. */
    @Synchronized
    fun closedByHub() {
        if (closed) return
        closed = true
        onClosed?.invoke()
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        hub.send(JsonMessageCodec.encodeBytes(HubRelayClose(sessionId)))
        onClosedByUs(this)
    }
}
