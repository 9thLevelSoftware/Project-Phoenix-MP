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
 * @param mtu The desired MTU size (typically 247 for Phoenix 96-byte frames)
 * @return The negotiated MTU, or null on iOS/failure
 */
expect suspend fun Peripheral.requestMtuIfSupported(mtu: Int): Int?
