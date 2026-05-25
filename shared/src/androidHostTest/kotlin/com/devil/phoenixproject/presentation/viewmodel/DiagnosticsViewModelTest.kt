package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DiagnosticsViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var viewModel: DiagnosticsViewModel

    @Before
    fun setup() {
        fakeBleRepository = FakeBleRepository()
        viewModel = DiagnosticsViewModel(fakeBleRepository)
    }

    @Test
    fun `ui state exposes decoded connected diagnostics`() = runTest {
        viewModel.uiState.test {
            assertFalse(awaitItem().isConnected)

            fakeBleRepository.simulateConnect("Vee_Test")
            fakeBleRepository.setDiagnostics(
                DiagnosticPacket(
                    runtimeSeconds = 42L,
                    faultWords = listOf(4, 0, 4, 0),
                    temperatures = listOf(25, 26, 27, 28, 29, 30, 31, 32),
                    hasFaults = true,
                    receivedAtMillis = 1_000L,
                ),
            )
            advanceUntilIdle()

            val state = awaitItemAfterUpdates { it.packet != null }
            assertTrue(state.isConnected)
            assertEquals(42L, state.packet?.runtimeSeconds)
            assertEquals("TI restarted", state.faults[0].label)
            assertEquals("Over voltage", state.faults[2].label)
            assertTrue(state.exportText.contains("Vee: TI restarted (0x0004 / 4)"))
            assertTrue(state.exportText.contains("Motor A: Over voltage (0x0004 / 4)"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ui state reports missing diagnostics when disconnected`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()

            assertFalse(state.isConnected)
            assertEquals(null, state.packet)
            assertTrue(state.exportText.contains("No diagnostic snapshot has been read."))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `buildDiagnosticsExportText includes crash and warnings`() {
        val packet = DiagnosticPacket(
            runtimeSeconds = 9L,
            faultWords = listOf(0, 0, 0, 0),
            temperatures = listOf(1, 2, 3, 4, 5, 6),
            hasFaults = false,
            crash = com.devil.phoenixproject.data.ble.DiagnosticCrash(
                seconds = 7L,
                stackBase64 = "AQID",
            ),
            warnings = 2147483652L,
            receivedAtMillis = 1_000L,
        )

        val export = buildDiagnosticsExportText(
            connectionLabel = "Connected to Vee_Test",
            packet = packet,
            faults = com.devil.phoenixproject.data.ble.decodeDiagnosticFaults(packet),
            exportedAtMillis = 2_000L,
        )

        assertTrue(export.contains("Crash:"))
        assertTrue(export.contains("Stack Base64: AQID"))
        assertTrue(export.contains("Warnings: 2147483652 (0x80000004)"))
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<DiagnosticsUiState>.awaitItemAfterUpdates(
        predicate: (DiagnosticsUiState) -> Boolean,
    ): DiagnosticsUiState {
        repeat(5) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
        error("Expected matching DiagnosticsUiState")
    }
}
