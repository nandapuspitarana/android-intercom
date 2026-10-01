package com.intercom.video.twoway.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.intercom.video.twoway.R

/** Permissions the app asks for, in one place so tests and UI agree. */
object AppPermissions {
    /** Without the microphone a call would be silently one-way, so the app blocks until it is granted. */
    const val REQUIRED = Manifest.permission.RECORD_AUDIO

    fun requestable(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    fun isRequiredGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, REQUIRED) == PackageManager.PERMISSION_GRANTED
}

/**
 * Shows [content] once the microphone permission is granted; until then explains why each permission
 * is needed and, if the user denied it, how to allow it (FR-020).
 */
@Composable
fun PermissionsGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        recheck++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck++ }

    @Suppress("UNUSED_EXPRESSION")
    recheck // read so this composable recomposes when permissions may have changed
    if (AppPermissions.isRequiredGranted(context)) {
        content()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.permissions_rationale))
        if (askedOnce) {
            Text(stringResource(R.string.permissions_denied_help), color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text(stringResource(R.string.permissions_open_settings)) }
        }
        Button(onClick = { launcher.launch(AppPermissions.requestable()) }) {
            Text(stringResource(R.string.permissions_grant))
        }
    }
}
