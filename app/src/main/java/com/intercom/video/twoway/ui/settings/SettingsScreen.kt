package com.intercom.video.twoway.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R
import com.intercom.video.twoway.data.Settings
import com.intercom.video.twoway.ui.LocaleHelper

/** Background listening, start on boot, ringtone and battery guidance (FR-010). */
@Composable
fun SettingsScreen(
    settings: Settings,
    onListenInBackground: (Boolean) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onAutoRejectUnknown: (Boolean) -> Unit = {},
    onLanguage: (String) -> Unit = {},
    onRingtone: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            onRingtone(uri?.toString())
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Text(stringResource(R.string.action_back)) }
        }

        SwitchRow(
            title = stringResource(R.string.settings_listen_background),
            description = stringResource(R.string.settings_listen_background_desc),
            checked = settings.listenInBackground,
            tag = "switch_listen_background",
            onChange = onListenInBackground,
        )
        SwitchRow(
            title = stringResource(R.string.settings_start_on_boot),
            description = null,
            checked = settings.startOnBoot,
            tag = "switch_start_on_boot",
            onChange = onStartOnBoot,
        )

        SwitchRow(
            title = stringResource(R.string.settings_auto_reject),
            description = stringResource(R.string.settings_auto_reject_desc),
            checked = settings.autoRejectUnknown,
            tag = "switch_auto_reject",
            onChange = onAutoRejectUnknown,
        )

        LanguagePicker(current = settings.language, onSelect = onLanguage)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.settings_ringtone), style = MaterialTheme.typography.titleMedium)
                Text(ringtoneTitle(context, settings.ringtoneUri), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ringtone_title"))
            }
            OutlinedButton(
                onClick = { picker.launch(ringtonePickerIntent(settings.ringtoneUri)) },
                modifier = Modifier.testTag("ringtone_choose"),
            ) { Text(stringResource(R.string.settings_ringtone_choose)) }
        }

        HorizontalDivider()
        BackgroundGuidance()
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun ringtoneTitle(context: Context, uri: String?): String {
    val default = stringResource(R.string.settings_ringtone_default)
    if (uri == null) return default
    return try {
        RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) ?: default
    } catch (_: Exception) {
        default
    }
}

private fun ringtonePickerIntent(current: String?): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let { Uri.parse(it) })

/** English / Indonesian / follow the phone (FR-023). */
@Composable
private fun LanguagePicker(current: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        listOf(
            LocaleHelper.SYSTEM to R.string.language_system,
            LocaleHelper.ENGLISH to R.string.language_en,
            LocaleHelper.INDONESIAN to R.string.language_in,
        ).forEach { (code, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = current == code, onClick = { onSelect(code) }, role = Role.RadioButton).testTag("language_$code"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == code, onClick = null)
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
