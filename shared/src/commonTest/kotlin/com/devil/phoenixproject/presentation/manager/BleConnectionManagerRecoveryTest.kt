package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class BleConnectionManagerRecoveryTest {

    @Test
    fun `reconnect cancels old job before radio cleanup and waits for connected`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        val oldScanStarted = CompletableDeferred<Unit>()
        var connectedCalls = 0
        try {
            repository.scanAndConnectBlock = { attempt ->
                if (attempt == 1) {
                    repository.fake.simulateConnecting()
                    oldScanStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        repository.events += "old-job-cancelled"
                    }
                } else {
                    Result.success(Unit)
                }
            }
            harness.manager.ensureConnection(onConnected = {})
            runCurrent()
            oldScanStarted.await()
            repository.fake.simulateConnect("Vee_Test")

            harness.manager.reconnectForWorkoutRecovery(
                onConnected = {
                    repository.events += "connected-callback"
                    connectedCalls++
                },
                onFailed = {},
            )
            runCurrent()

            assertEquals(
                listOf(
                    "scan-and-connect-1",
                    "old-job-cancelled",
                    "stop-scanning",
                    "cancel-connection",
                    "disconnect",
                    "scan-and-connect-2",
                ),
                repository.events,
            )
            assertEquals(0, connectedCalls)

            repository.fake.simulateConnect("Vee_Test")
            runCurrent()

            assertEquals(1, connectedCalls)
            assertEquals("connected-callback", repository.events.last())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `repeated reconnect taps create one radio operation`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        val scanResult = CompletableDeferred<Result<Unit>>()
        var connectedCalls = 0
        try {
            repository.scanAndConnectBlock = { scanResult.await() }

            harness.manager.reconnectForWorkoutRecovery(
                onConnected = { connectedCalls++ },
                onFailed = {},
            )
            harness.manager.reconnectForWorkoutRecovery(
                onConnected = { connectedCalls++ },
                onFailed = {},
            )
            runCurrent()

            assertEquals(1, repository.disconnectCallCount)
            assertEquals(1, repository.scanAndConnectCallCount)

            repository.fake.simulateConnect("Vee_Test")
            scanResult.complete(Result.success(Unit))
            runCurrent()

            assertEquals(1, connectedCalls)
            assertEquals(1, repository.scanAndConnectCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reconnect failure reports error and invokes only failed callback`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        var connectedCalls = 0
        var failedCalls = 0
        try {
            repository.scanAndConnectBlock = {
                Result.failure(IllegalStateException("recovery connection failed"))
            }

            harness.manager.reconnectForWorkoutRecovery(
                onConnected = { connectedCalls++ },
                onFailed = { failedCalls++ },
            )
            runCurrent()

            assertEquals(0, connectedCalls)
            assertEquals(1, failedCalls)
            assertEquals("recovery connection failed", harness.manager.connectionError.value)
            assertEquals(1, repository.disconnectCallCount)
            assertEquals(1, repository.scanAndConnectCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reconnect cancels active ensure and clears its bookkeeping`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        val oldScanStarted = CompletableDeferred<Unit>()
        val recoveryScanResult = CompletableDeferred<Result<Unit>>()
        var staleConnectedCalls = 0
        try {
            repository.scanAndConnectBlock = { attempt ->
                if (attempt == 1) {
                    oldScanStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        repository.events += "old-job-cancelled"
                    }
                } else {
                    recoveryScanResult.await()
                }
            }
            harness.manager.ensureConnection(onConnected = { staleConnectedCalls++ })
            runCurrent()
            oldScanStarted.await()
            harness.bleErrors.tryEmit("stale connection error")
            runCurrent()
            assertEquals("stale connection error", harness.manager.connectionError.value)

            harness.manager.reconnectForWorkoutRecovery(
                onConnected = {},
                onFailed = {},
            )
            runCurrent()

            assertNull(harness.manager.connectionError.value)
            assertEquals(0, staleConnectedCalls)

            repository.fake.simulateConnect("Vee_Test")
            recoveryScanResult.complete(Result.success(Unit))
            runCurrent()
            assertEquals(0, staleConnectedCalls)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `new reconnect clears prior failure before scan and keeps it clear on success`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        val successfulScan = CompletableDeferred<Result<Unit>>()
        var connectedCalls = 0
        try {
            repository.scanAndConnectBlock = { attempt ->
                if (attempt == 1) {
                    Result.failure(IllegalStateException("first recovery failed"))
                } else {
                    successfulScan.await()
                }
            }

            harness.manager.reconnectForWorkoutRecovery(
                onConnected = { connectedCalls++ },
                onFailed = {},
            )
            runCurrent()
            assertEquals("first recovery failed", harness.manager.connectionError.value)

            harness.manager.reconnectForWorkoutRecovery(
                onConnected = { connectedCalls++ },
                onFailed = {},
            )
            runCurrent()

            assertNull(harness.manager.connectionError.value)

            repository.fake.simulateConnect("Vee_Test")
            successfulScan.complete(Result.success(Unit))
            runCurrent()

            assertEquals(1, connectedCalls)
            assertNull(harness.manager.connectionError.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cancelScanOrConnection cancels active ensureConnection and scanAndConnect`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        val scanStarted = CompletableDeferred<Unit>()
        var connectedCalls = 0
        var failedCalls = 0
        try {
            repository.scanAndConnectBlock = {
                repository.fake.simulateConnecting()
                scanStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    repository.events += "old-job-cancelled"
                }
            }

            harness.manager.ensureConnection(
                onConnected = { connectedCalls++ },
                onFailed = { failedCalls++ },
            )
            runCurrent()
            scanStarted.await()

            harness.manager.cancelScanOrConnection()
            runCurrent()

            assertTrue(repository.events.contains("old-job-cancelled"))
            assertTrue(repository.events.contains("stop-scanning"))
            assertEquals(0, connectedCalls)
            assertEquals(0, failedCalls)
            assertNull(harness.manager.connectionError.value)
        } finally {
            harness.cleanup()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelScanOrConnection followed immediately by ensureConnection completes teardown first and connects`() = runTest {
        val repository = RecoveryRecordingBleRepository()
        val harness = BleManagerRecoveryHarness(this, repository)
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val unwindGate = CompletableDeferred<Unit>()
        var secondConnected = 0
        var secondFailed = 0
        try {
            repository.scanAndConnectBlock = { attempt ->
                if (attempt == 1) {
                    repository.fake.simulateConnecting()
                    firstAttemptStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            unwindGate.await()
                            repository.events += "old-job-unwound"
                        }
                    }
                } else {
                    repository.fake.simulateConnect("Vee_Test")
                    Result.success(Unit)
                }
            }

            harness.manager.ensureConnection(onConnected = {}, onFailed = {})
            runCurrent()
            firstAttemptStarted.await()

            harness.manager.cancelScanOrConnection()
            harness.manager.ensureConnection(
                onConnected = { secondConnected++ },
                onFailed = { secondFailed++ },
            )
            runCurrent()

            // Second scanAndConnect must wait until the first attempt finishes unwinding and cancel teardown runs.
            assertEquals(1, repository.scanAndConnectCallCount)

            unwindGate.complete(Unit)
            runCurrent()
            advanceTimeBy(500L)
            runCurrent()

            assertEquals(2, repository.scanAndConnectCallCount)
            assertEquals(1, secondConnected)
            assertEquals(0, secondFailed)
            val secondScanIdx = repository.events.indexOf("scan-and-connect-2")
            assertTrue(repository.events.indexOf("old-job-unwound") in 0 until secondScanIdx)
            assertTrue(repository.events.indexOf("stop-scanning") in 0 until secondScanIdx)
            assertTrue(repository.events.indexOf("cancel-connection") in 0 until secondScanIdx)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `init collectors propagate CancellationException instead of swallowing it`() = runTest {
        val managerJob = Job(coroutineContext[Job])
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + managerJob)
        val fake = FakeBleRepository()
        val gate = CompletableDeferred<Unit>()
        val cancellingErrors = object : kotlinx.coroutines.flow.SharedFlow<String> by MutableSharedFlow() {
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<String>): Nothing {
                gate.await()
                throw CancellationException("bleErrorEvents cancelled")
            }
        }
        val cancellingRepo = object : BleRepository by fake {
            override val connectionState: StateFlow<com.devil.phoenixproject.domain.model.ConnectionState> =
                flow<com.devil.phoenixproject.domain.model.ConnectionState> {
                    emit(com.devil.phoenixproject.domain.model.ConnectionState.Disconnected)
                    gate.await()
                    throw CancellationException("connectionState cancelled")
                }.let { upstream ->
                    object : StateFlow<com.devil.phoenixproject.domain.model.ConnectionState> by MutableStateFlow(
                        com.devil.phoenixproject.domain.model.ConnectionState.Disconnected,
                    ) {
                        override suspend fun collect(
                            collector: kotlinx.coroutines.flow.FlowCollector<com.devil.phoenixproject.domain.model.ConnectionState>,
                        ): Nothing {
                            upstream.collect(collector)
                            awaitCancellation()
                        }
                    }
                }
            override val reconnectionRequested: Flow<com.devil.phoenixproject.data.repository.ReconnectionRequest> =
                flow {
                    gate.await()
                    throw CancellationException("reconnectionRequested cancelled")
                }
        }
        val preferences = FakePreferencesManager()
        val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest() }
        val settings = SettingsManager(preferences, profiles, managerScope)

        BleConnectionManager(
            bleRepository = cancellingRepo,
            settingsManager = settings,
            workoutStateProvider = InactiveWorkoutStateProvider,
            bleErrorEvents = cancellingErrors,
            scope = managerScope,
        )
        runCurrent()

        val children = managerJob.children.toList()
        val completionCauses = mutableListOf<Throwable?>()
        children.forEach { child ->
            child.invokeOnCompletion { cause -> completionCauses += cause }
        }

        gate.complete(Unit)
        runCurrent()

        assertTrue(
            completionCauses.count { it is CancellationException } >= 3,
            "Expected all 3 guarded init collectors to propagate CancellationException, got $completionCauses",
        )
        managerScope.cancel()
    }

    private class BleManagerRecoveryHarness(
        testScope: kotlinx.coroutines.test.TestScope,
        repository: BleRepository,
    ) {
        private val managerJob = Job(testScope.coroutineContext[Job])
        private val managerScope =
            CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + managerJob)
        private val preferences = FakePreferencesManager()
        private val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest() }
        private val settings = SettingsManager(preferences, profiles, managerScope)

        val bleErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val manager = BleConnectionManager(
            bleRepository = repository,
            settingsManager = settings,
            workoutStateProvider = InactiveWorkoutStateProvider,
            bleErrorEvents = bleErrors,
            scope = managerScope,
        )

        fun cleanup() {
            managerScope.cancel()
        }
    }

    private class RecoveryRecordingBleRepository(
        val fake: FakeBleRepository = FakeBleRepository(),
    ) : BleRepository by fake {
        val events = mutableListOf<String>()
        var scanAndConnectCallCount = 0
        var disconnectCallCount = 0
        var scanAndConnectBlock: suspend (attempt: Int) -> Result<Unit> = {
            fake.scanAndConnect(timeoutMs = 30_000L)
        }

        override suspend fun stopScanning() {
            events += "stop-scanning"
            fake.stopScanning()
        }

        override suspend fun cancelConnection() {
            events += "cancel-connection"
            fake.cancelConnection()
        }

        override suspend fun disconnect() {
            events += "disconnect"
            disconnectCallCount++
            fake.disconnect()
        }

        override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
            scanAndConnectCallCount++
            events += "scan-and-connect-$scanAndConnectCallCount"
            return scanAndConnectBlock(scanAndConnectCallCount)
        }
    }

    private data object InactiveWorkoutStateProvider : WorkoutStateProvider {
        override val isWorkoutActiveForConnectionAlert: Boolean = false
        override val isWorkoutMidSet: Boolean = false
    }
}
