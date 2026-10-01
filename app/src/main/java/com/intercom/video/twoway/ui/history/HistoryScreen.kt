package com.intercom.video.twoway.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.data.entity.CallDirection
import com.intercom.video.twoway.data.entity.CallHistoryEntry
import com.intercom.video.twoway.data.entity.CallOutcome
import java.text.DateFormat
import java.util.Date

/** Incoming / outgoing / missed calls stored on this phone; tap an entry to call that person back. */
@Composable
fun HistoryScreen(entries: List<CallHistoryEntry>, onCallBack: (CallHistoryEntry) -> Unit, onClear: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
            Row {
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear, modifier = Modifier.testTag("history_clear")) {
                        Text(stringResource(R.string.history_clear))
                    }
                }
                TextButton(onClick = onBack, modifier = Modifier.testTag("history_back")) { Text(stringResource(R.string.action_back)) }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.history_empty), modifier = Modifier.testTag("history_empty"))
            }
        } else {
            LazyColumn(modifier = Modifier.testTag("history_list")) {
                items(entries, key = { it.id }) { e ->
                    HistoryRow(e, onClick = { onCallBack(e) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: CallHistoryEntry, onClick: () -> Unit) {
    val missed = e.outcome == CallOutcome.MISSED
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag("history_row_${e.id}")) {
        Text(
            e.peerName,
            style = MaterialTheme.typography.titleMedium,
            color = if (missed) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        val direction = stringResource(if (e.direction == CallDirection.INCOMING) R.string.history_incoming else R.string.history_outgoing)
        val outcome = stringResource(outcomeRes(e.outcome))
        val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.startedAt))
        val duration = if (e.durationMs > 0) " · ${formatDuration(e.durationMs)}" else ""
        Text("$direction · $outcome · $whenText$duration", style = MaterialTheme.typography.bodySmall)
    }
}

private fun outcomeRes(o: CallOutcome) = when (o) {
    CallOutcome.COMPLETED -> R.string.outcome_completed
    CallOutcome.MISSED -> R.string.outcome_missed
    CallOutcome.DECLINED -> R.string.outcome_declined
    CallOutcome.BUSY -> R.string.outcome_busy
    CallOutcome.CANCELLED -> R.string.outcome_cancelled
    CallOutcome.FAILED -> R.string.outcome_failed
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
