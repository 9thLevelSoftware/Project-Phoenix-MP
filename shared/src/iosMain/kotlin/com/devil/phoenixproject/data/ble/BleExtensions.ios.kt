package com.devil.phoenixproject.data.ble

import com.juul.kable.Peripheral

actual suspend fun Peripheral.requestHighPriority() {
    // No-op on iOS - CoreBluetooth handles connection priority automatically
}

actual suspend fun Peripheral.requestMtuIfSupported(mtu: Int): Int? {
    // iOS CoreBluetooth negotiates MTU automatically during connection
    // No explicit API to request MTU - returns null to indicate "use system default"
    return null
}
