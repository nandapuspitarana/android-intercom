package com.intercom.video.twoway.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AesGcmEnvelopeTest {
    private val key = ByteArray(32) { it.toByte() }
    private val salt = byteArrayOf(9, 8, 7, 6)
    private val aad = byteArrayOf(1, 2, 3)

    @Test
    fun roundTrip() {
        val g = AesGcm(key, salt)
        val sealed = g.seal(1, aad, "hello".toByteArray())
        assertEquals(5 + AesGcm.TAG_BYTES, sealed.size)
        assertArrayEquals("hello".toByteArray(), g.open(1, aad, sealed))
    }

    @Test
    fun tamperedCiphertextOrTagFails() {
        val g = AesGcm(key, salt)
        val sealed = g.seal(1, aad, "hello".toByteArray())
        for (i in sealed.indices) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor 1).toByte()
            assertNull(g.open(1, aad, bad), "flip at byte $i must fail")
        }
    }

    @Test
    fun wrongAadCounterOrKeyFails() {
        val g = AesGcm(key, salt)
        val sealed = g.seal(1, aad, "hello".toByteArray())
        assertNull(g.open(1, byteArrayOf(9), sealed))
        assertNull(g.open(2, aad, sealed))
        assertNull(AesGcm(ByteArray(32) { 5 }, salt).open(1, aad, sealed))
        assertNull(AesGcm(key, byteArrayOf(0, 0, 0, 0)).open(1, aad, sealed))
    }

    @Test
    fun nonceIsSaltPlusBigEndianCounter() {
        val n = AesGcm.nonce(salt, 0x0102030405060708L)
        assertEquals(12, n.size)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 1, 2, 3, 4, 5, 6, 7, 8), n)
    }

    @Test
    fun envelopeRoundTrip() {
        val g = AesGcm(key, salt)
        val sealed = g.seal(77, aad, "payload".toByteArray())
        val text = Envelope.encode(77, sealed)
        val parsed = Envelope.decode(text)!!
        assertEquals(77L, parsed.counter)
        assertArrayEquals("payload".toByteArray(), g.open(parsed.counter, aad, parsed.data))
    }

    @Test
    fun aLongRelayFrameIsNotAnEnvelopeAndDoesNotThrow() {
        val relay = com.intercom.video.twoway.core.protocol.JsonMessageCodec.encode(
            com.intercom.video.twoway.core.protocol.RelayFrame("s", ByteArray(2_000) { it.toByte() }),
        )
        assertNull(Envelope.decode(relay))
    }

    @Test
    fun nonEnvelopeObjectsAreNotEnvelopes() {
        assertNull(Envelope.decode("""{"t":"HELLO"}"""))
        assertNull(Envelope.decode("""{"c":1,"n":"AAAA","d":"AAAA"}""")) // counter must be 8 bytes
    }
}
