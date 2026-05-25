package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.ble.DiagnosticPacket
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class FakeBleRepositoryTest {

    @Test
    fun `simulateDisconnect clears diagnostics`() {
        val repository = FakeBleRepository()
        repository.setDiagnostics(testDiagnostics())

        repository.simulateDisconnect()

        assertNull(repository.diagnostics.value)
    }

    @Test
    fun `disconnect APIs clear diagnostics`() = runTest {
        val repository = FakeBleRepository()
        repository.setDiagnostics(testDiagnostics())

        repository.disconnect()
        assertNull(repository.diagnostics.value)

        repository.setDiagnostics(testDiagnostics())
        repository.cancelConnection()
        assertNull(repository.diagnostics.value)
    }

    private fun testDiagnostics(): DiagnosticPacket = DiagnosticPacket(
        runtimeSeconds = 1L,
        faultWords = listOf(1, 0, 0, 0),
        temperatures = listOf(20, 21, 22, 23, 24, 25),
        hasFaults = true,
    )
}
