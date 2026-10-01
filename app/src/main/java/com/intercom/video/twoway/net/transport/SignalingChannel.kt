package com.intercom.video.twoway.net.transport

import java.io.Closeable

/**
 * A two-way pipe of signaling frames to one peer. Either a real TCP connection ([SignalingConnection]) or, on a hotspot
 * whose clients cannot reach each other, a virtual channel relayed through the hotspot phone (RelayChannel).
 */
interface SignalingChannel : Closeable {
    /** Address the peer (or the relaying hub) is reached at. */
    val remoteHost: String

    val isClosed: Boolean

    /** Returns false if the frame could not be sent (the channel is then closed). */
    fun send(body: ByteArray): Boolean
}
