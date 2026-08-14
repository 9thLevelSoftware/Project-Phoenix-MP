package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkoutTeardownRecoveryTest {

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
