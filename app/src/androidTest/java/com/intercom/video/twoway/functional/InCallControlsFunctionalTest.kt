package com.intercom.video.twoway.functional

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.core.media.AudioRoute
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.FlakySocketProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * US6 through the real UI: duration, mute, audio route, microphone indicator, the peer's mute indicator, poor connection
 * and connection loss. The app's clock is fake and its sockets can be black-holed to simulate a lost WiFi.
 */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class InCallControlsFunctionalTest {
    private val clock = FakeClock()
    private val flaky = FlakySocketProvider()
    private val setup = FunctionalTestRule(clock = clock, sockets = flaky)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private fun appCall() = setup.container.runtimeOrNull!!.engine.state.value

    /** Launches the app, calls the (paired, auto-answering) peer and waits for the call screen with audio flowing. */
    @Before
    fun placeCall() {
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("call_${setup.peer.deviceId}")
        compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
        compose.waitForTag("end_call")
        assertTrue(await { setup.container.audio.totalLoudFrames > 5 && setup.peer.audio.totalLoudFrames > 5 })
    }

    @Test
    fun theDurationCounterAdvancesAndTheMicrophoneIndicatorIsVisible() {
        compose.waitForTag("call_duration")
        val first = compose.onNodeWithTag("call_duration").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("starts near zero: $first", first.startsWith("00:0"))
        // the UI ticker runs on the compose test clock, which only advances while the test waits on compose
        compose.waitUntil(6_000) {
            val now = compose.onNodeWithTag("call_duration").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
            now != first
        }
        compose.onNodeWithTag("mic_indicator").assertIsDisplayed()
        compose.onNodeWithTag("mic_indicator").assertTextContains("Microphone is on")
    }

    @Test
    fun muteSilencesTheVoiceToThePeerAndShowsTheIndicatorsBothWays() {
        compose.onNodeWithTag("mute_button").assertTextContains("Mute")
        compose.onNodeWithTag("mute_button").performClick()

        compose.waitForText("Unmute")
        compose.onNodeWithTag("mic_indicator").assertTextContains("Microphone is muted")
        // the peer is told: its engine shows our mute, and it hears nothing new
        assertTrue(await { (setup.peer.state as? CallUiState.InCall)?.peerMuted == true })
        val media = setup.peer.engine.currentMedia!!
        Thread.sleep(300)
        val voice = media.voiceReceived.get()
        val alive = media.keepAlivesReceived.get()
        // the app's clock is fake in this test, so move it on: a keep-alive is due once per second of engine time
        assertTrue(
            "keep-alives keep the path open",
            await(3_000) {
                clock.advance(1_100)
                media.keepAlivesReceived.get() > alive
            },
        )
        assertEquals("no voice while muted", voice, media.voiceReceived.get())

        compose.onNodeWithTag("mute_button").performClick()
        compose.waitForText("Mute")
        compose.onNodeWithTag("mic_indicator").assertTextContains("Microphone is on")
        assertTrue("voice resumes", await { media.voiceReceived.get() > voice })
    }

    @Test
    fun whenThePeerMutesTheAppShowsIt() {
        assertFalse(compose.hasTag("peer_muted"))
        setup.peer.engine.setMuted(true)
        compose.waitForTag("peer_muted")
        compose.onNodeWithTag("peer_muted").assertTextContains("Peer Phone muted their microphone")
        setup.peer.engine.setMuted(false)
        assertTrue(await { !compose.hasTag("peer_muted") })
    }

    @Test
    fun theAudioRouteButtonSwitchesBetweenEarpieceAndSpeaker() {
        compose.onNodeWithTag("route_button").assertTextContains("Audio: Earpiece")
        compose.onNodeWithTag("route_button").performClick()
        compose.waitForText("Audio: Speaker")
        assertEquals(AudioRoute.SPEAKER, setup.container.audio.route)
        compose.onNodeWithTag("route_button").performClick()
        compose.waitForText("Audio: Earpiece")
        assertEquals(AudioRoute.EARPIECE, setup.container.audio.route)
    }

    @Test
    fun aStalledNetworkShowsPoorConnectionThenEndsAsConnectionLost() {
        assertFalse(compose.hasTag("poor_connection"))

        flaky.dropping = true // WiFi stalls
        clock.advance(3_500)
        compose.waitForTag("poor_connection")
        compose.onNodeWithTag("poor_connection").assertTextContains("Poor connection", substring = true)
        assertTrue("the call is still up", appCall() is CallUiState.InCall)
        assertTrue(compose.hasTag("end_call"))

        clock.advance(7_000) // 10.5 s without any valid message
        compose.waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Connection lost")
        compose.onNodeWithTag("ended_ok").performClick()
        compose.waitForTag("call_${setup.peer.deviceId}")
    }

    @Test
    fun aShortStallRecoversWithoutEndingTheCall() {
        flaky.dropping = true
        clock.advance(3_500)
        compose.waitForTag("poor_connection")
        flaky.dropping = false
        assertTrue("banner disappears", await(8_000) { !compose.hasTag("poor_connection") })
        assertTrue(appCall() is CallUiState.InCall)
    }

    @Test
    fun theOngoingCallNotificationKeepsTheMicrophoneVisibleInTheShade() {
        val nm = setup.container.appContext.getSystemService(android.app.NotificationManager::class.java)
        // The foreground notification is replaced by the ongoing-call one (category call) while capture runs.
        assertTrue(
            "ongoing-call notification",
            await(8_000) {
                nm.activeNotifications.any { it.notification.category == android.app.Notification.CATEGORY_CALL }
            },
        )
        compose.onNodeWithTag("end_call").performClick()
        compose.waitForTag("ended_message")
        assertTrue(
            "and it goes away when the call ends",
            await(8_000) { nm.activeNotifications.none { it.notification.category == android.app.Notification.CATEGORY_CALL } },
        )
    }
}
