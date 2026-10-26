package com.devil.phoenixproject.data.sync

/** Wire-family classification, separate from native API availability. */
internal fun portalWirePlatform(platformName: String, isIosPlatform: Boolean): String {
    val raw = platformName.lowercase().trim()
    return when {
        raw.contains("android") -> "android"
        // Portal wire-family compatibility does not enable phone-only native APIs.
        raw.contains("ios") || raw.contains("tvos") || raw.contains("apple tv") -> "ios"
        isIosPlatform -> "ios"
        else -> "android"
    }
}
