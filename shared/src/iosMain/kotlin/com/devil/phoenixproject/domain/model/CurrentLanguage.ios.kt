package com.devil.phoenixproject.domain.model

import platform.Foundation.NSUserDefaults

/**
 * iOS actual for [currentLanguageCode]. Returns the language subtag of the
 * first entry of the `AppleLanguages` array in `NSUserDefaults` — this is
 * the user-selected preferred language set via Settings → General →
 * Language & Region, which is exactly the value the bug report's Italian
 * iPhone uses.
 *
 * Returns `""` when the array is missing, empty, or not an array of strings
 * (e.g. before the app has read user defaults). [NSUserDefaults.stringArrayForKey]
 * is the type-safe read for that case: it returns null unless the default is
 * an array of strings, which keeps the same empty fallback as before.
 *
 * `AppleLanguages` values are BCP-47 tags like `"en-US"` / `"it-IT"`; we
 * only need the language subtag for the percent-format decision.
 */
actual fun currentLanguageCode(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    val first = defaults.stringArrayForKey("AppleLanguages")
        ?.map { it.toString() }
        ?.firstOrNull()
        .orEmpty()
    return first.substringBefore('-').lowercase()
}
