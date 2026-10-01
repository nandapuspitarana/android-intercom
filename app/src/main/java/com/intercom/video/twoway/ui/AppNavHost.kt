package com.intercom.video.twoway.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.intercom.video.twoway.R
import com.intercom.video.twoway.TwoWayApp
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.ListenerService
import com.intercom.video.twoway.service.PairingUiState
import com.intercom.video.twoway.ui.call.CallingScreen
import com.intercom.video.twoway.ui.call.EndedScreen
import com.intercom.video.twoway.ui.call.InCallScreen
import com.intercom.video.twoway.ui.call.IncomingCallScreen
import com.intercom.video.twoway.ui.devices.DeviceListScreen
import com.intercom.video.twoway.ui.history.HistoryScreen
import com.intercom.video.twoway.ui.history.HistoryViewModel
import com.intercom.video.twoway.ui.hotspot.HotspotScreen
import com.intercom.video.twoway.ui.hotspot.HotspotViewModel
import com.intercom.video.twoway.ui.pairing.PairedDevicesScreen
import com.intercom.video.twoway.ui.pairing.PairedDevicesViewModel
import com.intercom.video.twoway.ui.pairing.PairingActions
import com.intercom.video.twoway.ui.pairing.PairingScreen
import com.intercom.video.twoway.ui.pairing.rememberQrScanner
import com.intercom.video.twoway.ui.settings.SettingsScreen
import com.intercom.video.twoway.ui.settings.SettingsViewModel

object Routes {
    const val DEVICES = "devices"
    const val PAIRING = "pairing"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val HOTSPOT = "hotspot"
}

/**
 * Root of the UI. A call in progress, then a pairing in progress, take over the whole screen; otherwise the
 * navigation graph is shown. State comes from the service-hosted runtime, so rotating or leaving the app never
 * loses a call or a pairing.
 */
@Composable
fun AppNavHost() {
    PermissionsGate {
        val context = LocalContext.current
        LaunchedEffect(Unit) { ListenerService.start(context) }

        val container = (context.applicationContext as TwoWayApp).container
        val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(container))
        val call by vm.callState.collectAsState()
        val pairing by vm.pairingState.collectAsState()

        val pairingActions = remember(vm) {
            PairingActions(
                onAccept = { vm.acceptPairing() },
                onDecline = { vm.declinePairing() },
                onConfirm = { vm.confirmCode(it) },
                onCancel = { vm.cancelPairing() },
                onDismiss = { vm.dismissPairing() },
            )
        }

        when (val s = call) {
            is CallUiState.Calling, is CallUiState.Connecting -> CallingScreen(s, onCancel = vm::hangup)
            is CallUiState.Incoming -> IncomingCallScreen(s.peerName, onAccept = vm::accept, onReject = vm::reject)
            is CallUiState.InCall -> InCallScreen(s, onEnd = vm::hangup, onMute = vm::setMuted, onRoute = vm::setRoute)
            is CallUiState.Ended -> EndedScreen(s, onDismiss = vm::dismissEnded)
            CallUiState.Idle -> if (pairing != PairingUiState.Idle) {
                PairingScreen(pairing, pairingActions)
            } else {
                val devices by vm.devices.collectAsState()
                val selfName by vm.selfName.collectAsState()
                val pairedIds by vm.pairedIds.collectAsState()
                val networkMode by vm.networkMode.collectAsState()
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.DEVICES) {
                    composable(Routes.DEVICES) {
                        DeviceListScreen(
                            devices = devices,
                            selfName = selfName,
                            onCall = vm::call,
                            pairedIds = pairedIds,
                            onPair = vm::pair,
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                            onOpenHistory = { nav.navigate(Routes.HISTORY) },
                            onOpenPaired = { nav.navigate(Routes.PAIRING) },
                            networkMode = networkMode,
                            onOpenHotspot = { nav.navigate(Routes.HOTSPOT) },
                            onAddByAddress = vm::addByAddress,
                        )
                    }
                    composable(Routes.PAIRING) {
                        val pvm: PairedDevicesViewModel = viewModel(factory = PairedDevicesViewModel.Factory(container))
                        val paired by pvm.devices.collectAsState()
                        val scan = rememberQrScanner(stringResource(R.string.qr_scan)) { text -> vm.pairFromCode(text) }
                        PairedDevicesScreen(
                            devices = paired,
                            myCode = remember { pvm.myCode() },
                            onScan = scan,
                            onPasteCode = vm::pairFromCode,
                            onRename = pvm::rename,
                            onRemove = pvm::remove,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.HISTORY) {
                        val hvm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(container))
                        val entries by hvm.entries.collectAsState()
                        HistoryScreen(
                            entries = entries,
                            onCallBack = { hvm.callBack(it) },
                            onClear = hvm::clear,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        val svm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))
                        val settings by svm.settings.collectAsState()
                        SettingsScreen(
                            settings = settings,
                            onListenInBackground = svm::setListenInBackground,
                            onStartOnBoot = svm::setStartOnBoot,
                            onAutoRejectUnknown = svm::setAutoRejectUnknown,
                            onLanguage = { language ->
                                svm.setLanguage(language)
                                (context as? android.app.Activity)?.recreate() // re-read every string in the new language
                            },
                            onRingtone = svm::setRingtone,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.HOTSPOT) {
                        val hvm: HotspotViewModel = viewModel(factory = HotspotViewModel.Factory(container))
                        val mode by hvm.mode.collectAsState()
                        val hotspotState by hvm.state.collectAsState()
                        val hasPermission by hvm.hasPermission.collectAsState()
                        HotspotScreen(
                            mode = mode,
                            state = hotspotState,
                            isSupported = hvm.isSupported,
                            hasPermission = hasPermission,
                            onStart = hvm::start,
                            onStop = hvm::stop,
                            onPermissionResult = hvm::refreshPermission,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
