package com.juul.kable

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK
import android.bluetooth.BluetoothDevice.PHY_LE_2M_MASK
import android.bluetooth.BluetoothDevice.PHY_LE_CODED_MASK
import android.bluetooth.BluetoothDevice.TRANSPORT_AUTO
import android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.juul.kable.gatt.Callback
import com.juul.kable.logs.Logging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

internal fun BluetoothDevice.connect(
    coroutineContext: CoroutineContext,
    context: Context,
    autoConnect: Boolean,
    transport: Transport,
    phy: Phy,
    state: MutableStateFlow<State>,
    services: MutableStateFlow<List<PlatformDiscoveredService>?>,
    mtu: MutableStateFlow<Int?>,
    onCharacteristicChanged: MutableSharedFlow<ObservationEvent<ByteArray>>,
    logging: Logging,
    threadingStrategy: ThreadingStrategy,
    disconnectTimeout: Duration,
): Connection {
    val callback = Callback(state, mtu, onCharacteristicChanged, logging, address)
    val threading = threadingStrategy.acquire()

    val bluetoothGatt = try {
        val handler = (threading as Threading.Handler).handler
        connectGatt(context, autoConnect, callback, transport.intValue, phy.intValue, handler)
            ?: throw IOException("Binder remote-invocation error")
    } catch (t: Throwable) {
        threading.release()
        throw t
    }

    return Connection(coroutineContext, bluetoothGatt, threading, callback, services, disconnectTimeout, logging)
}

internal val Transport.intValue: Int
    @RequiresApi(Build.VERSION_CODES.M)
    get() = when (this) {
        Transport.Auto -> TRANSPORT_AUTO
        Transport.BrEdr -> TRANSPORT_BREDR
        Transport.Le -> TRANSPORT_LE
    }

private val Phy.intValue: Int
    @RequiresApi(Build.VERSION_CODES.O)
    get() = when (this) {
        Phy.Le1M -> PHY_LE_1M_MASK
        Phy.Le2M -> PHY_LE_2M_MASK
        Phy.LeCoded -> PHY_LE_CODED_MASK
    }
