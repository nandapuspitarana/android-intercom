package com.intercom.video.twoway.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.intercom.video.twoway.R
import com.intercom.video.twoway.ui.MainActivity
import com.intercom.video.twoway.ui.call.IncomingCallActivity

/** Builds the notifications used for listening, incoming calls and ongoing calls. */
object CallNotificationFactory {
    const val ID_LISTENING = 1001
    const val ID_CALL = 1002
    const val ID_MISSED = 1003

    const val ACTION_REJECT = "com.intercom.video.twoway.action.REJECT"
    const val ACTION_HANGUP = "com.intercom.video.twoway.action.HANGUP"

    private fun immutable(extra: Int = 0) = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or extra

    /** Low-priority "ready to receive calls" notification (the foreground-service notification). */
    fun listening(context: Context): Notification {
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), immutable())
        return NotificationCompat.Builder(context, ListenerService.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.service_icon)
            .setContentTitle(context.getString(R.string.listening_title))
            .setContentText(context.getString(R.string.listening_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * High-importance incoming-call notification: category CALL, a full-screen intent to
     * [IncomingCallActivity] (shown over the lock screen), and Accept / Reject actions. When the system does
     * not allow full-screen intents (Android 14+ permission not granted) it still appears as a heads-up
     * notification with the same actions.
     */
    fun incoming(context: Context, peerName: String): Notification {
        val person = Person.Builder().setName(peerName).setImportant(true).build()
        val screen = PendingIntent.getActivity(context, 10, incomingActivityIntent(context, accept = false), immutable())
        val accept = PendingIntent.getActivity(context, 11, incomingActivityIntent(context, accept = true), immutable())
        val reject = PendingIntent.getService(
            context,
            12,
            Intent(context, ListenerService::class.java).setAction(ACTION_REJECT),
            immutable(),
        )
        return NotificationCompat.Builder(context, ListenerService.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.service_icon)
            .setContentTitle(context.getString(R.string.incoming_call_from, peerName))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, reject, accept))
            .build()
    }

    /** Notification while a call is connecting or in progress (also the microphone indicator). */
    fun ongoing(context: Context, peerName: String, connectedAtWallMs: Long = 0L): Notification {
        val person = Person.Builder().setName(peerName).build()
        val open = PendingIntent.getActivity(context, 20, incomingActivityIntent(context, accept = false), immutable())
        val hangup = PendingIntent.getService(
            context,
            21,
            Intent(context, ListenerService::class.java).setAction(ACTION_HANGUP),
            immutable(),
        )
        val b = NotificationCompat.Builder(context, ListenerService.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.service_icon)
            .setContentTitle(peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup))
        if (connectedAtWallMs > 0) b.setWhen(connectedAtWallMs).setUsesChronometer(true)
        return b.build()
    }

    /** "Missed call from X" shown when an incoming call ends unanswered (MISSED or cancelled by the caller). */
    fun missed(context: Context, peerName: String): Notification {
        val open = PendingIntent.getActivity(context, 30, Intent(context, MainActivity::class.java), immutable())
        return NotificationCompat.Builder(context, ListenerService.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.service_icon)
            .setContentTitle(context.getString(R.string.ended_missed))
            .setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    fun incomingActivityIntent(context: Context, accept: Boolean): Intent = Intent(context, IncomingCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(IncomingCallActivity.EXTRA_ACCEPT, accept)

    fun notify(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked: the call still rings through the foreground UI.
        }
    }

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)
}
