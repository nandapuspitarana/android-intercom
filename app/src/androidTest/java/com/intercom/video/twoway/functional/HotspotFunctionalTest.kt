package com.intercom.video.twoway.functional

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.IsolatedSocketProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * US5 through the real UI on a simulated hotspot. A FakePeerDevice plays the hotspot phone (hub) and another plays a
 * second client phone; the app's own sockets can be told to drop connections to that client ("client isolation").
 */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class HotspotFunctionalTest {
    private val isolation = IsolatedSocketProvider()

    /** `setup.peer` is the second CLIENT phone ("Peer Phone"), already paired with the app. */
    private val setup = FunctionalTestRule(sockets = isolation)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val hubPhones = mutableListOf<FakePeerDevice>()

    private fun hubPhone(): FakePeerDevice = FakePeerDevice("Hotspot Phone").also {
        it.start()
        it.device.becomeHub()
        hubPhones += it
    }

    @org.junit.After
    fun stopHub() = hubPhones.forEach { it.stop() }

    @Test
    fun theHubIsFoundByTheGatewayProbeAndItsDirectoryAppearsInTheList() {
        val hub = hubPhone()
        assertTrue(setup.peer.device.joinHub(hub.address)) // the other client registered with the hub
        setup.container.gateway = "127.0.0.1" to hub.address.port
        setup.launch()

        // The hub itself (found by the gateway probe) and the other client (from the hub's directory) both show up.
        compose.waitForTag("device_${hub.deviceId}")
        compose.waitForTag("device_${setup.peer.deviceId}")
        compose.onNodeWithTag("device_${setup.peer.deviceId}").assertIsDisplayed()
        val rt = setup.container.runtimeOrNull!!
        assertTrue(DiscoverySource.GATEWAY in rt.registry.get(hub.deviceId)!!.sources)
        assertTrue(DiscoverySource.HUB_DIRECTORY in rt.registry.get(setup.peer.deviceId)!!.sources)

        // The app joined the hotspot as a client: the banner says so.
        compose.waitForTag("network_banner")
        assertEquals(NetworkMode.HOTSPOT_CLIENT, setup.container.mode.value)
        assertTrue(await { hub.device.hubRegistry.clientCount == 2 })
    }

    @Test
    fun withTheDirectPathBlockedTheCallStillConnectsThroughTheHubWithAudio() {
        val hub = hubPhone()
        assertTrue(setup.peer.device.joinHub(hub.address))
        setup.container.gateway = "127.0.0.1" to hub.address.port
        isolation.block("127.0.0.1", setup.peer.address.port) // the hotspot drops phone-to-phone traffic
        setup.launch()
        compose.waitForTag("call_${setup.peer.deviceId}", 15_000)

        compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
        compose.waitForTag("end_call", 15_000)
        assertTrue("the direct attempt was really blocked", isolation.blockedConnects >= 1)
        assertEquals("the call is relayed by the hub", 1, hub.device.hubRegistry.relayCount)
        assertTrue(
            "audio flows both ways through the hub (app ${setup.container.audio.totalLoudFrames}, peer ${setup.peer.audio.totalLoudFrames})",
            await { setup.container.audio.totalLoudFrames > 5 && setup.peer.audio.totalLoudFrames > 5 },
        )

        compose.onNodeWithTag("end_call").performClick()
        compose.waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Call ended")
        assertTrue(await { hub.device.hubRegistry.relayCount == 0 })
    }

    @Test
    fun whenNeitherADirectPathNorTheRelayWorksTheUserIsToldWhy() {
        val hub = hubPhone() // the other client never joined the hub, so the hub cannot relay to it
        setup.container.gateway = "127.0.0.1" to hub.address.port
        isolation.block("127.0.0.1", setup.peer.address.port)
        setup.launch()
        compose.waitForTag("device_${hub.deviceId}")
        setup.discoverPeer() // known another way (e.g. typed address), but unreachable

        compose.waitForTag("call_${setup.peer.deviceId}")
        compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
        compose.waitForTag("ended_message", 10_000) // within the 10 s connect timeout
        compose.onNodeWithTag("ended_message")
            .assertTextContains("This network does not let phones talk to each other, so the call could not connect.")
        assertTrue(setup.peer.state is CallUiState.Idle)
    }

    @Test
    fun anInvalidTypedAddressShowsAMessage() {
        setup.launch()
        compose.waitForTag("devices_empty")
        compose.onNodeWithTag("manual_add").performClick()
        compose.waitForTag("manual_ip_input")
        compose.onNodeWithTag("manual_ip_input").performTextInput("not an address")
        compose.onNodeWithTag("manual_ip_add").performClick()
        compose.waitForTag("manual_ip_error")
        compose.onNodeWithTag("manual_ip_error").assertTextContains("That is not a valid address.")
    }

    @Test
    fun aTypedAddressOfARunningPhoneAddsItToTheList() {
        setup.launch()
        compose.waitForTag("devices_empty")
        compose.onNodeWithTag("manual_add").performClick()
        compose.waitForTag("manual_ip_input")
        compose.onNodeWithTag("manual_ip_input").performTextInput("127.0.0.1:${setup.peer.address.port}")
        compose.onNodeWithTag("manual_ip_add").performClick()

        compose.waitForTag("device_${setup.peer.deviceId}")
        val d = setup.container.runtimeOrNull!!.registry.get(setup.peer.deviceId)!!
        assertTrue(DiscoverySource.MANUAL in d.sources)
        assertEquals("Peer Phone", d.displayName)
    }

    @Test
    fun anAddressWhereNobodyAnswersShowsAMessage() {
        setup.launch()
        compose.waitForTag("devices_empty")
        compose.onNodeWithTag("manual_add").performClick()
        compose.waitForTag("manual_ip_input")
        compose.onNodeWithTag("manual_ip_input").performTextInput("127.0.0.1:1")
        compose.onNodeWithTag("manual_ip_add").performClick()
        compose.waitForTag("manual_ip_error", 10_000)
        compose.onNodeWithTag("manual_ip_error").assertTextContains("No Two Way phone answered at that address.")
        assertFalse(compose.hasTag("device_${setup.peer.deviceId}"))
    }

    @Test
    fun theBannerExplainsTheNetworkAndOpensTheHotspotHelp() {
        setup.container.mode.value = NetworkMode.NONE
        setup.launch()
        compose.waitForTag("network_banner")
        compose.onNodeWithTag("network_banner").assertIsDisplayed()
        compose.onNodeWithTag("open_hotspot").performClick()

        compose.waitForTag("hotspot_mode")
        compose.onNodeWithTag("hotspot_mode").assertTextContains("Network: Not connected")
        compose.waitForTag("hotspot_join_steps")
        compose.onNodeWithTag("hotspot_join_steps").assertIsDisplayed()
        compose.waitForTag("hotspot_open_settings")
        compose.onNodeWithTag("hotspot_wifi_settings").assertIsDisplayed()
        compose.onNodeWithTag("hotspot_back").performClick()
        compose.waitForTag("network_banner")
    }

    @Test
    fun aPhoneSharingItsHotspotIsTheHubAndAnotherPhoneCanJoinIt() {
        setup.container.mode.value = NetworkMode.HOTSPOT_HOST
        setup.launch()
        // the app becomes the hub: the other phone registers and is listed...
        assertTrue(await { setup.container.hubRegistry?.enabled == true })
        val appAsHub = setup.appAddress()
        assertTrue(setup.peer.device.joinHub(appAsHub))
        assertTrue(await { setup.container.hubRegistry?.clientCount == 1 })
        compose.waitForTag("network_banner")
        assertTrue(compose.hasTag("open_hotspot"))
        // ...and a probe sees it as a hub
        val probe = com.intercom.video.twoway.net.discovery.GatewayProbe.probe(
            com.intercom.video.twoway.net.transport.PlainSocketProvider,
            "127.0.0.1",
            appAsHub.port,
            "d".repeat(32),
            "Probe",
        )
        assertTrue(probe != null && probe.isHub)
    }
}
