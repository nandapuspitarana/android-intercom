package com.intercom.video.twoway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.intercom.video.twoway.TwoWayApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts call listening after a reboot, but only if the user turned "start on boot" on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as TwoWayApp).container
        val pending = goAsync() // null when invoked directly (tests); the broadcast is then not time-limited
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (container.settings.settings.first().startOnBoot) ListenerService.start(context.applicationContext)
            } finally {
                pending?.finish()
            }
        }
    }
}
