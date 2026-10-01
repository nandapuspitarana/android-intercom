package com.intercom.video.twoway.net.discovery

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.util.NameSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DiscoverySource { NSD, MULTICAST, GATEWAY, MANUAL, HUB_DIRECTORY }

enum class Reachability { UNKNOWN, DIRECT, VIA_HUB }

/** A discovered peer (data-model: Device). Names are sanitized; identity is NOT proven by discovery. */
data class Device(
    val deviceId: String,
    val displayName: String,
    val host: String,
    val port: Int,
    /** "p" peer or "h" hub (hotspot host). */
    val role: String,
    val sources: Set<DiscoverySource>,
    val lastSeenMs: Long,
    val reachability: Reachability = Reachability.UNKNOWN,
)

/** Merges all discovery sources into one list; entries expire after [ttlMs] without being seen again. */
class DeviceRegistry(private val clock: Clock, private val selfId: String, private val ttlMs: Long = DEFAULT_TTL_MS) {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val byId = LinkedHashMap<String, Device>()

    @Synchronized
    fun seen(deviceId: String, rawName: String?, host: String, port: Int, role: String, source: DiscoverySource) {
        if (deviceId == selfId || deviceId.length != ID_HEX_LENGTH) return
        if (port !in 1..65535) return
        val name = NameSanitizer.sanitizeOr(rawName, deviceId.take(8))
        val now = clock.nowMs()
        val old = byId[deviceId]
        byId[deviceId] = Device(
            deviceId = deviceId,
            displayName = name,
            host = host,
            port = port,
            role = if (role == "h") "h" else "p",
            sources = (old?.sources ?: emptySet()) + source,
            lastSeenMs = now,
            reachability = old?.reachability ?: Reachability.UNKNOWN,
        )
        publish()
    }

    @Synchronized
    fun setReachability(deviceId: String, reachability: Reachability) {
        val old = byId[deviceId] ?: return
        byId[deviceId] = old.copy(reachability = reachability)
        publish()
    }

    /** Removes entries not seen for [ttlMs]. Call periodically (about once a second). */
    @Synchronized
    fun expire() {
        val now = clock.nowMs()
        if (byId.values.removeAll { now - it.lastSeenMs > ttlMs }) publish()
    }

    @Synchronized
    fun remove(deviceId: String) {
        if (byId.remove(deviceId) != null) publish()
    }

    @Synchronized
    fun get(deviceId: String): Device? = byId[deviceId]

    @Synchronized
    fun clear() {
        byId.clear()
        publish()
    }

    private fun publish() {
        _devices.value = byId.values.sortedBy { it.displayName.lowercase() }
    }

    companion object {
        const val DEFAULT_TTL_MS = 15_000L
        const val ID_HEX_LENGTH = 32
    }
}
