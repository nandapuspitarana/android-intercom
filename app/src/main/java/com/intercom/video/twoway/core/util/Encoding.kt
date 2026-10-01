package com.intercom.video.twoway.core.util

/** Hex and Base64 helpers that work on every Android API level (java.util.Base64 needs API 26). */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[(b.toInt() shr 4) and 0xF]).append(DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        val pad = when {
            text.endsWith("==") -> 2
            text.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(text.length / 4 * 3 - pad)
        var o = 0
        var i = 0
        while (i < text.length) {
            var acc = 0
            for (k in 0 until 4) {
                val c = text[i + k]
                if (c == '=') {
                    acc = acc shl 6
                    continue
                }
                if (c.code >= 128 || REVERSE[c.code] < 0) return null
                acc = (acc shl 6) or REVERSE[c.code]
            }
            if (o < out.size) out[o++] = (acc shr 16).toByte()
            if (o < out.size) out[o++] = (acc shr 8).toByte()
            if (o < out.size) out[o++] = acc.toByte()
            i += 4
        }
        return out
    }
}
