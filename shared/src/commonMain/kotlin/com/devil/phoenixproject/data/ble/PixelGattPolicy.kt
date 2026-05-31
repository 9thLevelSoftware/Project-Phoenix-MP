package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.DeviceInfo

/**
 * Issue #333 Pixel BLE policy gates.
 *
 * Flag I is intentionally Pixel-only so non-Pixel devices keep the existing
 * negotiated-MTU and heartbeat behavior even if the developer flag is toggled.
 */
object PixelGattPolicy {
    fun isOfficialSmallMtuPathActive(isPixel: Boolean = DeviceInfo.isPixel()): Boolean =
        PixelGattFlags.officialSmallMtuPath && isPixel

    fun includeHeartbeat(isPixel: Boolean = DeviceInfo.isPixel()): Boolean =
        !isOfficialSmallMtuPathActive(isPixel)

    fun includePreReadyDiagnosticReads(isPixel: Boolean = DeviceInfo.isPixel()): Boolean =
        !isOfficialSmallMtuPathActive(isPixel)
}
