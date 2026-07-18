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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PhantomBleRepositoryTest {
    @Test
    fun `shutdown reentered from scanning wins before scan continuation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo)
        var shutdownTriggered = false
        val observer = launch(Dispatchers.Unconfined) {
            repository.connectionState.collect { state ->
                if (!shutdownTriggered && state == ConnectionState.Scanning) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.startScanning().isFailure)
            assertTrue(shutdownTriggered)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.SCAN_START })
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown reentered from connected wins before connection continuation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo)
        var shutdownTriggered = false
        var observer: Job? = null
        try {
            assertTrue(repository.startScanning().isSuccess)
            val device = repository.scannedDevices.value.single()
            observer = launch(Dispatchers.Unconfined) {
                repository.connectionState.collect { state ->
                    if (!shutdownTriggered && state is ConnectionState.Connected) {
                        shutdownTriggered = true
                        repository.shutdown()
                    }
                }
            }

            assertTrue(repository.connect(device).isFailure)
            assertTrue(shutdownTriggered)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.SERVICE_DISCOVERED })
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.CONNECT_SUCCESS })
        } finally {
            observer?.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown reentered from rep observer prevents post-event continuation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo = logRepo,
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
        )
        val params = WorkoutParameters(ProgramMode.OldSchool, reps = 5, warmupReps = 0, weightPerCableKg = 8f)
        var shutdownTriggered = false
        val observer = launch(Dispatchers.Unconfined) {
            repository.repEvents.collect {
                if (!shutdownTriggered) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.startWorkout(params).isSuccess)
            withContext(Dispatchers.Default) { delay(300L) }
            assertTrue(shutdownTriggered)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.REP_RECEIVED })
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `stale scan and connect timeout cannot tear down a newer scan`() = runTest {
        val repository = PhantomBleRepository()
        try {
            val staleAttempt = async { repository.scanAndConnect(timeoutMs = 200L) }
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            val newerAttempt = async { repository.startScanning() }
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertTrue(staleAttempt.await().isFailure)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())

            advanceTimeBy(250L)
            runCurrent()
            assertTrue(newerAttempt.await().isSuccess)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            assertEquals(1, repository.scannedDevices.value.size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct scan cancellation restores disconnected state`() = runTest {
        val repository = PhantomBleRepository()
        try {
            val attempt = launch { repository.startScanning() }
            runCurrent()
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            attempt.cancelAndJoin()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct connect cancellation restores disconnected state`() = runTest {
        val repository = PhantomBleRepository()
        try {
            assertTrue(repository.startScanning().isSuccess)
            val device = repository.scannedDevices.value.single()
            val attempt = launch { repository.connect(device) }
            runCurrent()
            assertEquals(ConnectionState.Connecting, repository.connectionState.value)
            attempt.cancelAndJoin()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `cancelled stale scan and connect attempts cannot tear down newer owners`() = runTest {
        val repository = PhantomBleRepository()
        try {
            val oldScan = launch { repository.startScanning() }
            runCurrent()
            val newScan = launch { repository.startScanning() }
            runCurrent()
            oldScan.cancelAndJoin()
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            advanceTimeBy(150L)
            runCurrent()
            assertTrue(newScan.isCompleted)
            val device = repository.scannedDevices.value.single()

            val oldConnect = launch { repository.connect(device) }
            runCurrent()
            val newConnect = launch { repository.connect(device) }
            runCurrent()
            oldConnect.cancelAndJoin()
            assertEquals(ConnectionState.Connecting, repository.connectionState.value)
            advanceTimeBy(250L)
            runCurrent()
            assertTrue(newConnect.isCompleted)
            assertEquals(ConnectionState.Connected(device.name, device.address), repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `regular and echo factory packets decode actual program fields`() = runTest {
        val repository = PhantomBleRepository(initialConfig = PhantomBleConfig(repDelayMs = 100L))
        val regular = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 4,
            warmupReps = 3,
            weightPerCableKg = 17.25f,
        )
        val echo = WorkoutParameters(
            programMode = ProgramMode.Echo,
            reps = 8,
            warmupReps = 5,
            weightPerCableKg = 11f,
        )
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.sendWorkoutCommand(BlePacketFactory.createProgramParams(regular)).isSuccess)
                assertTrue(repository.startWorkout(regular).isSuccess)
                withContext(Dispatchers.Default) { delay(150L) }
                val regularRep = withTimeout(2_000L) { awaitItem() }
                assertEquals(3, regularRep.repsRomTotal)
                assertEquals(4, regularRep.repsSetTotal)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(repository.stopWorkout().isSuccess)

            repository.repEvents.test {
                assertTrue(
                    repository.sendWorkoutCommand(
                        BlePacketFactory.createEchoControl(
                            level = echo.echoLevel,
                            warmupReps = echo.warmupReps,
                            targetReps = echo.reps,
                        ),
                    ).isSuccess,
                )
                assertTrue(repository.startWorkout(echo).isSuccess)
                withContext(Dispatchers.Default) { delay(150L) }
                val echoRep = withTimeout(2_000L) { awaitItem() }
                assertEquals(5, echoRep.repsRomTotal)
                assertEquals(8, echoRep.repsSetTotal)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `disconnected workout command is rejected without mutating program`() = runTest {
        val repository = PhantomBleRepository(initialConfig = PhantomBleConfig(repDelayMs = 100L))
        val rejected = WorkoutParameters(ProgramMode.OldSchool, reps = 9, warmupReps = 3, weightPerCableKg = 99f)
        val actual = WorkoutParameters(ProgramMode.OldSchool, reps = 2, warmupReps = 1, weightPerCableKg = 8f)
        try {
            assertTrue(repository.sendWorkoutCommand(BlePacketFactory.createProgramParams(rejected)).isFailure)
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.startWorkout(actual).isSuccess)
                withContext(Dispatchers.Default) { delay(150L) }
                val rep = withTimeout(2_000L) { awaitItem() }
                assertEquals(1, rep.repsRomTotal)
                assertEquals(2, rep.repsSetTotal)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `completed fixed set cannot resume when active polling restarts`() = runTest {
        val repository = PhantomBleRepository(initialConfig = PhantomBleConfig(repDelayMs = 100L))
        val params = WorkoutParameters(ProgramMode.OldSchool, reps = 2, warmupReps = 0, weightPerCableKg = 8f)
        val observed = mutableListOf<Int>()
        var observer: Job? = null
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            observer = launch(Dispatchers.Unconfined) {
                repository.repEvents.collect { observed += it.repsSetCount }
            }
            runCurrent()
            assertTrue(repository.startWorkout(params).isSuccess)
            withContext(Dispatchers.Default) { delay(300L) }
            assertEquals(listOf(1, 2), observed)
            repository.startActiveWorkoutPolling()
            withContext(Dispatchers.Default) { delay(300L) }
            assertEquals(listOf(1, 2), observed)
        } finally {
            observer?.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `slow rep observer receives every emitted fixed-set event`() = runTest {
        val repository = PhantomBleRepository(initialConfig = PhantomBleConfig(repDelayMs = 100L))
        val params = WorkoutParameters(ProgramMode.OldSchool, reps = 24, warmupReps = 0, weightPerCableKg = 8f)
        val observed = mutableListOf<Int>()
        var observer: Job? = null
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            observer = launch(Dispatchers.Unconfined) {
                repository.repEvents.take(params.reps).collect { rep ->
                    observed += rep.repsSetCount
                    delay(400L)
                }
            }
            assertTrue(repository.startWorkout(params).isSuccess)
            withContext(Dispatchers.Default) { observer.join() }
            assertEquals((1..params.reps).toList(), observed)
        } finally {
            observer?.cancelAndJoin()
            repository.shutdown()
        }
    }

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
