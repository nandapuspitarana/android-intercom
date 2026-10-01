package com.intercom.video.twoway.core.pairing

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PairingLinkTest {
    private val id = "ab".repeat(16)
    private val fp = ByteArray(16) { (it + 1).toByte() }
    private fun link(name: String = "Dapur Ibu", host: String = "192.168.43.1", port: Int = 45678) = PairingLink(id, host, port, fp, name)

    @Test
    fun roundTrips() {
        val parsed = PairingLink.parse(link().encode())!!
        assertEquals(id, parsed.deviceId)
        assertEquals("192.168.43.1", parsed.host)
        assertEquals(45678, parsed.port)
        assertArrayEquals(fp, parsed.fingerprint)
        assertEquals("Dapur Ibu", parsed.name)
    }

    @Test
    fun namesWithSpecialCharactersSurvive() {
        val parsed = PairingLink.parse(link(name = "A&B = ñ #1").encode())!!
        assertEquals("A&B = ñ #1", parsed.name)
    }

    @Test
    fun containsNoSecret() {
        val text = link().encode()
        assertEquals(true, text.startsWith("twoway://pair?v=1&"))
        // only id, ip, port, fp and name appear
        assertEquals(listOf("v", "id", "ip", "p", "fp", "n"), text.substringAfter('?').split('&').map { it.substringBefore('=') })
    }

    @Test
    fun rejectsEverythingMalformed() {
        val good = link().encode()
        val bad = listOf(
            "", "hello", "https://example.com", "twoway://pair", "twoway://pair?",
            good.replace("v=1", "v=2"),
            good.replace("id=$id", "id=short"),
            good.replace("id=$id", "id=" + "zz".repeat(16)),
            good.replace("ip=192.168.43.1", "ip=999.1.1.1"),
            good.replace("ip=192.168.43.1", "ip=example.com"),
            good.replace("p=45678", "p=0"),
            good.replace("p=45678", "p=70000"),
            good.replace("p=45678", "p=abc"),
            good.substringBeforeLast("&fp=") + "&fp=00",
            good.replace("&n=Dapur+Ibu", "&n=%00%01"),
            good + "&id=$id", // duplicate key
            good + "x".repeat(500), // too long
            good.replace("&n=", "&n"),
        )
        for (b in bad) assertNull(PairingLink.parse(b), "must reject: ${b.take(60)}")
    }

    @Test
    fun neverThrowsOnRandomInput() {
        val rnd = java.util.Random(1)
        repeat(500) {
            val s = String(CharArray(rnd.nextInt(80)) { (rnd.nextInt(95) + 32).toChar() })
            PairingLink.parse("twoway://pair?$s") // must not throw
        }
        assertNotNull(PairingLink.parse(link().encode()))
    }
}
