package com.devil.phoenixproject.util

/**
 * Platform-agnostic device and app information utility.
 * Platform implementations provide actual device/OS details.
 */
expect object DeviceInfo {

    /**
     * App version name (e.g., "0.5.1-beta")
     */
    val appVersionName: String

    /**
     * Whether this is a debug build
     */
    val isDebugBuild: Boolean

    /**
     * Issue #333: true on Pixel devices with the Broadcom BCM4389 Bluetooth
     * controller (Tensor G1/G2 generation: Pixel 6/7 phones, Pixel Fold,
     * Pixel Tablet), the only devices where large-MTU acknowledged writes wedge.
     */
    fun isBcm4389Pixel(): Boolean
}
