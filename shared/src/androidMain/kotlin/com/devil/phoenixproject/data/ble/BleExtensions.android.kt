package com.devil.phoenixproject.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import co.touchlab.kermit.Logger
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

actual suspend fun Peripheral.requestHighPriority() {
    // Cast to AndroidPeripheral to access Android-specific connection priority method
    val androidPeripheral = this as? AndroidPeripheral
    if (androidPeripheral == null) {
        Logger.w("BleExtensions") {
            "⚠️ Cannot request connection priority: not an AndroidPeripheral"
        }
        return
    }

    try {
        Logger.i("BleExtensions") { "🔧 Requesting HIGH connection priority..." }
        val success = androidPeripheral.requestConnectionPriority(AndroidPeripheral.Priority.High)
        // Small delay to ensure the priority change takes effect before starting high-speed polling
        // This matches parent repo behavior where operations wait for completion callbacks
        delay(100)
        if (success) {
            Logger.i("BleExtensions") { "✅ HIGH connection priority set successfully" }
        } else {
            Logger.w("BleExtensions") { "⚠️ Connection priority request returned false" }
        }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "❌ Failed to request high connection priority: ${e.message}" }
    }
}

actual suspend fun Peripheral.requestMtuIfSupported(mtu: Int): Int? {
    val androidPeripheral = this as? AndroidPeripheral
    if (androidPeripheral == null) {
        Logger.w("BleExtensions") { "⚠️ Cannot request MTU: not an AndroidPeripheral" }
        return null
    }

    return try {
        Logger.i("BleExtensions") { "🔧 Requesting MTU: $mtu bytes..." }
        val negotiatedMtu = androidPeripheral.requestMtu(mtu)
        Logger.i("BleExtensions") { "✅ MTU negotiated: $negotiatedMtu bytes (requested: $mtu)" }
        negotiatedMtu
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "❌ MTU negotiation failed: ${e.message}" }
        null
    }
}

actual suspend fun Peripheral.refreshGattCache(): Boolean {
    val androidPeripheral = this as? AndroidPeripheral
    if (androidPeripheral == null) {
        Logger.w("BleExtensions") { "Cannot refresh GATT cache: not an AndroidPeripheral" }
        return false
    }

    return try {
        // BluetoothGatt.refresh() is a hidden API — must use reflection
        // This clears the local GATT cache so service discovery fetches fresh data
        // Primary workaround for GATT_ERROR(133) on Pixel devices (Issue #220161109)
        val gatt = findBluetoothGatt(androidPeripheral)
        if (gatt == null) {
            Logger.w("BleExtensions") { "Could not find BluetoothGatt for cache refresh" }
            return false
        }

        val refreshMethod = gatt::class.java.getMethod("refresh")
        val result = refreshMethod.invoke(gatt) as Boolean
        Logger.i("BleExtensions") { "GATT cache refresh result: $result" }
        result
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "GATT cache refresh failed: ${e.message}" }
        false
    }
}

actual suspend fun Peripheral.forceCloseGatt() {
    val androidPeripheral = this as? AndroidPeripheral
    if (androidPeripheral == null) {
        Logger.w("BleExtensions") { "Cannot force close GATT: not an AndroidPeripheral" }
        return
    }

    try {
        val gatt = findBluetoothGatt(androidPeripheral)
        if (gatt == null) {
            Logger.w("BleExtensions") { "Could not find BluetoothGatt to force close" }
            return
        }

        Logger.i("BleExtensions") { "Force closing BluetoothGatt (matching official app)" }
        gatt.close()
        Logger.i("BleExtensions") { "BluetoothGatt force-closed successfully" }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "Force GATT close failed: ${e.message}" }
    }
}

/**
 * Issue #333 Flag H: Bypass Kable and write directly to BluetoothGatt.
 *
 * Mirrors the official Vitruvian app's write path at AndroidPeripheral.java:480:
 *   bluetoothGattCharacteristic.setWriteType(WRITE_TYPE_DEFAULT)  // WithResponse
 *   bluetoothGattCharacteristic.setValue(bytes)
 *   bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic)
 *
 * This completely bypasses:
 *   - Kable's internal Mutex (guard) — no double-Mutex with BleOperationQueue
 *   - Kable's connectionScope.async response wait — no "StandaloneCoroutine was cancelled"
 *   - Kable's coroutine cancellation handling and response channel draining
 *
 * The write uses the deprecated (pre-API 33) setValue+writeCharacteristic path
 * intentionally — this is exactly what the official app uses and it works on
 * every device including Pixel 6/7 with BCM4389.
 */
@Suppress("DEPRECATION") // Intentional: matches official app's deprecated API usage
actual suspend fun Peripheral.rawGattWriteCharacteristic(
    characteristicUuid: String,
    data: ByteArray,
): Result<Unit> {
    val androidPeripheral = this as? AndroidPeripheral
    if (androidPeripheral == null) {
        Logger.w("BleExtensions") { "[Flag H] Cannot raw-write: not an AndroidPeripheral" }
        return Result.failure(IllegalStateException("Not an AndroidPeripheral"))
    }

    val gatt = findBluetoothGatt(androidPeripheral)
    if (gatt == null) {
        Logger.w("BleExtensions") { "[Flag H] Cannot raw-write: BluetoothGatt not found" }
        return Result.failure(IllegalStateException("BluetoothGatt not found via reflection"))
    }

    // Find the TX characteristic from the GATT service list
    val uuid = UUID.fromString(characteristicUuid)
    var txChar: BluetoothGattCharacteristic? = null
    for (service in gatt.services ?: emptyList()) {
        val c = service.getCharacteristic(uuid)
        if (c != null) {
            txChar = c
            break
        }
    }
    if (txChar == null) {
        Logger.w("BleExtensions") { "[Flag H] TX characteristic $characteristicUuid not found in GATT" }
        return Result.failure(IllegalStateException("TX characteristic not found"))
    }

    return try {
        // Match the official app exactly: setValue + setWriteType + writeCharacteristic
        // Official app: AndroidPeripheral.java line 479-480
        //   bluetoothGattCharacteristic.setWriteType(2)  // WRITE_TYPE_DEFAULT = WithResponse
        //   bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic)
        txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // WithResponse
        txChar.value = data
        val accepted = gatt.writeCharacteristic(txChar)

        if (accepted) {
            Logger.i("BleExtensions") {
                "[Flag H] Raw GATT write accepted: ${data.size} bytes to $characteristicUuid"
            }
            // The write was accepted by the GATT stack. Unlike Kable, we don't wait
            // for the onCharacteristicWrite callback — same as the official app which
            // returns COROUTINE_SUSPENDED and doesn't block on the callback within
            // the write function itself. We give a brief settle time for the controller.
            delay(50)
            Result.success(Unit)
        } else {
            Logger.e("BleExtensions") {
                "[Flag H] Raw GATT write REJECTED by controller (returned false)"
            }
            Result.failure(IllegalStateException("writeCharacteristic returned false (GATT busy)"))
        }
    } catch (e: Exception) {
        Logger.e("BleExtensions") { "[Flag H] Raw GATT write exception: ${e.message}" }
        Result.failure(e)
    }
}

/**
 * Navigate Kable 0.42.0's internal object graph to find the BluetoothGatt instance.
 *
 * Kable's class hierarchy (from javap of kable-core-release.aar):
 *   BluetoothDeviceAndroidPeripheral (implements AndroidPeripheral)
 *     └── field `connection`: MutableStateFlow<Connection>
 *           └── .value → Connection
 *                 └── field `gatt`: BluetoothGatt
 *                     (also accessible via getGatt$kable_core_release())
 *
 * Previous implementation failed because it searched for fields whose TYPE
 * contained "Connection" — but the actual field type is MutableStateFlow,
 * which wraps the Connection. We now search by FIELD NAME instead.
 */
private fun findBluetoothGatt(androidPeripheral: AndroidPeripheral): BluetoothGatt? {
    try {
        // The actual class is BluetoothDeviceAndroidPeripheral, which extends BasePeripheral
        val peripheralClass = androidPeripheral::class.java
        Logger.d("BleExtensions") {
            "Peripheral class: ${peripheralClass.name}, fields: ${peripheralClass.declaredFields.map { "${it.name}:${it.type.simpleName}" }}"
        }

        // Strategy 1 (Primary): BluetoothDeviceAndroidPeripheral.connection (MutableStateFlow<Connection>)
        // Then Connection.gatt (BluetoothGatt)
        val connectionField = peripheralClass.declaredFields.firstOrNull { it.name == "connection" }
        if (connectionField != null) {
            connectionField.isAccessible = true
            val stateFlow = connectionField.get(androidPeripheral)
            if (stateFlow != null) {
                // MutableStateFlow has a .getValue() method
                val valueMethod = stateFlow::class.java.methods.firstOrNull {
                    it.name == "getValue" && it.parameterCount == 0
                }
                val connection = valueMethod?.invoke(stateFlow)
                if (connection != null) {
                    Logger.d("BleExtensions") {
                        "Connection class: ${connection::class.java.name}, fields: ${connection::class.java.declaredFields.map { "${it.name}:${it.type.simpleName}" }}"
                    }

                    // Try the public getter first (Kotlin internal visibility → mangled name)
                    try {
                        val getGattMethod = connection::class.java.methods.firstOrNull {
                            it.name.startsWith("getGatt") && it.returnType == BluetoothGatt::class.java
                        }
                        if (getGattMethod != null) {
                            val gatt = getGattMethod.invoke(connection) as? BluetoothGatt
                            if (gatt != null) {
                                Logger.d("BleExtensions") { "Found BluetoothGatt via Connection.getGatt*()" }
                                return gatt
                            }
                        }
                    } catch (_: Exception) { /* Fall through to field access */ }

                    // Direct field access: Connection.gatt
                    val gattField = connection::class.java.declaredFields.firstOrNull {
                        it.name == "gatt" || it.type == BluetoothGatt::class.java
                    }
                    if (gattField != null) {
                        gattField.isAccessible = true
                        val gatt = gattField.get(connection) as? BluetoothGatt
                        if (gatt != null) {
                            Logger.d("BleExtensions") { "Found BluetoothGatt via Connection.gatt field" }
                            return gatt
                        }
                    }
                } else {
                    Logger.w("BleExtensions") { "Connection StateFlow.value is null (not connected?)" }
                }
            }
        }

        // Strategy 2: Walk ALL fields looking for anything that holds a BluetoothGatt
        // (handles future Kable refactors)
        for (field in peripheralClass.declaredFields) {
            field.isAccessible = true
            val value = try { field.get(androidPeripheral) } catch (_: Exception) { continue }
            if (value is BluetoothGatt) {
                Logger.d("BleExtensions") { "Found BluetoothGatt via direct field: ${field.name}" }
                return value
            }
            // Check one level deep for any object holding a gatt
            if (value != null) {
                for (innerField in value::class.java.declaredFields) {
                    if (innerField.type == BluetoothGatt::class.java) {
                        innerField.isAccessible = true
                        val gatt = try { innerField.get(value) as? BluetoothGatt } catch (_: Exception) { null }
                        if (gatt != null) {
                            Logger.d("BleExtensions") { "Found BluetoothGatt via ${field.name}.${innerField.name}" }
                            return gatt
                        }
                    }
                }
            }
        }

        Logger.w("BleExtensions") { "BluetoothGatt not found via any reflection path" }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "Reflection error finding BluetoothGatt: ${e.message}" }
    }
    return null
}
