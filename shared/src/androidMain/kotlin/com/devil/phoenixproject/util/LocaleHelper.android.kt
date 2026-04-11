package com.devil.phoenixproject.util

import android.os.Build
import android.os.LocaleList
import co.touchlab.kermit.Logger
import java.util.Locale

actual fun applyAppLocale(languageCode: String) {
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
