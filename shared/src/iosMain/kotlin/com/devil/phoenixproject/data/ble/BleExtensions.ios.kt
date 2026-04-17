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

actual suspend fun Peripheral.requestLe1mPhy(onEvent: (String) -> Unit) {
    // No-op on iOS: CoreBluetooth does not expose a PHY preference API, and BCM4389
    // (the Pixel 6/7-specific root cause of Issue #333) does not apply to iOS silicon.
}
