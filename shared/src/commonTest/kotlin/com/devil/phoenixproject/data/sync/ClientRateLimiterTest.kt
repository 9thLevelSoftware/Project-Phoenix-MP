package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ClientRateLimiterTest {

    @Test
    fun acquireWithWaitReturnsImmediatelyWhenSlotIsFree() = runTest {
        var nowMs = 1_000L
        val waitCalls = mutableListOf<Long>()
        val limiter = ClientRateLimiter(
            windowMillis = 150L,
            nowMs = { nowMs },
            waitFor = { delayMs ->
                waitCalls += delayMs
                nowMs += delayMs
            },
        )

        limiter.acquireWithWait("pull", limit = 1)

        assertEquals(emptyList(), waitCalls, "free slot should not wait")
        assertFalse(
            limiter.tryAcquire("pull", limit = 1),
            "acquireWithWait should record the granted slot in the same sliding window",
        )
    }

    @Test
    fun acquireWithWaitBlocksUntilWindowRemainderExpires() = runTest {
        var nowMs = 1_000L
        val waitCalls = mutableListOf<Long>()
        val limiter = ClientRateLimiter(
            windowMillis = 150L,
            nowMs = { nowMs },
            waitFor = { delayMs ->
                waitCalls += delayMs
                nowMs += delayMs
            },
        )

        assertTrue(limiter.tryAcquire("pull", limit = 1), "first slot fill should succeed")

        limiter.acquireWithWait("pull", limit = 1)

        assertEquals(listOf(150L), waitCalls, "full window should wait exactly the remaining window")
        assertEquals(1_150L, nowMs, "wait path should advance to the first available instant")
    }
}
