package com.intercom.video.twoway.core.util

/** Display name rules: 1-32 chars, trimmed, control characters removed, plain text only. */
object NameSanitizer {
    const val MAX_LENGTH = 32

    /** Returns the sanitized name, or null if nothing usable is left. */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.filter { !it.isISOControl() && it != ' ' && it != ' ' }.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_LENGTH) cleaned.take(MAX_LENGTH).trimEnd() else cleaned
    }

    fun sanitizeOr(raw: String?, fallback: String): String = sanitize(raw) ?: fallback
}
