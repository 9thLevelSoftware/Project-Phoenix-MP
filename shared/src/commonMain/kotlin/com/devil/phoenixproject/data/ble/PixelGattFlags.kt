package com.devil.phoenixproject.data.ble

/**
 * Issue #333: Pixel 6/7 GATT cleanup experiment flags.
 *
 * Flag D: Call BluetoothGatt.refresh() after connection to clear stale GATT cache.
 * Flag E: Force immediate gatt.close() on disconnect (matches official Vitruvian app).
 * Flag F: Quiesce all BLE polling around the CONFIG/START write at workout start.
 *         Mirrors the official Vitruvian app's notification-only behavior at the
 *         most fragile moment, where BCM4389 returns GATT_ERROR(133) when a
 *         96-byte WithResponse write hits a saturated channel.
 *
 * All flags are Android-only and toggled from Developer Tools.
 */
object PixelGattFlags {
    /** Flag D: Refresh GATT cache after connectGatt() succeeds, before service discovery */
    @Volatile
    var refreshGattCache: Boolean = false

    /** Flag E: Force immediate gatt.close() when disconnect is detected */
    @Volatile
    var forceImmediateClose: Boolean = false

    /**
     * Flag F: Stop all polling loops + 150ms drain before sending the CONFIG/START
     * packets at workout start. Polling resumes on success (existing
     * startActiveWorkoutPolling call) or in the catch paths.
     */
    @Volatile
    var quiescePolling: Boolean = false

    fun activeFlagsSummary(): String {
        val active = mutableListOf<String>()
        if (refreshGattCache) active.add("D:GattRefresh")
        if (forceImmediateClose) active.add("E:ForceClose")
        if (quiescePolling) active.add("F:Quiesce")
        return if (active.isEmpty()) "None" else active.joinToString(", ")
    }
}
