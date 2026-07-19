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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PhantomBleRepositoryTest {
    @Test
    fun `caller cancellation from final scanned device publication aborts scan before final log`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var operation: Job? = null
        var operationResult: Result<Unit>? = null
        var cancellation: CancellationException? = null
        val observer = launch(dispatcher) {
            repository.scannedDevices.collect { devices ->
                if (devices.isNotEmpty()) operation?.cancel()
            }
        }
        operation = launch(dispatcher) {
            try {
                operationResult = repository.startScanning()
            } catch (error: CancellationException) {
                cancellation = error
                throw error
            }
        }
        try {
            advanceTimeBy(150L)
            runCurrent()
            operation?.join()
            advanceUntilIdle()

            assertTrue(cancellation != null)
            assertEquals(null, operationResult)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.DEVICE_FOUND })

            assertTrue(repository.startScanning().isSuccess)
        } finally {
            operation?.cancelAndJoin()
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `caller cancellation from final connected publication aborts connect before producers and logs`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var operation: Job? = null
        var operationResult: Result<Unit>? = null
        var cancellation: CancellationException? = null
        assertTrue(repository.startScanning().isSuccess)
        val device = repository.scannedDevices.value.single()
        logRepo.clearAll()
        val observer = launch(dispatcher) {
            repository.connectionState.collect { state ->
                if (state is ConnectionState.Connected) operation?.cancel()
            }
        }
        operation = launch(dispatcher) {
            try {
                operationResult = repository.connect(device)
            } catch (error: CancellationException) {
                cancellation = error
                throw error
            }
        }
        try {
            advanceTimeBy(250L)
            runCurrent()
            operation?.join()
            advanceUntilIdle()

            assertTrue(cancellation != null)
            assertEquals(null, operationResult)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.SERVICE_DISCOVERED })
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.CONNECT_SUCCESS })
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.HEARTBEAT })

            assertTrue(repository.startScanning().isSuccess)
        } finally {
            operation?.cancelAndJoin()
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `caller cancellation from final command log aborts command and cleans connection`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var operation: Job? = null
        var callerJob: Job? = null
        var operationResult: Result<Unit>? = null
        var cancellation: CancellationException? = null
        var cancellationRequested = false
        val scan = async(dispatcher) { repository.startScanning() }
        advanceTimeBy(150L)
        runCurrent()
        assertTrue(scan.await().isSuccess)
        val device = repository.scannedDevices.value.single()
        val connect = async(dispatcher) { repository.connect(device) }
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(connect.await().isSuccess)
        logRepo.clearAll()
        val observer = launch(dispatcher) {
            logRepo.logs.collect { logs ->
                if (logs.firstOrNull()?.eventType == LogEventType.COMMAND_SENT) {
                    cancellationRequested = true
                    callerJob?.cancel()
                }
            }
        }
        operation = launch(StandardTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
            callerJob = currentCoroutineContext()[Job]
            try {
                operationResult = repository.sendInitSequence()
            } catch (error: CancellationException) {
                cancellation = error
                throw error
            }
        }
        operation?.start()
        try {
            runCurrent()
            operation?.join()
            advanceTimeBy(2_000L)
            runCurrent()

            assertTrue(cancellationRequested)
            assertTrue(cancellation != null)
            assertEquals(null, operationResult)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertEquals(1, logRepo.logs.value.count { it.eventType == LogEventType.COMMAND_SENT })
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.SERVICE_DISCOVERED })
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.CONNECT_SUCCESS })
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.HEARTBEAT })

            assertTrue(repository.startScanning().isSuccess)
        } finally {
            operation?.cancelAndJoin()
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `disconnect reentered by disconnected state cannot republish or leave cleanup open`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var reentered = false
        val observer = launch(dispatcher) {
            repository.connectionState.collect { state ->
                if (!reentered && state == ConnectionState.Disconnected) {
                    reentered = true
                    repository.disconnect()
                    assertTrue(repository.startScanning().isFailure)
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.disconnect()
            assertTrue(reentered)
            assertTrue(repository.startScanning().isSuccess)
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `final command log reentered shutdown invalidates command result`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var shutdownTriggered = false
        val observer = launch(dispatcher) {
            logRepo.logs.collect { logs ->
                if (!shutdownTriggered && logs.firstOrNull()?.eventType == LogEventType.COMMAND_SENT) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.sendInitSequence().isFailure)
            assertTrue(shutdownTriggered)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `timeout reconnection publication cannot reenter a new scan before cleanup completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        val scanAttempt = CompletableDeferred<Result<Unit>>()
        val reconnectionObserver = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.reconnectionRequested.collect {
                scanAttempt.complete(repository.startScanning())
            }
        }
        try {
            val result = async { repository.scanAndConnect(timeoutMs = 100L) }
            runCurrent()
            advanceTimeBy(100L)
            advanceUntilIdle()
            assertTrue(result.await().isFailure)
            assertTrue(scanAttempt.await().isFailure)
            assertTrue(repository.startScanning().isSuccess)
        } finally {
            reconnectionObserver.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `disconnect log reentry is serialized by the cleanup owner`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var reentered = false
        val observer = launch(dispatcher) {
            logRepo.logs.collect { logs ->
                if (!reentered && logs.firstOrNull()?.eventType == LogEventType.DISCONNECT) {
                    reentered = true
                    repository.disconnect()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.disconnect()
            assertTrue(reentered)
            assertTrue(repository.startScanning().isSuccess)
            assertEquals(1, logRepo.logs.value.count { it.eventType == LogEventType.DISCONNECT })
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown during normal cleanup upgrades cleanup owner to terminal`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
        var connected = false
        var shutdownTriggered = false
        val observer = launch(dispatcher) {
            repository.connectionState.collect { state ->
                if (state is ConnectionState.Connected) connected = true
                if (connected && !shutdownTriggered && state == ConnectionState.Disconnected) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.disconnect()
            assertTrue(shutdownTriggered)
            assertTrue(repository.startScanning().isFailure)
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `scan and connect phases share one owner while newer scan invalidates stale connect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
        try {
            val first = async { repository.scanAndConnect(timeoutMs = 1_000L) }
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            val second = async { repository.startScanning() }
            runCurrent()
            advanceUntilIdle()
            assertTrue(first.await().isFailure)
            assertTrue(second.await().isSuccess)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            assertEquals(1, repository.scannedDevices.value.size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stale timeout cannot clean up newer operation owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
        try {
            val stale = async { repository.scanAndConnect(timeoutMs = 200L) }
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            val newer = async { repository.startScanning() }
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()
            assertTrue(stale.await().isFailure)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(newer.await().isSuccess)
            assertEquals(1, repository.scannedDevices.value.size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct cancellation only cleans the attempt that owns it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
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
            assertEquals(1, repository.scannedDevices.value.size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown during initial metric publication prevents connect success`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
        var shutdownTriggered = false
        val observer = launch(dispatcher) {
            repository.metricsFlow.collect {
                if (!shutdownTriggered) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isFailure)
            assertTrue(shutdownTriggered)
            assertTrue(repository.startScanning().isFailure)
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown during initial heuristic publication prevents connect success`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
        var shutdownTriggered = false
        val observer = launch(dispatcher) {
            repository.heuristicData.collect { value ->
                if (value != null && !shutdownTriggered) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isFailure)
            assertTrue(shutdownTriggered)
            assertTrue(repository.startScanning().isFailure)
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown during initial heartbeat publication prevents connect success`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo = logRepo, dispatcher = dispatcher)
        var shutdownTriggered = false
        val observer = launch(dispatcher) {
            logRepo.logs.collect { logs ->
                if (!shutdownTriggered && logs.firstOrNull()?.eventType == LogEventType.HEARTBEAT) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isFailure)
            assertTrue(shutdownTriggered)
            assertTrue(repository.startScanning().isFailure)
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown during initial rep publication prevents rep log continuation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo = logRepo,
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
            dispatcher = dispatcher,
        )
        val params = WorkoutParameters(ProgramMode.OldSchool, reps = 3, warmupReps = 0, weightPerCableKg = 8f)
        var shutdownTriggered = false
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
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
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(shutdownTriggered)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(logRepo.logs.value.none { it.eventType == LogEventType.REP_RECEIVED && it.level == LogLevel.INFO.name })
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `rep with no subscriber records an observable delivery loss warning`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo = logRepo,
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
            dispatcher = dispatcher,
        )
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.startWorkout(WorkoutParameters(ProgramMode.OldSchool, reps = 1, warmupReps = 0, weightPerCableKg = 8f)).isSuccess)
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(
                logRepo.logs.value.any {
                    it.eventType == LogEventType.REP_RECEIVED &&
                        it.level == LogLevel.WARNING.name &&
                        it.details?.contains("no_subscriber") == true
                },
            )
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `slow subscriber overflow records bounded rep delivery loss`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo = logRepo,
            initialConfig = PhantomBleConfig(repDelayMs = 100L),
            dispatcher = dispatcher,
        )
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.repEvents.collect {
                delay(1_000L)
            }
        }
        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.startWorkout(WorkoutParameters(ProgramMode.OldSchool, reps = 100, warmupReps = 0, weightPerCableKg = 8f)).isSuccess)
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(
                logRepo.logs.value.any {
                    it.eventType == LogEventType.REP_RECEIVED &&
                        it.level == LogLevel.WARNING.name &&
                        it.details?.contains("overflow") == true
                },
            )
        } finally {
            observer.cancelAndJoin()
            repository.shutdown()
        }
    }

    @Test
    fun `direct connect cancellation only cleans the attempt that owns it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PhantomBleRepository(dispatcher = dispatcher)
        try {
            val scan = async { repository.startScanning() }
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertTrue(scan.await().isSuccess)
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
