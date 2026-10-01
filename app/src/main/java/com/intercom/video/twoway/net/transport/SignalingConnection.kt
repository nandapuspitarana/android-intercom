package com.intercom.video.twoway.net.transport

import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.protocol.FrameCodec
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection carrying length-prefixed signaling frames. All writes are serialized; a reader
 * thread delivers frames. A connection that does not deliver a complete first frame within
 * [firstFrameTimeoutMs] is closed (contracts/signaling.md).
 */
class SignalingConnection(private val socket: Socket, private val logger: Logger, private val firstFrameTimeoutMs: Int = FIRST_FRAME_TIMEOUT_MS) :
    SignalingChannel {
    override val remoteHost: String = socket.inetAddress?.hostAddress ?: "unknown"

    @Volatile
    private var closed = false
    private val writeLock = Any()

    override val isClosed: Boolean get() = closed

    /** Returns false if the frame could not be written (the connection is then closed). */
    override fun send(body: ByteArray): Boolean {
        if (closed) return false
        return try {
            synchronized(writeLock) { FrameCodec.write(socket.getOutputStream(), body) }
            true
        } catch (e: IOException) {
            logger.d(TAG, "send failed: ${e.message}")
            close()
            false
        } catch (e: com.intercom.video.twoway.core.protocol.FrameException) {
            logger.w(TAG, "refusing to send bad frame: ${e.message}")
            false
        }
    }

    /** Starts the reader thread; [onClosed] is called exactly once when the connection ends. */
    fun startReading(onFrame: (ByteArray) -> Unit, onClosed: () -> Unit) {
        Thread({
            try {
                socket.soTimeout = firstFrameTimeoutMs
                var first = true
                val input = socket.getInputStream()
                while (!closed) {
                    val frame = FrameCodec.read(input)
                    if (first) {
                        socket.soTimeout = 0
                        first = false
                    }
                    onFrame(frame)
                }
            } catch (e: SocketTimeoutException) {
                logger.d(TAG, "closing idle connection from $remoteHost")
            } catch (e: IOException) {
                logger.d(TAG, "connection ended: ${e.message}")
            } catch (e: com.intercom.video.twoway.core.protocol.FrameException) {
                logger.w(TAG, "protocol error from $remoteHost: ${e.message}")
            } finally {
                close()
                onClosed()
            }
        }, "signaling-reader-$remoteHost").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val TAG = "SignalingConnection"
        const val FIRST_FRAME_TIMEOUT_MS = 10_000
    }
}
