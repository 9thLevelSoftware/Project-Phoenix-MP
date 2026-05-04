package com.devil.phoenixproject.data.ble

/**
 * Issue #333: Pixel 6/7 GATT cleanup experiment flags.
 *
 * Flag D: Call BluetoothGatt.refresh() after connection to clear stale GATT cache.
 * Flag E: Force immediate gatt.close() on disconnect (matches official Vitruvian app).
 *
 * Both flags are Android-only and toggled from Developer Tools.
 */
object PixelGattFlags {
    /** Flag D: Refresh GATT cache after connectGatt() succeeds, before service discovery */
    @Volatile
    var refreshGattCache: Boolean = false

    /** Flag E: Force immediate gatt.close() when disconnect is detected */
    @Volatile
    var forceImmediateClose: Boolean = false

    fun activeFlagsSummary(): String {
        val active = mutableListOf<String>()
        if (refreshGattCache) active.add("D:GattRefresh")
        if (forceImmediateClose) active.add("E:ForceClose")
        return if (active.isEmpty()) "None" else active.joinToString(", ")
    }
}
