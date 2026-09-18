package com.devil.phoenixproject.util

import android.os.Build

/**
 * Android implementation of DeviceInfo.
 * Uses android.os.Build for device information.
 *
 * Note: App version info requires initialization from the app module via [initialize].
 * Call [initialize] from your Application.onCreate() with BuildConfig values.
 */
actual object DeviceInfo {

    private var _appVersionName: String = Constants.APP_VERSION
    private var _isDebugBuild: Boolean = false

    /**
     * Initialize DeviceInfo with values from BuildConfig.
     * Call this from Application.onCreate():
     *
     * ```kotlin
     * DeviceInfo.initialize(
     *     isDebug = BuildConfig.DEBUG,
     *     versionName = BuildConfig.VERSION_NAME,
     * )
     * ```
     *
     * [versionName] defaults to [Constants.APP_VERSION] for call sites that have not yet
     * been updated to pass BuildConfig.VERSION_NAME.
     */
    fun initialize(isDebug: Boolean, versionName: String = Constants.APP_VERSION) {
        _isDebugBuild = isDebug
        _appVersionName = versionName
    }

    actual val appVersionName: String
        get() = _appVersionName

    actual val isDebugBuild: Boolean
        get() = _isDebugBuild

    private val manufacturer: String = Build.MANUFACTURER

    private val device: String = Build.DEVICE

    /**
     * Issue #333: Pixel devices with the Broadcom BCM4389 Bluetooth controller
     * (Tensor G1/G2 generation), where large-MTU acknowledged writes wedge.
     * Matched by Build.DEVICE codename — the only reliable discriminator across
     * carrier/regional model numbers:
     * oriole=Pixel 6, raven=Pixel 6 Pro, bluejay=Pixel 6a,
     * panther=Pixel 7, cheetah=Pixel 7 Pro, lynx=Pixel 7a,
     * felix=Pixel Fold, tangorpro=Pixel Tablet.
     */
    private val isBcm4389: Boolean =
        manufacturer.equals("Google", ignoreCase = true) && device.lowercase() in setOf(
            "oriole", "raven", "bluejay",
            "panther", "cheetah", "lynx",
            "felix", "tangorpro",
        )

    actual fun isBcm4389Pixel(): Boolean = isBcm4389

    /**
     * Check if running on Amazon Fire OS (Fire Tablets, Fire TV)
     */
    fun isFireOS(): Boolean = manufacturer.equals("Amazon", ignoreCase = true)
}
