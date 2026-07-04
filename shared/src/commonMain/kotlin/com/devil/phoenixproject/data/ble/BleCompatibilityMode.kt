package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.util.DeviceInfo

/**
 * Issue #333: BLE compatibility mode ("official small-MTU path").
 *
 * Root cause: on Pixel 6/7 (Tensor G1/G2 + Broadcom BCM4389 Bluetooth controller),
 * a 96-byte acknowledged write sent as a single large ATT PDU under the 517-byte
 * MTU that Android 14+ forces on the first requestMtu() call never completes —
 * the controller's write lane wedges (WriteRequestBusy forever), then surfaces
 * GATT_ERROR(133) and drops the link the moment a workout starts.
 *
 * The official Vitruvian app never calls requestMtu(), so it stays at the default
 * 23-byte ATT MTU and its 96/34-byte writes are automatically chunked by the
 * ATT long-write procedure (Prepare Write + Execute Write), which those
 * controllers handle fine. Compatibility mode reproduces that behavior:
 *  - no app-side requestMtu() call
 *  - high connection priority after service discovery
 *  - no GATT heartbeat loop
 *  - no pre-ready diagnostic version reads, no post-CONFIG diagnostic read
 *  - bounded retry for the workout CONFIG write
 *
 * Validated on-device in the pixel-test-new-v14 build (first-ever Pixel 7 Pro
 * success on 2026-07-04, issue #333).
 */
object BleCompatibilityMode {
    /** Persisted user choice, synced from preferences at startup and on change. */
    @Volatile
    var setting: BleCompatibilitySetting = BleCompatibilitySetting.AUTO

    /** Resolved state: should the small-MTU compatibility path be used right now? */
    fun isActive(isAffectedDevice: Boolean = DeviceInfo.isPixel6Or7()): Boolean =
        when (setting) {
            BleCompatibilitySetting.ON -> true
            BleCompatibilitySetting.OFF -> false
            BleCompatibilitySetting.AUTO -> isAffectedDevice
        }

    /** The GATT heartbeat loop is suppressed on the compatibility path. */
    fun includeHeartbeat(isAffectedDevice: Boolean = DeviceInfo.isPixel6Or7()): Boolean =
        !isActive(isAffectedDevice)

    /** Best-effort pre-ready firmware/version reads are skipped on the compatibility path. */
    fun includePreReadyDiagnosticReads(isAffectedDevice: Boolean = DeviceInfo.isPixel6Or7()): Boolean =
        !isActive(isAffectedDevice)

    fun summary(isAffectedDevice: Boolean = DeviceInfo.isPixel6Or7()): String =
        "setting=${setting.name}, affectedDevice=$isAffectedDevice, active=${isActive(isAffectedDevice)}"
}
