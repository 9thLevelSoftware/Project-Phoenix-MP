package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for Plan 41-01: Routine Auto-Start & Timer Controls.
 *
 * Section A tests validate the manager-level prerequisites that the
 * RoutineOverviewScreen LaunchedEffect checks before auto-starting.
 *
 * Section B tests validate exercise timer pause/resume/reset via
 * ActiveSessionEngine methods (pure state manipulation, no BLE).
 *
 * Each test calls harness.cleanup() before exiting to prevent
 * UncompletedCoroutinesError from DWSM's long-running init collectors.
 */
class DWSMAutoStartAndTimerTest {

    // ===== A. Auto-Start Routine Prerequisites =====

    @Test
    fun autoStart_enterSetReadyWithAdjustments_setsSetReadyState() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(
            weightKg = 30f,
            repsPerSet = 10,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Simulate what the auto-start LaunchedEffect does:
        // enterSetReadyWithAdjustments(0, 0, firstWeight, firstReps)
        val firstExercise = routine.exercises[0]
        val initialWeight = firstExercise.setWeightsPerCableKg.firstOrNull() ?: firstExercise.weightPerCableKg
        val initialReps = firstExercise.setReps.firstOrNull() ?: 10
        harness.dwsm.enterSetReadyWithAdjustments(0, 0, initialWeight, initialReps)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(
            state,
            "enterSetReadyWithAdjustments should transition to SetReady",
        )
        assertEquals(0, state.exerciseIndex, "Should be at exercise 0")
        assertEquals(0, state.setIndex, "Should be at set 0")
        assertEquals(
            initialWeight,
            state.adjustedWeight,
            "SetReady weight should match first exercise weight",
        )
        assertEquals(
            initialReps,
            state.adjustedReps,
            "SetReady reps should match first exercise reps",
        )
        harness.cleanup()
    }

    @Test
    fun autoStart_emptyRoutine_noExercisesToStart() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // Create an empty routine (no exercises)
        val emptyRoutine = com.devil.phoenixproject.domain.model.Routine(
            id = "empty-routine",
            name = "Empty Routine",
            exercises = emptyList(),
        )

        harness.dwsm.loadRoutine(emptyRoutine)
        advanceUntilIdle()

        // The auto-start LaunchedEffect checks routine.exercises.isNotEmpty() —
        // with empty exercises, it stays on overview. Verify the routine loaded
        // but has no exercises to auto-start from.
        val loaded = harness.dwsm.coordinator.loadedRoutine.value
        assertTrue(
            loaded == null || loaded.exercises.isEmpty(),
            "Empty routine should have no exercises to auto-start",
        )
        harness.cleanup()
    }

    @Test
    fun autoStart_hasResumableProgress_returnsTrue_blocksAutoStart() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(setsPerExercise = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Simulate mid-routine progress: exercise 0, set 1 (not first set)
        harness.dwsm.enterSetReady(0, 1)

        // hasResumableProgress should return true — auto-start should be blocked
        assertTrue(
            harness.dwsm.hasResumableProgress(routine.id),
            "hasResumableProgress should return true when partially through a routine",
        )
        harness.cleanup()
    }

    @Test
    fun autoStart_noResumableProgress_returnsFalse_allowsAutoStart() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // No sets started yet — hasResumableProgress should return false
        assertFalse(
            harness.dwsm.hasResumableProgress(routine.id),
            "hasResumableProgress should return false with no progress",
        )
        harness.cleanup()
    }

    @Test
    fun autoStart_prefDisabled_autoStartRoutineDefaultsFalse() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // Default preferences should have autoStartRoutine = false
        val prefs = harness.fakePrefsManager.preferencesFlow.value
        assertFalse(
            prefs.autoStartRoutine,
            "autoStartRoutine should default to false (user must opt in)",
        )
        harness.cleanup()
    }

    // ===== B. Exercise Timer Controls =====

    @Test
    fun exerciseTimer_pauseSuspendsCountdown() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // Simulate an active timer by setting state directly on coordinator
        harness.coordinator.exerciseTimerOriginalDuration = 30
        harness.coordinator._timedExerciseRemainingSeconds.value = 25
        harness.coordinator._isExerciseTimerPaused.value = false

        // Pause
        harness.activeSessionEngine.pauseExerciseTimer()

        assertTrue(
            harness.coordinator.isExerciseTimerPaused.value,
            "isExerciseTimerPaused should be true after pause",
        )
        // Remaining seconds unchanged by pause itself
        assertEquals(
            25,
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Remaining seconds should not change on pause (only flag set)",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_resumeContinuesFromPausedPosition() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // Set up a paused timer
        harness.coordinator.exerciseTimerOriginalDuration = 30
        harness.coordinator._timedExerciseRemainingSeconds.value = 18
        harness.coordinator._isExerciseTimerPaused.value = true

        // Resume
        harness.activeSessionEngine.resumeExerciseTimer()

        assertFalse(
            harness.coordinator.isExerciseTimerPaused.value,
            "isExerciseTimerPaused should be false after resume",
        )
        assertEquals(
            18,
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Remaining seconds should be preserved at paused value after resume",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_resetReturnsToOriginalDuration() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // Set up a partially-elapsed timer
        harness.coordinator.exerciseTimerOriginalDuration = 30
        harness.coordinator._timedExerciseRemainingSeconds.value = 12
        harness.coordinator._isExerciseTimerPaused.value = true

        // Reset
        harness.activeSessionEngine.resetExerciseTimer()

        assertFalse(
            harness.coordinator.isExerciseTimerPaused.value,
            "Reset should unpause the timer",
        )
        assertEquals(
            30,
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Remaining seconds should be reset to original duration",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_pauseNoOpWhenTimerNull() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // No active timer (timedExerciseRemainingSeconds is null by default)
        assertNull(
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Timer should be null when no timed exercise is active",
        )

        // Pause should be a no-op
        harness.activeSessionEngine.pauseExerciseTimer()

        assertFalse(
            harness.coordinator.isExerciseTimerPaused.value,
            "Pause should be no-op when no timer is active",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_resumeNoOpWhenTimerNull() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // No active timer
        harness.activeSessionEngine.resumeExerciseTimer()

        assertFalse(
            harness.coordinator.isExerciseTimerPaused.value,
            "Resume should be no-op when no timer is active",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_resetNoOpWhenTimerNull() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        harness.coordinator.exerciseTimerOriginalDuration = 30
        // But timedExerciseRemainingSeconds is null → no active timer

        harness.activeSessionEngine.resetExerciseTimer()

        assertNull(
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Reset should be no-op when timedExerciseRemainingSeconds is null",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_resetNoOpWhenOriginalDurationZero() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        harness.coordinator.exerciseTimerOriginalDuration = 0
        harness.coordinator._timedExerciseRemainingSeconds.value = 5

        harness.activeSessionEngine.resetExerciseTimer()

        // Should be no-op because originalDuration is 0
        assertEquals(
            5,
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Reset should be no-op when original duration is 0",
        )
        harness.cleanup()
    }

    @Test
    fun exerciseTimer_pauseResumeResetSequence() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle() // Let init block settle

        // Set up timer
        harness.coordinator.exerciseTimerOriginalDuration = 60
        harness.coordinator._timedExerciseRemainingSeconds.value = 60

        // Pause
        harness.activeSessionEngine.pauseExerciseTimer()
        assertTrue(harness.coordinator.isExerciseTimerPaused.value)
        assertEquals(60, harness.coordinator.timedExerciseRemainingSeconds.value)

        // Simulate time passing while paused (remaining won't change since loop isn't running)
        // Manually set remaining to simulate partial countdown before pause
        harness.coordinator._timedExerciseRemainingSeconds.value = 40

        // Resume from 40
        harness.activeSessionEngine.resumeExerciseTimer()
        assertFalse(harness.coordinator.isExerciseTimerPaused.value)
        assertEquals(40, harness.coordinator.timedExerciseRemainingSeconds.value)

        // Simulate more countdown
        harness.coordinator._timedExerciseRemainingSeconds.value = 15

        // Reset back to 60
        harness.activeSessionEngine.resetExerciseTimer()
        assertFalse(harness.coordinator.isExerciseTimerPaused.value)
        assertEquals(
            60,
            harness.coordinator.timedExerciseRemainingSeconds.value,
            "Full pause→resume→reset cycle should end at original duration",
        )
        harness.cleanup()
    }
}
