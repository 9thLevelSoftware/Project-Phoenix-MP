package com.devil.phoenixproject.data.ble

import com.juul.kable.Peripheral

/**
 * Request high connection priority (Android specific).
 * No-op on other platforms.
 */
expect suspend fun Peripheral.requestHighPriority()
