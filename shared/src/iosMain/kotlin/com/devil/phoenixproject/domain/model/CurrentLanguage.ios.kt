package com.devil.phoenixproject.domain.model

import platform.Foundation.NSUserDefaults

/**
 * iOS actual for [currentLanguageCode]. Returns the language subtag of the
 * first entry of the `AppleLanguages` array in `NSUserDefaults` — this is
 * the user-selected preferred language set via Settings → General →
 * Language & Region, which is exactly the value the bug report's Italian
 * iPhone uses.
 *
 * Returns `""` when the array is empty or its first entry is not a string
 * (e.g. before the app has read user defaults).
 *
 * `AppleLanguages` values are BCP-47 tags like `"en-US"` / `"it-IT"`; we
 * only need the language subtag for the percent-format decision.
 */
actual fun currentLanguageCode(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    val languages: Any? = defaults.objectForKey("AppleLanguages")
    @Suppress("UNCHECKED_CAST")
    val list = languages as? List<String>
    val first = list?.firstOrNull().orEmpty()
    return first.substringBefore('-').lowercase()
}
