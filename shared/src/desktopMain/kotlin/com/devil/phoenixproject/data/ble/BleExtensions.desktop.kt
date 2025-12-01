package com.devil.phoenixproject.data.ble

import com.juul.kable.Peripheral

actual suspend fun Peripheral.requestHighPriority() {
    // No-op on Desktop
}
