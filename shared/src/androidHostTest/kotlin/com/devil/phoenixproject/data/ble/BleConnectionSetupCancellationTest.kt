package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.AndroidPeripheral
import com.juul.kable.GattStatusException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Android connection setup catches [Exception]. [CancellationException] extends
 * [Exception], so a cancelled connection scope must leave [requestHighPriority]
 * and [requestMtuIfSupported] instead of being logged as a finished GATT call.
 * A real GATT failure still logs and returns.
 */
class BleConnectionSetupCancellationTest {

    @Test
    fun requestHighPriority_rethrowsCancellationFromThePriorityRequest() = runTest {
        val peripheral = mockk<AndroidPeripheral>()
        val cancellation = CancellationException("connection scope cancelled")
        every {
            peripheral.requestConnectionPriority(AndroidPeripheral.Priority.High)
        } throws cancellation

        val thrown = assertFailsWith<CancellationException> {
            peripheral.requestHighPriority()
        }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun requestHighPriority_rethrowsCancellationWhileThePriorityChangeSettles() = runTest {
        val peripheral = mockk<AndroidPeripheral>()
        every { peripheral.requestConnectionPriority(AndroidPeripheral.Priority.High) } returns true
        var continuedAfterSetup = false
        val setup = launch {
            peripheral.requestHighPriority()
            continuedAfterSetup = true
        }
        runCurrent()

        setup.cancel(CancellationException("connection scope cancelled"))
        advanceUntilIdle()

        assertFalse(continuedAfterSetup, "cancelled connection setup continued after the settle delay")
        assertTrue(setup.isCancelled)
    }

    @Test
    fun requestHighPriority_logsAndReturnsWhenThePriorityRequestFails() = runTest {
        val peripheral = mockk<AndroidPeripheral>()
        every {
            peripheral.requestConnectionPriority(AndroidPeripheral.Priority.High)
        } throws GattStatusException(message = "GATT 133", status = 133)

        val logged = captureLogs {
            peripheral.requestHighPriority()
        }

        assertTrue(
            logged.lines.any { it.contains("Failed to request high connection priority") && it.contains("GATT 133") },
            "GATT failure should still be logged, was: ${logged.lines}",
        )
    }

    @Test
    fun requestMtuIfSupported_rethrowsCancellation() = runTest {
        val peripheral = mockk<AndroidPeripheral>()
        val cancellation = CancellationException("connection scope cancelled")
        coEvery { peripheral.requestMtu(BleConstants.Timing.DESIRED_MTU) } throws cancellation

        val thrown = assertFailsWith<CancellationException> {
            peripheral.requestMtuIfSupported(BleConstants.Timing.DESIRED_MTU)
        }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun requestMtuIfSupported_logsAndReturnsNullWhenNegotiationFails() = runTest {
        val peripheral = mockk<AndroidPeripheral>()
        coEvery { peripheral.requestMtu(BleConstants.Timing.DESIRED_MTU) } throws
            GattStatusException(message = "GATT 133", status = 133)

        val logged = captureLogs {
            peripheral.requestMtuIfSupported(BleConstants.Timing.DESIRED_MTU)
        }

        assertNull(logged.value)
        assertTrue(
            logged.lines.any { it.contains("MTU negotiation failed") && it.contains("GATT 133") },
            "GATT failure should still be logged, was: ${logged.lines}",
        )
    }

    @Test
    fun requestMtuIfSupported_returnsTheNegotiatedMtu() = runTest {
        val peripheral = mockk<AndroidPeripheral>()
        coEvery { peripheral.requestMtu(BleConstants.Timing.DESIRED_MTU) } returns 185

        assertEquals(185, peripheral.requestMtuIfSupported(BleConstants.Timing.DESIRED_MTU))
    }

    private suspend fun <T> captureLogs(block: suspend () -> T): Logged<T> {
        val recording = RecordingLogWriter()
        val previous = Logger.config.logWriterList
        Logger.setLogWriters(recording)
        return try {
            Logged(block(), recording.lines.toList())
        } finally {
            Logger.setLogWriters(previous)
        }
    }

    private data class Logged<T>(val value: T, val lines: List<String>)

    private class RecordingLogWriter : LogWriter() {
        val lines: MutableList<String> = mutableListOf()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += message
        }
    }
}
