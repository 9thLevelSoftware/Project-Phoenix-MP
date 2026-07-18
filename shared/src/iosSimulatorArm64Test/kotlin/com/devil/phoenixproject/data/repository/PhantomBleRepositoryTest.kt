package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.BlePacketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest

class PhantomBleRepositoryTest {
    @Test
    fun `scan connect and disconnect publish deterministic simulator lifecycle`() = runTest {
        val repository = PhantomBleRepository()
        try {
            assertTrue(repository.startScanning().isSuccess)
            val device = repository.scannedDevices.value.single()
            assertEquals("Vee_PhantomSimulator", device.name)
            assertEquals("PH:AN:TO:MS:BX:01", device.address)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)

            assertTrue(repository.connect(device).isSuccess)
            assertEquals(
                ConnectionState.Connected(device.name, device.address),
                repository.connectionState.value,
            )

            repository.disconnect()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `existing workout packets are accepted and deterministic metrics and reps flow`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo = logRepo,
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
        )
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 2,
            warmupReps = 1,
            weightPerCableKg = 12.5f,
        )
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.sendWorkoutCommand(BlePacketFactory.createProgramParams(params)).isSuccess)

            repository.metricsFlow.test {
                assertTrue(repository.startWorkout(params).isSuccess)
                val metric = withTimeout(2_000L) { awaitItem() }
                assertTrue(metric.loadA >= 12.5f)
                assertTrue(metric.loadB >= 12.5f)
                cancelAndIgnoreRemainingEvents()
            }

            repository.repEvents.test {
                assertTrue(repository.stopWorkout().isSuccess)
                assertTrue(repository.startWorkout(params).isSuccess)
                withContext(Dispatchers.Default) { delay(150L) }
                val rep = withTimeout(2_000L) { awaitItem() }
                assertEquals(1, rep.topCounter)
                assertEquals(1, rep.completeCounter)
                assertEquals(1, rep.repsRomCount)
                assertEquals(0, rep.repsSetCount)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stop polling cancels metric and rep jobs while connection remains`() = runTest {
        val repository = PhantomBleRepository(
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
        )
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 8f,
        )
        try {
            assertTrue(repository.scanAndConnect().isSuccess)

            repository.metricsFlow.test {
                assertTrue(repository.startWorkout(params).isSuccess)
                awaitItem()
                repository.stopPolling()
                withContext(Dispatchers.Default) { delay(350L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(repository.connectionState.value is ConnectionState.Connected)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `rep flow emits after a fresh workout starts`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo, PhantomBleConfig(repDelayMs = 100L))
        val params = WorkoutParameters(ProgramMode.OldSchool, reps = 2, weightPerCableKg = 8f)
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.startWorkout(params).isSuccess)
                withContext(Dispatchers.Default) { delay(150L) }
                val rep = withTimeout(2_000L) { awaitItem() }
                assertEquals(1, rep.topCounter)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown clears state and prevents post shutdown emissions`() = runTest {
        val repository = PhantomBleRepository(
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
        )
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 8f,
        )
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.startWorkout(params).isSuccess)
            repository.shutdown()

            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
            assertFalse(repository.discoModeActive.value)
            assertTrue(repository.startScanning().isFailure)

            repository.metricsFlow.test {
                withContext(Dispatchers.Default) { delay(350L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }
}
