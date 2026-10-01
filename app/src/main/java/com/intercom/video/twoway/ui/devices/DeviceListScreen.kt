package com.intercom.video.twoway.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.Device
import com.intercom.video.twoway.service.CallRuntime
import kotlinx.coroutines.launch

/** Nearby devices with a Call button each (FR-001, FR-002). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceListScreen(
    devices: List<Device>,
    selfName: String,
    onCall: (Device) -> Unit,
    pairedIds: Set<String> = emptySet(),
    onPair: (Device) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenPaired: () -> Unit = {},
    networkMode: NetworkMode = NetworkMode.LAN,
    onOpenHotspot: () -> Unit = {},
    onAddByAddress: suspend (String) -> CallRuntime.AddResult = { CallRuntime.AddResult.NO_ANSWER },
) {
    var showAdd by remember { mutableStateOf(false) }
    if (showAdd) AddByAddressDialog(onDismiss = { showAdd = false }, onAdd = onAddByAddress)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineSmall)
        // the buttons wrap onto a second line on narrow screens instead of squeezing their labels
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenPaired, modifier = Modifier.testTag("open_paired")) { Text(stringResource(R.string.pairing_open), maxLines = 1) }
            TextButton(onClick = onOpenHistory, modifier = Modifier.testTag("open_history")) { Text(stringResource(R.string.history_open), maxLines = 1) }
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) { Text(stringResource(R.string.settings_open), maxLines = 1) }
        }
        NetworkBanner(networkMode, onOpenHotspot)
        if (selfName.isNotEmpty()) {
            Text(stringResource(R.string.devices_my_name, selfName), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { showAdd = true }, modifier = Modifier.testTag("manual_add")) { Text(stringResource(R.string.manual_add)) }
        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.devices_empty), modifier = Modifier.testTag("devices_empty"))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("device_list")) {
                items(devices, key = { it.deviceId }) { device ->
                    Card(Modifier.fillMaxWidth().testTag("device_${device.deviceId}")) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val paired = device.deviceId in pairedIds
                            Column {
                                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(device.host, style = MaterialTheme.typography.bodySmall)
                                if (paired) {
                                    Text(
                                        stringResource(R.string.paired_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("paired_badge_${device.deviceId}"),
                                    )
                                }
                            }
                            if (paired) {
                                Button(onClick = { onCall(device) }, modifier = Modifier.testTag("call_${device.deviceId}")) {
                                    Text(stringResource(R.string.action_call))
                                }
                            } else {
                                OutlinedButton(onClick = { onPair(device) }, modifier = Modifier.testTag("pair_${device.deviceId}")) {
                                    Text(stringResource(R.string.pair_button))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkBanner(mode: NetworkMode, onOpenHotspot: () -> Unit) {
    val text = when (mode) {
        NetworkMode.NONE -> R.string.network_none
        NetworkMode.HOTSPOT_HOST -> R.string.network_hotspot_host
        NetworkMode.HOTSPOT_CLIENT -> R.string.network_hotspot_client
        NetworkMode.LAN -> return
    }
    Card(Modifier.fillMaxWidth().testTag("network_banner")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenHotspot, modifier = Modifier.testTag("open_hotspot")) { Text(stringResource(R.string.network_help)) }
        }
    }
}

/** Dialog for adding a phone by typing its address: shows an error if the address is invalid or nobody answers. */
@Composable
fun AddByAddressDialog(onDismiss: () -> Unit, onAdd: suspend (String) -> CallRuntime.AddResult) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.manual_hint)) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.testTag("manual_ip_input"),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("manual_ip_error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                modifier = Modifier.testTag("manual_ip_add"),
                onClick = {
                    busy = true
                    scope.launch {
                        when (onAdd(text)) {
                            CallRuntime.AddResult.ADDED -> onDismiss()
                            CallRuntime.AddResult.INVALID -> error = R.string.manual_invalid
                            CallRuntime.AddResult.NO_ANSWER -> error = R.string.manual_failed
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.manual_add_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
