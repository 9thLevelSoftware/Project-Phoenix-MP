@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.devil.phoenixproject.util

import kotlin.native.Platform
import platform.Foundation.NSBundle

/**
 * iOS implementation of DeviceInfo.
 * Uses NSBundle for app version and the Kotlin/Native debug-binary flag.
 */
actual object DeviceInfo {

    actual val appVersionName: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey(
            "CFBundleShortVersionString",
        ) as? String
            ?: Constants.APP_VERSION

    actual val isDebugBuild: Boolean
        get() = Platform.isDebugBinary

    /** Issue #333: Android-only concern; never a Pixel on iOS. */
    actual fun isBcm4389Pixel(): Boolean = false
}
