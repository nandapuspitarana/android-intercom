package com.intercom.video.twoway.core.pairing

import com.intercom.video.twoway.core.util.Hex
import com.intercom.video.twoway.core.util.NameSanitizer
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The text inside a pairing QR code: `twoway://pair?v=1&id=<deviceId>&ip=<addr>&p=<port>&fp=<16-byte hex>&n=<name>`.
 * It carries NO secret: only where to connect and the first 16 bytes of the SHA-256 of the device's long-term identity
 * key, which the pairing handshake checks. The 6-digit code comparison still happens after scanning.
 */
class PairingLink(
    val deviceId: String,
    val host: String,
    val port: Int,
    /** First 16 bytes of SHA-256(identity public key). */
    val fingerprint: ByteArray,
    val name: String,
) {
    fun encode(): String = "$PREFIX?v=1&id=$deviceId&ip=$host&p=$port&fp=${Hex.encode(fingerprint)}&n=${URLEncoder.encode(name, "UTF-8")}"

    companion object {
        const val PREFIX = "twoway://pair"
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /** Returns null for anything that is not a well-formed Two Way pairing link. Never throws. */
        fun parse(text: String): PairingLink? {
            if (text.length > 400 || !text.startsWith("$PREFIX?")) return null
            val params = HashMap<String, String>()
            for (pair in text.substring(PREFIX.length + 1).split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) return null
                val key = pair.substring(0, i)
                val value = try {
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    return null
                }
                if (params.put(key, value) != null) return null // duplicate keys are ambiguous
            }
            if (params["v"] != "1") return null
            val id = params["id"]?.takeIf { it.length == 32 && Hex.decode(it) != null } ?: return null
            val host = params["ip"]?.takeIf { isIpv4(it) } ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fp = params["fp"]?.takeIf { it.length == 32 }?.let { Hex.decode(it) } ?: return null
            val name = NameSanitizer.sanitize(params["n"]) ?: return null
            return PairingLink(id.lowercase(), host, port, fp, name)
        }

        private fun isIpv4(s: String): Boolean {
            val m = IPV4.matchEntire(s) ?: return false
            return (1..4).all { m.groupValues[it].toInt() in 0..255 }
        }
    }
}
