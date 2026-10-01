package com.intercom.video.twoway.core.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

class FrameException(message: String) : Exception(message)

/**
 * TCP framing: u32 big-endian length followed by that many body bytes.
 * Length MUST be 1..[MAX_FRAME]; anything else is a protocol error and the connection must be closed.
 */
object FrameCodec {
    const val MAX_FRAME = 4096

    fun encode(body: ByteArray): ByteArray {
        if (body.isEmpty() || body.size > MAX_FRAME) throw FrameException("bad frame size ${body.size}")
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    fun write(out: OutputStream, body: ByteArray) {
        out.write(encode(body))
        out.flush()
    }

    /** Reads one frame body. Throws [FrameException] on bad length and [EOFException] on a closed stream. */
    fun read(input: InputStream): ByteArray {
        val header = readFully(input, 4)
        val length = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (length < 1 || length > MAX_FRAME) throw FrameException("bad frame length $length")
        return readFully(input, length)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("stream closed")
            off += r
        }
        return buf
    }
}
