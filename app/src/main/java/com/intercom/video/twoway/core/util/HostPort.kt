package com.intercom.video.twoway.core.util

/** A typed `ip` or `ip:port` (IPv4), for adding a device by its address (contracts/discovery.md section 4). */
class HostPort(val host: String, val port: Int) {
    companion object {
        const val DEFAULT_PORT = 45678
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /** Returns null unless [text] is a valid IPv4 address with an optional `:port` (1-65535). Never throws. */
        fun parse(text: String): HostPort? {
            val t = text.trim()
            if (t.isEmpty() || t.length > 21) return null
            val colon = t.indexOf(':')
            val hostPart = if (colon >= 0) t.substring(0, colon) else t
            val port = if (colon >= 0) t.substring(colon + 1).takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: return null else DEFAULT_PORT
            if (port !in 1..65535) return null
            val m = IPV4.matchEntire(hostPart) ?: return null
            if ((1..4).any { m.groupValues[it].toInt() !in 0..255 }) return null
            return HostPort(hostPart, port)
        }
    }
}
