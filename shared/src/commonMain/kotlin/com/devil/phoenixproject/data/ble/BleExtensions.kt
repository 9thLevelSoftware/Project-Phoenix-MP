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
 * Pin the BLE connection to LE 1M PHY (Android specific, API 26+).
 *
 * Works around Issue #333: on BCM4389-based Pixels (6/6 Pro/7/7 Pro), the BLE controller
 * negotiates LE 2M PHY by default. The Vitruvian firmware cannot sustain 2M PHY reliably,
 * so the link silently degrades and the next large write fails with GATT_ERROR(133).
 *
 * The decompiled official Vitruvian app passes `PHY_LE_1M_MASK` to the 6-arg `connectGatt()`.
 * Kable already does the same via its default `Phy.Le1M`, but that is only a hint for the initial
 * connection — the peer can renegotiate. Calling `BluetoothGatt.setPreferredPhy` post-connection
 * forces the link back to 1M explicitly with `PHY_OPTION_NO_PREFERRED`.
 *
 * No-op on iOS (CoreBluetooth does not expose a PHY API and has no known equivalent issue).
 *
 * @param onEvent Optional callback for surfacing progress strings to a user-facing log.
 */
expect suspend fun Peripheral.requestLe1mPhy(onEvent: (String) -> Unit = {})
