package com.intercom.video.twoway.core.crypto

import com.intercom.video.twoway.core.util.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** RFC 5869 appendix A test vectors (SHA-256). */
class HkdfTest {
    private fun h(s: String) = Hex.decode(s)!!

    @Test
    fun rfc5869Case1() {
        val ikm = h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = h("000102030405060708090a0b0c")
        val info = h("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Hex.encode(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(Hkdf.expand(prk, info, 42)),
        )
    }

    @Test
    fun rfc5869Case3EmptySaltAndInfo() {
        val ikm = h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hex.encode(Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42)),
        )
    }

    @Test
    fun rfc5869Case2LongInputs() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hex.encode(Hkdf.derive(ikm, salt, info, 82)),
        )
    }
}
