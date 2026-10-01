package com.intercom.video.twoway.testing

import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.net.transport.SocketProvider
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * Sockets whose traffic can be cut like a lost WiFi connection: while [dropping] is true everything sent is silently
 * discarded and everything received is thrown away. Only connections and datagram sockets created through this
 * provider are affected, i.e. the side of the call that uses it. Used by tests and functional tests.
 */
class FlakySocketProvider(private val delegate: SocketProvider = PlainSocketProvider) : SocketProvider {
    /** When true the network is "black-holed". */
    @Volatile
    var dropping = false

    override fun connect(host: String, port: Int, timeoutMs: Int): Socket = FlakySocket(delegate.connect(host, port, timeoutMs), this)

    override fun serverSocket(port: Int): ServerSocket = delegate.serverSocket(port)

    override fun datagramSocket(port: Int): DatagramSocket = FlakyDatagramSocket(this).also {
        it.reuseAddress = true
        it.bind(InetSocketAddress(port))
    }

    override fun multicastSocket(port: Int): MulticastSocket = delegate.multicastSocket(port)
}

private class FlakyDatagramSocket(private val owner: FlakySocketProvider) : DatagramSocket(null as java.net.SocketAddress?) {
    override fun send(p: DatagramPacket) {
        if (!owner.dropping) super.send(p)
    }

    override fun receive(p: DatagramPacket) {
        while (true) {
            super.receive(p) // throws SocketTimeoutException like a normal socket
            if (!owner.dropping) return
        }
    }
}

/** A TCP socket that wraps a real one and discards traffic while the provider is dropping. */
private class FlakySocket(private val real: Socket, private val owner: FlakySocketProvider) : Socket() {
    override fun getInetAddress(): InetAddress? = real.inetAddress
    override fun setSoTimeout(timeout: Int) = real.setSoTimeout(timeout)
    override fun getSoTimeout(): Int = real.soTimeout
    override fun setTcpNoDelay(on: Boolean) = real.setTcpNoDelay(on)
    override fun isClosed(): Boolean = real.isClosed
    override fun isConnected(): Boolean = real.isConnected
    override fun close() = real.close()

    override fun getInputStream(): InputStream = object : InputStream() {
        private val inner = real.getInputStream()

        override fun read(): Int {
            while (true) {
                val b = inner.read()
                if (b < 0 || !owner.dropping) return b
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val n = inner.read(b, off, len)
                if (n < 0 || !owner.dropping) return n
            }
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        private val inner = real.getOutputStream()

        override fun write(b: Int) {
            if (!owner.dropping) inner.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!owner.dropping) inner.write(b, off, len)
        }

        override fun flush() {
            if (!owner.dropping) inner.flush()
        }
    }
}
