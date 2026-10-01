package com.intercom.video.twoway.call

import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.service.HubCoordinator
import com.intercom.video.twoway.service.LocalDevice
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Which hotspot role a phone takes depending on the network it is on. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HubCoordinatorTest {
    private val devices = mutableListOf<TestDevice>()
    private val coordinators = mutableListOf<HubCoordinator>()

    private fun device(name: String) = TestDevice(name).also { devices += it }

    @AfterEach
    fun tearDown() {
        coordinators.forEach { it.stop() }
        devices.forEach { it.stop() }
    }

    private class Env(var mode: NetworkMode, var gateway: Pair<String, Int>?) {
        val hubReachable = AtomicBoolean(false)
    }

    private fun coordinatorFor(d: TestDevice, env: Env) = HubCoordinator(
        engine = d.engine,
        hubRegistry = d.hubRegistry,
        hubClient = d.hubClient,
        registry = d.registry,
        sockets = PlainSocketProvider,
        self = { LocalDevice(d.deviceId, d.name) },
        mode = { env.mode },
        gateway = { env.gateway },
        onHubReachable = { env.hubReachable.set(it) },
        intervalMs = 60_000, // tests call step() themselves
    ).also {
        coordinators += it
        it.start()
    }

    @Test
    fun aPhoneSharingAHotspotBecomesTheHubAndStopsWhenItStopsSharing() {
        val hub = device("Hotspot Phone")
        hub.start()
        val env = Env(NetworkMode.HOTSPOT_HOST, null)
        val c = coordinatorFor(hub, env)
        c.step()
        assertTrue(hub.hubRegistry.enabled)
        assertNotNull(hub.engine.hubRouter)

        env.mode = NetworkMode.LAN // the hotspot was switched off
        c.step()
        assertFalse(hub.hubRegistry.enabled)
        assertNull(hub.engine.hubRouter)
    }

    @Test
    fun aPhoneOnWifiWhoseGatewayIsAHubRegistersAndShowsTheHub() {
        val hub = device("Hotspot Phone")
        val hubAddr = hub.start()
        hub.becomeHub()
        val client = device("Client")
        client.start()
        val env = Env(NetworkMode.LAN, "127.0.0.1" to hubAddr.port)
        coordinatorFor(client, env).step()

        assertTrue(env.hubReachable.get(), "reported to the network layer (mode becomes HOTSPOT_CLIENT)")
        assertTrue(client.hubClient.available)
        assertNotNull(client.engine.relayProvider)
        assertTrue(await { hub.hubRegistry.clientCount == 1 })
        val seen = client.registry.devices.value.single { it.deviceId == hub.deviceId }
        assertEquals("h", seen.role)
        assertTrue(DiscoverySource.GATEWAY in seen.sources)
    }

    @Test
    fun aPhoneOnAnOrdinaryRouterIsNotAClientOfAnything() {
        val router = device("Plain Phone at the gateway address")
        val addr = router.start() // answers HELLO as a normal phone (role p)
        val client = device("Client")
        client.start()
        val env = Env(NetworkMode.LAN, "127.0.0.1" to addr.port)
        coordinatorFor(client, env).step()
        assertFalse(env.hubReachable.get())
        assertFalse(client.hubClient.available)
        assertNull(client.engine.relayProvider)
    }

    @Test
    fun noGatewayOrNoWifiMeansNoHubRole() {
        val client = device("Client")
        client.start()
        val env = Env(NetworkMode.LAN, null)
        val c = coordinatorFor(client, env)
        c.step()
        assertFalse(env.hubReachable.get())

        env.mode = NetworkMode.NONE
        c.step()
        assertFalse(client.hubClient.available)
        assertNull(client.engine.relayProvider)
    }

    @Test
    fun whenTheHubGoesAwayTheClientNoticesAndCanRejoinALaterHub() {
        val hub = device("Hotspot Phone")
        val hubAddr = hub.start()
        hub.becomeHub()
        val client = device("Client")
        client.start()
        val env = Env(NetworkMode.LAN, "127.0.0.1" to hubAddr.port)
        val c = coordinatorFor(client, env)
        c.step()
        assertTrue(client.hubClient.available)

        hub.hubRegistry.disable() // the hotspot phone stops sharing: it closes its clients
        assertTrue(await { !client.hubClient.available }, "client notices the disconnect")
        assertTrue(await { !env.hubReachable.get() })

        hub.becomeHub() // the hotspot comes back
        c.step()
        assertTrue(await { client.hubClient.available })
    }

    @Test
    fun theHubRaisesItsConnectionLimitAndRestoresItWhenDisabled() {
        val hub = device("Hotspot Phone")
        hub.start()
        assertEquals(8, hub.engine.connectionLimiter.maxConcurrent)
        val engineHub = com.intercom.video.twoway.net.network.HubRegistry(
            PlainSocketProvider,
            com.intercom.video.twoway.core.SystemClock,
            limiter = hub.engine.connectionLimiter,
        )
        engineHub.enable()
        assertTrue(hub.engine.connectionLimiter.maxConcurrent > 8, "a hub keeps one connection per registered phone")
        engineHub.disable()
        assertEquals(8, hub.engine.connectionLimiter.maxConcurrent)
    }
}
