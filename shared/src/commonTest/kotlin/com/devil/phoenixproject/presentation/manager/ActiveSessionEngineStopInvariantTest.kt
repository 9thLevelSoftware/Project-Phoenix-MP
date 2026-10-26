package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real DWSM/engine/guard/safety composition; only transport and storage are fakes. */
class ActiveSessionEngineStopInvariantTest {
    @Test
    fun openEndedJustLiftManualStopCompletesOnceWithoutAmrapFlag() = runTest {
        verifyJustLiftCompletion(exitingWorkout = false)
    }

    @Test
    fun openEndedJustLiftEndWorkoutCompletesOnceWithoutAmrapFlag() = runTest {
        verifyJustLiftCompletion(exitingWorkout = true)
    }

    @Test
    fun openEndedJustLiftAutomaticCompletionUsesTheSameNullableTarget() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            val lease = startSet(harness)
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3, isWarmupComplete = true)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.CABLE_RELEASED)
            assertNull(assertNotNull(harness.activeSessionEngine.claimedCompletion(lease)).targetReps)
            advanceUntilIdle()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertTrue(harness.machineSafetyStore.rows.isEmpty())
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun finiteRepSetRetainsItsTargetAndPerCableWeight() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startSet(harness, isJustLift = false, reps = 8)
            harness.dwsm.stopWorkout()
            advanceUntilIdle()

            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            assertEquals(8, completion.targetReps)
            assertEquals(25f, completion.configuredStartWeightPerCableKg)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun malformedCompletionStillTearsDownOnceAndDoesNotInventASavedSet() = runTest {
        verifyMalformedCompletionTeardown(exitingWorkout = false)
    }

    @Test
    fun malformedCompletionOnExitStillTearsDownOnceAndDoesNotInventASavedSet() = runTest {
        verifyMalformedCompletionTeardown(exitingWorkout = true)
    }

    @Test
    fun malformedCompletionKeepsFailedResetRecoverableAndBlocksAnotherMachineStart() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startSet(harness, isJustLift = false, reps = 8)
            harness.coordinator._repCount.value = RepCount(workingReps = -1)
            harness.fakeBleRepo.stopWorkoutBlock = { Result.failure(IllegalStateException("RESET failed")) }

            assertFailsWith<IllegalArgumentException> { harness.dwsm.stopWorkout() }
            runCurrent()

            assertIs<MachineTeardownState.RecoveryRequired>(harness.activeSessionEngine.machineTeardownState.value)
            assertTrue(harness.machineSafetyStore.rows.isNotEmpty())
            val commandsBeforeRetry = harness.fakeBleRepo.commandsReceived.size
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            assertEquals(commandsBeforeRetry, harness.fakeBleRepo.commandsReceived.size)

            harness.fakeBleRepo.stopWorkoutBlock = { Result.success(Unit) }
            harness.activeSessionEngine.retryMachineTeardown()
            advanceUntilIdle()
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertTrue(harness.machineSafetyStore.rows.isEmpty())
            assertTrue(harness.fakeWorkoutRepo.saveSessionAttempts.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.verifyJustLiftCompletion(exitingWorkout: Boolean) {
        lateinit var harness: DWSMTestHarness
        var capturedCompletion: SetExecutionCompletion? = null
        harness = DWSMTestHarness(
            this,
            afterCompletionClaim = { _, _, _ ->
                capturedCompletion = harness.activeSessionEngine.claimedCompletion(
                    harness.activeSessionEngine.currentExecutionLeaseForTest(),
                )
            },
        )
        val reset = CompletableDeferred<Result<Unit>>()
        try {
            val lease = startSet(harness)
            harness.fakeBleRepo.stopWorkoutBlock = { reset.await() }

            harness.dwsm.stopWorkout(exitingWorkout)
            harness.dwsm.stopWorkout(exitingWorkout)
            runCurrent()

            // End Workout retires the live claim, so inspect the immutable value at claim time.
            val completion = assertNotNull(capturedCompletion)
            assertNull(completion.targetReps, "Zero is an open-ended command sentinel, not a completion target")
            assertTrue(completion.isJustLift)
            assertFalse(completion.isAmrap, "Just Lift must not require the separate AMRAP flag")
            assertEquals(SetEndReason.USER_STOPPED, completion.reason)
            assertEquals(lease, completion.lease)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.activeSessionEngine.machineTeardownState.value)

            reset.complete(Result.success(Unit))
            advanceUntilIdle()
            harness.dwsm.stopWorkout(exitingWorkout)
            runCurrent()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertTrue(harness.machineSafetyStore.rows.isEmpty())
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            if (exitingWorkout) {
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            } else {
                assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            }
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.verifyMalformedCompletionTeardown(exitingWorkout: Boolean) {
        val harness = DWSMTestHarness(this)
        val reset = CompletableDeferred<Result<Unit>>()
        try {
            val lease = startSet(harness, isJustLift = false, reps = 8)
            // Exercise another completion invariant after a real, valid machine start.
            // Keep the invalid value observable: do not clamp it or forge a completion.
            harness.coordinator._repCount.value = RepCount(workingReps = -1)
            harness.fakeBleRepo.stopWorkoutBlock = { reset.await() }

            assertFailsWith<IllegalArgumentException> { harness.dwsm.stopWorkout(exitingWorkout) }
            harness.dwsm.stopWorkout(exitingWorkout)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount, "Metadata rejection must not skip RESET")
            assertIs<MachineTeardownState.TearingDown>(harness.activeSessionEngine.machineTeardownState.value)
            assertNull(harness.activeSessionEngine.claimedCompletion(lease))
            assertTrue(harness.fakeWorkoutRepo.saveSessionAttempts.isEmpty())
            assertTrue(harness.machineSafetyStore.rows.isNotEmpty(), "Arming must remain until RESET succeeds")

            reset.complete(Result.success(Unit))
            advanceUntilIdle()
            harness.dwsm.stopWorkout(exitingWorkout)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertTrue(harness.machineSafetyStore.rows.isEmpty())
            assertTrue(harness.fakeWorkoutRepo.saveSessionAttempts.isEmpty())
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.SetSummary)
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.startSet(
        harness: DWSMTestHarness,
        isJustLift: Boolean = true,
        reps: Int = 0,
    ): ExecutionLease {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = reps,
                warmupReps = 0,
                weightPerCableKg = 25f,
                isJustLift = isJustLift,
                isAMRAP = false,
                useAutoStart = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = isJustLift)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        assertEquals(reps, lease.workingRepTarget)
        assertEquals(lease.executionId, harness.machineSafetyStore.rows.values.single().executionId)
        assertTrue(harness.fakeBleRepo.commandsReceived.isNotEmpty())
        return lease
    }
}
