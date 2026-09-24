package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
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

/**
 * Issue #869: Set Summary is published only after the completed set has been committed, and
 * waiting for that commit never delays the machine RESET.
 */
class Issue869SummaryAfterCommitTest {

    @Test
    fun `automatic completion shows Set Summary only after the set is committed`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val savedAtFirstSummary = recordSessionSavedAtFirstSummary(harness, lease.sessionId)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount, "RESET must not wait for the save")
            assertIs<MachineTeardownState.Ready>(harness.activeSessionEngine.machineTeardownState.value)
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.SetSummary)

            releaseSave.complete(Unit)
            runCurrent()

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertEquals(listOf(true), savedAtFirstSummary)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `manual Stop Set shows Set Summary only after the set is committed`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val savedAtFirstSummary = recordSessionSavedAtFirstSummary(harness, lease.sessionId)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = false)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount, "RESET must not wait for the save")
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.SetSummary)

            releaseSave.complete(Unit)
            runCurrent()

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertEquals(listOf(true), savedAtFirstSummary)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `a stuck commit delays Set Summary only for the bounded wait`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()
            advanceTimeBy(SUMMARY_COMMIT_WAIT_MS - 1)
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.SetSummary)

            advanceTimeBy(2)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            releaseSave.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
            assertNull(harness.coordinator.workoutSaveFailureSessionId.value)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `a failed commit still shows Set Summary with the save failure offer`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { throw IllegalStateException("disk full") }

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertEquals(lease.sessionId, harness.coordinator.workoutSaveFailureSessionId.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `manual Stop Set during the automatic completion's save still waits for that save`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val savedAtFirstSummary = recordSessionSavedAtFirstSummary(harness, lease.sessionId)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()
            harness.dwsm.stopWorkout(exitingWorkout = false)
            runCurrent()
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.SetSummary)

            releaseSave.complete(Unit)
            runCurrent()

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertTrue(savedAtFirstSummary.isNotEmpty())
            assertTrue(savedAtFirstSummary.all { it })
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    /** Records, for every SetSummary emission, whether [sessionId] had been saved by then. */
    private fun TestScope.recordSessionSavedAtFirstSummary(
        harness: DWSMTestHarness,
        sessionId: String,
    ): List<Boolean> {
        val saved = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            var previous: WorkoutState? = null
            harness.coordinator.workoutState.collect { state ->
                if (state is WorkoutState.SetSummary && previous !is WorkoutState.SetSummary) {
                    saved += harness.fakeWorkoutRepo.allSessions().any { it.id == sessionId }
                }
                previous = state
            }
        }
        return saved
    }

    private fun startTrackedCableSet(harness: DWSMTestHarness) {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 3,
                warmupReps = 0,
                weightPerCableKg = 25f,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 2)
        harness.fakeBleRepo.stopWorkoutCallCount = 0
    }
}
