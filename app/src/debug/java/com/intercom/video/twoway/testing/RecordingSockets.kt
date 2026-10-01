package com.intercom.video.twoway.testing

import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.net.transport.SocketProvider
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * Records every byte this phone sends over TCP connections it opens, per connection, so tests can replay captured
 * (encrypted) signaling to check that replays are refused. Used by tests and functional tests.
 */
class RecordingSocketProvider(private val delegate: SocketProvider = PlainSocketProvider) : SocketProvider {
    private val captured = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    private val streams = java.util.concurrent.CopyOnWriteArrayList<ByteArrayOutputStream>()

    /** Everything sent so far, one array per connection. */
    fun capturedStreams(): List<ByteArray> = streams.map { synchronized(it) { it.toByteArray() } }

    override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val sink = ByteArrayOutputStream().also { streams += it }
        return RecordingSocket(delegate.connect(host, port, timeoutMs), sink)
    }

    override fun serverSocket(port: Int): ServerSocket = delegate.serverSocket(port)
    override fun datagramSocket(port: Int): DatagramSocket = delegate.datagramSocket(port)
    override fun multicastSocket(port: Int): MulticastSocket = delegate.multicastSocket(port)
}

private class RecordingSocket(private val real: Socket, private val tee: ByteArrayOutputStream) : Socket() {
    override fun getInetAddress(): InetAddress? = real.inetAddress
    override fun setSoTimeout(timeout: Int) = real.setSoTimeout(timeout)
    override fun getSoTimeout(): Int = real.soTimeout
    override fun setTcpNoDelay(on: Boolean) = real.setTcpNoDelay(on)
    override fun isClosed(): Boolean = real.isClosed
    override fun isConnected(): Boolean = real.isConnected
    override fun close() = real.close()
    override fun getInputStream(): InputStream = real.getInputStream()

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        private val inner = real.getOutputStream()

        override fun write(b: Int) {
            synchronized(tee) { tee.write(b) }
            inner.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(tee) { tee.write(b, off, len) }
            inner.write(b, off, len)
        }

        override fun flush() = inner.flush()
    }
}
