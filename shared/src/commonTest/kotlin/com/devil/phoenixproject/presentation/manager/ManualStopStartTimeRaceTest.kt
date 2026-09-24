package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SessionTiming
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "1970" bug: the manual-stop save with no current lease runs in a launched
 * continuation, after a suspension. It read `coordinator.workoutStartTime` there, so a reset
 * that landed first (clearSharedStateForNewWorkout, a routine start) made it save
 * `timestamp = 0` and `duration = now - 0`, which the push sent as a 1970 workout lasting
 * ~56 years. The timing is now read at Stop and resolved through [SessionTiming].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualStopStartTimeRaceTest {
    private fun routine() = Routine(
        id = "race-routine",
        name = "Race",
        exercises = listOf(
            RoutineExercise(
                id = "rex-1",
                exercise = Exercise(
                    id = "ex-1",
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

    private suspend fun TestScope.startRoutineSet(harness: DWSMTestHarness) {
        harness.fakeUserProfileRepo.seedReadyProfileForTest(OWNER, name = "Owner")
        harness.fakeUserProfileRepo.emitReadyForTest(OWNER)
        advanceUntilIdle()
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutineAsync(routine())
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
    }

    /** Leaves the engine with no current lease, the state the legacy manual-stop save runs in. */
    private suspend fun TestScope.runASetThenEndTheWorkout(harness: DWSMTestHarness) {
        startRoutineSet(harness)
        harness.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        advanceUntilIdle()
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()
        assertTrue(
            runCatching { harness.activeSessionEngine.currentExecutionLeaseForTest() }.isFailure,
            "the legacy manual-stop save only runs without a current lease",
        )
        harness.coordinator.collectedMetrics.clear()
    }

    @Test
    fun aResetDuringTheInFlightManualStopSaveKeepsTheRealStartAndDuration() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            runASetThenEndTheWorkout(harness)
            val start = harness.nowMs - 10_000
            harness.coordinator._repCount.value = RepCount(workingReps = REPS, totalReps = REPS, isWarmupComplete = true)
            harness.coordinator.workoutStartTime = start
            harness.coordinator.warmupCompleteTimeMs = 0L

            harness.dwsm.stopWorkout()
            // The reset lands before the launched save continuation runs.
            harness.coordinator.workoutStartTime = 0L
            harness.coordinator.warmupCompleteTimeMs = 0L
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.allSessions().single { it.totalReps == REPS }
            assertEquals(start, saved.timestamp, "the start must be the one read at Stop, not the reset value")
            assertTrue(saved.duration in 10_000L..(harness.nowMs - start), "duration was ${saved.duration}")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun aManualStopWithRepsButNoStartSavesAPlausibleStartAndNoFabricatedDuration() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            runASetThenEndTheWorkout(harness)
            harness.coordinator._repCount.value = RepCount(workingReps = REPS, totalReps = REPS, isWarmupComplete = true)
            harness.coordinator.workoutStartTime = 0L
            harness.coordinator.warmupCompleteTimeMs = 0L

            harness.dwsm.stopWorkout()
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.allSessions().single { it.totalReps == REPS }
            assertTrue(SessionTiming.isPlausibleStartMs(saved.timestamp), "timestamp was ${saved.timestamp}")
            assertEquals(0L, saved.duration, "no real start means no duration, never now minus zero")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun aManualStopWithNoStartNoRepsAndNoSamplesSavesNothing() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            runASetThenEndTheWorkout(harness)
            val before = harness.fakeWorkoutRepo.allSessions().map { it.id }.toSet()
            harness.coordinator._repCount.value = RepCount()
            harness.coordinator.workoutStartTime = 0L
            harness.coordinator.warmupCompleteTimeMs = 0L

            harness.dwsm.stopWorkout()
            advanceUntilIdle()

            val after = harness.fakeWorkoutRepo.allSessions().map { it.id }.toSet()
            assertEquals(before, after, "a Stop with no running set must not write an empty 1970 session")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun aSetCompletedWithAZeroedStartSavesAPlausibleStartAndSaneDuration() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRoutineSet(harness)
            harness.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
            harness.coordinator.workoutStartTime = 0L
            harness.coordinator.warmupCompleteTimeMs = 0L

            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.allSessions().single { it.totalReps == 8 }
            assertTrue(SessionTiming.isPlausibleStartMs(saved.timestamp), "timestamp was ${saved.timestamp}")
            assertTrue(
                saved.duration in 0L..SessionTiming.MAX_SESSION_DURATION_MS,
                "duration was ${saved.duration}",
            )
        } finally {
            harness.cleanup()
        }
    }

    private companion object {
        const val OWNER = "owner"
        const val REPS = 7
    }
}
