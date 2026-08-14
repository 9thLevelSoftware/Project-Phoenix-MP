package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Issue687RepIsolationTest {
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
}
