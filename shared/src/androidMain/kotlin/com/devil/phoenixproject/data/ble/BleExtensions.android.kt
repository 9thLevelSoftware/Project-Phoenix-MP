package com.devil.phoenixproject.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import co.touchlab.kermit.Logger
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * Issue #333 Flag H v8: Write to BluetoothGatt directly, bypassing Kable's
 * connectionScope.async that causes "StandaloneCoroutine was cancelled" on BCM4389.
 *
 * ## v8: WRITE_TYPE_NO_RESPONSE experiment
 * v7 quarantine proved the connection survives 2500ms of silence, but the GATT
 * write lane is permanently wedged — `WriteRequestBusy` after 2500ms means the
 * `onCharacteristicWrite` callback never completed for `WRITE_TYPE_DEFAULT`.
 *
 * v8 switches to `WRITE_TYPE_NO_RESPONSE` to bypass the acknowledged write path
 * entirely. If the BCM4389 problem is specifically the write completion callback,
 * NO_RESPONSE should succeed and the probe read should work. If the problem is
 * the 96-byte CONFIG payload itself, NO_RESPONSE will also fail.
 *
 * ## Evolution
 * - v4: Raw write, no response handling → 53ms acceptance, disconnect 12s later
 * - v5: Guard mutex + blocking drain → 2009ms timeout (deadlock)
 * - v6: Non-blocking tryReceive() drain → still uncertain about write success
 * - v7: Quarantine experiment → connection survived, but WriteRequestBusy (write lane wedged)
 * - v8: WRITE_TYPE_NO_RESPONSE — bypass the wedged acknowledged write path
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

    val kableDispatcher = findDispatcher(connection)

    return try {
        // Dispatch on Kable's handler thread for correct thread affinity.
        val writeContext = kableDispatcher ?: kotlinx.coroutines.Dispatchers.IO
        if (kableDispatcher != null) {
            Logger.d("BleExtensions") { "[Flag H] Dispatching on Kable's handler thread" }
        } else {
            Logger.w("BleExtensions") { "[Flag H] Kable dispatcher not found — using Dispatchers.IO" }
        }

        val accepted = kotlinx.coroutines.withContext(writeContext) {
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            txChar.value = data
            gatt.writeCharacteristic(txChar)
        }

        if (!accepted) {
            Logger.e("BleExtensions") {
                "[Flag H] Raw GATT write REJECTED (returned false — GATT busy)"
            }
            return Result.failure(IllegalStateException("writeCharacteristic returned false (GATT busy)"))
        }

        // v8: WRITE_TYPE_NO_RESPONSE means no onCharacteristicWrite callback.
        // The BLE stack fires and forgets. The caller (ActiveSessionEngine) will
        // enforce a 2500ms quarantine and then probe with a READ to check GATT health.
        Logger.i("BleExtensions") {
            "[Flag H] Raw GATT write INITIATED (NO_RESPONSE mode): ${data.size} bytes. " +
                "Quarantine begins — caller will probe with read."
        }

        Result.success(Unit)
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
 * Find Kable's threading dispatcher from the Connection object.
 * Path: Connection.dispatcher (CoroutineContext composed from threading.dispatcher)
 * This is the dispatcher Kable uses for all GATT operations (Handler thread on API 26+).
 */
private fun findDispatcher(connection: Any): kotlin.coroutines.CoroutineContext? {
    try {
        // Connection has: private val dispatcher = connectionScope.coroutineContext + threading.dispatcher
        val dispatcherField = connection::class.java.declaredFields.firstOrNull { it.name == "dispatcher" }
        if (dispatcherField != null) {
            dispatcherField.isAccessible = true
            val dispatcher = dispatcherField.get(connection)
            if (dispatcher is kotlin.coroutines.CoroutineContext) {
                Logger.d("BleExtensions") { "Found Kable dispatcher via Connection.dispatcher field" }
                return dispatcher
            }
        }
        // Fallback: try threading field → threading.dispatcher
        val threadingField = connection::class.java.declaredFields.firstOrNull { it.name == "threading" }
        if (threadingField != null) {
            threadingField.isAccessible = true
            val threading = threadingField.get(connection)
            if (threading != null) {
                val getDispatcher = threading::class.java.methods.firstOrNull {
                    it.name == "getDispatcher" && it.parameterCount == 0
                }
                if (getDispatcher != null) {
                    val d = getDispatcher.invoke(threading)
                    if (d is kotlin.coroutines.CoroutineContext) {
                        Logger.d("BleExtensions") { "Found Kable dispatcher via threading.dispatcher" }
                        return d
                    }
                }
            }
        }
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "findDispatcher error: ${e.message}" }
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
