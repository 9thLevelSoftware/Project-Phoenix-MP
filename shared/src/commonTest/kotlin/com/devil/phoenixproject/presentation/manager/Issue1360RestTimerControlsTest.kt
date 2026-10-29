package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.createTestRoutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class Issue1360RestTimerControlsTest {
    @Test
    fun extendTutRestBeforeNextSetSurvivesTimerTicks() = runTest {
        verifyExtension(nextExercise = false)
    }

    @Test
    fun extendTutRestBeforeNextExerciseSurvivesTimerTicks() = runTest {
        verifyExtension(nextExercise = true)
    }

    @Test
    fun pauseAndResumeTutRestBeforeNextSetControlsCountdown() = runTest {
        verifyPauseAndResume(nextExercise = false)
    }

    @Test
    fun pauseAndResumeTutRestBeforeNextExerciseControlsCountdown() = runTest {
        verifyPauseAndResume(nextExercise = true)
    }

    private suspend fun TestScope.verifyExtension(nextExercise: Boolean) {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness, nextExercise)
            val before = runtime(harness)
            val remaining = resting(harness).restSecondsRemaining
            val starts = harness.fakeBleRepo.programCommands.size

            harness.dwsm.extendRestTime(30)
            runCurrent()

            val extended = runtime(harness)
            assertEquals(before.originalRestDurationSeconds + 30, extended.originalRestDurationSeconds)
            assertEquals(remaining + 30, resting(harness).restSecondsRemaining)
            assertEquals(extended.originalRestDurationSeconds, harness.coordinator.restOriginalDuration.value)
            assertEquals(extended, committedRuntime(harness, extended))
            assertEquals(
                extended.originalRestDurationSeconds,
                assertIs<RestTransitionPlan.NormalAdvance>(extended.restTransitionPlan).restDurationSeconds,
            )

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(remaining + 25, resting(harness).restSecondsRemaining)
            assertEquals(remaining + 25, harness.coordinator._restSecondsRemaining.value)
            assertEquals(extended, committedRuntime(harness, extended))
            assertEquals(starts, harness.fakeBleRepo.programCommands.size)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun TestScope.verifyPauseAndResume(nextExercise: Boolean) {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness, nextExercise)
            val remaining = resting(harness).restSecondsRemaining
            val starts = harness.fakeBleRepo.programCommands.size

            harness.dwsm.toggleRestPause()
            runCurrent()

            val paused = runtime(harness)
            assertTrue(paused.isRestPaused)
            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(remaining, paused.pausedRestRemainingSeconds)
            assertEquals(null, paused.restDeadlineEpochMs)
            assertEquals(paused, committedRuntime(harness, paused))

            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(remaining, resting(harness).restSecondsRemaining)
            assertEquals(remaining, harness.coordinator._restSecondsRemaining.value)

            harness.dwsm.toggleRestPause()
            runCurrent()

            val resumed = runtime(harness)
            assertFalse(resumed.isRestPaused)
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(null, resumed.pausedRestRemainingSeconds)
            assertEquals(harness.nowMs + remaining * 1_000L, resumed.restDeadlineEpochMs)
            assertEquals(resumed, committedRuntime(harness, resumed))

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(remaining - 5, resting(harness).restSecondsRemaining)
            assertEquals(remaining - 5, harness.coordinator._restSecondsRemaining.value)
            assertEquals(starts, harness.fakeBleRepo.programCommands.size)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun TestScope.prepareTutRest(harness: DWSMTestHarness, nextExercise: Boolean) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveSummaryCountdownSeconds(-1)
        val source = createTestRoutine(
            exerciseCount = if (nextExercise) 2 else 1,
            setsPerExercise = if (nextExercise) 1 else 2,
        )
        val routine = source.copy(
            exercises = source.exercises.map { exercise ->
                exercise.copy(
                    programMode = ProgramMode.TUT,
                    setRestSeconds = List(exercise.setReps.size) { 60 },
                )
            },
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
        assertEquals(ProgramMode.TUT, harness.coordinator.workoutParameters.value.programMode)

        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
        advanceTimeBy(1_000)
        runCurrent()

        val rest = resting(harness)
        assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
        assertEquals(routine.exercises[if (nextExercise) 1 else 0].exercise.displayName, rest.nextExerciseName)
        assertTrue(rest.restSecondsRemaining > 0)
        assertTrue(harness.coordinator.restTimerJob?.isActive == true)
    }

    private fun resting(harness: DWSMTestHarness): WorkoutState.Resting = assertIs(harness.coordinator.workoutState.value)

    private fun runtime(harness: DWSMTestHarness): ActiveWorkoutRuntimeDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())

    private fun committedRuntime(
        harness: DWSMTestHarness,
        document: ActiveWorkoutRuntimeDocument,
    ): ActiveWorkoutRuntimeDocument? = harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
        document.profileId,
        document.routineSessionId,
    )
}
