package com.devil.phoenixproject.data.ble

import com.juul.kable.Peripheral

/**
 * Request high connection priority (Android specific).
 * No-op on other platforms.
 */
expect suspend fun Peripheral.requestHighPriority()

/**
 * Request MTU negotiation (Android specific).
 * Returns the negotiated MTU value, or null if not supported/failed.
 *
 * @param mtu The desired MTU size (typically 247 for Vitruvian 96-byte frames)
 * @return The negotiated MTU, or null on iOS/failure
 */
expect suspend fun Peripheral.requestMtuIfSupported(mtu: Int): Int?

/**
 * Issue #333 Flag D: Refresh the GATT cache via reflection.
 * Clears cached service/characteristic data so fresh discovery occurs.
 * Android-only; no-op on other platforms.
 *
 * @return true if refresh was invoked successfully, false otherwise
 */
expect suspend fun Peripheral.refreshGattCache(): Boolean

/**
 * Issue #333 Flag E: Force immediate gatt.close() via reflection.
 * The official Vitruvian app calls gatt.close() synchronously in
 * onConnectionStateChange(DISCONNECTED). Kable defers this.
 * Android-only; no-op on other platforms.
 */
expect suspend fun Peripheral.forceCloseGatt()

/**
 * Issue #333 Flag H: Bypass Kable entirely and write directly to BluetoothGatt.
 * Uses reflection to find the underlying BluetoothGatt and calls
 * writeCharacteristic() with the raw bytes — matching the official
 * Vitruvian app's AndroidPeripheral.java:480 write path exactly.
 *
 * This eliminates Kable's internal Mutex, connectionScope, and coroutine
 * machinery that cause "StandaloneCoroutine was cancelled" on BCM4389.
 *
 * Android-only; returns Result.failure on other platforms.
 *
 * @param characteristicUuid The UUID of the TX characteristic to write to
 * @param data The raw bytes to write
 * @return Result.success if writeCharacteristic() returned true, failure otherwise
 */
expect suspend fun Peripheral.rawGattWriteCharacteristic(
    characteristicUuid: String,
    data: ByteArray,
): Result<Unit>
