package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import platform.Foundation.NSUserDefaults

actual fun applyAppLocale(languageCode: String) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (languageCode.isBlank()) {
        // Reset to system default
        defaults.removeObjectForKey("AppleLanguages")
        Logger.d("LocaleHelper") { "Reset locale to system default" }
    } else {
        // Set preferred language. Takes effect on next app launch.
        defaults.setObject(listOf(languageCode), forKey = "AppleLanguages")
        Logger.d("LocaleHelper") {
            "Set AppleLanguages to [$languageCode] (effective on next launch)"
        }
    }
    defaults.synchronize()
}
