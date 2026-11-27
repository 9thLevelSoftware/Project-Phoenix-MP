package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoModeTest {

    @Test
    fun `shutdown cancels active disco job without restore`() = runTest {
        val discoMode = DiscoMode(scope = this, sendCommand = { Result.success(Unit) })

        discoMode.start()
        runCurrent()

        assertTrue(discoMode.isActive.value)
        assertTrue(discoMode.isJobActiveForTest())

        discoMode.shutdown()
        runCurrent()

        assertFalse(discoMode.isActive.value)
        assertFalse(discoMode.isJobActiveForTest())
    }

    @Test
    fun `start stops and clears isActive when sendCommand returns Result failure`() = runTest {
        var attempts = 0
        val discoMode = DiscoMode(
            scope = this,
            sendCommand = {
                attempts++
                Result.failure(IllegalStateException("GATT write failed"))
            },
        )

        discoMode.start()
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(1, attempts)
        assertFalse(discoMode.isActive.value)
        assertFalse(discoMode.isJobActiveForTest())
    }

    @Test
    fun `stop followed immediately by start keeps isActive true and new job running`() = runTest {
        var commandCount = 0
        val discoMode = DiscoMode(
            scope = this,
            sendCommand = {
                commandCount++
                Result.success(Unit)
            },
        )

        discoMode.start()
        runCurrent()
        assertTrue(discoMode.isActive.value)
        assertTrue(discoMode.isJobActiveForTest())

        discoMode.stop()
        discoMode.start()
        runCurrent()

        assertTrue(discoMode.isActive.value, "Cancelled prior disco job must not clobber new session isActive")
        assertTrue(discoMode.isJobActiveForTest(), "New disco job must stay active")

        advanceTimeBy(300L)
        runCurrent()
        assertTrue(discoMode.isActive.value)
        assertTrue(discoMode.isJobActiveForTest())
        assertTrue(commandCount >= 3)

        discoMode.shutdown()
    }
}
