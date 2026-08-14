package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.createTestRoutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Issue673SetEndReasonLifecycleTest {

    @Test
    fun `machine workout complete persists target reps reached`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(harness)
            harness.coordinator._repCount.value = RepCount(
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )

            harness.repCounter.onRepEvent?.invoke(
                RepEvent(
                    type = RepType.WORKOUT_COMPLETE,
                    warmupCount = 0,
                    workingCount = 3,
                ),
            )
            advanceUntilIdle()

            assertEquals(SetEndReason.TARGET_REPS_REACHED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `rep counter stop safety net persists target reps reached`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(harness, targetReps = 3)
            val eventCallback = harness.repCounter.onRepEvent
            harness.repCounter.onRepEvent = null
            harness.repCounter.process(
                repsRomCount = 0,
                repsSetCount = 3,
                repsSetTotal = 3,
                up = 3,
                down = 3,
                posA = 120f,
                posB = 120f,
            )
            harness.repCounter.onRepEvent = eventCallback

            harness.fakeBleRepo.emitMetric(activeMetric())
            advanceUntilIdle()

            assertEquals(SetEndReason.TARGET_REPS_REACHED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `velocity stall countdown persists stall failure`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(harness, stallDetectionEnabled = true)
            seedWorkingReps(harness)

            harness.fakeBleRepo.emitMetric(stalledMetric(position = 120f))
            advanceUntilIdle()
            harness.coordinator.stallStartTime = currentTimeMillis() - 6_000L
            harness.fakeBleRepo.emitMetric(stalledMetric(position = 120f))
            advanceUntilIdle()

            assertEquals(SetEndReason.STALL_FAILURE, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `deload armed velocity countdown persists cable released`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(harness, stallDetectionEnabled = true)
            seedWorkingReps(harness)

            harness.fakeBleRepo.emitDeloadOccurred()
            advanceUntilIdle()
            harness.coordinator.stallStartTime = currentTimeMillis() - 6_000L
            harness.fakeBleRepo.emitMetric(stalledMetric(position = 0f))
            advanceUntilIdle()

            assertEquals(SetEndReason.CABLE_RELEASED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `handles at rest position countdown persists cable released`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(
                harness = harness,
                stallDetectionEnabled = false,
                isAmrap = true,
            )
            seedWorkingReps(harness, rangeBottom = 100f)
            harness.coordinator.workoutStartTime = currentTimeMillis() - 10_000L
            harness.coordinator.autoStopStartTime = currentTimeMillis() - 10_000L

            harness.fakeBleRepo.emitMetric(stalledMetric(position = 0f))
            advanceUntilIdle()

            assertEquals(SetEndReason.CABLE_RELEASED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `danger zone cable release countdown persists cable released`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(
                harness = harness,
                stallDetectionEnabled = false,
                isAmrap = true,
            )
            seedWorkingReps(harness, rangeBottom = 100f)
            harness.activeSessionEngine.primeDangerZoneCountdownForTest(
                lease = lease,
                startTimeMs = currentTimeMillis() - 10_000L,
            )

            harness.fakeBleRepo.emitMetric(
                activeMetric().copy(
                    positionA = 105f,
                    positionB = 500f,
                ),
            )
            advanceUntilIdle()

            assertEquals(SetEndReason.CABLE_RELEASED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `danger zone countdown override cannot cross executions`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val leaseA = startTrackedCableSet(
                harness = harness,
                stallDetectionEnabled = false,
                isAmrap = true,
            )
            harness.activeSessionEngine.primeDangerZoneCountdownForTest(
                lease = leaseA,
                startTimeMs = currentTimeMillis() - 10_000L,
            )
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val leaseB = startTrackedCableSet(
                harness = harness,
                stallDetectionEnabled = false,
                isAmrap = true,
            )
            seedWorkingReps(harness, rangeBottom = 100f)
            harness.coordinator.workoutStartTime = currentTimeMillis() - 10_000L
            harness.activeSessionEngine.primeDangerZoneCountdownForTest(
                lease = leaseA,
                startTimeMs = currentTimeMillis() - 10_000L,
            )

            harness.fakeBleRepo.emitMetric(
                activeMetric().copy(
                    positionA = 105f,
                    positionB = 500f,
                ),
            )
            runCurrent()

            assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(emptyList(), harness.fakeCompletedSetRepo.getCompletedSets(leaseB.sessionId))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `VBT velocity loss auto end persists VBT auto end`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveProfilePreferences(
                UserPreferences(
                    vbtEnabled = true,
                    velocityLossThresholdPercent = 20,
                    autoEndOnVelocityLoss = true,
                ),
            )
            advanceUntilIdle()
            val lease = startTrackedCableSet(harness, targetReps = 10)
            harness.coordinator._repCount.value = RepCount(
                workingReps = 4,
                totalReps = 4,
                isWarmupComplete = true,
            )

            processVbtRep(harness, repNumber = 1, velocityMmS = 100.0)
            processVbtRep(harness, repNumber = 2, velocityMmS = 70.0)
            processVbtRep(harness, repNumber = 3, velocityMmS = 60.0)
            advanceUntilIdle()

            assertEquals(SetEndReason.VBT_AUTO_END, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout persists user stopped`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startTrackedCableSet(harness)
            harness.coordinator._repCount.value = RepCount(
                workingReps = 2,
                totalReps = 2,
                isWarmupComplete = true,
            )

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(SetEndReason.USER_STOPPED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stop and return with performed routine reps persists user stopped`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(
                workingReps = 2,
                totalReps = 2,
                isWarmupComplete = true,
            )

            harness.dwsm.stopAndReturnToSetReady()
            advanceUntilIdle()

            assertEquals(SetEndReason.USER_STOPPED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `timed cable completion persists timer expired`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = timedCableRoutine(durationSeconds = 1)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 3,
                workingReps = 2,
                totalReps = 5,
                isWarmupComplete = true,
            )

            runCurrent()
            advanceTimeBy(1_100L)
            advanceUntilIdle()

            assertEquals(SetEndReason.TIMER_EXPIRED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `timed bodyweight confirmation persists its originating timer reason`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = bodyweightRoutine(durationSeconds = 1)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            advanceTimeBy(1_100L)
            runCurrent()
            val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.coordinator.workoutState.value)
            harness.dwsm.confirmBodyweightSetResult(reps = 8, variant = entry.selectedVariant)
            advanceUntilIdle()

            assertEquals(SetEndReason.TIMER_EXPIRED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `bodyweight confirmation persists the immutable originating reason`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = bodyweightRoutine(durationSeconds = 60)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.USER_STOPPED)
            advanceUntilIdle()
            val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.coordinator.workoutState.value)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()
            harness.dwsm.confirmBodyweightSetResult(reps = 6, variant = entry.selectedVariant)
            advanceUntilIdle()
            harness.dwsm.confirmBodyweightSetResult(reps = 99, variant = entry.selectedVariant)
            advanceUntilIdle()

            assertEquals(SetEndReason.USER_STOPPED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `bodyweight confirmation cannot expose its origin to a competing completion`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = bodyweightRoutine(durationSeconds = 60)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.USER_STOPPED)
            advanceUntilIdle()
            val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.coordinator.workoutState.value)

            harness.coordinator.setCompletionInProgress.value = true
            harness.dwsm.confirmBodyweightSetResult(reps = 6, variant = entry.selectedVariant)
            harness.coordinator.setCompletionInProgress.value = false
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            harness.dwsm.confirmBodyweightSetResult(reps = 6, variant = entry.selectedVariant)
            advanceUntilIdle()

            assertEquals(SetEndReason.USER_STOPPED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale bodyweight confirmation cannot complete a replacement execution`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = bodyweightRoutine(durationSeconds = 60)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(leaseA, SetEndReason.TIMER_EXPIRED)
            advanceUntilIdle()
            val staleEntry = assertIs<WorkoutState.BodyweightRepEntry>(harness.coordinator.workoutState.value)
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val leaseB = startTrackedCableSet(harness)
            harness.dwsm.confirmBodyweightSetResult(reps = 12, variant = staleEntry.selectedVariant)
            advanceUntilIdle()

            assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(emptyList(), harness.fakeCompletedSetRepo.getCompletedSets(leaseA.sessionId))
            assertEquals(emptyList(), harness.fakeCompletedSetRepo.getCompletedSets(leaseB.sessionId))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `untagged positive-rep non Just Lift completion does not persist a completed set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    warmupReps = 0,
                    weightPerCableKg = 25f,
                    isJustLift = false,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
            assertNull(session.exerciseId)
            assertEquals(emptyList(), harness.fakeCompletedSetRepo.getCompletedSets(session.id))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `untagged positive-rep Just Lift completion persists completed set before later tagging`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    warmupReps = 0,
                    weightPerCableKg = 25f,
                    isJustLift = true,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
            assertNull(session.exerciseId)
            val captured = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single()
            assertEquals(3, captured.actualReps)
            assertEquals(SetEndReason.USER_STOPPED, captured.setEndReason)

            harness.dwsm.tagJustLiftSessionExercise(session.id, TestFixtures.deadlift, isAmrap = false)
            advanceUntilIdle()

            val afterTagging = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single()
            assertEquals(captured.id, afterTagging.id)
            assertEquals(SetEndReason.USER_STOPPED, afterTagging.setEndReason)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun startTrackedCableSet(
        harness: DWSMTestHarness,
        targetReps: Int = 8,
        stallDetectionEnabled: Boolean = false,
        isAmrap: Boolean = false,
    ): ExecutionLease {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = targetReps,
                warmupReps = 0,
                weightPerCableKg = 25f,
                stallDetectionEnabled = stallDetectionEnabled,
                isAMRAP = isAmrap,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private fun seedWorkingReps(
        harness: DWSMTestHarness,
        workingReps: Int = 2,
        rangeBottom: Float = 0f,
    ) {
        harness.repCounter.seedRomBoundaries(rangeTop = 800f, rangeBottom = rangeBottom)
        harness.repCounter.process(
            repsRomCount = 0,
            repsSetCount = workingReps,
            repsSetTotal = 8,
            up = workingReps,
            down = workingReps,
            posA = 120f,
            posB = 120f,
        )
        if (rangeBottom > 0f) {
            harness.repCounter.updatePositionRangesContinuously(rangeBottom, rangeBottom)
            harness.repCounter.updatePositionRangesContinuously(800f, 800f)
        }
        harness.coordinator._repCount.value = harness.repCounter.getRepCount()
    }

    private suspend fun processVbtRep(
        harness: DWSMTestHarness,
        repNumber: Int,
        velocityMmS: Double,
    ) {
        val metrics = List(4) { index ->
            WorkoutMetric(
                timestamp = repNumber * 1_000L + index,
                loadA = 20f,
                loadB = 20f,
                positionA = index * 50f,
                positionB = index * 50f,
                velocityA = velocityMmS,
                velocityB = velocityMmS,
            )
        }
        harness.coordinator.biomechanicsEngine.processRep(
            repNumber = repNumber,
            concentricMetrics = metrics,
            allRepMetrics = metrics,
            timestamp = repNumber * 1_000L,
        )
        harness.activeSessionEngine.evaluateLatestVbtResult(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
        )
    }

    private suspend fun persistedReason(
        harness: DWSMTestHarness,
        lease: ExecutionLease,
    ): SetEndReason = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single().setEndReason

    private fun activeMetric() = WorkoutMetric(
        positionA = 120f,
        positionB = 120f,
        velocityA = 80.0,
        velocityB = 80.0,
        loadA = 10f,
        loadB = 10f,
    )

    private fun stalledMetric(position: Float) = WorkoutMetric(
        positionA = position,
        positionB = position,
        velocityA = 0.0,
        velocityB = 0.0,
        loadA = 10f,
        loadB = 10f,
    )

    private fun timedCableRoutine(durationSeconds: Int) = Routine(
        id = "issue-673-timed-cable",
        name = "Issue 673 Timed Cable",
        exercises = listOf(
            RoutineExercise(
                id = "issue-673-timed-bench",
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(10),
                weightPerCableKg = 25f,
                duration = durationSeconds,
                setRestSeconds = listOf(0),
            ),
        ),
    )

    private fun bodyweightRoutine(durationSeconds: Int): Routine {
        val exercise = Exercise(
            id = "issue-673-push-up",
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
        )
        return Routine(
            id = "issue-673-bodyweight",
            name = "Issue 673 Bodyweight",
            exercises = listOf(
                RoutineExercise(
                    id = "issue-673-bodyweight-push-up",
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
}
