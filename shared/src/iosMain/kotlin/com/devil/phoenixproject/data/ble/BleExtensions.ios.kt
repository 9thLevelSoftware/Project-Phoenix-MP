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

actual suspend fun Peripheral.refreshGattCache(): Boolean {
    // No-op on iOS — CoreBluetooth manages GATT cache internally
    return false
}

actual suspend fun Peripheral.forceCloseGatt() {
    // No-op on iOS — CoreBluetooth handles cleanup automatically
}

actual suspend fun Peripheral.rawGattWriteCharacteristic(
    characteristicUuid: String,
    data: ByteArray,
): Result<Unit> {
    // No-op on iOS — BCM4389 GATT issue is Android/Pixel specific
    return Result.failure(UnsupportedOperationException("Raw GATT write is Android-only"))
}
