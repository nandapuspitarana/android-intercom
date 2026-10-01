package com.intercom.video.twoway.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.service.PairingFailure
import com.intercom.video.twoway.service.PairingUiState

/** Actions the pairing screens can trigger. */
class PairingActions(
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    val onConfirm: (Boolean) -> Unit,
    val onCancel: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Pairing takes over the screen while a handshake is in progress: waiting for an answer, an incoming request, the
 * 6-digit code both users compare, and the result. Nothing is saved until BOTH users pressed "Matches".
 */
@Composable
fun PairingScreen(state: PairingUiState, actions: PairingActions) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            PairingUiState.Idle -> Unit
            is PairingUiState.Requesting -> {
                Text(
                    stringResource(R.string.pairing_requesting, state.peerName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("pairing_status"),
                )
                OutlinedButton(onClick = actions.onCancel, modifier = Modifier.testTag("pairing_cancel")) { Text(stringResource(R.string.pairing_cancel)) }
            }
            is PairingUiState.IncomingRequest -> {
                Text(
                    stringResource(R.string.pairing_incoming_title, state.peerName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("pairing_incoming"),
                )
                Text(stringResource(R.string.pairing_incoming_hint), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = actions.onDecline, modifier = Modifier.testTag("pairing_decline")) { Text(stringResource(R.string.action_reject)) }
                    Button(onClick = actions.onAccept, modifier = Modifier.testTag("pairing_accept")) { Text(stringResource(R.string.action_accept)) }
                }
            }
            is PairingUiState.Verify -> {
                Text(stringResource(R.string.pairing_compare, state.peerName), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                SelectionContainer {
                    Text(
                        text = state.code.chunked(3).joinToString(" "),
                        fontSize = 48.sp,
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.testTag("pairing_code"),
                    )
                }
                if (state.awaitingPeer) {
                    Text(stringResource(R.string.pairing_waiting_peer, state.peerName), modifier = Modifier.testTag("pairing_waiting"))
                    OutlinedButton(onClick = actions.onCancel, modifier = Modifier.testTag("pairing_cancel")) { Text(stringResource(R.string.pairing_cancel)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { actions.onConfirm(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("pairing_not_matches"),
                        ) { Text(stringResource(R.string.pairing_not_matches)) }
                        Button(onClick = { actions.onConfirm(true) }, modifier = Modifier.testTag("pairing_matches")) {
                            Text(stringResource(R.string.pairing_matches))
                        }
                    }
                }
            }
            is PairingUiState.Done -> {
                Text(
                    stringResource(R.string.pairing_done, state.peerName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("pairing_done"),
                )
                Text(stringResource(R.string.pairing_done_hint))
                Button(onClick = actions.onDismiss, modifier = Modifier.testTag("pairing_ok")) { Text(stringResource(R.string.action_ok)) }
            }
            is PairingUiState.Failed -> {
                Text(
                    failureMessage(state),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pairing_failed"),
                )
                Button(onClick = actions.onDismiss, modifier = Modifier.testTag("pairing_ok")) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}

@Composable
fun failureMessage(f: PairingUiState.Failed): String = when (f.reason) {
    PairingFailure.DECLINED -> stringResource(R.string.pairing_failed_declined, f.peerName)
    PairingFailure.MISMATCH -> stringResource(R.string.pairing_failed_mismatch)
    PairingFailure.TIMEOUT -> stringResource(R.string.pairing_failed_timeout)
    PairingFailure.UNREACHABLE -> stringResource(R.string.pairing_failed_unreachable, f.peerName)
    PairingFailure.IDENTITY_CHANGED -> stringResource(R.string.pairing_failed_identity_changed, f.peerName)
    PairingFailure.QR_MISMATCH -> stringResource(R.string.pairing_failed_qr_mismatch)
    PairingFailure.ERROR -> stringResource(R.string.pairing_failed_error)
}
