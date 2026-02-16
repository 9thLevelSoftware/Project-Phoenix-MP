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
    private var _isDebugBuild: Boolean = false
    private var _initialized: Boolean = false

    /**
     * Initialize DeviceInfo with values from BuildConfig.
     * Call this from Application.onCreate():
     *
     * ```kotlin
     * DeviceInfo.initialize(
     *     versionCode = BuildConfig.VERSION_CODE,
     *     isDebug = BuildConfig.DEBUG
     * )
     * ```
     */
    fun initialize(versionCode: Int, isDebug: Boolean) {
        _appVersionCode = versionCode
        _isDebugBuild = isDebug
        _initialized = true
    }

    // ==================== App Build Info ====================

    actual val appVersionName: String = Constants.APP_VERSION

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

    actual fun getFormattedInfo(): String {
        return buildString {
            appendLine("App: VitruvianPhoenix v$appVersionName (build $appVersionCode)")
            appendLine("Build Type: $buildType")
            appendLine()
            appendLine("Device: $manufacturer $model")
            appendLine("Model Name: $device")
            appendLine("OS: $platformVersionFull")
            appendLine("Build: ${Build.DISPLAY}")
        }
    }

    actual fun getCompactInfo(): String {
        return "$manufacturer $model (Android $osVersion, SDK $sdkInt)"
    }

    actual fun getAppVersionInfo(): String {
        return "v$appVersionName ($buildType)"
    }

    actual fun toJson(): String {
        return buildString {
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
     * Check if running on Amazon Fire OS (Fire Tablets, Fire TV)
     */
    fun isFireOS(): Boolean = manufacturer.equals("Amazon", ignoreCase = true)

    /**
     * Check if running on Amazon Fire Tablet specifically.
     * Fire Tablet model names start with "AFT" (Amazon Fire Tablet).
     */
    fun isFireTablet(): Boolean = model.startsWith("AFT", ignoreCase = true)
}
