package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkoutTeardownRecoveryTest {

    @Test
    fun `stale reset cannot discard a replacement teardown continuation`() = runTest {
        // Installs B while A's reset is mid-flight (no RESET teardown for A), which the #782
        // barrier would refuse; this continuation-ownership test opts out of it.
        val harness = DWSMTestHarness(this, machineSafetyBarrier = false)
        val resetB = CompletableDeferred<Result<Unit>>()
        var callbackB = 0
        var installReplacementDuringReset = false
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = {
                if (installReplacementDuringReset) {
                    installReplacementDuringReset = false
                    harness.startCableSet(targetReps = 5)
                    val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
                    assertTrue(leaseB.executionId != leaseA.executionId)
                    harness.fakeBleRepo.stopWorkoutBlock = { resetB.await() }
                    harness.activeSessionEngine.requestTeardownForTransition(leaseB, TeardownReason.STOP_SET) {
                        callbackB++
                    }
                    testScheduler.runCurrent()
                    assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(leaseB))
                }
            }

            installReplacementDuringReset = true
            harness.dwsm.resetForNewWorkout()
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(leaseB.executionId != leaseA.executionId)
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(leaseB))

            resetB.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(1, callbackB)
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
            assertFalse(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(leaseB))
        } finally {
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = null
            if (!resetB.isCompleted) resetB.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `reset discards the exact pending teardown continuation`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetBarrier = CompletableDeferred<Result<Unit>>()
        var callbackCount = 0
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { resetBarrier.await() }
            harness.activeSessionEngine.requestTeardownForTransition(lease, TeardownReason.STOP_SET) {
                callbackCount++
            }
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(lease))

            harness.dwsm.resetForNewWorkout()

            assertFalse(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(lease))
            resetBarrier.complete(Result.success(Unit))
            advanceUntilIdle()
            assertEquals(0, callbackCount)
        } finally {
            if (!resetBarrier.isCompleted) resetBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `cleanup discards the exact pending teardown continuation`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { resetBarrier.await() }
            harness.activeSessionEngine.requestTeardownForTransition(lease, TeardownReason.STOP_SET) {}
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(lease))

            harness.dwsm.cleanup()

            assertFalse(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(lease))
        } finally {
            if (!resetBarrier.isCompleted) resetBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `stale teardown disposal cannot clear a replacement continuation`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetA = CompletableDeferred<Result<Unit>>()
        val resetB = CompletableDeferred<Result<Unit>>()
        var callbackB = 0
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { resetA.await() }
            harness.activeSessionEngine.requestTeardownForTransition(leaseA, TeardownReason.STOP_SET) {}
            runCurrent()
            harness.dwsm.resetForNewWorkout()
            resetA.complete(Result.success(Unit))
            advanceUntilIdle()

            harness.startCableSet(targetReps = 3)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { resetB.await() }
            harness.activeSessionEngine.requestTeardownForTransition(leaseB, TeardownReason.STOP_SET) {
                callbackB++
            }
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(leaseB))

            harness.activeSessionEngine.discardTeardownReadyContinuationForTest(leaseA)

            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(leaseB))
            resetB.complete(Result.success(Unit))
            advanceUntilIdle()
            assertEquals(1, callbackB)
            assertFalse(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(leaseB))
        } finally {
            if (!resetA.isCompleted) resetA.complete(Result.success(Unit))
            if (!resetB.isCompleted) resetB.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `retry sends one reset and reaches ready only after reset succeeds`() = runTest {
        val harness = DWSMTestHarness(this)
        val retryReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.stopWorkoutBlock = { retryReset.await() }

            harness.dwsm.retryMachineTeardown()
            runCurrent()

            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            val tearingDown =
                assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(2, tearingDown.attempt)

            retryReset.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry reset failure remains recovery required`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("retry RESET failed"))
            }

            harness.dwsm.retryMachineTeardown()
            advanceUntilIdle()

            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry reset timeout remains recovery required`() = runTest {
        val harness = DWSMTestHarness(this)
        val neverCompletes = CompletableDeferred<Result<Unit>>()
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.stopWorkoutBlock = { neverCompletes.await() }

            harness.dwsm.retryMachineTeardown()
            runCurrent()
            advanceTimeBy(BleConstants.GATT_OPERATION_TIMEOUT_MS)
            runCurrent()

            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry while teardown is in flight is idempotently ignored`() = runTest {
        val harness = DWSMTestHarness(this)
        val retryReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.stopWorkoutBlock = { retryReset.await() }

            harness.dwsm.retryMachineTeardown()
            harness.dwsm.retryMachineTeardown()
            runCurrent()

            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            retryReset.complete(Result.success(Unit))
            advanceUntilIdle()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry while disconnected keeps recovery closed and sends no reset`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.stopWorkoutBlock = { Result.success(Unit) }

            harness.dwsm.retryMachineTeardown()
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reconnect does not clear recovery until post-connect reset succeeds`() = runTest {
        val harness = DWSMTestHarness(this)
        val postConnectReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.stopWorkoutBlock = { postConnectReset.await() }

            harness.bleConnectionManager.reconnectForWorkoutRecovery(
                onConnected = harness.dwsm::retryMachineTeardown,
                onFailed = {},
            )
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            postConnectReset.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `public reconnect ignores a second tap while post-connect reset is in flight`() = runTest {
        val harness = DWSMTestHarness(this)
        val postConnectReset = CompletableDeferred<Result<Unit>>()
        val postConnectResetStarted = CompletableDeferred<Unit>()
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.stopWorkoutBlock = {
                postConnectResetStarted.complete(Unit)
                postConnectReset.await()
            }

            harness.dwsm.reconnectWorkoutTeardown(harness.bleConnectionManager)
            runCurrent()
            postConnectResetStarted.await()

            assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            harness.dwsm.reconnectWorkoutTeardown(harness.bleConnectionManager)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)

            postConnectReset.complete(Result.success(Unit))
            advanceUntilIdle()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reconnect failure leaves recovery required`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.forceResetFailureThenRecoveryRequired()
            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.shouldFailConnect = true

            harness.bleConnectionManager.reconnectForWorkoutRecovery(
                onConnected = harness.dwsm::retryMachineTeardown,
                onFailed = {},
            )
            advanceUntilIdle()

            assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    // ===== Issue #1391 (packet B1): teardown observation + start-path invariants =====

    private fun remoteRoutine() = Routine(
        id = "teardown-remote-routine",
        name = "Teardown Remote",
        exercises = listOf(
            RoutineExercise(
                id = "teardown-rex-1",
                exercise = Exercise(
                    id = "teardown-ex-1",
                    name = "Cable Row",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                setReps = listOf(8, 8),
                weightPerCableKg = 40f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )

    private suspend fun TestScope.startRemoteRoutineSet(harness: DWSMTestHarness, summarySeconds: Int = 0) {
        harness.setActiveSummaryCountdownSeconds(summarySeconds)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutineAsync(remoteRoutine())
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(
            workingReps = 3,
            totalReps = 3,
            isWarmupComplete = true,
        )
    }

    @Test
    fun `the remote operation observes teardown Ready before preparing the successor`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetGate = CompletableDeferred<Result<Unit>>()
        var receipt: RemoteSetControlReceipt? = null
        try {
            startRemoteRoutineSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val identity = executionIdentityOf(lease)
            harness.fakeBleRepo.stopWorkoutBlock = { resetGate.await() }

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                receipt = harness.dwsm.endCurrentSetAndPrepareNext(identity)
            }
            runCurrent()

            // Teardown initiated independently of the save, still in flight: the
            // operation must not have prepared anything yet.
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertNull(receipt, "the receipt must not be produced before teardown settles")
            assertEquals(0, harness.coordinator._currentSetIndex.value, "no successor preparation before Ready")

            resetGate.complete(Result.success(Unit))
            advanceUntilIdle()

            val settled = assertNotNullReceipt(receipt)
            assertEquals(RemoteTeardownState.READY, settled.teardownState)
            assertEquals(RemoteSetControlTerminal.PREPARED, settled.terminal)
            assertEquals(RemotePersistenceOutcome.SUCCEEDED, settled.persistence)
            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator._routineFlowState.value)
            assertEquals(1, ready.setIndex)
        } finally {
            if (!resetGate.isCompleted) resetGate.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `a failed teardown reports FAILED and never fabricates a prepared successor`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRemoteRoutineSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("initial RESET failed"))
            }

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            assertEquals(RemoteSetControlTerminal.FAILED, receipt.terminal)
            assertEquals(RemoteTeardownState.PENDING, receipt.teardownState)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            assertFalse(harness.coordinator._routineFlowState.value is RoutineFlowState.SetReady)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `an identity mismatch returns SUPERSEDED without mutating anything`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRemoteRoutineSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val identity = executionIdentityOf(lease)
            val stopsBefore = harness.fakeBleRepo.stopWorkoutCallCount

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext("999:stale-identity")

            assertEquals(RemoteSetControlTerminal.SUPERSEDED, receipt.terminal)
            assertEquals(RemoteCompletionDecision.NO_COMPLETION, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.NOT_REQUIRED, receipt.persistence)
            assertEquals(identity, receipt.observedExecutionIdentity, "the observed identity is reported honestly")
            // Without mutating: the set is still running, nothing saved, no RESET.
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(3, harness.coordinator._repCount.value.workingReps)
            assertTrue(harness.fakeWorkoutRepo.allSessions().isEmpty())
            assertEquals(stopsBefore, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(lease, harness.activeSessionEngine.currentExecutionLeaseForTest())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `the prepared successor starts through the routine start path exactly like the phone START`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRemoteRoutineSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)

            // The one routine start path — RoutineFlowManager.startSetFromReady(),
            // whose final step is startWorkout(skipCountdown = true) (the phone START).
            harness.routineFlowManager.startSetFromReady()
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(1, harness.coordinator._currentSetIndex.value, "the prepared set is the one that starts")
            val successor = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(successor.executionId != lease.executionId, "the successor is a fresh execution")
            assertTrue(
                harness.fakeBleRepo.commandsReceived.size > commandsBefore,
                "the started set must command the machine, exactly like the phone START",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `the prepared successor is never auto-started even with autoplay enabled`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // summaryCountdownSeconds > 0 => autoplay enabled; a timed summary also
            // exercises the summary auto-advance suppression inside the operation.
            startRemoteRoutineSet(harness, summarySeconds = 5)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)

            advanceTimeBy(60_000)
            runCurrent()

            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator._routineFlowState.value)
            assertEquals(1, ready.setIndex, "the prepared successor must be untouched")
            assertEquals(
                commandsBefore,
                harness.fakeBleRepo.commandsReceived.size,
                "no auto-start may command the machine after the operation",
            )
            val leaseAfter = harness.activeSessionEngine.currentExecutionLeaseOrNull()
            assertTrue(
                leaseAfter == null || leaseAfter.executionId == lease.executionId,
                "no successor execution may be started automatically",
            )
        } finally {
            harness.cleanup()
        }
    }

    private fun assertNotNullReceipt(receipt: RemoteSetControlReceipt?): RemoteSetControlReceipt {
        assertTrue(receipt != null, "the operation must produce a receipt after teardown settles")
        return receipt
    }

    private fun DWSMTestHarness.forceResetFailureThenRecoveryRequired() {
        fakeBleRepo.simulateConnect("Vee_Test")
        startCableSet(targetReps = 3)
        fakeBleRepo.stopWorkoutBlock = {
            Result.failure(IllegalStateException("initial RESET failed"))
        }
        dwsm.stopAndReturnToSetReady()
        testScope.testScheduler.advanceUntilIdle()
        assertIs<MachineTeardownState.RecoveryRequired>(dwsm.machineTeardownState.value)
    }
}
