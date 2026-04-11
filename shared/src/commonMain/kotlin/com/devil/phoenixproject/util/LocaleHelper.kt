package com.devil.phoenixproject.util

/**
 * Apply the given language code to the platform locale system.
 *
 * On Android this uses AppCompatDelegate.setApplicationLocales().
 * On iOS this sets AppleLanguages in NSUserDefaults.
 *
 * @param languageCode BCP-47 language tag (e.g., "en", "de", "es"). Empty string resets to system default.
 */
expect fun applyAppLocale(languageCode: String)
