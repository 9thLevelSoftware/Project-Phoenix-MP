package com.devil.phoenixproject.util

import android.os.Build
import android.os.LocaleList
import co.touchlab.kermit.Logger
import java.util.Locale

/**
 * API 33+: sets application locales through [android.app.LocaleManager].
 * API 26-32: updates the activity configuration and recreates the activity.
 * A blank [languageCode] resets resources to the system default.
 *
 * A non-blank code also updates [java.util.Locale.setDefault] so Locale-default
 * formatting matches the in-app language. A blank code leaves that default untouched.
 */
actual fun applyAppLocale(languageCode: String) {
    // JVM default does not need an activity. Update it even when resource
    // application has to wait for one, and before the pre-33 blank path reads it.
    syncDefaultLocale(languageCode)

    val activity = ActivityHolder.getActivity()
    if (activity == null) {
        Logger.w("LocaleHelper") { "No activity available to apply locale '$languageCode'" }
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+: Use LocaleManager for proper per-app language support
        val localeManager = activity.getSystemService(android.app.LocaleManager::class.java)
        val localeList = if (languageCode.isBlank()) {
            LocaleList.getEmptyLocaleList() // Reset to system default
        } else {
            LocaleList.forLanguageTags(languageCode)
        }
        localeManager.applicationLocales = localeList
        Logger.d("LocaleHelper") { "Applied locale via LocaleManager: $languageCode" }
    } else {
        // API 26-32: Update configuration and recreate activity
        val locale = if (languageCode.isBlank()) {
            Locale.getDefault()
        } else {
            Locale.forLanguageTag(languageCode)
        }
        val config = activity.resources.configuration
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        activity.recreate()
        Logger.d("LocaleHelper") { "Applied locale via configuration + recreate: $languageCode" }
    }
}

/**
 * Points [Locale.getDefault] at [languageCode] so formatters that use the JVM
 * default agree with the in-app language. Blank (system default, or no persisted
 * choice) leaves the current default untouched.
 */
fun syncDefaultLocale(languageCode: String) {
    if (languageCode.isBlank()) return
    Locale.setDefault(Locale.forLanguageTag(languageCode))
}
