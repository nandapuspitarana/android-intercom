package com.intercom.video.twoway.net.transport

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * Creates sockets. On Android the implementation binds every socket to the WiFi network so call
 * traffic never uses mobile data (FR-008); on the JVM (loopback tests) plain sockets are used.
 * This file has no android.* imports so the engine stays JVM-testable.
 */
interface SocketProvider {
    /** Connects with a timeout; throws [java.io.IOException] on failure. */
    fun connect(host: String, port: Int, timeoutMs: Int): Socket

    fun serverSocket(port: Int): ServerSocket

    fun datagramSocket(port: Int): DatagramSocket

    fun multicastSocket(port: Int): MulticastSocket
}

object PlainSocketProvider : SocketProvider {
    override fun connect(host: String, port: Int, timeoutMs: Int): Socket = Socket().also {
        it.tcpNoDelay = true
        it.connect(InetSocketAddress(host, port), timeoutMs)
    }

    override fun serverSocket(port: Int): ServerSocket = ServerSocket().also {
        it.reuseAddress = true
        it.bind(InetSocketAddress(port))
    }

    override fun datagramSocket(port: Int): DatagramSocket = DatagramSocket(null).also {
        it.reuseAddress = true
        it.bind(InetSocketAddress(port))
    }

    override fun multicastSocket(port: Int): MulticastSocket = MulticastSocket(null).also {
        it.reuseAddress = true
        it.bind(InetSocketAddress(port))
    }
}
