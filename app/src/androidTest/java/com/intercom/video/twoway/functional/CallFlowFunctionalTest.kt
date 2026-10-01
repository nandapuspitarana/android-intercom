package com.intercom.video.twoway.functional

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.service.CallUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * US1 end to end: the real UI and ListenerService talk to a FakePeerDevice over real localhost
 * sockets, with the real Opus codec and AES-GCM. Run with `./gradlew functionalTest`.
 */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class CallFlowFunctionalTest {
    private val setup = FunctionalTestRule()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun peerAppearsInTheListAndACompleteCallWorks() {
        val peer = setup.peer
        peer.answerDelayMs = 1_200 // ring long enough to observe the "calling" and "ringing" screens
        setup.launch()

        // 1. No devices yet, then the peer is discovered and shown by name.
        waitForTag("devices_empty")
        setup.discoverPeer()
        waitForTag("device_${peer.deviceId}")
        compose.onNodeWithTag("device_${peer.deviceId}").assertIsDisplayed()
        compose.onNodeWithText(peer.name).assertIsDisplayed() // shown by name, not by address

        // 2. Tap Call: the caller sees "calling", then "ringing" while the peer's phone rings.
        compose.onNodeWithTag("call_${peer.deviceId}").performClick()
        waitForTag("call_status")
        waitForText("Ringing")
        assertTrue("peer phone must ring", await { peer.state is CallUiState.Incoming })

        // 3. The peer answers: the app shows the connected call screen.
        waitForTag("end_call")
        compose.onNodeWithTag("call_status").assertTextContains("Connected")
        compose.onNodeWithTag("call_peer").assertTextContains(peer.name)

        // 4. Two-way audio at the same time: each side hears the other's tone.
        assertTrue(
            "audio must flow both ways (app heard ${setup.container.audio.totalLoudFrames}, peer heard ${peer.audio.totalLoudFrames})",
            await { setup.container.audio.totalLoudFrames > 5 && peer.audio.totalLoudFrames > 5 },
        )

        // 5. End the call from the app: both sides end and the app shows the result.
        compose.onNodeWithTag("end_call").performClick()
        waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Call ended")
        assertTrue("peer must see the call end", await { peer.state is CallUiState.Ended || peer.state is CallUiState.Idle })
        assertTrue("the peer's audio mode must be restored", await { !peer.audio.inCallMode })

        // 6. OK returns to the device list.
        compose.onNodeWithTag("ended_ok").performClick()
        waitForTag("device_${peer.deviceId}")
    }

    @Test
    fun whenThePeerHangsUpTheAppReturnsToTheList() {
        val peer = setup.peer
        setup.launch()
        waitForTag("devices_empty")
        setup.discoverPeer()
        waitForTag("call_${peer.deviceId}")

        compose.onNodeWithTag("call_${peer.deviceId}").performClick()
        waitForTag("end_call")

        peer.hangup()
        waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Call ended")
        compose.onNodeWithTag("ended_ok").performClick()
        waitForTag("device_${peer.deviceId}")
    }

    @Test
    fun anIncomingCallFromThePeerRingsAndCanBeAnswered() {
        val peer = setup.peer
        setup.launch()
        waitForTag("devices_empty")

        peer.callApp(setup.appAddress())
        waitForTag("incoming_title")
        compose.onNodeWithTag("incoming_title").assertTextContains(peer.name, substring = true)
        compose.onNodeWithTag("accept_call").performClick()
        waitForTag("end_call")
        assertTrue(await { setup.container.audio.totalLoudFrames > 5 && peer.audio.totalLoudFrames > 5 })
        compose.onNodeWithTag("end_call").performClick()
        waitForTag("ended_message")
    }

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }
}
