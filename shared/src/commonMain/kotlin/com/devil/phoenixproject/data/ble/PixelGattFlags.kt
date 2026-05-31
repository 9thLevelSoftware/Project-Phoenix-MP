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
 * Flag G: Skip the phantom START command (0x03) after CONFIG. The official Vitruvian
 *         app's CommandId enum has no ID 3 — it sends only the ActivationPacket (0x04).
 *         The 0x03 START was invented by the parent repo and carried into Phoenix.
 * Flag H: Bypass Kable's coroutine/Mutex layer for critical CONFIG writes and call
 *         BluetoothGatt.writeCharacteristic() directly via reflection. Matches the
 *         official app's raw GATT write path — eliminates the double-Mutex and the
 *         connectionScope cancellation that causes "StandaloneCoroutine was cancelled".
 * Flag I: Official small-MTU path. Pixel-only experiment that does not call
 *         requestMtu(), keeps CONFIG on Kable WithResponse, and suppresses heartbeat
 *         traffic. Mutually exclusive with D-H to keep validation clean.
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

    /**
     * Flag G: Skip the phantom START command (0x03) after sending CONFIG (0x04).
     * The official Vitruvian app never sends command ID 3 — its CommandId enum
     * contains: 1, 2, 4, 29, 31, 56, 57, 78, 79. The workout starts from the
     * ActivationPacket alone. Removing the redundant write eliminates one chance
     * for BCM4389 to hit GATT_ERROR(133) on a saturated channel.
     */
    @Volatile
    var skipPhantomStart: Boolean = false

    /**
     * Flag H: Bypass Kable entirely for the critical CONFIG write. Uses reflection
     * to call BluetoothGatt.writeCharacteristic() directly, matching the official
     * app's write path (AndroidPeripheral.java:480). This eliminates:
     *   - Kable's internal Mutex (`guard`) — avoids double-Mutex with BleOperationQueue
     *   - Kable's connectionScope.async response wait — prevents "StandaloneCoroutine
     *     was cancelled" when BCM4389 causes a transient connection blip
     *   - Kable's coroutine dispatch overhead on the critical 96-byte write
     * Android-only; ignored on iOS.
     */
    @Volatile
    var rawGattWrite: Boolean = false

    /**
     * Flag I: Evidence-first Pixel path for Issue #333. Android 14+ forces the
     * first requestMtu() call to ATT MTU 517, so this experiment avoids requesting
     * MTU at all and leaves writes on the standard WithResponse path.
     */
    @Volatile
    var officialSmallMtuPath: Boolean = false

    fun setOfficialSmallMtuPathEnabled(enabled: Boolean) {
        officialSmallMtuPath = enabled
        if (enabled) {
            clearLegacyExperiments()
        }
    }

    fun clearLegacyExperiments() {
        refreshGattCache = false
        forceImmediateClose = false
        quiescePolling = false
        skipPhantomStart = false
        rawGattWrite = false
    }

    fun hasAnyActiveFlag(): Boolean =
        refreshGattCache ||
            forceImmediateClose ||
            quiescePolling ||
            skipPhantomStart ||
            rawGattWrite ||
            officialSmallMtuPath

    fun activeFlagsSummary(): String {
        val active = mutableListOf<String>()
        if (refreshGattCache) active.add("D:GattRefresh")
        if (forceImmediateClose) active.add("E:ForceClose")
        if (quiescePolling) active.add("F:Quiesce")
        if (skipPhantomStart) active.add("G:NoStart")
        if (rawGattWrite) active.add("H:RawGatt")
        if (officialSmallMtuPath) active.add("I:SmallMtu")
        return if (active.isEmpty()) "None" else active.joinToString(", ")
    }
}
