package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal class BleDeviceInitializationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal suspend fun awaitBleReadyGate(
    readyGate: CompletableDeferred<Unit>,
    timeoutMs: Long = BleConstants.CONNECTION_TIMEOUT_MS,
    onTimeout: () -> Unit = {},
) {
    try {
        withTimeout(timeoutMs) {
            readyGate.await()
        }
    } catch (e: TimeoutCancellationException) {
        onTimeout()
        throw BleDeviceInitializationException("Device initialization timeout after ${timeoutMs}ms", e)
    }
}
