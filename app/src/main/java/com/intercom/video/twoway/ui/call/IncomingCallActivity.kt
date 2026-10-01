package com.intercom.video.twoway.ui.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.intercom.video.twoway.TwoWayApp
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.ui.AppViewModel
import com.intercom.video.twoway.ui.theme.TwoWayTheme

/**
 * The call screen shown over the lock screen with the screen turned on when a call arrives while the phone is
 * idle (FR-009). Accepting from here happens in a visible activity, which is what lets the microphone start on
 * Android 14+. It closes itself once the call is over.
 */
class IncomingCallActivity : ComponentActivity() {
    private var acceptRequested = false

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(com.intercom.video.twoway.ui.LocaleHelper.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        acceptRequested = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        val container = (applicationContext as TwoWayApp).container
        val vm = ViewModelProvider(this, AppViewModel.Factory(container))[AppViewModel::class.java]
        setContent {
            TwoWayTheme {
                val call by vm.callState.collectAsState()
                var sawCall by remember { mutableStateOf(false) }
                LaunchedEffect(call) {
                    when (val s = call) {
                        is CallUiState.Incoming -> {
                            sawCall = true
                            if (acceptRequested) {
                                acceptRequested = false
                                vm.accept()
                            }
                        }
                        is CallUiState.Calling, is CallUiState.Connecting, is CallUiState.InCall -> sawCall = true
                        is CallUiState.Ended -> {
                            vm.dismissEnded() // result is reported by the notification / main screen
                            finish()
                        }
                        CallUiState.Idle -> if (sawCall) finish()
                    }
                }
                when (val s = call) {
                    is CallUiState.Incoming -> IncomingCallScreen(s.peerName, onAccept = vm::accept, onReject = vm::reject)
                    is CallUiState.Calling, is CallUiState.Connecting -> CallingScreen(s, onCancel = vm::hangup)
                    is CallUiState.InCall -> InCallScreen(s, onEnd = vm::hangup, onMute = vm::setMuted, onRoute = vm::setRoute)
                    else -> Unit
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ACCEPT, false)) acceptRequested = true
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        }
    }

    companion object {
        const val EXTRA_ACCEPT = "accept"
    }
}
