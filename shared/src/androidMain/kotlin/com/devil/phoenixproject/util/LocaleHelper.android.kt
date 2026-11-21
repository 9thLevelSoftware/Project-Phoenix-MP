package com.devil.phoenixproject.util

import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import co.touchlab.kermit.Logger
import java.util.Locale

@Volatile
private var initialJvmLocale: Locale? = null

@Volatile
private var initialSystemResLocale: Locale? = null

private fun readSystemResourcesLocale(): Locale? = runCatching {
    val locales = Resources.getSystem()?.configuration?.locales
    if (locales != null && !locales.isEmpty) locales[0] else null
}.getOrNull()

/**
 * Captures the initial JVM and OS locales before per-app locale overrides mutate [Locale.setDefault].
 */
fun captureInitialSystemLocale() {
    if (initialJvmLocale == null) {
        initialJvmLocale = Locale.getDefault()
    }
    if (initialSystemResLocale == null) {
        initialSystemResLocale = readSystemResourcesLocale()
    }
}

/**
 * Resolves the OS default locale so resetting to System Default ("") restores the platform locale
 * rather than re-reading an overridden [Locale.getDefault].
 */
fun resolveAndroidSystemLocale(): Locale {
    captureInitialSystemLocale()
    val currentSystemRes = readSystemResourcesLocale()
    if (currentSystemRes != null && currentSystemRes != initialSystemResLocale) {
        return currentSystemRes
    }
    return initialJvmLocale ?: currentSystemRes ?: Locale.getDefault()
}

/**
 * API 33+: sets application locales through [android.app.LocaleManager].
 * API 26-32: updates the activity configuration and recreates the activity.
 * A blank [languageCode] resets to the system default.
 */
actual fun applyAppLocale(languageCode: String) {
    captureInitialSystemLocale()
    val trimmed = languageCode.trim()
    val targetLocale = if (trimmed.isEmpty()) {
        resolveAndroidSystemLocale()
    } else {
        Locale.forLanguageTag(trimmed)
    }
    Locale.setDefault(targetLocale)

    val activity = ActivityHolder.getActivity()
    if (activity == null) {
        Logger.w("LocaleHelper") { "No activity available to apply locale '$trimmed'" }
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+: Use LocaleManager for proper per-app language support
        val localeManager = activity.getSystemService(android.app.LocaleManager::class.java)
        val localeList = if (trimmed.isEmpty()) {
            LocaleList.getEmptyLocaleList() // Reset to system default
        } else {
            LocaleList.forLanguageTags(trimmed)
        }
        localeManager.applicationLocales = localeList
        Logger.d("LocaleHelper") { "Applied locale via LocaleManager: '$trimmed'" }
    } else {
        // API 26-32: Update configuration and recreate activity
        val config = activity.resources.configuration
        config.setLocale(targetLocale)
        config.setLocales(LocaleList(targetLocale))
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        activity.recreate()
        Logger.d("LocaleHelper") { "Applied locale via configuration + recreate: '$trimmed'" }
    }
}
