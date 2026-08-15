package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkoutTeardownRecoveryTest {

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
