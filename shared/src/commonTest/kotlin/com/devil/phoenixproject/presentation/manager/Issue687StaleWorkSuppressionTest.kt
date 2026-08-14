package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Issue687StaleWorkSuppressionTest {
    @Test
    fun `cleanup closes guard before a delayed teardown job can attach`() = runTest {
        val guard = WorkoutExecutionGuard()
        val lease = guard.beginExecution(executionSeed("cleanup-race")).getOrThrow()
        assertTrue(guard.beginTeardown(lease))
        var delayedJobRan = false
        val delayedJob = backgroundScope.launch(start = CoroutineStart.LAZY) {
            delayedJobRan = true
        }

        guard.cancelAllOwnedJobs()

        val attached = guard.attachTeardownJob(lease, delayedJob)
        if (attached) delayedJob.start() else delayedJob.cancel()
        runCurrent()

        assertFalse(attached)
        assertTrue(delayedJob.isCancelled)
        assertFalse(delayedJobRan)
    }

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
    fun `End A cancels haptic-suspended completion before B starts`() = runTest {
        val harness = DWSMTestHarness(this)
        var hapticBlock: HapticBlock? = null
        try {
            harness.setActiveSummaryCountdownSeconds(10)
            startExecution(harness, routine("completion-a", restSeconds = 5))
            hapticBlock = blockHapticEmissions(harness)

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            val leaseB = startExecution(harness, routine("completion-b", restSeconds = 5))
            val observed = observeReleasedStates(harness)

            hapticBlock.release.complete(Unit)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            assertTrue(
                HapticEvent.WORKOUT_END !in hapticBlock.eventsDeliveredAfterRelease,
                "stale A completion haptic was delivered after B started",
            )
            observed.job.cancel()
        } finally {
            hapticBlock?.release?.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `final A countdown delay cannot clean motion state or send config under B`() = runTest {
        val harness = DWSMTestHarness(this)
        var detachedCountdownJob: Job? = null
        try {
            loadRoutineNow(harness, routine("countdown-a"))
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.fakeBleRepo.commandsReceived.clear()
            harness.dwsm.startWorkout(skipCountdown = false)
            runCurrent()
            assertEquals(WorkoutState.Countdown(5), harness.coordinator.workoutState.value)

            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(WorkoutState.Countdown(1), harness.coordinator.workoutState.value)
            detachedCountdownJob = harness.coordinator.workoutJob
            harness.coordinator.workoutJob = null

            endExecution(harness)
            loadRoutineNow(harness, routine("countdown-b", exerciseCount = 2))
            harness.fakeBleRepo.commandsReceived.clear()
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandsBeforeARelease = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._motionStartHoldProgress.value = 0.75f

            advanceTimeBy(1_000)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertEquals(commandsBeforeARelease, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(0.75f, harness.coordinator.motionStartHoldProgress.value)
        } finally {
            detachedCountdownJob?.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun `rest haptic suspended under A cannot publish Resting under B`() = runTest {
        val harness = DWSMTestHarness(this)
        var hapticBlock: HapticBlock? = null
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.setActiveCountdownBeepsEnabled(true)
            startExecution(harness, routine("rest-haptic-a", setCount = 2, restSeconds = 10))
            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,
            )
            runCurrent()
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

            hapticBlock = blockHapticEmissions(harness)
            advanceTimeBy(1_000)
            runCurrent()

            loadRoutineNow(harness, routine("rest-haptic-b", exerciseCount = 2))
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val observed = observeReleasedStates(harness)

            hapticBlock.release.complete(Unit)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            observed.job.cancel()
        } finally {
            hapticBlock?.release?.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `cable startup haptic cannot resume A baseline writes under bodyweight B`() = runTest {
        val harness = DWSMTestHarness(this)
        var hapticBlock: HapticBlock? = null
        var detachedCableJob: Job? = null
        try {
            loadRoutineNow(harness, routine("cable-haptic-a"))
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.coordinator._currentMetric.value = WorkoutMetric(
                positionA = 10f,
                positionB = 20f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 31f,
                loadB = 32f,
            )
            hapticBlock = blockHapticEmissions(harness)
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            detachedCableJob = harness.coordinator.workoutJob
            harness.coordinator.workoutJob = null

            val bodyweightB = bodyweightRoutine("cable-haptic-b", durationSeconds = 30)
            bodyweightB.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.coordinator._loadedRoutine.value = bodyweightB
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._loadBaselineA.value = 777f
            harness.coordinator._loadBaselineB.value = 778f

            hapticBlock.release.complete(Unit)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertEquals(777f, harness.coordinator.loadBaselineA.value)
            assertEquals(778f, harness.coordinator.loadBaselineB.value)
        } finally {
            detachedCableJob?.cancel()
            hapticBlock?.release?.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `stale summary countdown cannot advance routine B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(5)
            startExecution(harness, routine("summary-a", restSeconds = 5))
            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,
            )
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
            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,
            )
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
            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,
            )
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
    fun `manual stop continuation suspended after Ready cannot publish A summary under B`() = runTest {
        val harness = DWSMTestHarness(this)
        var hapticBlock: HapticBlock? = null
        try {
            startExecution(harness, routine("manual-stop-a"))
            hapticBlock = blockHapticEmissions(harness)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)

            val leaseB = startExecution(harness, routine("manual-stop-b", exerciseCount = 2))
            val observed = observeReleasedStates(harness)

            hapticBlock.release.complete(Unit)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertNoTerminalAState(observed.states)
            observed.job.cancel()
        } finally {
            hapticBlock?.release?.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `Stop Set continuation queued after Ready cannot enter SetReady under B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startExecution(harness, routine("stop-set-a", exerciseCount = 2))
            val replacement = startReplacementWhenTeardownReady(harness)
            val statesAfterB = observeRoutineStatesAfter(replacement, harness)

            harness.dwsm.stopAndReturnToSetReady()
            runCurrent()
            val leaseB = replacement.await()

            assertExecutionBStillActive(harness, leaseB)
            assertTrue(statesAfterB.states.none { it is RoutineFlowState.SetReady })
            statesAfterB.job.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Skip Exercise continuation queued after Ready cannot advance B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startExecution(harness, routine("skip-a", exerciseCount = 2))
            val replacement = startReplacementWhenTeardownReady(harness)
            val statesAfterB = observeRoutineStatesAfter(replacement, harness)

            harness.dwsm.stopAndSkipCurrentExercise()
            runCurrent()
            val leaseB = replacement.await()

            assertExecutionBStillActive(harness, leaseB)
            assertTrue(statesAfterB.states.none { it is RoutineFlowState.SetReady })
            assertTrue(statesAfterB.states.none { it is RoutineFlowState.Complete })
            statesAfterB.job.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `exercise jump request with captured A lease cannot teardown or navigate B`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val leaseA = startExecution(harness, routine("jump-captured-a", exerciseCount = 2))
            endExecution(harness)
            val leaseB = startExecution(harness, routine("jump-captured-b", exerciseCount = 2))
            val stopCallsBeforeStaleRequest = harness.fakeBleRepo.stopWorkoutCallCount
            var navigated = false

            harness.activeSessionEngine.requestTeardownForTransition(
                expectedLease = leaseA,
                reason = TeardownReason.EXERCISE_JUMP,
            ) {
                navigated = true
                harness.coordinator._currentExerciseIndex.value = 1
            }
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertFalse(navigated)
            assertEquals(stopCallsBeforeStaleRequest, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `End A cancels haptic-suspended warmup successor before B starts`() = runTest {
        val harness = DWSMTestHarness(this)
        var hapticBlock: HapticBlock? = null
        try {
            startExecution(harness, warmupRoutine("warmup-a"))
            hapticBlock = blockHapticEmissions(harness)

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            val leaseB = startExecution(harness, routine("warmup-b", exerciseCount = 2))
            val commandsBeforeARelease = harness.fakeBleRepo.commandsReceived.size

            hapticBlock.release.complete(Unit)
            runCurrent()

            assertExecutionBStillActive(harness, leaseB)
            assertEquals(commandsBeforeARelease, harness.fakeBleRepo.commandsReceived.size)
            assertTrue(
                HapticEvent.WORKOUT_END !in hapticBlock.eventsDeliveredAfterRelease,
                "stale A warm-up haptic was delivered after B started",
            )
        } finally {
            hapticBlock?.release?.complete(Unit)
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
            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,
            )
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
        loadRoutineNow(harness, routine)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        advanceUntilIdle()
        harness.fakeBleRepo.commandsReceived.clear()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private fun TestScope.loadRoutineNow(
        harness: DWSMTestHarness,
        routine: Routine,
    ) {
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        runCurrent()
        assertEquals(routine.id, harness.coordinator.loadedRoutine.value?.id)
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

    private fun TestScope.blockHapticEmissions(harness: DWSMTestHarness): HapticBlock {
        val release = CompletableDeferred<Unit>()
        val eventsDeliveredAfterRelease = mutableListOf<HapticEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.collect { event ->
                release.await()
                eventsDeliveredAfterRelease += event
            }
        }
        runCurrent()
        val bufferSentinel = HapticEvent.COUNTDOWN_TICK(Int.MIN_VALUE)
        assertTrue(harness.coordinator._hapticEvents.tryEmit(bufferSentinel))
        repeat(32) {
            assertTrue(harness.coordinator._hapticEvents.tryEmit(bufferSentinel))
        }
        assertFalse(harness.coordinator._hapticEvents.tryEmit(bufferSentinel))
        return HapticBlock(release, eventsDeliveredAfterRelease, collector)
    }

    private fun TestScope.startReplacementWhenTeardownReady(
        harness: DWSMTestHarness,
    ): CompletableDeferred<ExecutionLease> {
        val replacement = CompletableDeferred<ExecutionLease>()
        backgroundScope.launch {
            harness.activeSessionEngine.machineTeardownState.drop(1).first {
                it is MachineTeardownState.Ready
            }
            harness.dwsm.startWorkout(skipCountdown = true)
            replacement.complete(harness.activeSessionEngine.currentExecutionLeaseForTest())
        }
        runCurrent()
        return replacement
    }

    private fun TestScope.observeRoutineStatesAfter(
        replacement: CompletableDeferred<ExecutionLease>,
        harness: DWSMTestHarness,
    ): ObservedRoutineStates {
        val states = mutableListOf<RoutineFlowState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.routineFlowState.collect { state ->
                if (replacement.isCompleted) states += state
            }
        }
        return ObservedRoutineStates(states, job)
    }

    private fun assertExecutionBStillActive(
        harness: DWSMTestHarness,
        leaseB: ExecutionLease,
    ) {
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
        val currentLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        assertEquals(leaseB.executionId, currentLease.executionId)
        assertEquals(leaseB.sessionId, currentLease.sessionId)
        assertEquals(leaseB.profileId, currentLease.profileId)
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

    private data class ObservedRoutineStates(
        val states: MutableList<RoutineFlowState>,
        val job: Job,
    )

    private data class HapticBlock(
        val release: CompletableDeferred<Unit>,
        val eventsDeliveredAfterRelease: MutableList<HapticEvent>,
        val collector: Job,
    )
}
