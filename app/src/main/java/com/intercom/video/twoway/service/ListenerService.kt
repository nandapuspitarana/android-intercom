package com.intercom.video.twoway.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.intercom.video.twoway.R
import com.intercom.video.twoway.TwoWayApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that keeps the device reachable for incoming calls. It starts the shared call runtime held
 * by the app container and reacts to call state: ringing + a full-screen notification for an incoming call, an
 * ongoing-call notification (with the microphone foreground type) while talking, and back to the quiet
 * "listening" notification afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenerService : Service() {
    inner class LocalBinder : Binder() {
        val service: ListenerService get() = this@ListenerService
    }

    private val binder = LocalBinder()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container get() = (application as TwoWayApp).container

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(com.intercom.video.twoway.ui.LocaleHelper.wrap(newBase))

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        promote(CallNotificationFactory.listening(this), microphone = false)
        ioScope.launch { container.startRuntime() }
        observeCallState()
        observeAvailability()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CallNotificationFactory.ACTION_REJECT -> container.runtime.value?.engine?.reject()
            CallNotificationFactory.ACTION_HANGUP -> container.runtime.value?.engine?.hangup()
            else -> ioScope.launch { container.startRuntime() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        container.ringer.stop()
        CallNotificationFactory.cancel(this, CallNotificationFactory.ID_CALL)
        runBlocking { container.stopRuntime() }
        ioScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    /** Reacts to engine state: ring, notify, and move the foreground notification between listening and in-call. */
    private fun observeCallState() {
        mainScope.launch {
            container.runtime.filterNotNull().flatMapLatest { it.engine.state }.collect { state ->
                when (state) {
                    is CallUiState.Incoming -> {
                        CallNotificationFactory.notify(
                            this@ListenerService,
                            CallNotificationFactory.ID_CALL,
                            CallNotificationFactory.incoming(this@ListenerService, state.peerName),
                        )
                        ioScope.launch { container.ringer.start(container.settings.currentRingtone()) }
                    }
                    is CallUiState.Calling, is CallUiState.Connecting -> {
                        container.ringer.stop()
                        CallNotificationFactory.cancel(this@ListenerService, CallNotificationFactory.ID_CALL)
                        promote(CallNotificationFactory.ongoing(this@ListenerService, nameOf(state)), microphone = true)
                    }
                    is CallUiState.InCall -> {
                        container.ringer.stop()
                        CallNotificationFactory.cancel(this@ListenerService, CallNotificationFactory.ID_CALL)
                        promote(
                            CallNotificationFactory.ongoing(this@ListenerService, state.peerName, state.connectedAtMs),
                            microphone = true,
                        )
                    }
                    is CallUiState.Ended, CallUiState.Idle -> {
                        container.ringer.stop()
                        CallNotificationFactory.cancel(this@ListenerService, CallNotificationFactory.ID_CALL)
                        promote(CallNotificationFactory.listening(this@ListenerService), microphone = false)
                    }
                }
            }
        }
    }

    /**
     * FR-010: with background listening off, incoming calls are only accepted while the app is on screen; the
     * service then stops itself when the app goes to the background and no call is active.
     */
    private fun observeAvailability() {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
        mainScope.launch {
            combine(container.settings.settings, foreground, container.runtime.filterNotNull()) { settings, fg, runtime ->
                Availability(settings.listenInBackground || fg, settings.listenInBackground, settings.autoRejectUnknown, runtime)
            }.collect { (accepting, listenInBackground, autoReject, runtime) ->
                runtime.engine.acceptingCalls = accepting
                runtime.engine.autoRejectUnknown = autoReject
                val idle = runtime.engine.state.value.let { it is CallUiState.Idle || it is CallUiState.Ended }
                if (!listenInBackground && !accepting && idle) stopSelf()
            }
        }
    }

    private data class Availability(val accepting: Boolean, val listenInBackground: Boolean, val autoReject: Boolean, val runtime: CallRuntime)

    private fun nameOf(state: CallUiState) = when (state) {
        is CallUiState.Calling -> state.peerName
        is CallUiState.Connecting -> state.peerName
        else -> ""
    }

    /**
     * Shows [notification] as the foreground notification. While a call is active the microphone foreground type is
     * added (research R8); Android 14+ only allows that while the app is visible, which is the case when the user
     * places or accepts a call, so a refusal just falls back to the plain type.
     */
    private fun promote(notification: android.app.Notification, microphone: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && microphone) {
                startForeground(
                    CallNotificationFactory.ID_LISTENING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(CallNotificationFactory.ID_LISTENING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(CallNotificationFactory.ID_LISTENING, notification)
            }
        } catch (e: RuntimeException) {
            container.logger.w(TAG, "cannot update foreground type: ${e.message}")
            if (microphone) promote(CallNotificationFactory.listening(this), microphone = false)
        }
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_INCOMING = "incoming"
        private const val TAG = "ListenerService"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ListenerService::class.java))
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LISTENING,
                    context.getString(R.string.channel_listening),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INCOMING,
                    context.getString(R.string.channel_incoming),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(null, null) }, // the app plays its own ringtone and honours the ringer mode
            )
        }
    }
}
