package com.intercom.video.twoway.ui.hotspot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.network.HotspotManager
import com.intercom.video.twoway.net.network.HotspotState
import com.intercom.video.twoway.ui.pairing.QrPairing

/** The permission the app needs before it may start a hotspot (Android 13+: nearby devices; before: location). */
fun hotspotPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

@Composable
fun networkModeLabel(mode: NetworkMode): String = stringResource(
    when (mode) {
        NetworkMode.LAN -> R.string.mode_lan
        NetworkMode.HOTSPOT_HOST -> R.string.mode_hotspot_host
        NetworkMode.HOTSPOT_CLIENT -> R.string.mode_hotspot_client
        NetworkMode.NONE -> R.string.mode_none
    },
)

/**
 * Hotspot help (FR-021): start a hotspot from the app (Android 8+) and show the network name, password and a join QR
 * code, or step-by-step instructions for the system hotspot and for joining one; plus a note about client isolation.
 */
@Composable
fun HotspotScreen(
    mode: NetworkMode,
    state: HotspotState,
    isSupported: Boolean,
    hasPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissionResult: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult()
        if (granted) onStart()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack, modifier = Modifier.testTag("hotspot_back")) { Text(stringResource(R.string.action_back)) }
        }
        Text(stringResource(R.string.hotspot_mode, networkModeLabel(mode)), modifier = Modifier.testTag("hotspot_mode"))

        // --- share a hotspot ---
        if (isSupported) {
            when (state) {
                HotspotState.Off, is HotspotState.Failed -> {
                    if (state is HotspotState.Failed) {
                        Text(
                            stringResource(R.string.hotspot_failed, state.reason),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("hotspot_failed"),
                        )
                    }
                    if (!hasPermission) Text(stringResource(R.string.hotspot_permission_needed), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { if (hasPermission) onStart() else permissionLauncher.launch(hotspotPermission()) },
                        modifier = Modifier.testTag("hotspot_start"),
                    ) { Text(stringResource(if (hasPermission) R.string.hotspot_start else R.string.hotspot_grant)) }
                }
                HotspotState.Starting -> Text(stringResource(R.string.hotspot_starting), modifier = Modifier.testTag("hotspot_starting"))
                is HotspotState.On -> {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.hotspot_ssid, state.ssid), modifier = Modifier.testTag("hotspot_ssid"))
                            Text(stringResource(R.string.hotspot_password, state.password), modifier = Modifier.testTag("hotspot_password"))
                        }
                    }
                    val bitmap = remember(state) { QrPairing.bitmap(HotspotManager.wifiQrText(state.ssid, state.password)) }
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.hotspot_ssid, state.ssid),
                        modifier = Modifier.size(220.dp).testTag("hotspot_qr"),
                    )
                    Text(stringResource(R.string.hotspot_scan_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onStop, modifier = Modifier.testTag("hotspot_stop")) { Text(stringResource(R.string.hotspot_stop)) }
                }
            }
        } else {
            Text(stringResource(R.string.hotspot_unsupported), modifier = Modifier.testTag("hotspot_unsupported"))
        }
        OutlinedButton(onClick = { openHotspotSettings(context) }, modifier = Modifier.testTag("hotspot_open_settings")) {
            Text(stringResource(R.string.hotspot_open_settings))
        }

        HorizontalDivider()

        // --- join a hotspot ---
        Text(stringResource(R.string.hotspot_join_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.hotspot_join_steps), modifier = Modifier.testTag("hotspot_join_steps"))
        OutlinedButton(onClick = { openWifiSettings(context) }, modifier = Modifier.testTag("hotspot_wifi_settings")) {
            Text(stringResource(R.string.hotspot_wifi_settings))
        }
        Text(stringResource(R.string.hotspot_isolation_note), style = MaterialTheme.typography.bodySmall)
    }
}

private fun openHotspotSettings(context: Context) {
    launchFirst(context, listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS)))
}

private fun openWifiSettings(context: Context) {
    launchFirst(context, listOf(Intent(Settings.ACTION_WIFI_SETTINGS)))
}

private fun launchFirst(context: Context, intents: List<Intent>) {
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // try the next screen
        }
    }
}
