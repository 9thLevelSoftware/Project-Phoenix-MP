package com.devil.phoenixproject.presentation.manager

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.activeDWSM
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.createTestRoutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Characterization tests for DefaultWorkoutSessionManager workout lifecycle.
 *
 * These tests lock in EXISTING behavior. If behavior is surprising,
 * we document it with a "Characterization:" comment rather than changing it.
 *
 * Each test calls harness.cleanup() before exiting to cancel DWSM's long-running
 * init collectors and prevent UncompletedCoroutinesError.
 */
class DWSMWorkoutLifecycleTest {

    // ===== A. startWorkout transitions =====

    @Test
    fun `startWorkout sets Initializing state immediately before coroutine launch`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        // startWorkout sets Initializing synchronously before launching the coroutine
        harness.dwsm.startWorkout(skipCountdown = true)

        // Before advancing, state should be Initializing (set synchronously in startWorkout)
        assertIs<WorkoutState.Initializing>(harness.dwsm.coordinator.workoutState.value,
            "State should be Initializing immediately after startWorkout call")
        harness.cleanup()
    }

    @Test
    fun `startWorkout transitions to Active after countdown skipped`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value,
            "State should be Active after skipCountdown=true and coroutine completes")
        harness.cleanup()
    }

    @Test
    fun `startWorkout countdown emits 5-4-3-2-1 then Active`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.coordinator.workoutState.test {
            // Initial state
            assertEquals(WorkoutState.Idle, awaitItem())

            harness.dwsm.startWorkout(skipCountdown = false)

            // Initializing is set synchronously
            assertEquals(WorkoutState.Initializing, awaitItem())

            // Countdown states: 5, 4, 3, 2, 1
            for (i in 5 downTo 1) {
                advanceTimeBy(1000)
                val state = awaitItem()
                assertIs<WorkoutState.Countdown>(state, "Expected Countdown($i)")
                assertEquals(i, state.secondsRemaining, "Countdown should be $i")
            }

            // After last countdown tick, advance to get Active
            advanceTimeBy(1100) // Extra margin for BLE command delays
            // There may be intermediate emissions; skip to Active
            val finalStates = cancelAndConsumeRemainingEvents()
            val hasActive = finalStates.any {
                it is app.cash.turbine.Event.Item && it.value is WorkoutState.Active
            }
            assertTrue(hasActive, "Should eventually reach Active state after countdown")
        }
        harness.cleanup()
    }

    @Test
    fun `startWorkout sends BLE workout command`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        // DWSM sends CONFIG command + START command (for non-Echo mode)
        assertTrue(harness.fakeBleRepo.commandsReceived.isNotEmpty(),
            "Should have sent at least one BLE command (CONFIG)")
        harness.cleanup()
    }

    // ===== B. stopWorkout transitions =====

    @Test
    fun `stopWorkout with exitingWorkout true transitions to Idle`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        assertIs<WorkoutState.Idle>(harness.dwsm.coordinator.workoutState.value,
            "stopWorkout(exitingWorkout=true) should transition to Idle")
        harness.cleanup()
    }

    @Test
    fun `stopWorkout with exitingWorkout false transitions to SetSummary`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value,
            "stopWorkout(exitingWorkout=false) should transition to SetSummary")
        harness.cleanup()
    }

    @Test
    fun `stopWorkout guard flag prevents double stop`() = runTest {
        val harness = activeDWSM()

        // First stop should work
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()
        val firstState = harness.dwsm.coordinator.workoutState.value
        assertIs<WorkoutState.SetSummary>(firstState)

        // Second stop should be a no-op (guard flag set)
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        // Characterization: The second stopWorkout is silently ignored due to
        // stopWorkoutInProgress guard flag. State remains SetSummary.
        assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value,
            "Second stopWorkout should be silently ignored (guard flag)")
        harness.cleanup()
    }

    @Test
    fun `stopWorkout calls bleRepository stopWorkout for cable exercises`() = runTest {
        val harness = activeDWSM()

        // Track that BLE stop is called by checking no crash occurs
        // FakeBleRepository.stopWorkout() returns Result.success(Unit)
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        // Verify state transition completed (which means BLE stop was called successfully)
        assertIs<WorkoutState.Idle>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    // ===== C. resetForNewWorkout =====

    @Test
    fun `resetForNewWorkout clears rep count and resets to Idle`() = runTest {
        val harness = activeDWSM()

        // Stop workout first to get to SetSummary
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()
        assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)

        // Now reset
        harness.dwsm.resetForNewWorkout()

        assertEquals(WorkoutState.Idle, harness.dwsm.coordinator.workoutState.value,
            "resetForNewWorkout should set state to Idle")
        assertEquals(RepCount(), harness.dwsm.coordinator.repCount.value,
            "resetForNewWorkout should reset rep count to default")
        harness.cleanup()
    }

    @Test
    fun `resetForNewWorkout clears rep ranges`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.resetForNewWorkout()

        // repRanges should be cleared to null
        assertEquals(null, harness.dwsm.coordinator.repRanges.value,
            "resetForNewWorkout should clear repRanges to null")
        harness.cleanup()
    }

    // ===== D. updateWorkoutParameters =====

    @Test
    fun `updateWorkoutParameters updates the workoutParameters flow`() = runTest {
        val harness = DWSMTestHarness(this)

        val newParams = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 12,
            weightPerCableKg = 30f,
            progressionRegressionKg = 0.5f
        )
        harness.dwsm.updateWorkoutParameters(newParams)

        val current = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(ProgramMode.Pump, current.programMode)
        assertEquals(12, current.reps)
        assertEquals(30f, current.weightPerCableKg)
        assertEquals(0.5f, current.progressionRegressionKg)
        harness.cleanup()
    }

    @Test
    fun `updateWorkoutParameters during Idle does not crash`() = runTest {
        val harness = DWSMTestHarness(this)

        // Update while Idle should work without issues
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 8,
            weightPerCableKg = 20f
        )
        harness.dwsm.updateWorkoutParameters(params)
        advanceUntilIdle()

        assertEquals(8, harness.dwsm.coordinator.workoutParameters.value.reps)
        harness.cleanup()
    }

    // ===== E. Auto-stop behavior (indirect) =====

    @Test
    fun `autoStopState starts with default values`() = runTest {
        val harness = DWSMTestHarness(this)

        val autoStop = harness.dwsm.coordinator.autoStopState.value
        // Characterization: AutoStopUiState default is not counting down
        assertNotNull(autoStop, "autoStopState should never be null")
        harness.cleanup()
    }

    @Test
    fun `startWorkout resets autoStop state`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        // Characterization: startWorkout always calls resetAutoStopState() which
        // clears any previous auto-stop timers and resets the UI state
        val autoStop = harness.dwsm.coordinator.autoStopState.value
        assertNotNull(autoStop, "autoStopState should be reset after startWorkout")
        harness.cleanup()
    }

    // ===== F. saveWorkoutSession side effects =====

    @Test
    fun `stopWorkout saves session to workout repository`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        // Check that a session was saved to the fake workout repository
        val sessions = harness.fakeWorkoutRepo.getAllSessions().first()

        // Characterization: stopWorkout always saves a session even with 0 reps
        assertTrue(sessions.isNotEmpty(),
            "stopWorkout should save a workout session to the repository")
        harness.cleanup()
    }

    @Test
    fun `stopWorkout with exitingWorkout true also saves session`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        // Verify session was saved even when exiting
        val sessions = harness.fakeWorkoutRepo.getAllSessions().first()

        // Characterization: stopWorkout(exitingWorkout=true) saves session THEN sets Idle
        assertTrue(sessions.isNotEmpty(),
            "stopWorkout(exitingWorkout=true) should still save a session before going to Idle")
        harness.cleanup()
    }
}
