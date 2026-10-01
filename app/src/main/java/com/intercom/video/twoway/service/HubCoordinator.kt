package com.intercom.video.twoway.service

import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.NoopLogger
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.discovery.GatewayProbe
import com.intercom.video.twoway.net.network.HubClient
import com.intercom.video.twoway.net.network.HubRegistry
import com.intercom.video.twoway.net.transport.SocketProvider
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Decides every few seconds which hotspot role this phone plays (research R7):
 *  - it shares a hotspot  -> it is the hub: keeps the directory and relays calls;
 *  - it is on WiFi        -> it probes the DHCP gateway; if that answers as a hub it registers there and may relay
 *                           calls through it when a direct path is blocked;
 *  - no WiFi              -> nothing.
 * No android.* imports: the Android pieces (network mode, gateway address) are passed in as functions.
 */
class HubCoordinator(
    private val engine: CallEngine,
    private val hubRegistry: HubRegistry,
    private val hubClient: HubClient,
    private val registry: DeviceRegistry,
    private val sockets: SocketProvider,
    private val self: () -> LocalDevice,
    private val mode: () -> NetworkMode,
    /** The DHCP gateway as host and port, or null when unknown. */
    private val gateway: () -> Pair<String, Int>?,
    private val onHubReachable: (Boolean) -> Unit,
    private val onTick: () -> Unit = {},
    private val logger: Logger = NoopLogger,
    private val intervalMs: Long = 5_000,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hub-coordinator").apply { isDaemon = true } }
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        hubClient.onDisconnected = { onHubReachable(false) }
        scheduler.scheduleWithFixedDelay({ step() }, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        scheduler.shutdownNow()
        hubClient.disconnect()
        hubRegistry.disable()
        engine.hubRouter = null
        engine.relayProvider = null
        engine.roleProvider = { "p" }
        onHubReachable(false)
    }

    /** One decision. Public so tests can drive it. */
    @Synchronized
    fun step() {
        if (!started) return
        try {
            onTick()
            when (mode()) {
                NetworkMode.HOTSPOT_HOST -> becomeHub()
                NetworkMode.NONE -> idle()
                NetworkMode.LAN, NetworkMode.HOTSPOT_CLIENT -> joinHubIfPresent()
            }
        } catch (e: RuntimeException) {
            logger.e(TAG, "hub coordination failed", e)
        }
    }

    private fun becomeHub() {
        if (hubClient.available) hubClient.disconnect()
        engine.relayProvider = null
        if (!hubRegistry.enabled) {
            hubRegistry.enable()
            engine.hubRouter = hubRegistry
            engine.roleProvider = { "h" }
            logger.d(TAG, "this phone is the hotspot hub")
        }
    }

    private fun idle() {
        leaveHubRole()
        hubClient.disconnect()
        engine.relayProvider = null
        onHubReachable(false)
    }

    private fun joinHubIfPresent() {
        leaveHubRole()
        if (hubClient.available) return // still registered
        val gw = gateway()
        if (gw == null) {
            onHubReachable(false)
            return
        }
        val me = self()
        val probe = GatewayProbe.probe(sockets, gw.first, gw.second, me.deviceId, me.name, logger = logger)
        if (probe == null || !probe.isHub) {
            engine.relayProvider = null
            onHubReachable(false)
            return
        }
        registry.seen(probe.deviceId, probe.name, gw.first, gw.second, "h", DiscoverySource.GATEWAY)
        engine.relayProvider = hubClient
        if (hubClient.connect(gw.first, gw.second)) {
            onHubReachable(true)
            logger.d(TAG, "registered with the hotspot hub ${probe.name}")
        } else {
            engine.relayProvider = null
            onHubReachable(false)
        }
    }

    private fun leaveHubRole() {
        if (hubRegistry.enabled) {
            hubRegistry.disable()
            engine.hubRouter = null
            engine.roleProvider = { "p" }
        }
    }

    private companion object {
        const val TAG = "HubCoordinator"
    }
}
