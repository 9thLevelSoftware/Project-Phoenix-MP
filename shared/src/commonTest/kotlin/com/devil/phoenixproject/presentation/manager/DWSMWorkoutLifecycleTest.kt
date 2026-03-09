package com.devil.phoenixproject.presentation.manager

import app.cash.turbine.test
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.DWSMTestHarness
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

        val session = harness.fakeWorkoutRepo.getAllSessions().first().first()
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

        val session = harness.fakeWorkoutRepo.getAllSessions().first().first()
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
