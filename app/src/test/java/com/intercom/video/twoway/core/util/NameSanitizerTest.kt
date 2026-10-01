package com.intercom.video.twoway.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NameSanitizerTest {
    @Test
    fun trimsWhitespace() = assertEquals("Kitchen", NameSanitizer.sanitize("  Kitchen \n"))

    @Test
    fun removesControlCharacters() = assertEquals("AB", NameSanitizer.sanitize("A\u0000\u0007B"))

    @Test
    fun truncatesTo32Chars() {
        val name = NameSanitizer.sanitize("x".repeat(100))
        assertEquals(32, name!!.length)
    }

    @Test
    fun emptyAfterSanitizingIsRejected() {
        assertNull(NameSanitizer.sanitize("   "))
        assertNull(NameSanitizer.sanitize("\u0000\u0001"))
        assertNull(NameSanitizer.sanitize(null))
    }

    @Test
    fun fallbackIsUsedWhenRejected() = assertEquals("Phone", NameSanitizer.sanitizeOr("", "Phone"))

    @Test
    fun keepsNonAsciiText() = assertEquals("Dapur Ibu", NameSanitizer.sanitize("Dapur Ibu"))
}
