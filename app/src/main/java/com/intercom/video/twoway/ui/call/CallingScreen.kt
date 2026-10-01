package com.intercom.video.twoway.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.service.CallUiState

/** Caller side: calling / ringing / connecting (cancel available). */
@Composable
fun CallingScreen(state: CallUiState, onCancel: () -> Unit) {
    val (peer, status) = when (state) {
        is CallUiState.Calling ->
            state.peerName to stringResource(if (state.ringing) R.string.state_ringing else R.string.state_calling)
        is CallUiState.Connecting -> state.peerName to stringResource(R.string.state_connecting)
        else -> "" to ""
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.calling_to, peer), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("call_peer"))
        Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("call_status"))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("cancel_call"),
        ) { Text(stringResource(R.string.action_end)) }
    }
}

/** Maps why a call ended to the message the user reads. */
@Composable
fun endedMessage(reason: EndReason): String = stringResource(
    when (reason) {
        EndReason.COMPLETED -> R.string.ended_completed
        EndReason.DECLINED -> R.string.ended_declined
        EndReason.BUSY -> R.string.ended_busy
        EndReason.CANCELLED -> R.string.ended_cancelled
        EndReason.MISSED -> R.string.ended_missed
        EndReason.UNAVAILABLE -> R.string.ended_unavailable
        EndReason.CONNECTION_FAILED -> R.string.ended_connection_failed
        EndReason.CONNECTION_LOST -> R.string.ended_connection_lost
        EndReason.NOT_PAIRED -> R.string.ended_not_paired
        EndReason.NETWORK_BLOCKED -> R.string.ended_network_blocked
    },
)

/** Result screen after a call; stays until the user taps OK. */
@Composable
fun EndedScreen(state: CallUiState.Ended, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.peerName.isNotEmpty()) Text(state.peerName, style = MaterialTheme.typography.headlineSmall)
        Text(endedMessage(state.reason), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("ended_message"))
        Button(onClick = onDismiss, modifier = Modifier.testTag("ended_ok")) { Text(stringResource(R.string.action_ok)) }
    }
}
