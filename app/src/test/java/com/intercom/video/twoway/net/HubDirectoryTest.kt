package com.intercom.video.twoway.net

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.core.protocol.HubDirectory
import com.intercom.video.twoway.core.protocol.HubRegister
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.discovery.GatewayProbe
import com.intercom.video.twoway.net.network.HubRegistry
import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.testing.FakeChannel
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** The hotspot hub's directory and the gateway probe (contracts/signaling.md "Hub", contracts/discovery.md section 3). */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HubDirectoryTest {
    private val clock = FakeClock()
    private val hub = HubRegistry(PlainSocketProvider, clock).also { it.enable() }
    private val devices = mutableListOf<TestDevice>()

    @AfterEach
    fun tearDown() {
        hub.disable()
        devices.forEach { it.stop() }
    }

    private fun id(n: Int) = "%032x".format(n)

    private fun directoryOf(ch: FakeChannel): List<HubDirectory> = ch.sentTexts().mapNotNull { JsonMessageCodec.decode(it) as? HubDirectory }

    private fun register(ch: FakeChannel, n: Int, port: Int = 5000 + n) = hub.onHubMessage(ch, HubRegister(id(n), "Phone $n", port))

    @Test
    fun everyRegisteredPhoneReceivesTheOthersAndUpdatesWhenOneLeaves() {
        val a = FakeChannel("10.0.0.2")
        val b = FakeChannel("10.0.0.3")
        val c = FakeChannel("10.0.0.4")
        register(a, 1)
        register(b, 2)
        register(c, 3)

        val lastForA = directoryOf(a).last().peers
        assertEquals(setOf(id(2), id(3)), lastForA.map { it.id }.toSet(), "a sees b and c but not itself")
        val b2 = lastForA.first { it.id == id(2) }
        assertEquals("10.0.0.3", b2.ip, "address comes from the connection, not from the phone's claim")
        assertEquals(5002, b2.port)
        assertEquals("Phone 2", b2.name)

        hub.onHubChannelClosed(b)
        assertEquals(setOf(id(3)), directoryOf(a).last().peers.map { it.id }.toSet(), "directory is pushed again when b leaves")
        assertEquals(2, hub.clientCount)
    }

    @Test
    fun theDirectoryHoldsAtMost32PhonesAndAnExtraRegistrationIsRefused() {
        val channels = (1..HubRegistry.MAX_CLIENTS).map { n -> FakeChannel("10.0.1.$n").also { register(it, n) } }
        assertEquals(HubRegistry.MAX_CLIENTS, hub.clientCount)
        assertTrue(directoryOf(channels.first()).last().peers.size <= 32)

        val extra = FakeChannel("10.0.9.9")
        register(extra, 99)
        assertTrue(extra.isClosed, "the 33rd phone is refused")
        assertEquals(HubRegistry.MAX_CLIENTS, hub.clientCount)

        // a phone that is already registered may register again (it replaces its old connection)
        val again = FakeChannel("10.0.1.1")
        register(again, 1)
        assertTrue(channels.first().isClosed)
        assertEquals(HubRegistry.MAX_CLIENTS, hub.clientCount)
    }

    @Test
    fun invalidRegistrationsAreRefused() {
        val badId = FakeChannel()
        hub.onHubMessage(badId, HubRegister("short", "x", 5000))
        val badPort = FakeChannel()
        hub.onHubMessage(badPort, HubRegister(id(1), "x", 0))
        assertTrue(badId.isClosed && badPort.isClosed)
        assertEquals(0, hub.clientCount)
    }

    @Test
    fun aDisabledHubRefusesEverything() {
        hub.disable()
        val ch = FakeChannel()
        register(ch, 1)
        assertTrue(ch.isClosed)
        assertEquals(0, hub.clientCount)
    }

    @Test
    fun theGatewayProbeRecognisesAHubByItsHelloReply() {
        val hubPhone = TestDevice("Hotspot Phone").also { devices += it }
        val addr = hubPhone.start()
        hubPhone.becomeHub()
        val client = TestDevice("Client").also { devices += it }

        val result = GatewayProbe.probe(PlainSocketProvider, "127.0.0.1", addr.port, client.deviceId, client.name)
        assertNotNull(result)
        assertTrue(result!!.isHub, "role h")
        assertEquals(hubPhone.deviceId, result.deviceId)
        assertEquals("Hotspot Phone", result.name)
    }

    @Test
    fun aNormalPhoneAnswersTheProbeAsNotAHub() {
        val phone = TestDevice("Plain Phone").also { devices += it }
        val addr = phone.start()
        val result = GatewayProbe.probe(PlainSocketProvider, "127.0.0.1", addr.port, "a".repeat(32), "Me")
        assertNotNull(result)
        assertFalse(result!!.isHub)
    }

    @Test
    fun probingAnAddressWhereNothingListensReturnsNullQuickly() {
        val started = System.currentTimeMillis()
        assertEquals(null, GatewayProbe.probe(PlainSocketProvider, "127.0.0.1", 1, "a".repeat(32), "Me"))
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun clientsThatJoinTheHubSeeEachOtherEvenWithoutAnyOtherDiscovery() {
        val hubPhone = TestDevice("Hotspot Phone").also { devices += it }
        val hubAddr = hubPhone.start()
        hubPhone.becomeHub()
        val a = TestDevice("Alice").also { devices += it }
        val b = TestDevice("Bob").also { devices += it }
        a.start()
        b.start()

        assertTrue(a.joinHub(hubAddr))
        assertTrue(b.joinHub(hubAddr))

        assertTrue(await { a.registry.devices.value.any { it.deviceId == b.deviceId } }, "a lists b: ${a.registry.devices.value}")
        assertTrue(await { b.registry.devices.value.any { it.deviceId == a.deviceId } })
        val seen = a.registry.devices.value.first { it.deviceId == b.deviceId }
        assertEquals("Bob", seen.displayName)
        assertTrue(DiscoverySource.HUB_DIRECTORY in seen.sources)
        assertEquals(2, hubPhone.hubRegistry.clientCount)
    }

    @Test
    fun leavingTheHubRemovesThePhoneFromOthersDirectories() {
        val hubPhone = TestDevice("Hotspot Phone").also { devices += it }
        val hubAddr = hubPhone.start()
        hubPhone.becomeHub()
        val a = TestDevice("Alice").also { devices += it }
        val b = TestDevice("Bob").also { devices += it }
        a.start()
        b.start()
        a.joinHub(hubAddr)
        b.joinHub(hubAddr)
        assertTrue(await { hubPhone.hubRegistry.clientCount == 2 })

        b.hubClient.disconnect()
        assertTrue(await { hubPhone.hubRegistry.clientCount == 1 }, "hub forgets b")
    }
}
