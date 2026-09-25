package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Issue687RepIsolationTest {
    @Test
    fun `bodyweight start does not consult machine teardown state while disconnected`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val cableLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(harness.activeSessionEngine.executionGuard.beginTeardown(cableLease))

            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.commandsReceived.clear()
            harness.coordinator._loadedRoutine.value = bodyweightRoutine()
            harness.coordinator._currentExerciseIndex.value = 0

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertIs<MachineTeardownState.TearingDown>(harness.activeSessionEngine.machineTeardownState.value)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(false, harness.activeSessionEngine.currentExecutionLeaseForTest().requiresMachine)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `issue 687 delayed terminal packet cannot complete the new execution`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val cutoverB = harness.activeSessionEngine.currentExecutionLeaseForTest()
                .activationCutoverTimestampMs
                ?: error("execution B was not activated")

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 3,
                    timestamp = cutoverB - 1,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `issue 687 post cutover terminal packet waits for current execution evidence`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val cutoverB = harness.activeSessionEngine.currentExecutionLeaseForTest()
                .activationCutoverTimestampMs
                ?: error("execution B was not activated")

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 3,
                    timestamp = cutoverB + 1,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `issue 687 movement then terminal packet completes current one rep execution`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 1)
            val cutoverB = harness.activeSessionEngine.currentExecutionLeaseForTest()
                .activationCutoverTimestampMs
                ?: error("execution B was not activated")

            harness.fakeBleRepo.setHandleState(HandleState.Moving)
            runCurrent()
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 1,
                    repsSetTotal = 1,
                    timestamp = cutoverB + 1,
                ),
            )
            runCurrent()

            assertEquals(1, harness.coordinator.repCount.value.workingReps)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `issue 687 first legacy packet with carried counters establishes baseline only`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val cutoverB = harness.activeSessionEngine.currentExecutionLeaseForTest()
                .activationCutoverTimestampMs
                ?: error("execution B was not activated")

            harness.fakeBleRepo.emitRepNotification(
                harness.legacyRepPacket(
                    topCounter = 7,
                    completeCounter = 7,
                    timestamp = cutoverB + 1,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.repCount.value.totalReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `issue 687 conflicting modern target is rejected`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val cutoverB = harness.activeSessionEngine.currentExecutionLeaseForTest()
                .activationCutoverTimestampMs
                ?: error("execution B was not activated")

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 1,
                    repsSetTotal = 4,
                    timestamp = cutoverB + 1,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `armed B rejects a delayed terminal packet with A target without side effects`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutoverB = leaseB.activationCutoverTimestampMs
                ?: error("execution B was not activated")

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 1,
                    repsSetTotal = 3,
                    timestamp = cutoverB + 1,
                ),
            )
            runCurrent()
            assertEquals(1, harness.coordinator.repCount.value.workingReps)
            assertEquals(
                RepFreshnessState.Armed,
                harness.activeSessionEngine.executionGuard.repFreshnessGate.stateFor(leaseB),
            )

            val haptics = mutableListOf<HapticEvent>()
            harness.workoutScope.launch {
                harness.coordinator.hapticEvents.collect { haptics += it }
            }
            runCurrent()
            val savedSessionsBefore = harness.fakeWorkoutRepo.saveSessionAttempts.size
            val savedSetsBefore = harness.fakeCompletedSetRepo.saved.size
            val resetCallsBefore = harness.fakeBleRepo.stopWorkoutCallCount

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 4,
                    repsSetTotal = 4,
                    timestamp = cutoverB + 2,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(1, harness.coordinator.repCount.value.workingReps)
            assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertTrue(haptics.isEmpty())
            assertEquals(savedSessionsBefore, harness.fakeWorkoutRepo.saveSessionAttempts.size)
            assertEquals(savedSetsBefore, harness.fakeCompletedSetRepo.saved.size)
            assertEquals(resetCallsBefore, harness.fakeBleRepo.stopWorkoutCallCount)
        } finally {
            harness.cleanup()
        }
    }

    private fun bodyweightRoutine(): Routine {
        val pushUp = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "issue-687-push-up",
        )
        return Routine(
            id = "issue-687-bodyweight-routine",
            name = "Bodyweight Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "issue-687-bodyweight-set",
                    exercise = pushUp,
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 0f,
                    duration = 30,
                    setRestSeconds = listOf(0),
                ),
            ),
        )
    }
}
