package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class NextOwnershipTransferCreatedAtTest {
    @Test
    fun noPriorReturnsNow() {
        assertEquals(1_000L, nextOwnershipTransferCreatedAt(now = 1_000L, latestCreatedAt = null))
    }

    @Test
    fun priorBeforeNowReturnsNow() {
        assertEquals(1_000L, nextOwnershipTransferCreatedAt(now = 1_000L, latestCreatedAt = 400L))
        assertEquals(1_000L, nextOwnershipTransferCreatedAt(now = 1_000L, latestCreatedAt = 999L))
    }

    @Test
    fun priorAtOrAfterNowReturnsOnePastThePrior() {
        assertEquals(1_001L, nextOwnershipTransferCreatedAt(now = 1_000L, latestCreatedAt = 1_000L))
        assertEquals(1_500L, nextOwnershipTransferCreatedAt(now = 1_000L, latestCreatedAt = 1_499L))
    }
}
