package com.intercom.video.twoway.core.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class FrameCodecTest {
    @Test
    fun roundTrip() {
        val body = "hello".toByteArray()
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, body)
        val bytes = out.toByteArray()
        assertEquals(4 + body.size, bytes.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 5), bytes.copyOf(4)) // big-endian length
        assertArrayEquals(body, FrameCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun maxSizeAccepted() {
        val body = ByteArray(FrameCodec.MAX_FRAME) { 1 }
        assertArrayEquals(body, FrameCodec.read(ByteArrayInputStream(FrameCodec.encode(body))))
    }

    @Test
    fun oversizeAndEmptyAreRejectedOnWrite() {
        assertThrows(FrameException::class.java) { FrameCodec.encode(ByteArray(FrameCodec.MAX_FRAME + 1)) }
        assertThrows(FrameException::class.java) { FrameCodec.encode(ByteArray(0)) }
    }

    @Test
    fun zeroLengthOnReadIsRejected() {
        assertThrows(FrameException::class.java) {
            FrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0, 1)))
        }
    }

    @Test
    fun hugeLengthOnReadIsRejectedWithoutAllocating() {
        // 0x7FFFFFFF would be a 2 GiB body: must fail on the length alone.
        assertThrows(FrameException::class.java) {
            FrameCodec.read(ByteArrayInputStream(byteArrayOf(0x7F, -1, -1, -1)))
        }
        // High bit set (negative as a signed int) is also rejected.
        assertThrows(FrameException::class.java) {
            FrameCodec.read(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)))
        }
    }

    @Test
    fun truncatedStreamThrowsEof() {
        assertThrows(EOFException::class.java) {
            FrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 10, 1, 2, 3)))
        }
    }

    @Test
    fun handlesPartialReads() {
        val body = ByteArray(300) { it.toByte() }
        val encoded = FrameCodec.encode(body)
        val oneByteAtATime = object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i < encoded.size) encoded[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= encoded.size) return -1
                b[off] = encoded[i++]
                return 1
            }
        }
        assertArrayEquals(body, FrameCodec.read(oneByteAtATime))
    }
}
