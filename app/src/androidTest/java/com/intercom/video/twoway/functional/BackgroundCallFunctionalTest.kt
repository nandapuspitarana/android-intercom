package com.intercom.video.twoway.functional

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.intercom.video.twoway.service.BootReceiver
import com.intercom.video.twoway.service.CallNotificationFactory
import com.intercom.video.twoway.service.CallUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US2: calls reach a phone whose app is in the background and whose screen is off, and can be answered
 * from the lock screen. Uses UiAutomator for the system UI (screen, notification, lock screen).
 */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class BackgroundCallFunctionalTest {
    @get:Rule
    val setup = FunctionalTestRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val context: Context get() = instrumentation.targetContext

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    private val engine get() = setup.container.runtimeOrNull!!.engine

    @Test
    fun aCallRingsAndCanBeAnsweredWhileTheScreenIsOff() {
        setup.launch()
        device.pressHome() // app to the background
        device.sleep() // screen off
        assertTrue("screen should be off", await(5_000) { !device.isScreenOn })

        val callStart = System.currentTimeMillis()
        setup.peer.callApp(setup.appAddress())

        // Ringtone and vibration start, and the call notification is posted (category call).
        assertTrue("phone must ring", await(5_000) { setup.container.ringer.isRinging })
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("incoming-call notification", await(5_000) { nm.activeNotifications.any { it.id == CallNotificationFactory.ID_CALL } })
        val posted = nm.activeNotifications.first { it.id == CallNotificationFactory.ID_CALL }.notification
        assertTrue(posted.category == android.app.Notification.CATEGORY_CALL)

        // The screen turns on and the call is shown over the lock screen within 3 s of the call (SC-005).
        assertTrue("screen must turn on for the call", await(3_000) { device.isScreenOn })
        assertTrue("alert within 3 s", System.currentTimeMillis() - callStart < 3_500)

        // Accept from the incoming-call screen without unlocking.
        val accept = device.wait(Until.findObject(By.text("Accept")), 10_000)
        assertNotNull("incoming-call screen with an Accept button", accept)
        accept.click()

        assertTrue("call connects", await { engine.state.value is CallUiState.InCall })
        assertTrue("ringing stops once answered", await { !setup.container.ringer.isRinging })
        assertTrue(
            "two-way audio (app ${setup.container.audio.totalLoudFrames}, peer ${setup.peer.audio.totalLoudFrames})",
            await { setup.container.audio.totalLoudFrames > 5 && setup.peer.audio.totalLoudFrames > 5 },
        )
        assertTrue("call notification replaced by the ongoing-call one", await { nm.activeNotifications.none { it.id == CallNotificationFactory.ID_CALL } })

        setup.peer.hangup()
        assertTrue("call ends", await { engine.state.value is CallUiState.Ended || engine.state.value is CallUiState.Idle })
    }

    @Test
    fun aCallThatIsNotAnsweredStopsRingingWhenTheCallerGivesUp() {
        setup.launch()
        device.pressHome()
        setup.peer.callApp(setup.appAddress())
        assertTrue(await { setup.container.ringer.isRinging })

        setup.peer.hangup() // caller cancels while ringing
        assertTrue("ringing stops", await { !setup.container.ringer.isRinging })
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("incoming notification removed", await { nm.activeNotifications.none { it.id == CallNotificationFactory.ID_CALL } })
        assertTrue(await { engine.state.value is CallUiState.Ended || engine.state.value is CallUiState.Idle })
    }

    @Test
    fun withBackgroundListeningOffACallerIsToldTheDeviceIsUnavailable() {
        setup.launch()
        val appAddress = setup.appAddress() // where the phone used to listen
        runBlocking { setup.container.settings.setListenInBackground(false) }
        device.pressHome() // app leaves the screen: the service stops listening
        assertTrue("service stops listening", await(10_000) { setup.container.runtimeOrNull == null })

        setup.peer.callApp(appAddress)
        // The fake peer dismisses its end screen immediately, so read the call record instead of the transient state.
        assertTrue("caller is told the call ended", await { setup.peer.device.records.isNotEmpty() })
        val record = setup.peer.device.records.last()
        assertTrue("unavailable, not a ring: ${record.reason}", record.reason == com.intercom.video.twoway.core.call.EndReason.UNAVAILABLE)
        assertFalse("the phone must not ring", setup.container.ringer.isRinging)
    }

    @Test
    fun afterRebootListeningStartsOnlyWhenStartOnBootIsOn() {
        setup.launch()
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Off by default: the receiver does nothing.
        runBlocking { setup.container.stopRuntime() }
        BootReceiver().onReceive(context, boot)
        Thread.sleep(1_000)
        assertTrue("not started when the option is off", setup.container.runtimeOrNull == null)

        // On: listening is running again.
        runBlocking { setup.container.settings.setStartOnBoot(true) }
        BootReceiver().onReceive(context, boot)
        assertTrue("listening restarts after boot", await(10_000) { setup.container.runtimeOrNull?.started == true })
    }
}
