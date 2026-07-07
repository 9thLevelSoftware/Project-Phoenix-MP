package com.devil.phoenixproject.data.sync

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ClientRateLimiterTest {

    @Test
    fun acquireWithWaitReturnsImmediatelyWhenSlotIsFree() {
        val limiter = ClientRateLimiter(windowMillis = 150L)

        val elapsed = measureTimeMillis {
            runBlocking {
                limiter.acquireWithWait("pull", limit = 1)
            }
        }

        assertTrue(elapsed < 100L, "free slot should not wait, elapsed=${elapsed}ms")
        assertFalse(
            runBlocking { limiter.tryAcquire("pull", limit = 1) },
            "acquireWithWait should record the granted slot in the same sliding window",
        )
    }

    @Test
    fun acquireWithWaitBlocksUntilWindowRemainderExpires() {
        val limiter = ClientRateLimiter(windowMillis = 150L)
        runBlocking {
            assertTrue(limiter.tryAcquire("pull", limit = 1), "first slot fill should succeed")
        }

        val elapsed = measureTimeMillis {
            runBlocking {
                limiter.acquireWithWait("pull", limit = 1)
            }
        }

        assertTrue(elapsed >= 110L, "full window should wait close to remainder, elapsed=${elapsed}ms")
    }
}
