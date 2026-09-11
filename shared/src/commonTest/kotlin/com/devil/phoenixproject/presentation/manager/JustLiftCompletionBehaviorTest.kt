package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral coverage for PR #768. Starts use polling-gated handle stimuli;
 * completion is injected at the engine boundary, not via a simulated ROM/rep stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JustLiftCompletionBehaviorTest {
    private suspend fun TestScope.prepare(harness: DWSMTestHarness, summarySeconds: Int) {
        harness.setActiveProfilePreferences(UserPreferences(autoStartCountdownSeconds = 2))
        harness.setActiveSummaryCountdownSeconds(summarySeconds)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(TestFixtures.justLiftParams.copy(justLiftRestSeconds = 30))
        harness.activeSessionEngine.prepareForJustLift()
        runCurrent()
        assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
    }

    private fun TestScope.grabToStart(harness: DWSMTestHarness) {
        // StateFlow needs a distinct edge for each grab; do not call startWorkout here.
        assertTrue(harness.fakeBleRepo.emitPolledHandleState(HandleState.WaitingForRest))
        runCurrent()
        assertTrue(harness.fakeBleRepo.emitPolledHandleState(HandleState.Grabbed))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        assertTrue(harness.activeSessionEngine.currentExecutionLeaseForTest().isJustLift)
    }

    private fun TestScope.completeSet(harness: DWSMTestHarness) {
        harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3, isWarmupComplete = true)
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        runCurrent()
    }

    @Test
    fun `three consecutive handle started sets each reset and rearm exactly once`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepare(harness, summarySeconds = -1)
            val executionIds = mutableSetOf<Long>()
            repeat(3) { index ->
                grabToStart(harness)
                assertTrue(executionIds.add(harness.activeSessionEngine.currentExecutionLeaseForTest().executionId))
                completeSet(harness)
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
                assertEquals(30, harness.coordinator.justLiftRestCountdown.value)
                assertEquals(index + 1, harness.fakeBleRepo.stopWorkoutCallCount)
                assertEquals(index + 1, harness.fakeBleRepo.restartPollingCallCount)
                assertTrue(harness.fakeBleRepo.monitorPollingActive)
                assertEquals(0, harness.fakeBleRepo.disconnectCallCount)
                assertEquals(0, harness.fakeBleRepo.reconnectCallCount)
            }
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `timed summary expiry resets presentation without another physical teardown`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepare(harness, summarySeconds = 5)
            grabToStart(harness)
            completeSet(harness)
            assertEquals(3, assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value).repCount)
            val pollingStops = harness.fakeBleRepo.stopPollingCallCount
            advanceTimeBy(5_000)
            runCurrent()
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(30, harness.coordinator.justLiftRestCountdown.value)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(1, harness.fakeBleRepo.restartPollingCallCount)
            assertEquals(pollingStops, harness.fakeBleRepo.stopPollingCallCount)
            grabToStart(harness)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `unlimited summary remains until handles start a successor`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepare(harness, summarySeconds = 0)
            grabToStart(harness)
            val first = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completeSet(harness)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value).repCount)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            grabToStart(harness)
            assertNotEquals(first, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `handle grab during timed summary protects successor from expired summary work`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepare(harness, summarySeconds = 5)
            grabToStart(harness)
            completeSet(harness)
            assertEquals(3, assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value).repCount)
            grabToStart(harness)
            val successor = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val pollingStops = harness.fakeBleRepo.stopPollingCallCount
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(successor, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(pollingStops, harness.fakeBleRepo.stopPollingCallCount)
            completeSet(harness)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(2, harness.fakeBleRepo.restartPollingCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `failed completion teardown blocks start until successful recovery then permits successor`() = runTest {
        val harness = DWSMTestHarness(this)
        val release = CompletableDeferred<Result<Unit>>()
        try {
            prepare(harness, summarySeconds = -1)
            grabToStart(harness)
            val source = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCount = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { release.await() }
            completeSet(harness)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(0, harness.fakeBleRepo.restartPollingCallCount)
            release.complete(Result.failure(IllegalStateException("test reset failure")))
            runCurrent()
            harness.fakeBleRepo.setHandleState(HandleState.WaitingForRest)
            runCurrent()
            harness.fakeBleRepo.setHandleState(HandleState.Grabbed)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(source, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(0, harness.fakeBleRepo.restartPollingCallCount)
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.Idle)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            assertFalse(harness.fakeBleRepo.monitorPollingActive)

            harness.fakeBleRepo.stopWorkoutBlock = { Result.success(Unit) }
            harness.dwsm.retryMachineTeardown()
            runCurrent()
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(1, harness.fakeBleRepo.restartPollingCallCount)
            grabToStart(harness)
            assertNotEquals(source, harness.activeSessionEngine.currentExecutionLeaseForTest())
        } finally {
            release.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `screen preparation during live Just Lift preserves execution reps and command ownership`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepare(harness, summarySeconds = 5)
            grabToStart(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val reps = RepCount(workingReps = 2, totalReps = 2, isWarmupComplete = true)
            harness.coordinator._repCount.value = reps
            val commands = harness.fakeBleRepo.commandsReceived.size
            // Lifecycle boundary only: actual Android recreation is covered by the
            // separate emulator evidence, not claimed by this host test.
            harness.activeSessionEngine.prepareForJustLift()
            runCurrent()
            assertEquals(lease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(reps, harness.coordinator.repCount.value)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(commands, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(0, harness.fakeBleRepo.stopWorkoutCallCount)
            assertTrue(harness.fakeBleRepo.monitorPollingActive)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `invalidated completion teardown does not stop polling or publish summary`() = runTest {
        val harness = DWSMTestHarness(this)
        val release = CompletableDeferred<Result<Unit>>()
        try {
            prepare(harness, summarySeconds = 5)
            grabToStart(harness)
            harness.fakeBleRepo.stopWorkoutBlock = { release.await() }
            completeSet(harness)
            // Capture before invalidation: cancellation can execute the old finally
            // during runCurrent(), before the suspended stop is explicitly released.
            val pollingStops = harness.fakeBleRepo.stopPollingCallCount
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            release.complete(Result.success(Unit))
            runCurrent()
            assertEquals(pollingStops, harness.fakeBleRepo.stopPollingCallCount)
            assertEquals(0, harness.fakeBleRepo.restartPollingCallCount)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            release.complete(Result.success(Unit))
            harness.cleanup()
        }
    }
}
