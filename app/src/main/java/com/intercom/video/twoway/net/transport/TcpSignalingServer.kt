package com.intercom.video.twoway.net.transport

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import java.io.IOException
import java.net.ServerSocket

/**
 * Accepts signaling connections. Limits: at most 8 concurrent inbound connections, at most 5 new
 * connections per source per 10 s, and a connection that sends nothing for 10 s before its first
 * complete frame is closed. Everything over the limits is dropped without a reply.
 */
class TcpSignalingServer(
    private val sockets: SocketProvider,
    private val clock: Clock,
    private val logger: Logger,
    private val onConnection: (SignalingConnection) -> Unit,
    private val limiter: ConnectionLimiter = ConnectionLimiter(clock),
) {
    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    /** The bound port (useful when started with port 0 in tests), or -1 when stopped. */
    val port: Int get() = server?.localPort ?: -1

    /** Binds [preferredPort], falling back to an ephemeral port if it is taken. Returns the bound port. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        if (running) return port
        val s = try {
            sockets.serverSocket(preferredPort)
        } catch (e: IOException) {
            logger.w(TAG, "port $preferredPort unavailable, using an ephemeral port")
            sockets.serverSocket(0)
        }
        server = s
        running = true
        Thread({ acceptLoop(s) }, "signaling-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                if (running) logger.w(TAG, "accept failed: ${e.message}")
                break
            }
            val source = socket.inetAddress?.hostAddress ?: "unknown"
            if (!limiter.tryAcquire(source)) {
                logger.d(TAG, "dropping connection from $source (limit)")
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                continue
            }
            socket.tcpNoDelay = true
            val conn = SignalingConnection(socket, logger)
            try {
                onConnection(conn)
            } catch (e: RuntimeException) {
                logger.e(TAG, "connection handler failed", e)
                conn.close()
            }
        }
    }

    /** Handlers call this when a connection they were given ends, so the concurrent count stays right. */
    fun connectionClosed() = limiter.released()

    private companion object {
        const val TAG = "TcpSignalingServer"
    }
}
