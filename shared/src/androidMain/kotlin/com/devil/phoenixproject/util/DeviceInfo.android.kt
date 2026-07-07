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

    // ==================== Initialized Build Info ====================
    // These are set via initialize() from the app module's BuildConfig

    private var _appVersionCode: Int = 1
    private var _appVersionName: String = Constants.APP_VERSION
    private var _isDebugBuild: Boolean = false
    private var _initialized: Boolean = false

    /**
     * Initialize DeviceInfo with values from BuildConfig.
     * Call this from Application.onCreate():
     *
     * ```kotlin
     * DeviceInfo.initialize(
     *     versionCode = BuildConfig.VERSION_CODE,
     *     isDebug = BuildConfig.DEBUG,
     *     versionName = BuildConfig.VERSION_NAME,
     * )
     * ```
     *
     * [versionName] defaults to [Constants.APP_VERSION] for call sites that have not yet
     * been updated to pass BuildConfig.VERSION_NAME.
     */
    fun initialize(versionCode: Int, isDebug: Boolean, versionName: String = Constants.APP_VERSION) {
        _appVersionCode = versionCode
        _isDebugBuild = isDebug
        _appVersionName = versionName
        _initialized = true
    }

    // ==================== App Build Info ====================

    actual val appVersionName: String
        get() = _appVersionName

    actual val appVersionCode: Int
        get() = _appVersionCode

    actual val isDebugBuild: Boolean
        get() = _isDebugBuild

    actual val buildType: String
        get() = if (_isDebugBuild) "debug" else "release"

    // ==================== Android Device Info ====================

    actual val manufacturer: String = Build.MANUFACTURER

    actual val model: String = Build.MODEL

    actual val osVersion: String = Build.VERSION.RELEASE

    private val sdkInt: Int = Build.VERSION.SDK_INT

    actual val platformVersionFull: String = "Android $osVersion (SDK $sdkInt)"

    private val device: String = Build.DEVICE

    private val fingerprint: String = Build.FINGERPRINT

    // ==================== Formatted Output ====================

    actual fun getFormattedInfo(): String = buildString {
        appendLine("App: VitruvianPhoenix v$appVersionName (build $appVersionCode)")
        appendLine("Build Type: $buildType")
        appendLine()
        appendLine("Device: $manufacturer $model")
        appendLine("Model Name: $device")
        appendLine("OS: $platformVersionFull")
        appendLine("Build: ${Build.DISPLAY}")
    }

    actual fun getCompactInfo(): String = "$manufacturer $model (Android $osVersion, SDK $sdkInt)"

    actual fun getAppVersionInfo(): String = "v$appVersionName ($buildType)"

    actual fun toJson(): String = buildString {
        append("{")
        append("\"appVersion\":\"$appVersionName\",")
        append("\"appVersionCode\":$appVersionCode,")
        append("\"buildType\":\"$buildType\",")
        append("\"manufacturer\":\"$manufacturer\",")
        append("\"model\":\"$model\",")
        append("\"device\":\"$device\",")
        append("\"osVersion\":\"$osVersion\",")
        append("\"sdkInt\":$sdkInt,")
        append("\"fingerprint\":\"$fingerprint\"")
        append("}")
    }

    // ==================== Android-Specific Helpers ====================

    /**
     * Check if running on Android 12 or higher (new BLE permissions)
     */
    fun isAndroid12OrHigher(): Boolean = sdkInt >= Build.VERSION_CODES.S

    /**
     * Check if running on Samsung device
     */
    fun isSamsung(): Boolean = manufacturer.equals("samsung", ignoreCase = true)

    /**
     * Check if running on Google Pixel
     */
    fun isPixel(): Boolean = manufacturer.equals("Google", ignoreCase = true)

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
        isPixel() && device.lowercase() in setOf(
            "oriole", "raven", "bluejay",
            "panther", "cheetah", "lynx",
            "felix", "tangorpro",
        )

    actual fun isBcm4389Pixel(): Boolean = isBcm4389

    /**
     * Check if running on Amazon Fire OS (Fire Tablets, Fire TV)
     */
    fun isFireOS(): Boolean = manufacturer.equals("Amazon", ignoreCase = true)

    /**
     * Check if running on Amazon Fire Tablet specifically.
     * Fire Tablet model names start with "AFT" (Amazon Fire Tablet).
     */
    fun isFireTablet(): Boolean = model.startsWith("AFT", ignoreCase = true)
}
