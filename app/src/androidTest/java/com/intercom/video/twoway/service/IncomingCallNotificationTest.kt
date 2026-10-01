package com.intercom.video.twoway.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingCallNotificationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ListenerService.createChannels(context)
    }

    @After
    fun tearDown() = nm.cancel(CallNotificationFactory.ID_CALL)

    @Test
    fun incomingNotificationIsACallWithFullScreenIntentAndAcceptRejectActions() {
        val n = CallNotificationFactory.incoming(context, "Alice")
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertNotNull("full-screen intent shows the call over the lock screen", n.fullScreenIntent)
        assertTrue("Accept and Reject actions", n.actions != null && n.actions.size >= 2)
        assertEquals(ListenerService.CHANNEL_INCOMING, n.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue("cannot be swiped away while ringing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun incomingChannelIsHighImportanceSoItAppearsAsHeadsUpWhenFullScreenIsNotAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = nm.getNotificationChannel(ListenerService.CHANNEL_INCOMING)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun listeningChannelIsQuiet() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(ListenerService.CHANNEL_LISTENING).importance)
    }

    @Test
    fun ongoingCallNotificationOffersHangup() {
        val n = CallNotificationFactory.ongoing(context, "Alice", System.currentTimeMillis())
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertTrue(n.actions != null && n.actions.isNotEmpty())
    }

    @Test
    fun postedIncomingNotificationIsActive() {
        CallNotificationFactory.notify(context, CallNotificationFactory.ID_CALL, CallNotificationFactory.incoming(context, "Alice"))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && nm.activeNotifications.none { it.id == CallNotificationFactory.ID_CALL }) {
            Thread.sleep(50)
        }
        assertTrue(nm.activeNotifications.any { it.id == CallNotificationFactory.ID_CALL })
    }
}
