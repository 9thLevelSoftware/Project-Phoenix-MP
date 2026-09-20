package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #782 durable safety barrier through the real engine: the default [DWSMTestHarness] wires a
 * real [MachineSafetyCoordinator] with machine arming ON, exactly like production.
 */
class MachineSafetyArmingEngineTest {

    @Test
    fun `routine with two consecutive machine sets reaches Active for both with arming on`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startFirstRoutineSet(harness, setsPerExercise = 2)
            val first = assertArmedActive(harness)

            completeCurrentSet(harness)
            // The successful RESET teardown resolved set 1's arm row before any successor start.
            assertTrue(harness.machineSafetyStore.rows.isEmpty())

            // Rest autoplay starts set 2 through the same machine-start gate.
            advanceUntilIdle()
            val second = assertArmedActive(harness)
            assertNotEquals(first, second)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stop set then start again succeeds with arming on`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startFirstRoutineSet(harness, setsPerExercise = 2)
            val first = assertArmedActive(harness)

            harness.dwsm.stopAndReturnToSetReady()
            advanceUntilIdle()
            assertTrue(harness.machineSafetyStore.rows.isEmpty())

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertNotEquals(first, assertArmedActive(harness))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `arm row is resolved before the teardown publishes Ready or runs its successor start`() = runTest {
        val harness = DWSMTestHarness(this)
        val gate = CompletableDeferred<Unit>()
        try {
            startFirstRoutineSet(harness, setsPerExercise = 2)
            val first = assertArmedActive(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size
            harness.machineSafetyStore.beforeDelete = { gate.await() }
            var continuations = 0

            assertTrue(
                harness.activeSessionEngine.requestTeardownForTransition(lease, TeardownReason.STOP_SET) {
                    continuations++
                    harness.dwsm.startWorkout(skipCountdown = true)
                },
            )
            runCurrent()

            // RESET succeeded, but the durable delete is still in flight: nothing downstream may run.
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(0, continuations)
            assertEquals(first, harness.machineSafetyStore.rows.values.single().executionId)
            // A user start in the window is held off by the teardown, never refused by the barrier.
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(MachineSafetyUiState.Hidden, harness.machineSafetyCoordinator?.uiState?.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, continuations)
            assertEquals(MachineSafetyUiState.Hidden, harness.machineSafetyCoordinator?.uiState?.value)
            assertNotEquals(first, assertArmedActive(harness))
        } finally {
            gate.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `replacement start over a live armed set is refused without a false hazard warning`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startFirstRoutineSet(harness, setsPerExercise = 2)
            val first = assertArmedActive(harness)
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            // Pinned behavior: B is refused at the barrier by A's own live arm row, no command
            // is sent, and no hazard is surfaced for it.
            assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(MachineSafetyUiState.Hidden, harness.machineSafetyCoordinator?.uiState?.value)
            assertEquals(first, harness.machineSafetyStore.rows.values.single().executionId)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `failed teardown reset keeps the row and a later start is refused with visible recovery`() = runTest {
        assertFailedTeardownKeepsBarrier { harness -> Result.failure(IllegalStateException("reset write failed")) }
    }

    @Test
    fun `disconnect during teardown keeps the row and a later start is refused with visible recovery`() = runTest {
        assertFailedTeardownKeepsBarrier { harness ->
            harness.fakeBleRepo.simulateDisconnect()
            Result.success(Unit)
        }
    }

    private suspend fun TestScope.assertFailedTeardownKeepsBarrier(
        stopWorkout: (DWSMTestHarness) -> Result<Unit>,
    ) {
        val harness = DWSMTestHarness(this)
        val relaunched = DWSMTestHarness(this, machineSafetyStore = harness.machineSafetyStore)
        try {
            startFirstRoutineSet(harness, setsPerExercise = 2)
            val armed = assertArmedActive(harness)
            harness.fakeBleRepo.stopWorkoutBlock = { stopWorkout(harness) }

            completeCurrentSet(harness)
            advanceUntilIdle()
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            assertEquals(armed, harness.machineSafetyStore.rows.values.single().executionId)
            assertEquals(MachineSafetyUiState.Hidden, harness.machineSafetyCoordinator?.uiState?.value)

            // In-process, the teardown-recovery guard refuses the next start before the barrier.
            val commandsAfterFailure = harness.fakeBleRepo.commandsReceived.size
            harness.startCableSet(targetReps = 5)
            assertEquals(commandsAfterFailure, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(armed, harness.machineSafetyStore.rows.values.single().executionId)

            // A relaunched process sees the persisted row: the start is refused, no command is
            // sent, and the recovery UI is shown instead of a silent failure.
            relaunched.fakeBleRepo.simulateConnect("Vee_Test")
            relaunched.startCableSet(targetReps = 5)
            assertTrue(relaunched.fakeBleRepo.commandsReceived.isEmpty())
            assertIs<WorkoutState.Idle>(relaunched.coordinator.workoutState.value)
            val visible = assertIs<MachineSafetyUiState.Visible>(relaunched.machineSafetyCoordinator?.uiState?.value)
            assertEquals(armed, visible.document.executionId)
            assertEquals(1, relaunched.machineSafetyStore.rows.size)

            // The user's explicit physical check clears the barrier; the next start goes live.
            relaunched.machineSafetyCoordinator?.acknowledgeUnloaded(visible.document.generation)
            advanceUntilIdle()
            assertTrue(relaunched.machineSafetyStore.rows.isEmpty())
            relaunched.startCableSet(targetReps = 5)
            assertEquals(WorkoutState.Active, relaunched.coordinator.workoutState.value)
            assertEquals(1, relaunched.fakeBleRepo.commandsReceived.size)
        } finally {
            relaunched.cleanup()
            harness.cleanup()
        }
    }

    private fun TestScope.startFirstRoutineSet(harness: DWSMTestHarness, setsPerExercise: Int) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = setsPerExercise)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
    }

    /** Asserts the set is live and exactly its own arm row is persisted; returns its executionId. */
    private fun assertArmedActive(harness: DWSMTestHarness): Long {
        assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
        val executionId = harness.activeSessionEngine.currentExecutionLeaseForTest().executionId
        assertEquals(executionId, harness.machineSafetyStore.rows.values.single().executionId)
        return executionId
    }

    private fun TestScope.completeCurrentSet(harness: DWSMTestHarness) {
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        runCurrent()
    }
}
