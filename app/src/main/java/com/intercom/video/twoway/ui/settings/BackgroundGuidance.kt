package com.intercom.video.twoway.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.video.twoway.R

/** Which phone-maker-specific advice applies (research R8: vendors kill background apps differently). */
enum class Vendor(val guidanceRes: Int) {
    XIAOMI(R.string.guidance_vendor_xiaomi),
    OPPO(R.string.guidance_vendor_oppo),
    VIVO(R.string.guidance_vendor_vivo),
    HUAWEI(R.string.guidance_vendor_huawei),
    SAMSUNG(R.string.guidance_vendor_samsung),
    OTHER(R.string.guidance_vendor_generic),
    ;

    companion object {
        fun fromManufacturer(manufacturer: String): Vendor {
            val m = manufacturer.lowercase()
            return when {
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
                m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> OPPO
                m.contains("vivo") || m.contains("iqoo") -> VIVO
                m.contains("huawei") || m.contains("honor") -> HUAWEI
                m.contains("samsung") -> SAMSUNG
                else -> OTHER
            }
        }
    }
}

/** Opens the system screens a user needs to keep calls ringing when the screen is off (FR-010). */
@Composable
fun BackgroundGuidance(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vendor = Vendor.fromManufacturer(Build.MANUFACTURER ?: "")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.guidance_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.guidance_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { openBatterySettings(context) }, modifier = Modifier.testTag("guidance_battery")) {
            Text(stringResource(R.string.guidance_battery_button))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            OutlinedButton(onClick = { openFullScreenIntentSettings(context) }, modifier = Modifier.testTag("guidance_fullscreen")) {
                Text(stringResource(R.string.guidance_fullscreen_button))
            }
        }
        Text(stringResource(vendor.guidanceRes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("guidance_vendor"))
    }
}

private fun openBatterySettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    launchFirstAvailable(context, intents)
}

private fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    launchFirstAvailable(context, intents)
}

private fun launchFirstAvailable(context: Context, intents: List<Intent>) {
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // try the next, more generic screen
        }
    }
}
