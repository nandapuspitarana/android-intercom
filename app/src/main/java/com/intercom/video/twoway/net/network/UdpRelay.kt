package com.intercom.video.twoway.net.network

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.media.MediaPacket
import com.intercom.video.twoway.net.transport.SocketProvider
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP side of the hotspot hub: forwards voice packets between the two phones of a relay session without reading them.
 * The session is identified by the 32-bit session tag every media packet carries in its header (bytes 8-11); each
 * phone's address is learned from the first packet it sends. Packets from a third address, for unknown tags,
 * oversize or too short are dropped.
 */
class UdpRelay(private val sockets: SocketProvider, private val clock: Clock, private val logger: Logger) {
    class Session(val tag: Int) {
        @Volatile
        var a: InetSocketAddress? = null

        @Volatile
        var b: InetSocketAddress? = null

        @Volatile
        var lastActivityMs = 0L
    }

    private val sessions = ConcurrentHashMap<Int, Session>()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** The relay's UDP port, or -1 while stopped. */
    val port: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start(): Int {
        if (running) return port
        val s = sockets.datagramSocket(0).also { it.soTimeout = 500 }
        socket = s
        running = true
        Thread({ loop(s) }, "hub-udp-relay").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        socket?.close()
        socket = null
        sessions.clear()
    }

    /** Returns null if a session with this tag already exists. */
    fun addSession(tag: Int): Session? {
        val s = Session(tag).also { it.lastActivityMs = clock.nowMs() }
        return if (sessions.putIfAbsent(tag, s) == null) s else null
    }

    fun removeSession(tag: Int) {
        sessions.remove(tag)
    }

    fun lastActivity(tag: Int): Long = sessions[tag]?.lastActivityMs ?: 0L

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(MediaPacket.MAX_DATAGRAM + 1)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) logger.d(TAG, "relay socket ended: ${e.message}")
                return
            }
            if (p.length < MediaPacket.HEADER_SIZE || p.length > MediaPacket.MAX_DATAGRAM) continue
            val tag = ((buf[8].toInt() and 0xFF) shl 24) or ((buf[9].toInt() and 0xFF) shl 16) or
                ((buf[10].toInt() and 0xFF) shl 8) or (buf[11].toInt() and 0xFF)
            val session = sessions[tag] ?: continue
            val src = InetSocketAddress(p.address, p.port)
            val target: InetSocketAddress? = when {
                src == session.a -> session.b
                src == session.b -> session.a
                session.a == null -> {
                    session.a = src
                    session.b
                }
                session.b == null -> {
                    session.b = src
                    session.a
                }
                else -> continue // a third address: ignore
            }
            session.lastActivityMs = clock.nowMs()
            if (target != null) {
                try {
                    s.send(DatagramPacket(buf, p.length, target))
                } catch (_: IOException) {
                    // peer unreachable for now
                }
            }
        }
    }

    private companion object {
        const val TAG = "UdpRelay"
    }
}
