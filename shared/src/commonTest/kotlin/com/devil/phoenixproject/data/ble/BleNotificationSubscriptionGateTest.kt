package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class BleNotificationSubscriptionGateTest {

    @Test
    fun `awaitBleNotificationSubscription returns true when subscription callback fires`() = runTest {
        var startLogged = false
        var subscribedLogged = false

        val ready = awaitBleNotificationSubscription(
            scope = this,
            timeoutMs = 100L,
            startCollector = { onSubscription ->
                onSubscription()
            },
            onStart = { startLogged = true },
            onSubscribed = { subscribedLogged = true },
        )

        assertTrue(ready)
        assertTrue(startLogged)
        assertTrue(subscribedLogged)
    }

    @Test
    fun `awaitBleNotificationSubscription reports timeout instead of rethrowing cancellation`() = runTest {
        var timedOutAfter: Long? = null
        var collectorCancelled = false

        val ready = awaitBleNotificationSubscription(
            scope = this,
            timeoutMs = 100L,
            startCollector = {
                try {
                    awaitCancellation()
                } finally {
                    collectorCancelled = true
                }
            },
            onSubscriptionTimeout = { timedOutAfter = it },
        )

        runCurrent()

        assertFalse(ready)
        assertEquals(100L, timedOutAfter)
        assertTrue(collectorCancelled)
    }

    @Test
    fun `awaitBleNotificationSubscription reports collector failure before subscription`() = runTest {
        var collectorFailureBeforeSubscription = false
        var awaitFailure: Throwable? = null

        val ready = awaitBleNotificationSubscription(
            scope = this,
            timeoutMs = 100L,
            startCollector = {
                error("CCCD write failed")
            },
            onCollectorFailure = { _, subscriptionAlreadyResolved ->
                collectorFailureBeforeSubscription = !subscriptionAlreadyResolved
            },
            onAwaitFailure = { awaitFailure = it },
        )

        assertFalse(ready)
        assertTrue(collectorFailureBeforeSubscription)
        assertIs<IllegalStateException>(awaitFailure)
        assertEquals("CCCD write failed", awaitFailure?.message)
    }
}
