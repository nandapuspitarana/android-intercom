package com.intercom.video.twoway.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val listenInBackground: Boolean = true,
    val startOnBoot: Boolean = false,
    val autoRejectUnknown: Boolean = false,
    val ringtoneUri: String? = null,
    /** "system", "en" or "in". */
    val language: String = "system",
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            listenInBackground = p[LISTEN_IN_BACKGROUND] ?: true,
            startOnBoot = p[START_ON_BOOT] ?: false,
            autoRejectUnknown = p[AUTO_REJECT_UNKNOWN] ?: false,
            ringtoneUri = p[RINGTONE_URI],
            language = p[LANGUAGE] ?: "system",
        )
    }

    suspend fun currentRingtone(): String? = settings.first().ringtoneUri

    suspend fun setListenInBackground(v: Boolean) = store.edit { it[LISTEN_IN_BACKGROUND] = v }
    suspend fun setStartOnBoot(v: Boolean) = store.edit { it[START_ON_BOOT] = v }
    suspend fun setAutoRejectUnknown(v: Boolean) = store.edit { it[AUTO_REJECT_UNKNOWN] = v }
    suspend fun setLanguage(v: String) = store.edit { it[LANGUAGE] = v }
    suspend fun setRingtoneUri(v: String?) = store.edit {
        if (v == null) it.remove(RINGTONE_URI) else it[RINGTONE_URI] = v
    }

    private companion object {
        val LISTEN_IN_BACKGROUND = booleanPreferencesKey("listen_in_background")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val AUTO_REJECT_UNKNOWN = booleanPreferencesKey("auto_reject_unknown")
        val RINGTONE_URI = stringPreferencesKey("ringtone_uri")
        val LANGUAGE = stringPreferencesKey("language")
    }
}
