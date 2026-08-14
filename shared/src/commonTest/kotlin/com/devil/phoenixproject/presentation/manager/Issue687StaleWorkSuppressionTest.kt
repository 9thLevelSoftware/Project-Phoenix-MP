package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WarmupSet
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Issue687StaleWorkSuppressionTest {
    @Test
    fun `guard cancels only the exact lease completion job`() {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(executionSeed("guard-a")).getOrThrow()
        val completionA = Job()
        assertTrue(guard.attachCompletionJob(leaseA, completionA))

        val leaseB = guard.beginExecution(executionSeed("guard-b")).getOrThrow()
        val completionB = Job()
        assertFalse(guard.attachCompletionJob(leaseB, completionB))

        guard.cancelPresentationJobsFor(leaseA)

        assertTrue(completionA.isCancelled)
        assertFalse(completionB.isCancelled)
        assertTrue(guard.attachCompletionJob(leaseB, completionB))
        guard.clearCompletionJobIfOwned(leaseA)
        guard.cancelPresentationJobsFor(leaseB)
        assertTrue(completionB.isCancelled)

        val cleanupCompletion = Job()
        val cleanupTeardown = Job()
        assertTrue(guard.attachCompletionJob(leaseB, cleanupCompletion))
        assertTrue(guard.beginTeardown(leaseB))
        assertTrue(guard.attachTeardownJob(leaseB, cleanupTeardown))

        guard.cancelAllOwnedJobs()

        assertTrue(cleanupCompletion.isCancelled)
        assertTrue(cleanupTeardown.isCancelled)
    }

    @Test
    fun `delayed A completion cannot publish summary after B starts`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.setActiveSummaryCountdownSeconds(10)
            startExecution(harness, routine("completion-a", restSeconds = 5))
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }

            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            harness.dwsm.stopWorkout(exitingWorkout = true)

            releaseReset.complete(Result.success(Unit))
            runCurrent()
            val leaseB = startExecution(harness, routine("completion-b", restSeconds = 5))

            advanceTimeBy(10_000)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `stale summary countdown cannot advance routine B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(5)
            startExecution(harness, routine("summary-a", restSeconds = 5))
            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            endExecution(harness)
            val leaseB = startExecution(harness, routine("summary-b", exerciseCount = 2))
            val observed = observeReleasedStates(harness)

            advanceTimeBy(5_000)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            observed.job.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale rest countdown cannot advance routine B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            startExecution(harness, routine("rest-a", setCount = 2, restSeconds = 2))
            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

            endExecution(harness)
            val leaseB = startExecution(harness, routine("rest-b", exerciseCount = 2))
            val observed = observeReleasedStates(harness)

            advanceTimeBy(2_100)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            observed.job.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale timed cable timer cannot complete routine B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startExecution(harness, routine("timed-cable-a", durationSeconds = 2))

            endExecution(harness)
            val leaseB = startExecution(harness, routine("timed-cable-b", exerciseCount = 2))
            val observed = observeReleasedStates(harness)

            advanceTimeBy(2_100)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            observed.job.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale bodyweight timer cannot complete routine B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startExecution(harness, bodyweightRoutine("bodyweight-a", durationSeconds = 2))

            endExecution(harness)
            val leaseB = startExecution(harness, routine("bodyweight-b", exerciseCount = 2))
            val observed = observeReleasedStates(harness)

            advanceTimeBy(2_100)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            observed.job.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout disarms stale auto start countdown before B starts`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(5)
            startExecution(harness, routine("auto-start-a"))
            harness.coordinator._workoutParameters.value = harness.coordinator._workoutParameters.value.copy(
                isJustLift = true,
                useAutoStart = true,
            )
            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            harness.fakeBleRepo.setHandleState(HandleState.Grabbed)
            runCurrent()
            assertTrue(harness.coordinator.autoStartJob?.isActive == true)

            endExecution(harness)

            assertNull(harness.coordinator.autoStartJob)
            assertNull(harness.coordinator.autoStartCountdown.value)

            val leaseB = startExecution(harness, routine("auto-start-b", exerciseCount = 2))
            advanceTimeBy(10_000)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale warmup successor cannot start after B replaces A`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            startExecution(harness, warmupRoutine("warmup-a"))
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }

            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            releaseReset.complete(Result.success(Unit))
            runCurrent()

            val leaseB = startExecution(harness, routine("warmup-b", exerciseCount = 2))
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued proceedFromSummary callback cannot complete routine B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startExecution(harness, routine("proceed-a"))
            harness.coordinator._workoutState.value = summaryState()

            harness.dwsm.proceedFromSummary()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            val leaseB = startExecution(harness, routine("proceed-b", exerciseCount = 2))

            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale startNextSetOrExercise callback cannot mutate B indices`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            startExecution(harness, routine("next-a", setCount = 2, restSeconds = 1))
            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

            endExecution(harness)
            val leaseB = startExecution(harness, routine("next-b", exerciseCount = 2))

            advanceTimeBy(1_100)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.startExecution(
        harness: DWSMTestHarness,
        routine: Routine,
    ): ExecutionLease {
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.fakeBleRepo.commandsReceived.clear()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private fun TestScope.endExecution(harness: DWSMTestHarness) {
        harness.dwsm.stopWorkout(exitingWorkout = true)
        runCurrent()
        assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
    }

    private fun TestScope.observeReleasedStates(harness: DWSMTestHarness): ObservedStates {
        val states = mutableListOf<WorkoutState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.workoutState.drop(1).collect(states::add)
        }
        return ObservedStates(states, job)
    }

    private fun assertExecutionBStillActive(
        harness: DWSMTestHarness,
        leaseB: ExecutionLease,
    ) {
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
        assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())
        assertEquals(0, harness.coordinator.currentExerciseIndex.value)
        assertEquals(0, harness.coordinator.currentSetIndex.value)
    }

    private fun assertNoTerminalAState(states: List<WorkoutState>) {
        assertTrue(states.none { it is WorkoutState.SetSummary })
        assertTrue(states.none { it is WorkoutState.Resting })
        assertTrue(states.none { it is WorkoutState.Completed })
    }

    private fun summaryState() = WorkoutState.SetSummary(
        metrics = emptyList(),
        peakLoadKgPerCable = 25f,
        avgLoadKgPerCable = 20f,
        repCount = 1,
        workingReps = 1,
    )

    private fun executionSeed(sessionId: String) = ExecutionSeed(
        sessionId = sessionId,
        profileId = "default",
        requiresMachine = false,
        workingRepTarget = 5,
    )

    private fun routine(
        id: String,
        setCount: Int = 1,
        exerciseCount: Int = 1,
        restSeconds: Int = 0,
        durationSeconds: Int? = null,
    ): Routine = Routine(
        id = id,
        name = id,
        exercises = List(exerciseCount) { index ->
            val exercise = Exercise(
                name = "$id exercise $index",
                muscleGroup = "Chest",
                muscleGroups = "Chest,Triceps",
                equipment = "HANDLES",
                id = "$id-exercise-$index",
            )
            RoutineExercise(
                id = "$id-routine-exercise-$index",
                exercise = exercise,
                orderIndex = index,
                setReps = List(setCount) { 5 },
                setWeightsPerCableKg = List(setCount) { 25f },
                weightPerCableKg = 25f,
                programMode = ProgramMode.OldSchool,
                duration = durationSeconds,
                setRestSeconds = List(setCount) { restSeconds },
            )
        },
    )

    private fun bodyweightRoutine(id: String, durationSeconds: Int): Routine {
        val exercise = Exercise(
            name = "$id push up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps",
            equipment = "",
            id = "$id-exercise",
        )
        return Routine(
            id = id,
            name = id,
            exercises = listOf(
                RoutineExercise(
                    id = "$id-routine-exercise",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 0f,
                    duration = durationSeconds,
                    setRestSeconds = listOf(0),
                ),
            ),
        )
    }

    private fun warmupRoutine(id: String): Routine = Routine(
        id = id,
        name = id,
        exercises = listOf(
            RoutineExercise(
                id = "$id-routine-exercise",
                exercise = TestFixtures.benchPress.copy(id = "$id-exercise"),
                orderIndex = 0,
                setReps = listOf(8),
                setWeightsPerCableKg = listOf(40f),
                weightPerCableKg = 40f,
                programMode = ProgramMode.OldSchool,
                warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50)),
            ),
        ),
    )

    private data class ObservedStates(
        val states: MutableList<WorkoutState>,
        val job: Job,
    )
}
