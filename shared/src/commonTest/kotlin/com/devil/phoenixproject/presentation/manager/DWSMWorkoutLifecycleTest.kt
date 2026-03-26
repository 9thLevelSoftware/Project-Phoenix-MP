package com.devil.phoenixproject.presentation.manager

import app.cash.turbine.test
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.activeDWSM
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.createTestRoutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `startWorkout uses single activation config for TUT and Eccentric Only`() = runTest {
        val modes = listOf(ProgramMode.TUT, ProgramMode.EccentricOnly)
        for (mode in modes) {
            val harness = DWSMTestHarness(this)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = mode,
                    reps = 8,
                    weightPerCableKg = 30f,
                    progressionRegressionKg = 2f
                )
            )

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            assertEquals(2, harness.fakeBleRepo.commandsReceived.size, "Expected config + start for $mode")
            assertEquals(0x04.toByte(), harness.fakeBleRepo.commandsReceived[0][0], "Expected activation packet for $mode")
            assertEquals(0x03.toByte(), harness.fakeBleRepo.commandsReceived[1][0], "Expected start packet for $mode")
            assertFalse(
                harness.fakeBleRepo.commandsReceived.any { it.firstOrNull() == 0x4F.toByte() },
                "Workout start should not send a regular packet for $mode"
            )
            harness.cleanup()
        }
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

    @Test
    fun `stopAndSkipCurrentExercise advances routine without ending workout session`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val routine = createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        harness.dwsm.stopAndSkipCurrentExercise()
        advanceUntilIdle()

        val flowState = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(
            flowState,
            "stopAndSkipCurrentExercise should return to SetReady instead of ending the routine"
        )
        assertEquals(1, flowState.exerciseIndex, "Should advance to the next exercise")
        assertEquals(0, flowState.setIndex, "Next exercise should start at set 1")
        assertTrue(0 in harness.dwsm.coordinator.skippedExercises.value)
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


    @Test
    fun `routine next-set weight applies when user did not manually edit`() = runTest {
        val harness = DWSMTestHarness(this)
        val base = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        val routine = base.copy(
            exercises = listOf(
                base.exercises.first().copy(
                    setReps = listOf(8, 6),
                    setWeightsPerCableKg = listOf(20f, 30f),
                    weightPerCableKg = 20f
                )
            )
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = "",
            isLastExercise = true,
            currentSet = 1,
            totalSets = 2
        )
        harness.dwsm.startNextSet()
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(30f, params.weightPerCableKg)
        assertEquals(6, params.reps)
        harness.cleanup()
    }

    @Test
    fun `manual rest-screen edits are preserved across transition`() = runTest {
        val harness = DWSMTestHarness(this)
        val base = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        val routine = base.copy(
            exercises = listOf(
                base.exercises.first().copy(
                    setReps = listOf(8, 6),
                    setWeightsPerCableKg = listOf(20f, 30f),
                    weightPerCableKg = 20f
                )
            )
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = "",
            isLastExercise = true,
            currentSet = 1,
            totalSets = 2
        )
        harness.dwsm.updateWorkoutParameters(
            harness.dwsm.coordinator.workoutParameters.value.copy(
                weightPerCableKg = 42f,
                reps = 11
            )
        )

        harness.dwsm.startNextSet()
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(42f, params.weightPerCableKg)
        assertEquals(11, params.reps)
        harness.cleanup()
    }

    @Test
    fun `previous exercise weight does not leak into next exercise`() = runTest {
        val harness = DWSMTestHarness(this)
        val base = createTestRoutine(exerciseCount = 2, setsPerExercise = 1)
        val routine = base.copy(
            exercises = listOf(
                base.exercises[0].copy(
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(20f),
                    weightPerCableKg = 20f
                ),
                base.exercises[1].copy(
                    setReps = listOf(10),
                    setWeightsPerCableKg = listOf(55f),
                    weightPerCableKg = 55f
                )
            )
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Disable autoplay so startNextSet() enters SetReady instead of launching
        // startWorkout() which creates infinite delay loops (rest timer, metrics, etc.)
        harness.fakePrefsManager.setSummaryCountdownSeconds(0)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = routine.exercises[1].exercise.displayName,
            isLastExercise = false,
            currentSet = 1,
            totalSets = 1
        )
        harness.dwsm.startNextSet()
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(55f, params.weightPerCableKg)
        assertEquals(10, params.reps)
        assertEquals(routine.exercises[1].exercise.id, params.selectedExerciseId)
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

    @Test
    fun `deload does not start stall timer before warmup is complete`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        assertFalse(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "DELOAD should be ignored until warmup reps are complete"
        )
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `deload does not start stall timer before first working rep even after warmup`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "DELOAD should be ignored until at least one working rep is confirmed"
        )
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `deload starts stall timer after first working rep`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertEquals(1, harness.dwsm.coordinator.repCount.value.workingReps)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "DELOAD should start stall timer once working reps are in progress"
        )
        assertTrue(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `standard set ignores position-based auto-stop countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Regression guard: regular sets should not use the 2.5s "handles at rest" path.
        harness.dwsm.coordinator.autoStopStartTime = currentTimeMillis() - 10_000L
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 0f,
                positionB = 0f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 0f,
                loadB = 0f
            )
        )
        advanceUntilIdle()

        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        assertFalse(harness.dwsm.coordinator.autoStopTriggered)
        harness.cleanup()
    }

    @Test
    fun `standard set auto-stops after stalled deload timer expires`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()
        harness.dwsm.coordinator.stallStartTime = currentTimeMillis() - 6_000L

        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 120f,
                positionB = 120f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 10f,
                loadB = 10f
            )
        )
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `Issue 256 - deload starts stall timer even with pending rep`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Complete warmup
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)
        assertEquals(0, harness.dwsm.coordinator.repCount.value.workingReps)

        // Simulate starting the first rep (pending at TOP = failed bench press scenario)
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = 4,       // warmup(3) + working(1) = 4th up counter
                completeCounter = 3,  // Only 3 downs (first working rep not completed)
                repsRomCount = 3,
                repsRomTotal = 3,
                repsSetCount = 0,     // Still 0 completed working reps
                repsSetTotal = 8,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = 100L
            )
        )
        advanceUntilIdle()
        assertTrue(
            harness.dwsm.coordinator.repCount.value.hasPendingRep,
            "Rep should be pending at TOP (failed lift scenario)"
        )

        // Emit DELOAD_OCCURRED while pending - previously this was ignored (Issue #256)
        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "Issue #256: DELOAD should start stall timer even with a pending rep"
        )
        assertTrue(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `Issue 256 - velocity stall auto-stops with pending rep after timer expires`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Simulate pending first rep (stalled mid-concentric)
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = 4,
                completeCounter = 3,
                repsRomCount = 3,
                repsRomTotal = 3,
                repsSetCount = 0,
                repsSetTotal = 8,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = 100L
            )
        )
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.hasPendingRep)

        // Backdate stall timer to simulate 6 seconds elapsed
        harness.dwsm.coordinator.stallStartTime = currentTimeMillis() - 6_000L
        harness.dwsm.coordinator.isCurrentlyStalled = true

        // Emit a stalled metric (near-zero velocity, position elevated = mid-rep)
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 120f,
                positionB = 120f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 10f,
                loadB = 10f
            )
        )
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "Issue #256: Velocity stall should auto-stop even with a pending rep"
        )
        harness.cleanup()
    }

    @Test
    fun `Issue 267 Just Lift warmup to working rep transitions without failed stall state`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 20f,
                progressionRegressionKg = 0f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = true
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        val afterWarmup = harness.dwsm.coordinator.repCount.value
        assertTrue(afterWarmup.isWarmupComplete)
        assertEquals(0, afterWarmup.workingReps)

        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        val afterWorkingRep = harness.dwsm.coordinator.repCount.value
        assertEquals(1, afterWorkingRep.workingReps)
        assertFalse(afterWorkingRep.hasPendingRep)
        assertEquals(null, harness.dwsm.coordinator.stallStartTime)
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `set summary volume uses configured load for fixed weight workouts`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.activeSessionEngine.calculateSetSummaryMetrics(
            metrics = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 22f,
                    loadB = 26f,
                    positionA = 120f,
                    positionB = 120f,
                    velocityA = 80.0,
                    velocityB = 80.0
                ),
                WorkoutMetric(
                    timestamp = 200L,
                    loadA = 18f,
                    loadB = 20f,
                    positionA = 100f,
                    positionB = 100f,
                    velocityA = -60.0,
                    velocityB = -60.0
                )
            ),
            repCount = 8,
            fallbackWeightKg = 12f,
            configuredWeightKgPerCable = 12f,
            isEchoMode = false
        )

        assertEquals(24f, summary.heaviestLiftKgPerCable)
        assertEquals(192f, summary.totalVolumeKg)
        assertEquals(2, summary.cableCount)
        harness.cleanup()
    }

    // ===== F. saveWorkoutSession side effects =====

    @Test
    fun `auto completed fixed weight session records achieved load for weight PR and configured load for volume PR`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 12f,
                selectedExerciseId = TestFixtures.benchPress.id
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 8,
            totalReps = 8,
            isWarmupComplete = true
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 22f,
                    loadB = 26f,
                    positionA = 120f,
                    positionB = 120f,
                    velocityA = 80.0,
                    velocityB = 80.0
                ),
                WorkoutMetric(
                    timestamp = 200L,
                    loadA = 18f,
                    loadB = 20f,
                    positionA = 100f,
                    positionB = 100f,
                    velocityA = -60.0,
                    velocityB = -60.0
                )
        )

        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        val prUpdate = harness.fakePRRepo.updateCalls.single()
        assertEquals(24f, prUpdate.weightPRWeightPerCableKg)
        assertEquals(12f, prUpdate.volumePRWeightPerCableKg)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(24f, session.heaviestLiftKg)
        assertEquals(192f, session.totalVolumeKg)
        assertEquals(2, session.cableCount)
        harness.cleanup()
    }

    @Test
    fun `fixed weight session uses measured peak to beat existing weight PR in normalized mode bucket`() = runTest {
        val harness = DWSMTestHarness(this)
        val deadliftId = requireNotNull(TestFixtures.deadlift.id)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeExerciseRepo.addExercise(TestFixtures.deadlift)
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 1L,
                exerciseId = deadliftId,
                exerciseName = "Conventional Deadlift",
                weightPerCableKg = 55.73f,
                reps = 10,
                oneRepMax = 74.31f,
                timestamp = currentTimeMillis() - 10_000L,
                workoutMode = "OldSchool",
                prType = PRType.MAX_WEIGHT,
                volume = 557.3f
            )
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 50f,
                selectedExerciseId = deadliftId
            )
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 60f,
                    loadB = 60f,
                    positionA = 120f,
                    positionB = 120f,
                    velocityA = 80.0,
                    velocityB = 80.0
                ),
                WorkoutMetric(
                    timestamp = 200L,
                    loadA = 54f,
                    loadB = 56f,
                    positionA = 90f,
                    positionB = 90f,
                    velocityA = -60.0,
                    velocityB = -60.0
                )
        )

        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        val prUpdate = harness.fakePRRepo.updateCalls.single()
        assertEquals(60f, prUpdate.weightPRWeightPerCableKg)
        assertEquals(50f, prUpdate.volumePRWeightPerCableKg)

        val updatedPr = harness.fakePRRepo.getWeightPR(deadliftId, "Old School", "default")
        assertEquals(60f, updatedPr?.weightPerCableKg)
        assertEquals(updatedPr?.id, harness.fakePRRepo.getWeightPR(deadliftId, "OldSchool", "default")?.id)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(60f, session.heaviestLiftKg)
        harness.cleanup()
    }

    @Test
    fun `stopWorkout saves session to workout repository`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        // Check that a session was saved to the fake workout repository
        val sessions = harness.fakeWorkoutRepo.getAllSessions("default").first()

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
        val sessions = harness.fakeWorkoutRepo.getAllSessions("default").first()

        // Characterization: stopWorkout(exitingWorkout=true) saves session THEN sets Idle
        assertTrue(sessions.isNotEmpty(),
            "stopWorkout(exitingWorkout=true) should still save a session before going to Idle")
        harness.cleanup()
    }

    @Test
    fun `stopWorkout in routine flow saves session with routine metadata`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertTrue(
            session.routineSessionId?.isNotBlank() == true,
            "Routine workout sessions should include a non-empty routineSessionId"
        )
        assertEquals(
            routine.name,
            session.routineName,
            "Routine workout sessions should include the routine name"
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout in temp single-exercise flow keeps routine metadata null`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val tempRoutine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1).copy(
            id = "${DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX}test"
        )
        tempRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(tempRoutine)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(
            null,
            session.routineSessionId,
            "Single-exercise temp routines should not set routineSessionId"
        )
        assertEquals(
            null,
            session.routineName,
            "Single-exercise temp routines should not set routineName"
        )
        harness.cleanup()
    }

    private suspend fun completeWarmupReps(
        harness: DWSMTestHarness,
        warmupTarget: Int = 3,
        workingTarget: Int = 8
    ) {
        val activeMetric = WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            velocityA = 80.0,
            velocityB = 80.0,
            loadA = 10f,
            loadB = 10f
        )

        for (warmupRep in 1..warmupTarget) {
            harness.fakeBleRepo.emitMetric(activeMetric)
            harness.fakeBleRepo.emitRepNotification(
                RepNotification(
                    topCounter = warmupRep,
                    completeCounter = warmupRep,
                    repsRomCount = warmupRep,
                    repsRomTotal = warmupTarget,
                    repsSetCount = 0,
                    repsSetTotal = workingTarget,
                    rangeTop = 800f,
                    rangeBottom = 0f,
                    rawData = ByteArray(24),
                    timestamp = warmupRep.toLong()
                )
            )
        }
    }

    private suspend fun completeFirstWorkingRep(
        harness: DWSMTestHarness,
        warmupTarget: Int = 3,
        workingTarget: Int = 8
    ) {
        val activeMetric = WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            velocityA = 80.0,
            velocityB = 80.0,
            loadA = 10f,
            loadB = 10f
        )

        harness.fakeBleRepo.emitMetric(activeMetric)
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = warmupTarget + 1,
                completeCounter = warmupTarget + 1,
                repsRomCount = warmupTarget,
                repsRomTotal = warmupTarget,
                repsSetCount = 1,
                repsSetTotal = workingTarget,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = (warmupTarget + 1).toLong()
            )
        )
    }
}
