package com.intercom.video.twoway.net.discovery

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.protocol.Json
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.protocol.PROTOCOL_VERSION

/** UDP multicast announce/query datagrams (contracts/discovery.md). */
object Announce {
    const val MAX_BYTES = 256
    const val GROUP = "239.255.42.99"
    const val PORT = 45679

    sealed interface Parsed {
        data class Announcement(val id: String, val name: String, val port: Int, val role: String) : Parsed
        data object Query : Parsed
    }

    fun encodeAnnounce(id: String, name: String, port: Int, role: String): ByteArray {
        var n = name
        var bytes: ByteArray
        // Keep the datagram within MAX_BYTES even for long or multi-byte names.
        do {
            bytes = Json.write(
                mapOf("t" to "announce", "v" to PROTOCOL_VERSION, "id" to id, "n" to n, "p" to port.toLong(), "r" to role),
            ).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) n = n.dropLast(1)
        } while (bytes.size > MAX_BYTES && n.isNotEmpty())
        return bytes
    }

    fun encodeQuery(): ByteArray = Json.write(mapOf("t" to "query", "v" to PROTOCOL_VERSION)).toByteArray(Charsets.UTF_8)

    /** Returns null for anything that must be ignored (oversize, invalid JSON, other major version...). */
    fun parse(data: ByteArray, length: Int = data.size): Parsed? {
        if (length <= 0 || length > MAX_BYTES) return null
        val o = try {
            Json.parseObject(String(data, 0, length, Charsets.UTF_8))
        } catch (e: JsonException) {
            return null
        }
        if (o["v"] != PROTOCOL_VERSION) return null
        return when (o["t"]) {
            "query" -> Parsed.Query
            "announce" -> {
                val id = o["id"] as? String ?: return null
                val name = o["n"] as? String ?: return null
                val port = (o["p"] as? Long)?.toInt() ?: return null
                val role = o["r"] as? String ?: "p"
                Parsed.Announcement(id, name, port, role)
            }
            else -> null
        }
    }
}

/** Allows at most [maxPerSecond] datagrams per source per second; the rest are dropped. */
class SourceRateLimiter(private val clock: Clock, private val maxPerSecond: Int = 10) {
    private class Window(var startMs: Long, var count: Int)

    private val windows = HashMap<String, Window>()

    @Synchronized
    fun allow(source: String): Boolean {
        val now = clock.nowMs()
        val w = windows.getOrPut(source) { Window(now, 0) }
        if (now - w.startMs >= 1_000) {
            w.startMs = now
            w.count = 0
        }
        if (windows.size > MAX_SOURCES) windows.entries.removeAll { now - it.value.startMs >= 1_000 }
        w.count++
        return w.count <= maxPerSecond
    }

    private companion object {
        const val MAX_SOURCES = 1024
    }
}
