package com.intercom.video.twoway.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HostPortTest {
    @Test
    fun parsesAddressWithAndWithoutPort() {
        val a = HostPort.parse("192.168.43.1")!!
        assertEquals("192.168.43.1", a.host)
        assertEquals(HostPort.DEFAULT_PORT, a.port)
        val b = HostPort.parse(" 10.0.0.5:5000 ")!!
        assertEquals("10.0.0.5", b.host)
        assertEquals(5000, b.port)
    }

    @Test
    fun rejectsEverythingElse() {
        val bad = listOf(
            "", " ", "abc", "example.com",
            "1.2.3", "1.2.3.4.5", "256.1.1.1",
            "1.1.1.1:", "1.1.1.1:0", "1.1.1.1:65536", "1.1.1.1:abc", "1.1.1.1:-1", "1.1.1.1:5000:1", "1.1.1.1 :5000",
            "::1", "9".repeat(30),
        )
        for (text in bad) {
            assertNull(HostPort.parse(text), "must reject '$text'")
        }
    }
}
