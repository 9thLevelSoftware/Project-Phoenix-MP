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
 * Issue #333 Flag H: Write to BluetoothGatt directly, bypassing Kable's
 * connectionScope.async that causes "StandaloneCoroutine was cancelled" on BCM4389.
 *
 * ## Evolution
 * - v4: Raw write + no stale response handling → CONFIG succeeded in 53ms
 *   but disconnect 12s later (onCharacteristicWrite poisoned Kable's channel)
 * - v5: Added guard mutex lock + blocking channel drain → 2009ms timeout,
 *   callback never arrived because blocking receive() + mutex contention
 *   stalled the whole operation
 * - v6: Minimal approach — dispatch on Kable's own threading dispatcher for
 *   correct thread affinity, use non-blocking tryReceive() after a short
 *   settle delay. NO guard mutex (Flag F already stopped polling — nothing
 *   competes). This matches v4's simplicity while cleaning up the channel.
 *
 * ## Why no guard mutex?
 * Flag F quiesces all polling 150ms before the write. With no Kable
 * operations in flight, the guard mutex is uncontested — acquiring it just
 * adds latency and risk. v5 proved that blocking on the mutex + channel
 * receive together causes the 2009ms timeout. Keep it simple.
 *
 * ## Why dispatch on Kable's threading dispatcher?
 * Kable's Connection.execute() dispatches GATT actions via
 * withContext(dispatcher) which routes to the Handler thread (API 26+).
 * The BluetoothGattCallback also fires on this handler. Using the same
 * dispatcher ensures correct thread affinity and matches the official app's
 * single-threaded BLE approach.
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

    // Get the Connection object (holds gatt, threading dispatcher, and callback)
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

    // Get Kable's threading dispatcher and callback channel
    val kableDispatcher = findDispatcher(connection)
    val responseChannel = findResponseChannel(connection)

    return try {
        // Dispatch the raw write on Kable's threading dispatcher for correct
        // thread affinity (Handler thread on API 26+). This matches how
        // Connection.execute() dispatches GATT operations.
        val writeContext = kableDispatcher ?: kotlinx.coroutines.Dispatchers.IO
        if (kableDispatcher != null) {
            Logger.d("BleExtensions") { "[Flag H] Dispatching on Kable's handler thread" }
        } else {
            Logger.w("BleExtensions") { "[Flag H] Kable dispatcher not found — using Dispatchers.IO" }
        }

        val accepted = kotlinx.coroutines.withContext(writeContext) {
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txChar.value = data
            gatt.writeCharacteristic(txChar)
        }

        if (!accepted) {
            Logger.e("BleExtensions") {
                "[Flag H] Raw GATT write REJECTED (returned false — GATT busy)"
            }
            return Result.failure(IllegalStateException("writeCharacteristic returned false (GATT busy)"))
        }

        Logger.i("BleExtensions") {
            "[Flag H] Raw GATT write accepted: ${data.size} bytes"
        }

        // Short settle delay for the onCharacteristicWrite callback to fire.
        // The callback pushes into Kable's onResponse channel (CONFLATED).
        // We DON'T block waiting for it — that caused v5's 2009ms timeout.
        // 150ms matches the official app's observed callback latency.
        delay(150)

        // Non-blocking drain: remove any stale response from the channel
        // so the next Kable operation doesn't pick up our write response.
        // tryReceive() returns immediately — no blocking, no timeout risk.
        if (responseChannel != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val channel = responseChannel as kotlinx.coroutines.channels.Channel<Any>
                val result = channel.tryReceive()
                if (result.isSuccess) {
                    val drained = result.getOrNull()
                    Logger.d("BleExtensions") {
                        "[Flag H] Drained callback response: ${drained?.let { it::class.simpleName } ?: "null"}"
                    }
                } else if (result.isClosed) {
                    // Channel closed = connection dropped (onConnectionStateChange → DISCONNECTED)
                    Logger.w("BleExtensions") {
                        "[Flag H] Response channel closed — connection may have dropped"
                    }
                } else {
                    // No response yet — callback hasn't fired in 150ms.
                    // This is OK: the callback will fire eventually and since
                    // polling is stopped (Flag F), nothing will read the channel
                    // before polling resumes. When polling resumes, the first
                    // Kable read will acquire guard, and if there's a stale
                    // write response it will get a type mismatch. To prevent
                    // that, do one more drain attempt after a longer wait.
                    Logger.d("BleExtensions") {
                        "[Flag H] No callback yet after 150ms — retrying after 200ms"
                    }
                    delay(200)
                    val retry = channel.tryReceive()
                    if (retry.isSuccess) {
                        Logger.d("BleExtensions") {
                            "[Flag H] Drained callback on retry: ${retry.getOrNull()?.let { it::class.simpleName }}"
                        }
                    } else if (retry.isClosed) {
                        Logger.w("BleExtensions") {
                            "[Flag H] Channel closed on retry — connection dropped"
                        }
                    } else {
                        // Still nothing after 350ms total. The write was accepted
                        // by the BLE stack but no callback came. Either:
                        // 1. BCM4389 is very slow (but not erroring)
                        // 2. The callback fired but on a thread we can't see
                        // Log it but proceed — the write WAS accepted.
                        Logger.w("BleExtensions") {
                            "[Flag H] No callback after 350ms — write was accepted, proceeding"
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w("BleExtensions") {
                    "[Flag H] Channel drain error (non-fatal): ${e.message}"
                }
            }
        } else {
            Logger.w("BleExtensions") {
                "[Flag H] Could not find response channel — 200ms settle fallback"
            }
            delay(200)
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
