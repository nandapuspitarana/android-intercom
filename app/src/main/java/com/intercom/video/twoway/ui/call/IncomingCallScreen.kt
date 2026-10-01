package com.intercom.video.twoway.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** Callee side: who is calling, with Accept and Reject. Also shown over the lock screen (US2). */
@Composable
fun IncomingCallScreen(peerName: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.incoming_call_from, peerName),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("incoming_title"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = onReject,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("reject_call"),
            ) { Text(stringResource(R.string.action_reject)) }
            Button(onClick = onAccept, modifier = Modifier.testTag("accept_call")) {
                Text(stringResource(R.string.action_accept))
            }
        }
    }
}
