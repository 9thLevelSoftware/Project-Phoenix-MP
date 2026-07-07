package com.devil.phoenixproject.util

/**
 * Platform-agnostic device and app information utility.
 * Platform implementations provide actual device/OS details.
 */
expect object DeviceInfo {

    // ==================== App Build Info ====================

    /**
     * App version name (e.g., "0.5.1-beta")
     */
    val appVersionName: String

    /**
     * App version code (incrementing integer)
     */
    val appVersionCode: Int

    /**
     * Build type (debug or release)
     */
    val buildType: String

    /**
     * Whether this is a debug build
     */
    val isDebugBuild: Boolean

    // ==================== Platform Device Info ====================

    /**
     * Device manufacturer (e.g., "samsung", "Apple")
     */
    val manufacturer: String

    /**
     * Device model (e.g., "SM-G998U", "iPhone14,2")
     */
    val model: String

    /**
     * OS version string (e.g., "14", "17.0")
     */
    val osVersion: String

    /**
     * Full platform version string
     */
    val platformVersionFull: String

    // ==================== Formatted Output ====================

    /**
     * Get a formatted device and app info string for logging
     */
    fun getFormattedInfo(): String

    /**
     * Get a compact one-line device description
     */
    fun getCompactInfo(): String

    /**
     * Get a compact one-line app version description
     */
    fun getAppVersionInfo(): String

    /**
     * Get device info as structured JSON string for metadata storage
     */
    fun toJson(): String

    /**
     * Issue #333: true on Pixel devices with the Broadcom BCM4389 Bluetooth
     * controller (Tensor G1/G2 generation: Pixel 6/7 phones, Pixel Fold,
     * Pixel Tablet), the only devices where large-MTU acknowledged writes wedge.
     */
    fun isBcm4389Pixel(): Boolean
}
