package com.intercom.video.twoway.testing

import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.net.transport.SignalingChannel
import com.intercom.video.twoway.net.transport.SocketProvider
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sockets for a phone on a hotspot with client isolation: connections to the listed `host:port` pairs fail as if the
 * access point dropped the traffic, everything else (notably the hub) works. Used by tests and functional tests.
 */
class IsolatedSocketProvider(private val delegate: SocketProvider = PlainSocketProvider) : SocketProvider {
    private val blocked = CopyOnWriteArrayList<Pair<String, Int>>()

    @Volatile
    var blockedConnects = 0
        private set

    fun block(host: String, port: Int) {
        blocked += host to port
    }

    fun unblockAll() = blocked.clear()

    override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        if (blocked.any { it.first == host && it.second == port }) {
            blockedConnects++
            throw ConnectException("blocked by client isolation: $host:$port")
        }
        return delegate.connect(host, port, timeoutMs)
    }

    override fun serverSocket(port: Int): ServerSocket = delegate.serverSocket(port)
    override fun datagramSocket(port: Int): DatagramSocket = delegate.datagramSocket(port)
    override fun multicastSocket(port: Int): MulticastSocket = delegate.multicastSocket(port)
}

/** An in-memory signaling channel that records what is sent to it (for testing the hub without sockets). */
class FakeChannel(override val remoteHost: String = "10.0.0.1") : SignalingChannel {
    val sent = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    override var isClosed = false
        private set

    override fun send(body: ByteArray): Boolean {
        if (isClosed) return false
        sent += body
        return true
    }

    override fun close() {
        isClosed = true
    }

    fun sentTexts(): List<String> = sent.map { String(it, Charsets.UTF_8) }
}
