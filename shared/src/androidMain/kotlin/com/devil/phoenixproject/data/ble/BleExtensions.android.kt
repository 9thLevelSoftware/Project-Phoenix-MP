package com.devil.phoenixproject.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import co.touchlab.kermit.Logger
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import java.util.UUID

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
 * Issue #333 Flag H: Write to BluetoothGatt directly but cooperate with Kable's
 * internal state so subsequent operations don't get corrupted.
 *
 * v4 proved the raw write works (CONFIG succeeded in 53ms) but the workout
 * disconnected ~12s later because onCharacteristicWrite fired into Kable's
 * callback.onResponse channel. The next Kable polling read picked up that
 * stale write response instead of its own read response → type mismatch →
 * Kable interpreted it as a connection failure.
 *
 * v5 fix: Hold Kable's internal guard Mutex during the write AND drain the
 * onCharacteristicWrite callback response from Kable's channel afterward.
 * This gives us raw GATT dispatch (no connectionScope cancellation risk)
 * while keeping Kable's internal state clean for subsequent operations.
 *
 * The write uses the deprecated (pre-API 33) setValue+writeCharacteristic path
 * intentionally — this is exactly what the official app uses.
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

    // Get the Connection object (holds gatt, guard mutex, and callback)
    val connection = findConnection(androidPeripheral)
    if (connection == null) {
        Logger.w("BleExtensions") { "[Flag H] Cannot raw-write: Connection not found" }
        return Result.failure(IllegalStateException("Kable Connection not found"))
    }

    val gatt = findGattFromConnection(connection)
    if (gatt == null) {
        Logger.w("BleExtensions") { "[Flag H] Cannot raw-write: BluetoothGatt not found in Connection" }
        return Result.failure(IllegalStateException("BluetoothGatt not found"))
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

    // Get Kable's internal guard Mutex and callback response channel
    val guardMutex = findGuardMutex(connection)
    val responseChannel = findResponseChannel(connection)

    return try {
        // Acquire Kable's guard mutex so no other Kable operation runs concurrently.
        // This prevents the stale response problem: we hold the lock, dispatch the
        // raw write, wait for the callback, drain the response, then release.
        val writeBlock: suspend () -> Result<Unit> = {
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txChar.value = data
            val accepted = gatt.writeCharacteristic(txChar)

            if (accepted) {
                Logger.i("BleExtensions") {
                    "[Flag H] Raw GATT write accepted: ${data.size} bytes"
                }
                // Wait for the onCharacteristicWrite callback to fire.
                // This response MUST be drained before releasing the mutex,
                // otherwise the next Kable operation gets a stale response.
                // 2000ms timeout covers even slow BCM4389 responses.
                if (responseChannel != null) {
                    try {
                        val drained = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                            @Suppress("UNCHECKED_CAST")
                            (responseChannel as kotlinx.coroutines.channels.Channel<Any>).receive()
                        }
                        if (drained != null) {
                            Logger.d("BleExtensions") {
                                "[Flag H] Drained onCharacteristicWrite callback: ${drained::class.simpleName}"
                            }
                        } else {
                            Logger.w("BleExtensions") {
                                "[Flag H] Callback drain timed out (2s) — proceeding anyway"
                            }
                        }
                    } catch (e: Exception) {
                        Logger.w("BleExtensions") {
                            "[Flag H] Callback drain error (non-fatal): ${e.message}"
                        }
                    }
                } else {
                    // Couldn't find the channel — just wait a fixed time for the callback
                    // to settle before releasing the mutex
                    Logger.w("BleExtensions") {
                        "[Flag H] Could not find response channel — using fixed 200ms settle"
                    }
                    delay(200)
                }
                Result.success(Unit)
            } else {
                Logger.e("BleExtensions") {
                    "[Flag H] Raw GATT write REJECTED (returned false)"
                }
                Result.failure(IllegalStateException("writeCharacteristic returned false (GATT busy)"))
            }
        }

        if (guardMutex != null) {
            Logger.d("BleExtensions") { "[Flag H] Acquiring Kable guard mutex..." }
            guardMutex.withLock { writeBlock() }
        } else {
            Logger.w("BleExtensions") { "[Flag H] Could not find Kable guard mutex — writing without lock" }
            writeBlock()
        }
    } catch (e: Exception) {
        Logger.e("BleExtensions") { "[Flag H] Raw GATT write exception: ${e.message}" }
        Result.failure(e)
    }
}

/**
 * Find the Kable Connection object from BluetoothDeviceAndroidPeripheral.
 * Path: peripheral.connection (MutableStateFlow<Connection>) → .value
 */
private fun findConnection(androidPeripheral: AndroidPeripheral): Any? {
    try {
        val peripheralClass = androidPeripheral::class.java
        val connectionField = peripheralClass.declaredFields.firstOrNull { it.name == "connection" }
        if (connectionField != null) {
            connectionField.isAccessible = true
            val stateFlow = connectionField.get(androidPeripheral) ?: return null
            val valueMethod = stateFlow::class.java.methods.firstOrNull {
                it.name == "getValue" && it.parameterCount == 0
            }
            return valueMethod?.invoke(stateFlow)
        }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "findConnection error: ${e.message}" }
    }
    return null
}

/**
 * Find BluetoothGatt from the Connection object.
 */
private fun findGattFromConnection(connection: Any): BluetoothGatt? {
    try {
        // Try public getter first
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
        // Direct field access
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
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "findGattFromConnection error: ${e.message}" }
    }
    return null
}

/**
 * Find Kable's internal guard Mutex from the Connection object.
 */
private fun findGuardMutex(connection: Any): kotlinx.coroutines.sync.Mutex? {
    try {
        val guardField = connection::class.java.declaredFields.firstOrNull { it.name == "guard" }
        if (guardField != null) {
            guardField.isAccessible = true
            return guardField.get(connection) as? kotlinx.coroutines.sync.Mutex
        }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "findGuardMutex error: ${e.message}" }
    }
    return null
}

/**
 * Find Kable's callback.onResponse channel from the Connection object.
 * Path: Connection.callback → Callback.onResponse (Channel<Response>)
 */
private fun findResponseChannel(connection: Any): Any? {
    try {
        val callbackField = connection::class.java.declaredFields.firstOrNull { it.name == "callback" }
        if (callbackField != null) {
            callbackField.isAccessible = true
            val callback = callbackField.get(connection) ?: return null
            val onResponseField = callback::class.java.declaredFields.firstOrNull { it.name == "onResponse" }
            if (onResponseField != null) {
                onResponseField.isAccessible = true
                return onResponseField.get(callback)
            }
            // Try getter
            val getOnResponse = callback::class.java.methods.firstOrNull {
                it.name == "getOnResponse"
            }
            if (getOnResponse != null) {
                return getOnResponse.invoke(callback)
            }
        }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "findResponseChannel error: ${e.message}" }
    }
    return null
}

/**
 * Navigate Kable 0.42.0's internal object graph to find the BluetoothGatt instance.
 * Used by refreshGattCache() and forceCloseGatt() (non-critical operations).
 * For Flag H raw writes, use findConnection() + findGattFromConnection() directly.
 */
private fun findBluetoothGatt(androidPeripheral: AndroidPeripheral): BluetoothGatt? {
    val connection = findConnection(androidPeripheral)
    if (connection != null) {
        val gatt = findGattFromConnection(connection)
        if (gatt != null) return gatt
    }

    // Fallback: walk all fields for any BluetoothGatt (handles Kable refactors)
    try {
        for (field in androidPeripheral::class.java.declaredFields) {
            field.isAccessible = true
            val value = try { field.get(androidPeripheral) } catch (_: Exception) { continue }
            if (value is BluetoothGatt) return value
        }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "findBluetoothGatt fallback error: ${e.message}" }
    }
    Logger.w("BleExtensions") { "BluetoothGatt not found via any reflection path" }
    return null
}
