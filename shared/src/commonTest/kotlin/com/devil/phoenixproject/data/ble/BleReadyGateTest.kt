package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class BleReadyGateTest {

    @Test
    fun `awaitBleReadyGate returns when readiness completes`() = runTest {
        val readyGate = CompletableDeferred<Unit>()
        readyGate.complete(Unit)

        awaitBleReadyGate(readyGate, timeoutMs = 100L)
    }

    @Test
    fun `awaitBleReadyGate fails with initialization timeout when readiness never completes`() = runTest {
        var timeoutCallbackCalled = false

        val error = assertFailsWith<IllegalStateException> {
            awaitBleReadyGate(
                readyGate = CompletableDeferred(),
                timeoutMs = 100L,
                onTimeout = { timeoutCallbackCalled = true },
            )
        }

        assertTrue(timeoutCallbackCalled)
        assertTrue(error.message?.contains("Device initialization timeout after 100ms") == true)
    }
}
