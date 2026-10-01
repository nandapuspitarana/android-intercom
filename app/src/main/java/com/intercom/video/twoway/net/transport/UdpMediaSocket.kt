package com.intercom.video.twoway.net.transport

import com.intercom.video.twoway.core.media.MediaPacket
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** UDP socket for voice packets. Datagrams above [MediaPacket.MAX_DATAGRAM] bytes are never sent or accepted. */
class UdpMediaSocket(private val socket: DatagramSocket) {
    val localPort: Int get() = socket.localPort

    private val buffer = ByteArray(MediaPacket.MAX_DATAGRAM + 1)

    @Volatile
    private var closed = false

    init {
        socket.soTimeout = RECEIVE_TIMEOUT_MS
    }

    fun send(host: InetAddress, port: Int, data: ByteArray) {
        if (closed || data.size > MediaPacket.MAX_DATAGRAM) return
        try {
            socket.send(DatagramPacket(data, data.size, host, port))
        } catch (_: IOException) {
            // transient network errors are expected when WiFi drops; the heartbeat decides when the call is over
        }
    }

    /**
     * Blocks up to ~200 ms. Returns the datagram or null on timeout/close. Oversized datagrams
     * (longer than the maximum) are dropped. Not thread-safe: one receiver thread only.
     */
    fun receive(): ByteArray? {
        if (closed) return null
        val p = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(p)
            if (p.length > MediaPacket.MAX_DATAGRAM) null else buffer.copyOf(p.length)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun close() {
        closed = true
        socket.close()
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 200
    }
}
