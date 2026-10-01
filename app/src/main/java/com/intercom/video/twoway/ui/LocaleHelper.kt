package com.intercom.video.twoway.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language choice (FR-023): "system" follows the phone, "en" is English, "in" is Indonesian. The choice is also
 * kept in SharedPreferences so activities and the service can apply it synchronously when they are created.
 */
object LocaleHelper {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val INDONESIAN = "in"
    val SUPPORTED = listOf(SYSTEM, ENGLISH, INDONESIAN)

    private const val PREFS = "locale"
    private const val KEY = "language"

    fun saved(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)?.takeIf { it in SUPPORTED } ?: SYSTEM

    fun save(context: Context, language: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    /** Returns [base] with the saved language applied (or [base] itself when the phone's language is followed). */
    fun wrap(base: Context): Context = wrap(base, saved(base))

    fun wrap(base: Context, language: String): Context {
        if (language == SYSTEM) return base
        val locale = Locale(language) // "in" is the legacy code Android still uses for Indonesian
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
