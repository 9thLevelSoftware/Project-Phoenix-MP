package com.devil.phoenixproject.data.ble

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes all BLE operations through a single Mutex.
 * Prevents interleaving that causes fault 16384 (Issue #222).
 *
 * The parent repo (VitruvianRedux) uses Nordic BLE library's .enqueue()
 * which provides automatic serialization. Kable has no such feature,
 * so we must serialize manually via Mutex.
 *
 * IMPORTANT: Kotlin's Mutex is NOT reentrant. Never nest read()/write()/withLock() calls.
 */
class BleOperationQueue {
    private val mutex = Mutex()

    /** Check if mutex is currently locked (for diagnostic logging). */
    val isLocked: Boolean get() = mutex.isLocked

    /**
     * Execute a read operation through the serialization gate.
     * All BLE reads MUST go through this method.
     */
    suspend fun <T> read(operation: suspend () -> T): T =
        mutex.withLock { operation() }

    /**
     * Execute a write operation with retry logic.
     * Retries on "Busy" errors with exponential backoff (50ms, 100ms, 150ms).
     *
     * @param peripheral The Kable peripheral to write to
     * @param characteristic The characteristic to write to
     * @param data The data to write
     * @param writeType Write type (WithResponse for V-Form, WithoutResponse for Trainer+)
     * @param maxRetries Maximum retry attempts (default: 3)
     * @return Result.success(Unit) on success, Result.failure(exception) on failure
     */
    suspend fun write(
        peripheral: Peripheral,
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType = WriteType.WithResponse,
        maxRetries: Int = 3
    ): Result<Unit> {
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                mutex.withLock {
                    peripheral.write(characteristic, data, writeType)
                }
                return Result.success(Unit)
            } catch (e: Exception) {
                lastException = e
                val isBusyError = e.message?.contains("Busy", ignoreCase = true) == true ||
                    e.message?.contains("WriteRequestBusy", ignoreCase = true) == true

                if (isBusyError && attempt < maxRetries - 1) {
                    // Exponential backoff: 50ms, 100ms, 150ms
                    val delayMs = 50L * (attempt + 1)
                    delay(delayMs)
                } else {
                    break
                }
            }
        }
        return Result.failure(lastException ?: IllegalStateException("Unknown write error"))
    }

    /**
     * Execute a simple write without retry (for internal operations like heartbeat).
     * Use write() for user-facing operations that should retry on busy.
     */
    suspend fun writeSimple(
        peripheral: Peripheral,
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType = WriteType.WithResponse
    ) {
        mutex.withLock {
            peripheral.write(characteristic, data, writeType)
        }
    }

    /**
     * Execute a custom operation with the lock held.
     * Use for compound operations (read-then-write patterns).
     */
    suspend fun <T> withLock(operation: suspend () -> T): T =
        mutex.withLock { operation() }
}
