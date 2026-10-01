package com.intercom.video.twoway.core.protocol

class JsonException(message: String) : Exception(message)

/**
 * Minimal strict JSON reader/writer. The wire protocol never deserializes objects:
 * values are only Map, List, String, Long, Double, Boolean or null.
 * Limits: nesting depth <= [MAX_DEPTH], strings (and keys) <= [MAX_STRING] chars.
 */
object Json {
    const val MAX_DEPTH = 4
    const val MAX_STRING = 256

    /** Upper bound for any string while parsing: a signaling frame is at most 4096 bytes anyway. */
    const val MAX_STRING_HARD = 4096

    fun parseObject(text: String, maxString: Int = MAX_STRING): Map<String, Any?> {
        val p = Parser(text, maxString)
        p.skipWs()
        if (p.peek() != '{') throw JsonException("top level must be an object")
        val v = p.readValue(1)
        p.skipWs()
        if (!p.atEnd()) throw JsonException("trailing data")
        @Suppress("UNCHECKED_CAST")
        return v as Map<String, Any?>
    }

    /** Throws if any string value or key anywhere in [value] is longer than [limit]. */
    fun requireStringsAtMost(value: Any?, limit: Int) {
        when (value) {
            is String -> if (value.length > limit) throw JsonException("string too long")
            is Map<*, *> -> value.forEach { (k, v) ->
                requireStringsAtMost(k, limit)
                requireStringsAtMost(v, limit)
            }
            is List<*> -> value.forEach { requireStringsAtMost(it, limit) }
            else -> Unit
        }
    }

    fun write(obj: Map<String, Any?>): String = StringBuilder().also { writeValue(it, obj) }.toString()

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Double -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k as String)
                    sb.append(':')
                    writeValue(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    writeValue(sb, e)
                }
                sb.append(']')
            }
            else -> throw JsonException("unsupported type ${v::class.simpleName}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String, private val maxString: Int) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun readValue(depth: Int): Any? {
            skipWs()
            if (atEnd()) throw JsonException("unexpected end")
            return when (val c = s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("bad literal")
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            if (depth > MAX_DEPTH) throw JsonException("too deep")
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("key expected")
                val key = readString()
                skipWs()
                if (peek() != ':') throw JsonException("':' expected")
                i++
                map[key] = readValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> throw JsonException("',' or '}' expected")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            if (depth > MAX_DEPTH) throw JsonException("too deep")
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                list.add(readValue(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> throw JsonException("',' or ']' expected")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw JsonException("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> throw JsonException("control character in string")
                    else -> sb.append(c)
                }
                if (sb.length > maxString) throw JsonException("string too long")
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (peek() == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (peek() == 'e' || peek() == 'E') {
                isDouble = true
                i++
                if (peek() == '+' || peek() == '-') i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            if (text.length > 20) throw JsonException("number too long")
            return if (isDouble) {
                text.toDoubleOrNull() ?: throw JsonException("bad number")
            } else {
                text.toLongOrNull() ?: throw JsonException("bad number")
            }
        }
    }
}
