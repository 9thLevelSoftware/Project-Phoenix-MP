package com.devil.phoenixproject.data.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        val sizes = jobs.map { it.await() }

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

        val results = jobs.map { it.await() }

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
}
