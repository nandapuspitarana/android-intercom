package com.intercom.video.twoway.net.transport

import com.intercom.video.twoway.core.Logger
import java.io.IOException

/** Opens outgoing signaling connections through the [SocketProvider] (bound to WiFi on Android). */
class TcpSignalingClient(private val sockets: SocketProvider, private val logger: Logger) {
    /** Connects to [host]:[port]; returns null if the peer is unreachable within [timeoutMs]. */
    fun connect(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): SignalingConnection? = try {
        SignalingConnection(sockets.connect(host, port, timeoutMs), logger)
    } catch (e: IOException) {
        logger.d(TAG, "cannot connect to $host:$port: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "TcpSignalingClient"
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
