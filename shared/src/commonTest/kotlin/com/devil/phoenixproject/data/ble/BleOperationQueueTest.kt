package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BleOperationQueueTest {

    @Test
    fun `isLocked returns false when idle`() {
        val queue = BleOperationQueue()
        assertFalse(queue.isLocked)
    }

    @Test
    fun `isLocked returns true when operation in progress`() = runTest {
        val queue = BleOperationQueue()
        val job = async {
            queue.read {
                assertTrue(queue.isLocked)
                delay(10)
            }
        }
        delay(1) // Give the async a moment to start
        assertTrue(queue.isLocked)
        job.await()
        assertFalse(queue.isLocked)
    }

    @Test
    fun `read serializes concurrent calls`() = runTest {
        val queue = BleOperationQueue()
        val results = mutableListOf<Int>()

        // Launch multiple concurrent reads
        val jobs = (1..5).map { i ->
            async {
                queue.read {
                    // Record entry order
                    results.add(i)
                    delay(5)
                    // Verify no interleaving - size should equal i at this point
                    results.size
                }
            }
        }

        val sizes = jobs.awaitAll()

        // Each operation should see monotonically increasing size
        // (proves no interleaving - each completed before next started)
        assertEquals(listOf(1, 2, 3, 4, 5), sizes)
    }

    @Test
    fun `withLock serializes operations`() = runTest {
        val queue = BleOperationQueue()
        var counter = 0

        val jobs = (1..3).map {
            async {
                queue.withLock {
                    val before = counter
                    delay(5)
                    counter = before + 1
                    counter
                }
            }
        }

        val results = jobs.awaitAll()

        // Each withLock should see the result of the previous one
        assertEquals(listOf(1, 2, 3), results)
    }

    @Test
    fun `read returns operation result`() = runTest {
        val queue = BleOperationQueue()
        val result = queue.read { "test-value" }
        assertEquals("test-value", result)
    }

    @Test
    fun `withLock returns operation result`() = runTest {
        val queue = BleOperationQueue()
        val result = queue.withLock { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `write does not retry Write failed Unknown when peripheral is Disconnected`() = runTest {
        val queue = BleOperationQueue()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = CompositionGattFake(CompositionAdvertisement(), dispatcher)
        var writeCalls = 0
        val disconnectedPeripheral = object : Peripheral by fake {
            override val state: StateFlow<State> = MutableStateFlow(State.Disconnected())
            override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) {
                writeCalls++
                throw IllegalStateException("Write failed: Unknown")
            }
        }

        val result = queue.write(
            peripheral = disconnectedPeripheral,
            characteristic = BleConstants.txCharacteristic,
            data = byteArrayOf(0x0A, 0x00, 0x00, 0x00),
        )

        assertTrue(result.isFailure)
        assertEquals(1, writeCalls)
        fake.close()
    }

    @Test
    fun `write retries Write failed Unknown up to maxRetries when peripheral is Connected`() = runTest {
        val queue = BleOperationQueue()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = CompositionGattFake(CompositionAdvertisement(), dispatcher)
        val connectedScope = CoroutineScope(SupervisorJob() + dispatcher)
        var writeCalls = 0
        val connectedPeripheral = object : Peripheral by fake {
            override val state: StateFlow<State> = MutableStateFlow(State.Connected(connectedScope))
            override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) {
                writeCalls++
                if (writeCalls < 3) {
                    throw IllegalStateException("Write failed: Unknown")
                }
            }
        }

        val result = queue.write(
            peripheral = connectedPeripheral,
            characteristic = BleConstants.txCharacteristic,
            data = byteArrayOf(0x0A, 0x00, 0x00, 0x00),
            maxRetries = 3,
        )

        assertTrue(result.isSuccess)
        assertEquals(3, writeCalls)
        fake.close()
    }
}
