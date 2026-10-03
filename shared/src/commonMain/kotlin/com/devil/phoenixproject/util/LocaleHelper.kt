package com.devil.phoenixproject.util

/**
 * Apply the given language code to the platform locale system.
 *
 * On Android API 33+ this sets application locales through LocaleManager.
 * On Android API 26-32 it updates the activity configuration and recreates the activity.
 * On iOS this sets AppleLanguages in NSUserDefaults.
 *
 * @param languageCode BCP-47 language tag (e.g., "en", "de", "es"). Empty string resets to system default.
 */
expect fun applyAppLocale(languageCode: String)
