package com.intercom.video.twoway.functional

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.data.entity.CallDirection
import com.intercom.video.twoway.data.entity.CallOutcome
import com.intercom.video.twoway.service.CallNotificationFactory
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.pair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** US3: decline, cancel, busy and simultaneous calls, seen through the real UI and the stored history. */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class CallOutcomesFunctionalTest {
    private val setup = FunctionalTestRule()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private fun history() = runBlocking { setup.container.database.callHistoryDao().getAll() }

    private fun startAndDiscover() {
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("call_${setup.peer.deviceId}")
    }

    @Test
    fun whenThePeerRejectsTheCallerSeesDeclinedAndItIsRecorded() {
        setup.peer.behavior = FakePeerDevice.Behavior.REJECT
        startAndDiscover()

        compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
        compose.waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Call declined")

        assertTrue("history entry", await { history().isNotEmpty() })
        val entry = history().single()
        assertEquals(CallOutcome.DECLINED, entry.outcome)
        assertEquals(CallDirection.OUTGOING, entry.direction)
        assertEquals(0L, entry.durationMs)
        assertEquals(setup.peer.deviceId, entry.peerDeviceId)
    }

    @Test
    fun whenThePeerIsAlreadyInACallTheCallerSeesBusyAndTheFirstCallIsUntouched() {
        val other = FakePeerDevice("Other Phone").also { it.start() }
        try {
            pair(setup.peer.device, other.device)
            startAndDiscover()

            // Other phone calls the peer; the peer answers: they are now in a call.
            other.callApp(setup.peer.address)
            assertTrue("peer in a call with the other phone", await { setup.peer.state is CallUiState.InCall && other.state is CallUiState.InCall })

            val started = System.currentTimeMillis()
            compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
            compose.waitForTag("ended_message", 5_000)
            compose.onNodeWithTag("ended_message").assertTextContains("The other person is busy")
            assertTrue("busy within 5 s", System.currentTimeMillis() - started < 5_000)

            assertTrue("the call in progress is not disturbed", setup.peer.state is CallUiState.InCall && other.state is CallUiState.InCall)
            assertTrue(await { history().any { it.outcome == CallOutcome.BUSY } })
        } finally {
            other.stop()
        }
    }

    @Test
    fun whenTheCallerCancelsWhileThePeerRingsThePeerSeesAMissedCall() {
        setup.peer.behavior = FakePeerDevice.Behavior.IGNORE
        startAndDiscover()

        compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
        compose.waitForTag("cancel_call")
        assertTrue("peer rings", await { setup.peer.state is CallUiState.Incoming })

        compose.onNodeWithTag("cancel_call").performClick()
        compose.waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Call cancelled")
        assertTrue("peer's call ends as missed", await { setup.peer.device.records.any { it.reason == EndReason.MISSED } })
        assertTrue(await { history().any { it.outcome == CallOutcome.CANCELLED && it.direction == CallDirection.OUTGOING } })
    }

    @Test
    fun whenBothCallEachOtherAtTheSameTimeExactlyOneCallIsEstablished() {
        setup.peer.behavior = FakePeerDevice.Behavior.ACCEPT
        startAndDiscover()

        // Both phones dial each other at the same moment (driven through the engines so the two INVITEs really cross).
        val appEngine = setup.container.runtimeOrNull!!.engine
        setup.peer.callApp(setup.appAddress())
        appEngine.call(com.intercom.video.twoway.service.PeerAddress(setup.peer.deviceId, setup.peer.name, "127.0.0.1", setup.peer.address.port))

        // Whichever phone has the higher id gives up its own call and rings for the other's. If the app is that
        // phone it shows the incoming call; the test answers it. Otherwise the peer auto-answers.
        assertTrue(
            "one call connects",
            await(15_000) {
                if (compose.hasTag("accept_call")) compose.onNodeWithTag("accept_call").performClick()
                compose.hasTag("end_call")
            },
        )
        assertTrue("the peer is in that same call", await { setup.peer.state is CallUiState.InCall })
        assertTrue(await { setup.container.audio.totalLoudFrames > 5 && setup.peer.audio.totalLoudFrames > 5 })

        compose.onNodeWithTag("end_call").performClick()
        compose.waitForTag("ended_message")
        assertTrue("exactly one call completed", await { history().count { it.outcome == CallOutcome.COMPLETED } == 1 })
        assertEquals("no second call survived", 0, history().count { it.outcome == CallOutcome.FAILED })
    }
}

/** US3 ring timeout: the app's clock is fake so the 30 s timer can be fast-forwarded. */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class RingTimeoutFunctionalTest {
    private val clock = FakeClock()
    private val setup = FunctionalTestRule(clock = clock)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    @Test
    fun anUnansweredIncomingCallStopsRingingAfter30SecondsAndIsReportedAsMissed() {
        setup.launch()
        compose.waitForTag("devices_empty")

        setup.peer.callApp(setup.appAddress())
        compose.waitForTag("incoming_title")
        assertTrue("phone rings", await { setup.container.ringer.isRinging })

        clock.advance(30_000) // nobody answers

        compose.waitForTag("ended_message")
        compose.onNodeWithTag("ended_message").assertTextContains("Missed call")
        assertTrue("ringing stops", await { !setup.container.ringer.isRinging })

        val dao = setup.container.database.callHistoryDao()
        assertTrue(
            "missed entry stored",
            await {
                runBlocking { dao.getAll() }.any { it.outcome == CallOutcome.MISSED && it.direction == CallDirection.INCOMING }
            },
        )

        val nm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue("missed-call notification", await { nm.activeNotifications.any { it.id == CallNotificationFactory.ID_MISSED } })
        nm.cancel(CallNotificationFactory.ID_MISSED)
    }
}
