package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.testutil.readProjectFile
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkoutMachineTeardownTest {

    @Test
    fun `bodyweight completes and jumps while prior cable reset remains suspended`() = runTest {
        val harness = DWSMTestHarness(this)
        val cableReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            val cableLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { cableReset.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            val routine = twoExerciseBodyweightRoutine()
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.setActiveBodyWeightKg(80f)
            harness.coordinator._loadedRoutine.value = routine
            harness.coordinator._currentExerciseIndex.value = 0
            harness.coordinator._currentSetIndex.value = 0
            harness.fakeBleRepo.commandsReceived.clear()

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val bodyweightLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertFalse(bodyweightLease.requiresMachine)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            advanceTimeBy(1_100)
            runCurrent()
            val repEntry = assertIs<WorkoutState.BodyweightRepEntry>(
                harness.coordinator.workoutState.value,
            )

            harness.dwsm.confirmBodyweightSetResult(
                reps = 10,
                variant = repEntry.selectedVariant,
            )
            advanceTimeBy(1_000)
            runCurrent()

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertEquals(
                1,
                harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == bodyweightLease.sessionId },
            )

            harness.dwsm.jumpToExercise(1)
            runCurrent()
            val successorLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            assertEquals(1, harness.coordinator.currentExerciseIndex.value)
            assertIs<WorkoutState.Countdown>(harness.coordinator.workoutState.value)
            assertFalse(successorLease.requiresMachine)
            assertTrue(successorLease.executionId > bodyweightLease.executionId)
            assertTrue(successorLease.executionId > cableLease.executionId)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())

            cableReset.complete(Result.success(Unit))
            runCurrent()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertEquals(successorLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(1, harness.coordinator.currentExerciseIndex.value)
            assertIs<WorkoutState.Countdown>(harness.coordinator.workoutState.value)
            advanceTimeBy(5_100)
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(
                1,
                harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == bodyweightLease.sessionId },
            )
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `same teardown attempt cannot replace its owned job before clear`() {
        val guard = WorkoutExecutionGuard()
        val lease = guard.beginExecution(
            ExecutionSeed(
                sessionId = "owned-job-session",
                profileId = "profile-a",
                requiresMachine = true,
                workingRepTarget = 3,
            ),
        ).getOrThrow()
        val firstJob = Job()
        val replacementJob = Job()

        assertTrue(guard.beginTeardown(lease))
        assertTrue(guard.attachTeardownJob(lease, firstJob))
        firstJob.cancel()

        assertFalse(guard.attachTeardownJob(lease, replacementJob))

        guard.clearTeardownJobIfOwned(lease)
        assertTrue(guard.attachTeardownJob(lease, replacementJob))
        replacementJob.cancel()
    }

    @Test
    fun `teardown is published before reset starts and success restores ready`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetResult = CompletableDeferred<Result<Unit>>()
        var stateAtResetStart: MachineTeardownState? = null
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = {
                stateAtResetStart = harness.activeSessionEngine.machineTeardownState.value
                resetResult.await()
            }

            harness.dwsm.stopAndReturnToSetReady()
            runCurrent()

            assertIs<MachineTeardownState.TearingDown>(stateAtResetStart)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            resetResult.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(
                MachineTeardownState.Ready,
                harness.activeSessionEngine.machineTeardownState.value,
            )
            assertEquals(1, harness.fakeBleRepo.stopPollingCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `new config cannot start until reset succeeds and emits one rejection`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetResult = CompletableDeferred<Result<Unit>>()
        val feedback = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.userFeedbackEvents.collect(feedback::add)
        }
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { resetResult.await() }

            harness.dwsm.stopAndReturnToSetReady()
            runCurrent()
            val configCountBefore = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertEquals(configCountBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(1, feedback.size)

            resetResult.complete(Result.success(Unit))
            advanceUntilIdle()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reset failure requires recovery and always stops polling`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("RESET write failed"))
            }

            harness.dwsm.stopAndReturnToSetReady()
            advanceUntilIdle()

            assertIs<MachineTeardownState.RecoveryRequired>(
                harness.activeSessionEngine.machineTeardownState.value,
            )
            assertEquals(1, harness.fakeBleRepo.stopPollingCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reset timeout after five virtual seconds requires recovery and stops polling`() = runTest {
        val harness = DWSMTestHarness(this)
        val neverCompletes = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { neverCompletes.await() }

            harness.dwsm.stopAndReturnToSetReady()
            runCurrent()
            advanceTimeBy(BleConstants.GATT_OPERATION_TIMEOUT_MS)
            runCurrent()

            assertIs<MachineTeardownState.RecoveryRequired>(
                harness.activeSessionEngine.machineTeardownState.value,
            )
            assertEquals(1, harness.fakeBleRepo.stopPollingCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `disconnect after successful reset requires recovery and stops polling`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = {
                harness.fakeBleRepo.simulateDisconnect()
                Result.success(Unit)
            }

            harness.dwsm.stopAndReturnToSetReady()
            advanceUntilIdle()

            assertIs<MachineTeardownState.RecoveryRequired>(
                harness.activeSessionEngine.machineTeardownState.value,
            )
            assertEquals(1, harness.fakeBleRepo.stopPollingCallCount)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `repeated transition taps cannot create overlapping reset jobs`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetResult = CompletableDeferred<Result<Unit>>()
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 2)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._workoutState.value = WorkoutState.Idle
            harness.fakeBleRepo.stopWorkoutBlock = { resetResult.await() }

            harness.dwsm.jumpToExercise(1)
            harness.dwsm.jumpToExercise(1)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            resetResult.complete(Result.success(Unit))
            runCurrent()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `bodyweight stop set sends no reset and leaves machine ready`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = bodyweightRoutine()
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            harness.dwsm.stopAndReturnToSetReady()
            advanceUntilIdle()

            assertEquals(0, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(
                MachineTeardownState.Ready,
                harness.activeSessionEngine.machineTeardownState.value,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `warmup successor starts only after reset succeeds`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetResult = CompletableDeferred<Result<Unit>>()
        try {
            val routine = warmupRoutine()
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.fakeBleRepo.commandsReceived.clear()
            harness.fakeBleRepo.stopWorkoutBlock = { resetResult.await() }

            harness.activeSessionEngine.handleSetCompletion()
            runCurrent()

            assertEquals(0, harness.coordinator.currentWarmupSetIndex.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            resetResult.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(-1, harness.coordinator.currentWarmupSetIndex.value)
            assertTrue(
                harness.fakeBleRepo.commandsReceived.any { it.firstOrNull() == 0x04.toByte() },
            )
            assertEquals(
                MachineTeardownState.Ready,
                harness.activeSessionEngine.machineTeardownState.value,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `end workout invalidates execution while retained teardown token owns reset`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetResult = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { resetResult.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertNull(harness.activeSessionEngine.executionGuard.currentLease)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            resetResult.complete(Result.success(Unit))
            advanceUntilIdle()
            assertEquals(
                MachineTeardownState.Ready,
                harness.activeSessionEngine.machineTeardownState.value,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `end invalidated teardown drops exercise jump successor while reset is in flight`() = runTest {
        val harness = DWSMTestHarness(this)
        val resetResult = CompletableDeferred<Result<Unit>>()
        try {
            val routine = cableToBodyweightRoutine()
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._workoutState.value = WorkoutState.Idle
            harness.fakeBleRepo.stopWorkoutBlock = { resetResult.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)

            assertNull(harness.activeSessionEngine.executionGuard.currentLease)
            assertIs<MachineTeardownState.TearingDown>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            harness.dwsm.jumpToExercise(1)

            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertNull(harness.activeSessionEngine.executionGuard.currentLease)

            resetResult.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertNull(harness.activeSessionEngine.executionGuard.currentLease)
            assertEquals(
                MachineTeardownState.Ready,
                harness.activeSessionEngine.machineTeardownState.value,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `lease null transition request does not run successor in recovery required`() = runTest {
        val harness = DWSMTestHarness(this)
        var successorCalls = 0
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.startCableSet(targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("RESET write failed"))
            }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertNull(harness.activeSessionEngine.executionGuard.currentLease)
            assertIs<MachineTeardownState.RecoveryRequired>(
                harness.activeSessionEngine.machineTeardownState.value,
            )

            harness.activeSessionEngine.requestTeardownForTransition(TeardownReason.EXERCISE_JUMP) {
                successorCalls++
            }

            assertEquals(0, successorCalls)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `exit reset calls are centralized with pause as documented non-exit exception`() {
        val activeSessionSource = requireNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt",
            ),
        )
        val defaultManagerSource = requireNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt",
            ),
        )
        val routineFlowSource = requireNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt",
            ),
        )

        assertEquals(2, Regex("bleRepository\\.stopWorkout\\(\\)").findAll(activeSessionSource).count())
        assertTrue(activeSessionSource.contains("pause/resume non-exit RESET exception"))
        assertTrue(!defaultManagerSource.contains("bleRepository.stopWorkout()"))
        assertTrue(!routineFlowSource.contains("stopMachineWorkout"))
    }

    private fun bodyweightRoutine(): Routine {
        val exercise = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "teardown-bodyweight-push-up",
        )
        return Routine(
            id = "teardown-bodyweight-routine",
            name = "Bodyweight teardown",
            exercises = listOf(
                RoutineExercise(
                    id = "teardown-bodyweight-set",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 0f,
                ),
            ),
        )
    }

    private fun twoExerciseBodyweightRoutine(): Routine {
        val pushUp = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "teardown-bodyweight-push-up-first",
        )
        val squat = Exercise(
            name = "Bodyweight Squat",
            muscleGroup = "Legs",
            muscleGroups = "Quadriceps,Glutes",
            equipment = "",
            id = "teardown-bodyweight-squat-second",
        )
        return Routine(
            id = "teardown-two-bodyweight-routine",
            name = "Bodyweight while cable reset is pending",
            exercises = listOf(
                RoutineExercise(
                    id = "teardown-bodyweight-first",
                    exercise = pushUp,
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 0f,
                    duration = 1,
                    setRestSeconds = listOf(0),
                ),
                RoutineExercise(
                    id = "teardown-bodyweight-second",
                    exercise = squat,
                    orderIndex = 1,
                    setReps = listOf(12),
                    weightPerCableKg = 0f,
                    duration = 30,
                    setRestSeconds = listOf(0),
                ),
            ),
        )
    }

    private fun cableToBodyweightRoutine(): Routine {
        val bodyweightExercise = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "teardown-transition-push-up",
        )
        return Routine(
            id = "teardown-transition-routine",
            name = "Cable to bodyweight teardown",
            exercises = listOf(
                RoutineExercise(
                    id = "teardown-transition-cable",
                    exercise = TestFixtures.benchPress,
                    orderIndex = 0,
                    setReps = listOf(8),
                    weightPerCableKg = 40f,
                ),
                RoutineExercise(
                    id = "teardown-transition-bodyweight",
                    exercise = bodyweightExercise,
                    orderIndex = 1,
                    setReps = listOf(10),
                    weightPerCableKg = 0f,
                ),
            ),
        )
    }

    private fun warmupRoutine(): Routine = Routine(
        id = "teardown-warmup-routine",
        name = "Teardown warm-up",
        exercises = listOf(
            RoutineExercise(
                id = "teardown-warmup-exercise",
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(8),
                weightPerCableKg = 40f,
                setWeightsPerCableKg = listOf(40f),
                programMode = ProgramMode.OldSchool,
                warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50)),
            ),
        ),
    )
}
