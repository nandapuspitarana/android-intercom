package com.intercom.video.twoway.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.data.entity.PairedDevice

/**
 * Paired devices (rename locally, remove) plus this phone's pairing QR code, a camera scanner and a text box for
 * pasting a code (for phones without a camera).
 */
@Composable
fun PairedDevicesScreen(
    devices: List<PairedDevice>,
    myCode: String?,
    onScan: () -> Unit,
    onPasteCode: (String) -> Boolean,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf<PairedDevice?>(null) }
    var removing by remember { mutableStateOf<PairedDevice?>(null) }
    var pasted by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.paired_devices_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack, modifier = Modifier.testTag("paired_back")) { Text(stringResource(R.string.action_back)) }
        }

        if (devices.isEmpty()) {
            Text(stringResource(R.string.paired_devices_empty), modifier = Modifier.testTag("paired_empty"))
        } else {
            devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().testTag("paired_${d.deviceId}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(d.displayName, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = {
                            renaming = d
                        }, modifier = Modifier.testTag("rename_${d.deviceId}")) { Text(stringResource(R.string.paired_rename)) }
                        TextButton(onClick = {
                            removing = d
                        }, modifier = Modifier.testTag("remove_${d.deviceId}")) { Text(stringResource(R.string.paired_remove)) }
                    }
                }
                HorizontalDivider()
            }
        }

        Text(stringResource(R.string.qr_my_code), style = MaterialTheme.typography.titleMedium)
        if (myCode != null) {
            val bitmap = remember(myCode) { QrPairing.bitmap(myCode) }
            Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.qr_my_code), modifier = Modifier.size(220.dp).testTag("my_qr"))
            Text(stringResource(R.string.qr_my_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.qr_no_address), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("qr_no_address"))
        }
        Button(onClick = onScan, modifier = Modifier.testTag("qr_scan")) { Text(stringResource(R.string.qr_scan)) }

        OutlinedTextField(
            value = pasted,
            onValueChange = {
                pasted = it
                invalid = false
            },
            label = { Text(stringResource(R.string.qr_paste_hint)) },
            isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(R.string.qr_invalid)) }) else null,
            modifier = Modifier.fillMaxWidth().testTag("pairing_link_input"),
        )
        OutlinedButton(
            onClick = { invalid = !onPasteCode(pasted) },
            enabled = pasted.isNotBlank(),
            modifier = Modifier.testTag("pairing_link_go"),
        ) { Text(stringResource(R.string.qr_paste_go)) }
    }

    renaming?.let { d ->
        var name by remember(d.deviceId) { mutableStateOf(d.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.paired_rename_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.testTag("rename_input")) },
            confirmButton = {
                TextButton(onClick = {
                    onRename(d.deviceId, name)
                    renaming = null
                }, modifier = Modifier.testTag("rename_save")) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.paired_remove_title, d.displayName)) },
            text = { Text(stringResource(R.string.paired_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(d.deviceId)
                    removing = null
                }, modifier = Modifier.testTag("remove_confirm")) { Text(stringResource(R.string.paired_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
