package com.intercom.video.twoway.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.core.media.AudioRoute
import com.intercom.video.twoway.service.CallUiState
import kotlinx.coroutines.delay

/**
 * Established call: peer, duration, microphone indicator (always visible while the mic is live, FR-018), mute and audio
 * route controls, an indicator when the peer muted, and a banner when the connection is poor.
 */
@Composable
fun InCallScreen(state: CallUiState.InCall, onEnd: () -> Unit, onMute: (Boolean) -> Unit = {}, onRoute: (AudioRoute) -> Unit = {}) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.connectedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsedMs = if (state.connectedAtMs > 0) (now - state.connectedAtMs).coerceAtLeast(0) else 0L

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.peerName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("call_peer"))
        Text(stringResource(R.string.state_connected), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("call_status"))
        Text(formatElapsed(elapsedMs), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("call_duration"))

        // Microphone indicator: a red dot while the microphone is being sent, a clear "muted" label when it is not.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // one announcement for screen readers: "Microphone is on" / "Microphone is muted"
            modifier = Modifier.testTag("mic_indicator").semantics(mergeDescendants = true) {},
        ) {
            if (!state.muted && state.micLive) Row(Modifier.size(12.dp).background(Color.Red, CircleShape)) {}
            Text(stringResource(if (state.muted) R.string.mic_muted else R.string.mic_live), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.peerMuted) {
            Text(stringResource(R.string.peer_muted, state.peerName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("peer_muted"))
        }
        if (state.poorConnection) {
            Text(
                stringResource(R.string.poor_connection),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("poor_connection"),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onMute(!state.muted) }, modifier = Modifier.testTag("mute_button")) {
                Text(stringResource(if (state.muted) R.string.action_unmute else R.string.action_mute))
            }
            val next = nextRoute(state.route, state.availableRoutes)
            OutlinedButton(onClick = { onRoute(next) }, enabled = next != state.route, modifier = Modifier.testTag("route_button")) {
                Text(stringResource(R.string.route_label, routeLabel(state.route)))
            }
        }
        Button(
            onClick = onEnd,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("end_call"),
        ) { Text(stringResource(R.string.action_end)) }
    }
}

@Composable
private fun routeLabel(r: AudioRoute): String = stringResource(
    when (r) {
        AudioRoute.EARPIECE -> R.string.route_earpiece
        AudioRoute.SPEAKER -> R.string.route_speaker
        AudioRoute.BLUETOOTH -> R.string.route_bluetooth
    },
)

/** The next route to switch to, cycling earpiece, speaker, Bluetooth over what is [available]. */
fun nextRoute(current: AudioRoute, available: Set<AudioRoute>): AudioRoute {
    val order = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER, AudioRoute.BLUETOOTH).filter { it in available || it == current }
    if (order.size <= 1) return current
    return order[(order.indexOf(current) + 1) % order.size]
}

/** "m:ss" (or "h:mm:ss" past an hour). */
fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
