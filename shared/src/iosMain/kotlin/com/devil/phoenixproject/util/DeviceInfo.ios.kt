@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.devil.phoenixproject.util

import kotlin.native.Platform
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/**
 * iOS implementation of DeviceInfo.
 * Uses UIDevice and NSBundle for device and app information.
 */
actual object DeviceInfo {

    // ==================== App Build Info ====================

    actual val appVersionName: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey(
            "CFBundleShortVersionString",
        ) as? String
            ?: Constants.APP_VERSION

    actual val appVersionCode: Int
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)
            ?.toIntOrNull() ?: 1

    actual val buildType: String
        get() = if (isDebugBuild) "debug" else "release"

    actual val isDebugBuild: Boolean
        get() {
            // Check for debugger attachment or debug-specific environment
            // In iOS, we can check if assertions are enabled (debug builds enable them)
            // or check for specific debug environment indicators
            return Platform.isDebugBinary
        }

    // ==================== iOS Device Info ====================

    actual val manufacturer: String = "Apple"

    actual val model: String
        get() = UIDevice.currentDevice.model

    actual val osVersion: String
        get() = UIDevice.currentDevice.systemVersion

    actual val platformVersionFull: String
        get() = "iOS $osVersion (${UIDevice.currentDevice.systemName})"

    private val deviceName: String
        get() = UIDevice.currentDevice.name

    private val identifierForVendor: String
        get() = UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "unknown"

    // ==================== Formatted Output ====================

    actual fun getFormattedInfo(): String = buildString {
        appendLine("App: VitruvianPhoenix v$appVersionName (build $appVersionCode)")
        appendLine("Build Type: $buildType")
        appendLine()
        appendLine("Device: $manufacturer $model")
        appendLine("Device Name: $deviceName")
        appendLine("OS: $platformVersionFull")
    }

    actual fun getCompactInfo(): String = "$manufacturer $model (iOS $osVersion)"

    actual fun getAppVersionInfo(): String = "v$appVersionName ($buildType)"

    actual fun toJson(): String = buildString {
        append("{")
        append("\"appVersion\":\"$appVersionName\",")
        append("\"appVersionCode\":$appVersionCode,")
        append("\"buildType\":\"$buildType\",")
        append("\"manufacturer\":\"$manufacturer\",")
        append("\"model\":\"$model\",")
        append("\"deviceName\":\"$deviceName\",")
        append("\"osVersion\":\"$osVersion\",")
        append("\"identifierForVendor\":\"$identifierForVendor\"")
        append("}")
    }
}
