package com.intercom.video.twoway.core.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EncodingTest {
    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0, 1, 0x7F, -1, -128)
        assertEquals("00017fff80", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("00017fff80"))
    }

    @Test
    fun hexRejectsBadInput() {
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
    }

    @Test
    fun base64MatchesRfc4648Vectors() {
        assertEquals("", B64.encode(ByteArray(0)))
        assertEquals("Zg==", B64.encode("f".toByteArray()))
        assertEquals("Zm8=", B64.encode("fo".toByteArray()))
        assertEquals("Zm9v", B64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", B64.encode("foob".toByteArray()))
        assertArrayEquals("fooba".toByteArray(), B64.decode("Zm9vYmE="))
    }

    @Test
    fun base64RoundTripAllLengths() {
        for (n in 0..40) {
            val data = ByteArray(n) { (it * 7 + 3).toByte() }
            assertArrayEquals(data, B64.decode(B64.encode(data)))
        }
    }

    @Test
    fun base64RejectsBadInput() {
        assertNull(B64.decode("abc"))
        assertNull(B64.decode("ab!d"))
    }
}
