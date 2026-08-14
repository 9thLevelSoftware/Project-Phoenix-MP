package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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

        val manager = BleConnectionManager(
            bleRepository = repository,
            settingsManager = settings,
            workoutStateProvider = InactiveWorkoutStateProvider,
            bleErrorEvents = MutableSharedFlow(),
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

        override fun onWorkoutConnectionLost() = Unit
    }
}
