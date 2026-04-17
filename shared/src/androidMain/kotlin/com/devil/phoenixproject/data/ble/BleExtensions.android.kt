package com.devil.phoenixproject.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import co.touchlab.kermit.Logger
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Pin BLE link to LE 1M PHY via reflection on Kable's internal [BluetoothGatt] handle.
 *
 * Kable 0.40.2 does not publicly expose [BluetoothGatt.setPreferredPhy] on the [AndroidPeripheral]
 * interface, so we walk the internal field layout:
 *
 *   `BluetoothDeviceAndroidPeripheral.connection: MutableStateFlow<Connection?>`
 *   └── `Connection.gatt: BluetoothGatt`
 *
 * Both field names are stable in Kable source (not obfuscated in release AARs). If a future
 * Kable upgrade renames these fields, the reflection falls back by searching for any field
 * of the expected type — logged as a warning but non-fatal.
 *
 * Issue #333: see [requestLe1mPhy] in `BleExtensions.kt` for full rationale.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT declared in androidApp AndroidManifest; minSdk=26 so setPreferredPhy is always available.
actual suspend fun Peripheral.requestLe1mPhy(onEvent: (String) -> Unit) {
    val androidPeripheral = this as? AndroidPeripheral
    if (androidPeripheral == null) {
        Logger.w("BleExtensions") { "⚠️ Cannot pin PHY: not an AndroidPeripheral" }
        return
    }

    val gatt = extractBluetoothGatt(androidPeripheral)
    if (gatt == null) {
        Logger.w("BleExtensions") {
            "⚠️ Cannot pin PHY: BluetoothGatt handle not found on ${androidPeripheral::class.simpleName} — Kable internals changed?"
        }
        onEvent("PHY pin skipped — internal handle unavailable")
        return
    }

    try {
        Logger.i("BleExtensions") {
            "🔧 Pinning LE 1M PHY (Issue #333 — Pixel 6/7 BCM4389 compat)"
        }
        onEvent("Requesting LE 1M PHY (Issue #333)")
        gatt.setPreferredPhy(
            BluetoothDevice.PHY_LE_1M_MASK,
            BluetoothDevice.PHY_LE_1M_MASK,
            BluetoothDevice.PHY_OPTION_NO_PREFERRED,
        )
        // onPhyUpdate fires on Kable's internal Callback; wait briefly so the Android BLE stack
        // completes the PHY update before MTU / priority requests flood in behind it.
        delay(300)
        // Trigger a readPhy so the negotiated PHY appears in both the HCI snoop log and
        // Kable's "Kable/Callback" debug log as `onPhyRead txPhy=? rxPhy=? status=?`.
        try {
            gatt.readPhy()
        } catch (e: Exception) {
            Logger.w("BleExtensions") { "readPhy after setPreferredPhy failed (non-fatal): ${e.message}" }
        }
        Logger.i("BleExtensions") {
            "✅ setPreferredPhy(1M, 1M, NO_PREFERRED) issued — verify via Kable/Callback logcat or HCI snoop"
        }
        onEvent("LE 1M PHY pinned (verify via logcat Kable/Callback)")
    } catch (e: SecurityException) {
        Logger.w("BleExtensions") { "⚠️ setPreferredPhy SecurityException (missing BLUETOOTH_CONNECT?): ${e.message}" }
        onEvent("PHY pin failed — permission denied")
    } catch (e: Exception) {
        Logger.w("BleExtensions") { "⚠️ setPreferredPhy failed: ${e.message}" }
        onEvent("PHY pin failed — ${e.message}")
    }
}

/**
 * Reflective extraction of Kable's internal [BluetoothGatt]. Defensive against field renames:
 * first tries the known field name (`connection`), then falls back to any [StateFlow] field
 * whose value has a `gatt: BluetoothGatt` field.
 */
private fun extractBluetoothGatt(androidPeripheral: AndroidPeripheral): BluetoothGatt? {
    val peripheralClass = androidPeripheral::class.java

    // Preferred path: BluetoothDeviceAndroidPeripheral.connection
    val byName = runCatching {
        val field = peripheralClass.declaredFields.firstOrNull { it.name == "connection" }
            ?: return@runCatching null
        field.isAccessible = true
        val value = field.get(androidPeripheral)
        (value as? StateFlow<*>)?.value?.let(::gattFieldOf)
    }.getOrNull()
    if (byName != null) return byName

    // Fallback: scan every field for a StateFlow whose current value has a BluetoothGatt
    for (field in peripheralClass.declaredFields) {
        try {
            field.isAccessible = true
            val raw = field.get(androidPeripheral) ?: continue
            val inner = (raw as? StateFlow<*>)?.value ?: continue
            val gatt = gattFieldOf(inner)
            if (gatt != null) {
                Logger.d("BleExtensions") { "Located BluetoothGatt via fallback field scan (${field.name})" }
                return gatt
            }
        } catch (_: Exception) {
            // try next field
        }
    }

    return null
}

/** Read a `gatt: BluetoothGatt` field off an arbitrary holder via reflection. */
private fun gattFieldOf(holder: Any): BluetoothGatt? {
    return try {
        val field = holder::class.java.declaredFields.firstOrNull { it.name == "gatt" }
            ?: holder::class.java.declaredFields.firstOrNull { BluetoothGatt::class.java.isAssignableFrom(it.type) }
            ?: return null
        field.isAccessible = true
        field.get(holder) as? BluetoothGatt
    } catch (_: Exception) {
        null
    }
}
