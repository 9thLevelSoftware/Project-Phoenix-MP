package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepQualityScore
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.CommandLimits
import com.devil.phoenixproject.util.Constants
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class WorkoutExitPersistenceTest {

    @Test
    fun `routine completion preserves start key after set index mutation`() = runTest {
        assertRoutineAttemptIdentityAfterMutation(mutateSetIndex = true, mutateSetKind = false)
    }

    @Test
    fun `routine completion preserves start key after set kind mutation`() = runTest {
        assertRoutineAttemptIdentityAfterMutation(mutateSetIndex = false, mutateSetKind = true)
    }

    private suspend fun TestScope.assertRoutineAttemptIdentityAfterMutation(
        mutateSetIndex: Boolean,
        mutateSetKind: Boolean,
    ) {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 1,
                repsPerSet = 3,
            ).copy(id = "attempt-routine", name = "attempt-routine")
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            val routineSessionId = "routine-session-attempt"
            harness.coordinator.currentRoutineSessionId = routineSessionId
            harness.coordinator.currentRoutineId = routine.id
            harness.coordinator.currentRoutineName = routine.name
            val originalKey = LogicalSetKey(
                routineSessionId = routineSessionId,
                routineExerciseId = routine.exercises.single().id,
                setIndex = 0,
                setKind = SetType.STANDARD,
            )
            listOf(1, 2).forEach { attempt ->
                val sessionId = "historical-attempt-$attempt"
                harness.fakeCompletedSetRepo.setSessionRoutine(sessionId, routineSessionId)
                harness.fakeCompletedSetRepo.saveCompletedSet(
                    CompletedSet(
                        id = "historical-set-$attempt",
                        sessionId = sessionId,
                        plannedSetId = null,
                        setNumber = originalKey.setIndex,
                        setType = originalKey.setKind,
                        actualReps = 3,
                        actualWeightKg = 25f,
                        loggedRpe = null,
                        isPr = false,
                        completedAt = attempt.toLong(),
                        routineExerciseId = originalKey.routineExerciseId,
                        attemptNumber = attempt,
                    ),
                )
            }

            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeCompletedSetRepo.setSessionRoutine(lease.sessionId, routineSessionId)
            harness.coordinator._loadedRoutine.value = routine.copy(
                exercises = listOf(routine.exercises.single().copy(id = "mutated-occurrence")),
            )
            if (mutateSetIndex) harness.coordinator._currentSetIndex.value = 1
            if (mutateSetKind) {
                harness.coordinator._workoutParameters.value = harness.coordinator._workoutParameters.value.copy(
                    isAMRAP = true,
                )
            }
            harness.coordinator._repCount.value = RepCount(workingReps = 2)

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()

            val persisted = harness.fakeCompletedSetRepo.saved.single { it.sessionId == lease.sessionId }
            assertEquals(originalKey.routineExerciseId, persisted.routineExerciseId)
            assertEquals(originalKey.setIndex, persisted.setNumber)
            assertEquals(originalKey.setKind, persisted.setType)
            assertEquals(3, persisted.attemptNumber)
            assertTrue(harness.fakeCompletedSetRepo.isAttemptDurable(lease.sessionId, originalKey, 3))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `zero-rep stall failure persists durable attempt identity`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 1,
                repsPerSet = 3,
            ).copy(id = "zero-rep-stall", name = "zero-rep-stall")
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val routineSessionId = assertNotNull(harness.coordinator.currentRoutineSessionId)
            harness.fakeCompletedSetRepo.setSessionRoutine(lease.sessionId, routineSessionId)
            harness.coordinator._repCount.value = RepCount(workingReps = 0, hasPendingRep = true)

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            advanceUntilIdle()

            val persisted = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single()
            assertEquals(0, persisted.actualReps)
            assertEquals(SetEndReason.STALL_FAILURE, persisted.setEndReason)
            assertEquals(routine.exercises.single().id, persisted.routineExerciseId)
            assertFalse(persisted.isPr)
            val key = LogicalSetKey(
                routineSessionId = routineSessionId,
                routineExerciseId = routine.exercises.single().id,
                setIndex = 0,
                setKind = SetType.STANDARD,
            )
            assertTrue(harness.fakeCompletedSetRepo.isAttemptDurable(lease.sessionId, key, 1))
            val session = harness.fakeWorkoutRepo.saveSessionAttempts.single { it.id == lease.sessionId }
            assertEquals(0, session.workingReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `manual stop persists immutable snapshot when RESET fails`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { Result.failure(IllegalStateException("reset failed")) }

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
            assertIs<MachineTeardownState.RecoveryRequired>(harness.activeSessionEngine.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `manual stop persists immutable snapshot when RESET times out`() = runTest {
        val harness = DWSMTestHarness(this)
        val neverReset = CompletableDeferred<Result<Unit>>()
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { neverReset.await() }

            harness.dwsm.stopWorkout(exitingWorkout = false)
            runCurrent()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            advanceTimeBy(BleConstants.GATT_OPERATION_TIMEOUT_MS)
            runCurrent()
            assertIs<MachineTeardownState.RecoveryRequired>(harness.activeSessionEngine.machineTeardownState.value)
        } finally {
            neverReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `automatic completion persists immutable snapshot when RESET fails`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { Result.failure(IllegalStateException("reset failed")) }

            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
            assertIs<MachineTeardownState.RecoveryRequired>(harness.activeSessionEngine.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `automatic completion persists immutable snapshot when RESET times out`() = runTest {
        val harness = DWSMTestHarness(this)
        val neverReset = CompletableDeferred<Result<Unit>>()
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { neverReset.await() }

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            advanceTimeBy(BleConstants.GATT_OPERATION_TIMEOUT_MS)
            runCurrent()
            assertIs<MachineTeardownState.RecoveryRequired>(harness.activeSessionEngine.machineTeardownState.value)
        } finally {
            neverReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `automatic completion claiming first prevents End Workout duplicate writes`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedCableSet(harness)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.size)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.size)

            releaseSave.complete(Unit)
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(saved.id, harness.fakeCompletedSetRepo.saved.single().sessionId)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout claiming first suppresses suspended automatic completion persistence`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(lease.sessionId, saved.id)

            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `profile switch before suspended exit save keeps origin attribution`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-a")
            startTrackedCableSet(harness)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-b")
            runCurrent()

            releaseSave.complete(Unit)
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals("profile-a", saved.profileId)
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == saved.id })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == saved.id })
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `automatic completion keeps A attribution and cannot overwrite B after profile switch`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        val releaseSave = CompletableDeferred<Unit>()
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-a")
            harness.setActiveSummaryCountdownSeconds(5)
            startTrackedCableSet(harness)
            harness.coordinator.repQualityScorer.scoreRep(repMetric())
            harness.gamificationManager.processSetQualityEvent(90, "profile-a")
            harness.gamificationManager.processSetQualityEvent(90, "profile-a")
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }
            harness.fakeGamificationRepo.badgeLookupProfileIds.clear()
            harness.fakeGamificationRepo.updateStatsProfileIds.clear()

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()
            harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-b")
            harness.setActiveSummaryCountdownSeconds(5)
            releaseSave.complete(Unit)
            runCurrent()
            releaseReset.complete(Result.success(Unit))
            runCurrent()

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            startTrackedCableSet(harness)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            advanceUntilIdle()

            assertEquals("profile-b", leaseB.profileId)
            assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeGamificationRepo.badgeLookupProfileIds.isNotEmpty())
            assertTrue(harness.fakeGamificationRepo.badgeLookupProfileIds.all { it == "profile-a" })
            assertTrue(harness.fakeGamificationRepo.updateStatsProfileIds.isNotEmpty())
            assertTrue(harness.fakeGamificationRepo.updateStatsProfileIds.all { it == "profile-a" })
        } finally {
            releaseReset.complete(Result.success(Unit))
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout invalidation suppresses delayed automatic presentation`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            startTrackedCableSet(harness)
            harness.coordinator.repQualityScorer.scoreRep(repMetric())
            harness.gamificationManager.processSetQualityEvent(90, "default")
            harness.gamificationManager.processSetQualityEvent(90, "default")
            harness.fakeGamificationRepo.badgeLookupProfileIds.clear()
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)

            releaseReset.complete(Result.success(Unit))
            advanceUntilIdle()

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeGamificationRepo.badgeLookupProfileIds.isEmpty())
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `delayed routine A persistence cannot mutate active routine B bookkeeping`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedRoutineSet(harness, routineId = "routine-a")
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)

            startTrackedRoutineSet(harness, routineId = "routine-b")
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator.routineAccumulatedCalories = 77f
            harness.coordinator._completedRoutineSetKeys.value = setOf(4 to 5)

            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertNotEquals(leaseA.sessionId, leaseB.sessionId)
            assertEquals(77f, harness.coordinator.routineAccumulatedCalories)
            assertEquals(setOf(4 to 5), harness.coordinator._completedRoutineSetKeys.value)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `persistence for execution A remains claimed while safe execution B starts`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedRoutineCableSet(harness, "routine-a")
            advanceTimeBy(1_000)
            harness.coordinator.collectedMetrics.seedAll(biomechanicsMetrics())
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertIs<PersistenceClaimResult.DuplicateInProgress>(
                harness.activeSessionEngine.executionGuard.claimPersistence(
                    leaseA.sessionId,
                    TerminalPath.AUTO_COMPLETE,
                ),
            )

            startTrackedRoutineCableSet(harness, "routine-b")
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val bCompletedKeys = setOf(7 to 9)
            harness.coordinator.routineAccumulatedCalories = 42f
            harness.coordinator._completedRoutineSetKeys.value = bCompletedKeys
            harness.coordinator.activeCycleId = "cycle-b"
            harness.coordinator.activeCycleDayNumber = 4

            assertNotEquals(leaseA.sessionId, leaseB.sessionId)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertEquals(42f, harness.coordinator.routineAccumulatedCalories)
            assertEquals(bCompletedKeys, harness.coordinator._completedRoutineSetKeys.value)
            assertEquals("cycle-b", harness.coordinator.activeCycleId)
            assertEquals(4, harness.coordinator.activeCycleDayNumber)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `cycle update uses capture-time cycle and day after active cycle changes`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            val cycleA = trainingCycle("cycle-a")
            val cycleB = trainingCycle("cycle-b")
            harness.fakeTrainingCycleRepo.addCycle(cycleA)
            harness.fakeTrainingCycleRepo.addCycle(cycleB)
            harness.fakeTrainingCycleRepo.setActiveCycle(cycleA.id, "default")
            harness.coordinator.activeCycleId = cycleA.id
            harness.coordinator.activeCycleDayNumber = 1
            startTrackedCableSet(harness)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            harness.fakeTrainingCycleRepo.setActiveCycle(cycleB.id, "default")
            harness.coordinator.activeCycleId = cycleB.id
            harness.coordinator.activeCycleDayNumber = 2
            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertEquals(setOf(1), harness.fakeTrainingCycleRepo.getCycleProgress(cycleA.id)?.completedDays)
            assertTrue(harness.fakeTrainingCycleRepo.getCycleProgress(cycleB.id)?.completedDays.orEmpty().isEmpty())
            assertEquals(cycleB.id, harness.coordinator.activeCycleId)
            assertEquals(2, harness.coordinator.activeCycleDayNumber)
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `cycle cancellation reopens the claim and retains the immutable snapshot`() = runTest {
        val harness = DWSMTestHarness(this)
        val cycle = trainingCycle("cycle-cancel")
        var cancelOnce = true
        try {
            harness.fakeTrainingCycleRepo.addCycle(cycle)
            harness.fakeTrainingCycleRepo.setActiveCycle(cycle.id, "default")
            harness.coordinator.activeCycleId = cycle.id
            harness.coordinator.activeCycleDayNumber = 1
            harness.fakeTrainingCycleRepo.beforeUpdateCycleProgress = {
                if (cancelOnce) {
                    cancelOnce = false
                    throw CancellationException("cancel cycle persistence")
                }
            }
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertTrue(harness.fakeTrainingCycleRepo.getCycleProgress(cycle.id)?.completedDays.orEmpty().isEmpty())
            assertTrue(harness.activeSessionEngine.retryWorkoutExitPersistence(lease.sessionId))
            advanceUntilIdle()

            assertEquals(2, harness.fakeTrainingCycleRepo.updateCycleProgressAttempts.size)
            assertEquals(setOf(1), harness.fakeTrainingCycleRepo.getCycleProgress(cycle.id)?.completedDays)
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `post save hook cancellation reopens non cycle snapshot claim for stable retry`() = runTest {
        val hookEntered = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val hookInputs = mutableListOf<Pair<String, String>>()
        var hookAttempts = 0
        val harness = DWSMTestHarness(this) { exerciseId, profileId, _ ->
            hookAttempts++
            hookInputs += exerciseId to profileId
            if (hookAttempts == 1) {
                hookEntered.complete(Unit)
                releaseCancellation.await()
                throw CancellationException("cancel suspended post-save hook")
            }
        }
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertTrue(hookEntered.isCompleted)
            val savedSession = harness.fakeWorkoutRepo.saveSessionAttempts.single {
                it.id == lease.sessionId
            }
            val savedCompletedSet = harness.fakeCompletedSetRepo.saved.single {
                it.sessionId == lease.sessionId
            }

            releaseCancellation.complete(Unit)
            runCurrent()

            harness.coordinator._workoutParameters.value = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 99,
                weightPerCableKg = 99f,
                selectedExerciseId = "replacement-exercise",
            )
            assertTrue(
                harness.activeSessionEngine.retryWorkoutExitPersistence(lease.sessionId),
                "Cancellation must reopen the persistence claim and retain its snapshot",
            )
            advanceUntilIdle()

            assertEquals(2, hookAttempts)
            assertEquals(
                listOf(
                    TestFixtures.benchPress.id to lease.profileId,
                    TestFixtures.benchPress.id to lease.profileId,
                ),
                hookInputs,
            )
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(savedSession, harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId })
            assertEquals(
                1,
                harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count {
                    it.sessionId == lease.sessionId && it.id == savedCompletedSet.id
                },
            )
            assertTrue(!harness.activeSessionEngine.retryWorkoutExitPersistence(lease.sessionId))
        } finally {
            releaseCancellation.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `retry after later failure does not duplicate raw workout metrics`() = runTest {
        val harness = DWSMTestHarness(this)
        var failOnce = true
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val rawMetrics = biomechanicsMetrics()
            harness.coordinator.collectedMetrics.seedAll(rawMetrics)
            harness.fakeCompletedSetRepo.afterSaveCompletedSet = {
                if (failOnce) {
                    failOnce = false
                    error("forced failure after raw metrics write")
                }
            }

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            advanceUntilIdle()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(rawMetrics, harness.fakeWorkoutRepo.metricsForSession(lease.sessionId))
            assertEquals(
                2,
                harness.fakeWorkoutRepo.committedMetricSnapshots.count { it.first == lease.sessionId },
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `racing terminal captures install one stable CompletedSet identity`() = runTest {
        val lease = ExecutionLease(
            executionId = 1L,
            sessionId = "stable-session",
            profileId = "profile-a",
            requiresMachine = true,
            workingRepTarget = 3,
            isBodyweight = false,
            isJustLift = false,
            isAmrap = false,
            isTimedCable = false,
        )
        val snapshotStore = WorkoutExitSnapshotStore()
        val readyBuilders = atomic(0)

        val snapshots = withContext(Dispatchers.Default) {
            listOf(
                TerminalPath.AUTO_COMPLETE to SetEndReason.STALL_FAILURE,
                TerminalPath.END_WORKOUT to SetEndReason.USER_STOPPED,
            ).mapIndexed { index, (path, reason) ->
                async {
                    val completion = completionFixture(lease, reason)
                    snapshotStore.getOrCapture(completion, path) {
                        readyBuilders.incrementAndGet()
                        while (readyBuilders.value < 2) {
                            // Force both terminal paths to build before either can install.
                        }
                        exitSnapshot(completion, path, completedSetId = "set-${index + 1}")
                    }
                }
            }.awaitAll()
        }

        assertEquals(setOf(lease.sessionId), snapshots.map { it.session.id }.toSet())
        assertEquals(1, snapshots.mapNotNull { it.completedSet?.id }.distinct().size)
        assertEquals(1, snapshots.map { it.completion.reason }.distinct().size)
        assertEquals(1, snapshots.mapNotNull { it.completedSet?.setEndReason }.distinct().size)
    }

    @Test
    fun `bodyweight completion gate rejects a stale A publication after B begins`() {
        val leaseA = executionLease(executionId = 1L, sessionId = "bodyweight-a")
        val leaseB = executionLease(executionId = 2L, sessionId = "bodyweight-b")
        val completionA = completionFixture(leaseA, SetEndReason.USER_STOPPED)
        val completionB = completionFixture(leaseB, SetEndReason.TIMER_EXPIRED)
        val gate = BodyweightCompletionGate()

        gate.beginExecution(leaseA)
        gate.invalidate(leaseA)
        gate.beginExecution(leaseB)
        gate.beginExecution(leaseA)

        assertFalse(gate.tryPublish(completionA))
        assertNull(gate.pendingFor(leaseB))
        assertTrue(gate.tryPublish(completionB))
        assertEquals(completionB, gate.pendingFor(leaseB))
        assertTrue(gate.tryConsume(completionB))
        assertFalse(gate.tryConsume(completionB))
    }

    @Test
    fun `danger countdown gate preserves newer B against delayed A prime and clear`() {
        val leaseA = executionLease(executionId = 1L, sessionId = "danger-a")
        val leaseB = executionLease(executionId = 2L, sessionId = "danger-b")
        val gate = DangerZoneCountdownGate()

        assertTrue(gate.tryPrime(leaseA, startTimeMs = 100L))
        assertTrue(gate.tryPrime(leaseB, startTimeMs = 200L))
        assertFalse(gate.tryPrime(leaseA, startTimeMs = 300L))
        gate.clear(leaseA)

        assertEquals(200L, gate.consume(leaseB))
        assertNull(gate.consume(leaseB))
    }

    @Test
    fun `starting B automatically retries failed A with its stable identities`() = runTest {
        val harness = DWSMTestHarness(this)
        var failedCompletedSetId: String? = null
        try {
            startTrackedCableSet(harness)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator.setRepMetrics.seed(repMetric())
            harness.fakeCompletedSetRepo.afterSaveCompletedSet = { completedSet ->
                if (completedSet.sessionId == leaseA.sessionId && failedCompletedSetId == null) {
                    failedCompletedSetId = completedSet.id
                    error("forced failure after CompletedSet insert")
                }
            }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)
            assertTrue(harness.fakeRepMetricRepo.getRepMetrics(leaseA.sessionId).isEmpty())

            startTrackedCableSet(harness)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            advanceUntilIdle()

            assertNotEquals(leaseA.sessionId, leaseB.sessionId)
            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == leaseA.sessionId })
            val savedASet = harness.fakeCompletedSetRepo.saved.single { it.sessionId == leaseA.sessionId }
            assertEquals(failedCompletedSetId, savedASet.id)
            val savedRep = harness.fakeRepMetricRepo.getRepMetrics(leaseA.sessionId).single()
            assertEquals(1, savedRep.repNumber)
            assertContentEquals(floatArrayOf(10f, 20f), savedRep.concentricPositions)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `successful snapshots and claims prune together while failed A survives beyond 32`() = runTest {
        val harness = DWSMTestHarness(this)
        val successfulSessionIds = mutableListOf<String>()
        try {
            startTrackedCableSet(harness)
            val failedLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { session ->
                if (session.id == failedLease.sessionId) error("keep A failed")
            }
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            repeat(34) { index ->
                startTrackedCableSet(harness)
                val successfulLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
                successfulSessionIds += successfulLease.sessionId
                harness.coordinator.collectedMetrics.seed(
                    WorkoutMetric(
                        timestamp = 1_000L + index,
                        loadA = 20f,
                        loadB = 21f,
                        positionA = 100f,
                        positionB = 101f,
                        velocityA = 1.0,
                        velocityB = 1.1,
                    ),
                )
                harness.dwsm.stopWorkout(exitingWorkout = true)
                advanceUntilIdle()
            }

            assertTrue(
                successfulSessionIds.all { sessionId ->
                    harness.fakeWorkoutRepo.committedMetricSnapshots.count { it.first == sessionId } == 1
                },
            )

            harness.fakeWorkoutRepo.beforeSaveSession = {}
            startTrackedCableSet(harness)
            val activeLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == failedLease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == failedLease.sessionId })
            assertIs<PersistenceClaimResult.Claimed>(
                harness.activeSessionEngine.executionGuard.claimPersistence(
                    successfulSessionIds.first(),
                    TerminalPath.AUTO_COMPLETE,
                ),
            )
            assertIs<PersistenceClaimResult.AlreadyPersisted>(
                harness.activeSessionEngine.executionGuard.claimPersistence(
                    successfulSessionIds.last(),
                    TerminalPath.AUTO_COMPLETE,
                ),
            )
            assertEquals(activeLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry after session partial write leaves one stable session and completed set`() = runTest {
        val harness = DWSMTestHarness(this)
        var failOnce = true
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.afterSaveSession = {
                if (failOnce) {
                    failOnce = false
                    error("forced failure after session insert")
                }
            }

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.activeSessionEngine.machineTeardownState.value)

            assertTrue(harness.activeSessionEngine.retryWorkoutExitPersistence(lease.sessionId))
            advanceUntilIdle()

            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `terminal capture deep copies rep metric and biomechanics arrays`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            startTrackedCableSet(harness)
            val repMetric = repMetric()
            val biomechanicsResult = harness.coordinator.biomechanicsEngine.processRep(
                repNumber = 1,
                concentricMetrics = biomechanicsMetrics(),
                allRepMetrics = biomechanicsMetrics(),
                timestamp = harness.nowMs,
            )
            val expectedRepPositions = repMetric.concentricPositions.copyOf()
            val expectedForces = biomechanicsResult.forceCurve.normalizedForceN.copyOf()
            harness.coordinator.setRepMetrics.seed(repMetric)
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)

            repMetric.concentricPositions.fill(-999f)
            biomechanicsResult.forceCurve.normalizedForceN.fill(-999f)
            runCurrent()
            releaseSave.complete(Unit)
            advanceUntilIdle()

            val sessionId = harness.fakeWorkoutRepo.saveSessionAttempts.single().id
            assertContentEquals(
                expectedRepPositions,
                harness.fakeRepMetricRepo.savedMetrics.getValue(sessionId).single().concentricPositions,
            )
            assertContentEquals(
                expectedForces,
                harness.fakeBiomechanicsRepo.savedBiomechanics.getValue(sessionId).single().forceCurve.normalizedForceN,
            )
        } finally {
            releaseSave.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `terminal summary copy isolates quality score list`() {
        val sourceScores = mutableListOf(
            RepQualityScore(
                composite = 90,
                romScore = 27f,
                velocityScore = 22f,
                eccentricControlScore = 23f,
                smoothnessScore = 18f,
                repNumber = 1,
            ),
        )
        val summary = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 25f,
            avgLoadKgPerCable = 25f,
            repCount = 1,
            qualitySummary = SetQualitySummary(
                averageScore = 90,
                bestScore = 90,
                worstScore = 90,
                bestRepNumber = 1,
                worstRepNumber = 1,
                trend = QualityTrend.STABLE,
                repScores = sourceScores,
            ),
        )

        val snapshot = summary.deepCopyForExitSnapshot()
        sourceScores.clear()

        assertEquals(1, snapshot.qualitySummary?.repScores?.size)
    }

    /**
     * F-058: `is_pr` is a data fact, not a display preference. With gamification
     * off nothing celebrates, but the PR rows are still written and History's PR
     * marker must still appear.
     */
    @Test
    fun `a weight PR still marks the completed set when gamification is off`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.settingsManager.setGamificationEnabled(false)
            advanceUntilIdle()
            assertFalse(
                harness.settingsManager.gamificationEnabled.value,
                "The fixture must actually turn gamification off",
            )
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val savedSet = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single()
            assertTrue(
                harness.fakePRRepo.updateCalls.isNotEmpty(),
                "PR evaluation must run regardless of the gamification toggle",
            )
            assertTrue(savedSet.isPr, "A broken weight PR must mark the set even with gamification off")
        } finally {
            harness.cleanup()
        }
    }

    /**
     * F-021: the PR is stamped with the session's own timestamp, not the
     * post-save wall clock, so the portal push key `"$exerciseId:$timestamp"`
     * can match it back to the session that set it.
     */
    @Test
    fun `a PR from a completed set carries the session timestamp`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            // The engine stamps the start from the real clock but saves on the harness clock;
            // put the start on the harness clock so the set spans the 45 s advanced below.
            harness.coordinator.workoutStartTime = harness.nowMs
            advanceTimeBy(45_000L)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            val prTimestamps = harness.fakePRRepo.updateCalls.map { it.timestamp }.distinct()
            assertTrue(session.duration > 0L, "The fixture must span real time, or the two stamps cannot differ")
            assertEquals(
                listOf(session.timestamp),
                prTimestamps,
                "Every PR written for this set must carry the session's timestamp",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * F-040: post-save bookkeeping runs on top of a workout that is already
     * durable, so its failure must not claim the workout was not saved.
     */
    @Test
    fun `a post save failure does not report that the workout could not be saved`() = runTest {
        val harness = DWSMTestHarness(this)
        val feedback = mutableListOf<String>()
        try {
            harness.fakeCompletedSetRepo.beforeMarkAsPr = {
                throw IllegalStateException("post-save bookkeeping failed")
            }
            val collector = launch(Dispatchers.Unconfined) {
                harness.coordinator.userFeedbackEvents.collect(feedback::add)
            }
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            collector.cancel()

            assertNull(
                harness.coordinator.workoutSaveFailureSessionId.value,
                "A post-save failure must not raise a save failure",
            )
            assertTrue(feedback.isEmpty(), "A post-save failure must not emit a user-facing save error")
            assertEquals(
                1,
                harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId },
                "The workout itself must be saved",
            )
            assertEquals(1, harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).size)
            assertFalse(
                harness.activeSessionEngine.hasRetainedWorkoutExitSnapshotForTest(lease.sessionId),
                "A committed set must not be retained for retry",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * The other half of F-040: when the COMMIT itself fails the user is told, and
     * the failure names the session so the UI can offer a Retry that works.
     */
    @Test
    fun `a commit failure raises a retryable save failure`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeWorkoutRepo.beforeSaveSession = {
                throw IllegalStateException("disk full")
            }
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(
                lease.sessionId,
                harness.coordinator.workoutSaveFailureSessionId.value,
                "A failed commit must name the session the Retry action needs",
            )
            harness.fakeWorkoutRepo.beforeSaveSession = {}
            assertTrue(
                harness.activeSessionEngine.retryWorkoutExitPersistence(lease.sessionId),
                "The named session must still be retryable",
            )
            advanceUntilIdle()
            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
            assertNull(
                harness.coordinator.workoutSaveFailureSessionId.value,
                "A commit that later succeeds must withdraw its own failure offer, or the " +
                    "screen tells the user a saved set was lost",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * A completed set records the load the machine was COMMANDED to hold. A
     * request above the hardware maximum is refused before the set can start,
     * so it must not appear in the session row, the CompletedSet or the volume
     * PR input — all three read the same commanded figure.
     *
     * The out-of-range request is the set's own start load. An active-set edit
     * of `_workoutParameters` is for the NEXT set and must not rewrite this
     * completion (`withExecutedCommand` freezes the start metadata), so a
     * mid-set mutation would not reach the recorder at all.
     */
    @Test
    fun `a set records the commanded load rather than an out-of-range request`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness, weightPerCableKg = 500f)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            // The CONNECTED model's ceiling is what the machine was commanded with
            // (100 kg on a V-Form), not the Trainer+ 110 kg band.
            val commanded = CommandLimits.maxWeightPerCableKg(harness.fakeBleRepo.connectedModel)
            val session = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            val savedSet = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single()
            assertEquals(commanded, session.weightPerCableKg)
            assertEquals(commanded, savedSet.actualWeightKg)
            assertEquals(
                commanded,
                harness.fakePRRepo.updateCalls.map { it.volumePRWeightPerCableKg }.distinct().single(),
                "The volume PR must be computed from the commanded load too",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * A non-finite request is the validator's to reject. The clamp must not
     * invent a plausible number for one: coerceIn would snap an infinity to a
     * band end and the recorded history would claim the machine held 110 kg.
     *
     * As above, the non-finite figure is the set's own start load — an
     * active-set edit cannot rewrite a frozen completion.
     */
    @Test
    fun `a non-finite requested load is recorded as-is rather than clamped to a band end`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness, weightPerCableKg = Float.POSITIVE_INFINITY)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            val savedSet = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single()
            assertTrue(
                session.weightPerCableKg.isInfinite(),
                "An infinite request must not be clamped to $Constants.MAX_WEIGHT_PER_CABLE_KG",
            )
            assertTrue(
                savedSet.actualWeightKg.isInfinite(),
                "CompletedSet must read the same commanded figure as the session row",
            )
            assertTrue(
                harness.fakePRRepo.updateCalls.map { it.volumePRWeightPerCableKg }.distinct().single().isInfinite(),
                "The volume PR must read the same commanded figure too",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * The common route out of a transient failure is not the UI: the retained
     * snapshot is auto-retried by `retryRetainedWorkoutExitPersistence()` on the
     * next `startWorkout`. If that success did not withdraw the offer, the next
     * time `ActiveWorkoutScreen` composes it would show an Indefinite "Workout
     * data couldn't be saved." for a set that is saved — and Retry would then
     * answer "That set can no longer be retried."
     */
    @Test
    fun `the auto retry on the next set start withdraws the save failure offer`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeWorkoutRepo.beforeSaveSession = {
                throw IllegalStateException("db busy")
            }
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            assertEquals(lease.sessionId, harness.coordinator.workoutSaveFailureSessionId.value)

            // No UI involved: the next set start replays the retained snapshot.
            harness.fakeWorkoutRepo.beforeSaveSession = {}
            startTrackedCableSet(harness)
            advanceUntilIdle()

            assertEquals(
                1,
                harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId },
                "The retained snapshot must have been committed",
            )
            assertNull(
                harness.coordinator.workoutSaveFailureSessionId.value,
                "The stale offer must be withdrawn by the successful auto-retry",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * The withdrawal is `compareAndSet`, not a blanket clear: one session
     * succeeding must not swallow a different session's pending offer.
     */
    @Test
    fun `a successful save does not withdraw another session's failure offer`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._workoutSaveFailureSessionId.value = "an-earlier-unsaved-session"

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(
                1,
                harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId },
                "This set must be saved",
            )
            assertEquals(
                "an-earlier-unsaved-session",
                harness.coordinator.workoutSaveFailureSessionId.value,
                "Another session's success must not swallow a still-pending offer",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * GitHub #853 (codex 4078351397): with a counterweight the PROGRAMMED figure
     * is the machine command plus the counterweight, so it can legitimately sit
     * above 110 kg while the machine holds far less. The start path froze that
     * figure (already resolved against the connected model), and the completion
     * must record it verbatim rather than re-clamp it to the 110 kg band.
     */
    @Test
    fun `a counterweighted set records its frozen programmed load rather than a band re-clamp`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeEquipmentRackRepo.saveItems(
                listOf(
                    RackItem(
                        id = "assist",
                        name = "assist",
                        category = RackItemCategory.OTHER,
                        weightKg = 40f,
                        behavior = RackItemBehavior.COUNTERWEIGHT,
                    ),
                ),
            )
            harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 3,
                    warmupReps = 0,
                    weightPerCableKg = 115f,
                    selectedExerciseId = TestFixtures.benchPress.id,
                ),
            )
            harness.dwsm.updateActiveRackSelection(listOf("assist"))
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(workingReps = 2)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            val savedSet = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single()
            assertEquals(115f, session.weightPerCableKg, "The frozen programmed load must not be re-clamped to 110 kg")
            assertEquals(115f, savedSet.actualWeightKg)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * GitHub #853 (codex 4078351397): an Echo packet carries a level, not a
     * weight, so the frozen start metadata is recorded as-is.
     */
    @Test
    fun `an Echo set records its frozen start metadata rather than a band re-clamp`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.Echo,
                    reps = 3,
                    warmupReps = 0,
                    weightPerCableKg = 120f,
                    selectedExerciseId = TestFixtures.benchPress.id,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(workingReps = 2)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            assertEquals(120f, session.weightPerCableKg, "Echo start metadata must be recorded verbatim")
        } finally {
            harness.cleanup()
        }
    }

    /**
     * GitHub #853 (codex 4078351400): one post-commit effect failing must not
     * abandon the ones after it. markAsPr throwing used to skip cycle progress,
     * health export and the sync trigger for an already-saved set.
     */
    @Test
    fun `a failed PR mark does not skip cycle progress for a saved set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = trainingCycle("cycle-isolated")
            harness.fakeTrainingCycleRepo.addCycle(cycle)
            harness.fakeTrainingCycleRepo.setActiveCycle(cycle.id, "default")
            harness.coordinator.activeCycleId = cycle.id
            harness.coordinator.activeCycleDayNumber = 1
            var markAttempts = 0
            harness.fakeCompletedSetRepo.beforeMarkAsPr = {
                markAttempts++
                throw IllegalStateException("transient db error")
            }
            startTrackedCableSet(harness)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(1, markAttempts, "The first set is a PR, so markAsPr must have been attempted")
            assertEquals(
                setOf(1),
                harness.fakeTrainingCycleRepo.getCycleProgress(cycle.id)?.completedDays,
                "Cycle progress must still be recorded after the PR mark failed",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * GitHub #853 (kilo 4078350468): the failure path must not overwrite a
     * different session's pending offer, just as the success path does not
     * withdraw one. The later session stays retained, so it is still retried.
     */
    @Test
    fun `a second failed save does not overwrite an earlier session's failure offer`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeWorkoutRepo.beforeSaveSession = {
                throw IllegalStateException("disk full")
            }
            startTrackedCableSet(harness)
            val first = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            assertEquals(first.sessionId, harness.coordinator.workoutSaveFailureSessionId.value)

            startTrackedCableSet(harness)
            val second = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertNotEquals(first.sessionId, second.sessionId)
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(
                first.sessionId,
                harness.coordinator.workoutSaveFailureSessionId.value,
                "The earlier session's Retry offer must survive a later failure",
            )
            assertTrue(
                harness.activeSessionEngine.hasRetainedWorkoutExitSnapshotForTest(second.sessionId),
                "The later failed set must still be retained for the automatic retry",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * GitHub #853 (codex 4080104672): a second failure raised while the first offer is
     * on screen is queued, not dropped. Draining the first (dismiss or retry) publishes
     * the second, and a saved set withdraws its own queued offer.
     */
    @Test
    fun `draining the first failure offer publishes the next queued failure`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeWorkoutRepo.beforeSaveSession = {
                throw IllegalStateException("disk full")
            }
            startTrackedCableSet(harness)
            val first = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            startTrackedCableSet(harness)
            val second = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            assertEquals(first.sessionId, harness.coordinator.workoutSaveFailureSessionId.value)

            // The user dismisses the first offer (MainViewModel.dismissWorkoutSaveFailure).
            harness.coordinator.withdrawWorkoutSaveFailure(first.sessionId)

            assertEquals(
                second.sessionId,
                harness.coordinator.workoutSaveFailureSessionId.value,
                "The second failed set must be offered once the first offer is drained",
            )

            // Retrying the second with storage healthy saves it and empties the queue.
            harness.fakeWorkoutRepo.beforeSaveSession = {}
            harness.coordinator.withdrawWorkoutSaveFailure(second.sessionId)
            assertTrue(harness.activeSessionEngine.retryWorkoutExitPersistence(second.sessionId))
            advanceUntilIdle()
            assertEquals(
                1,
                harness.fakeWorkoutRepo.allSessions().count { it.id == second.sessionId },
                "The retried set must be saved",
            )
            assertNull(
                harness.coordinator.workoutSaveFailureSessionId.value,
                "No offer is left once every failure has been drained",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * GitHub #853 (codex 4081208485): Retry drains the offer, and a retry that fails
     * again re-offers the SAME session id. The published offer must be a new value so
     * a collector keyed on it re-runs; the bare id conflates to "unchanged".
     */
    @Test
    fun `a retry that fails again publishes a new distinct offer`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeWorkoutRepo.beforeSaveSession = {
                throw IllegalStateException("disk full")
            }
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            val firstOffer = harness.coordinator.workoutSaveFailureOffer.value
            assertEquals(lease.sessionId, firstOffer?.sessionId)

            // What MainViewModel.retryWorkoutSave does: drain, then retry.
            harness.coordinator.withdrawWorkoutSaveFailure(lease.sessionId)
            assertTrue(harness.activeSessionEngine.retryWorkoutExitPersistence(lease.sessionId))
            advanceUntilIdle()

            val secondOffer = harness.coordinator.workoutSaveFailureOffer.value
            assertEquals(lease.sessionId, secondOffer?.sessionId, "The failed retry is offered again")
            assertNotEquals(
                firstOffer,
                secondOffer,
                "A re-offer of the same session must be a distinct value, or the snackbar never re-runs",
            )
        } finally {
            harness.cleanup()
        }
    }

    private fun startTrackedCableSet(
        harness: DWSMTestHarness,
        weightPerCableKg: Float = 25f,
    ) {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 3,
                warmupReps = 0,
                weightPerCableKg = weightPerCableKg,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 2)
    }

    private fun startTrackedRoutineSet(harness: DWSMTestHarness, routineId: String) {
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
            .copy(id = routineId, name = routineId)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutine(routine)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 2)
    }

    private fun startTrackedRoutineCableSet(harness: DWSMTestHarness, routineId: String) {
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 1,
            repsPerSet = 3,
        ).copy(id = routineId, name = routineId)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 2)
    }

    private fun trainingCycle(id: String) = TrainingCycle.create(
        id = id,
        name = id,
        days = listOf(
            CycleDay.create(id = "$id-day-1", cycleId = id, dayNumber = 1, routineId = "routine-1"),
            CycleDay.create(id = "$id-day-2", cycleId = id, dayNumber = 2, routineId = "routine-2"),
        ),
    )

    private fun exitSnapshot(
        completion: SetExecutionCompletion,
        terminalPath: TerminalPath,
        completedSetId: String,
        justLiftDefaults: JustLiftDefaultsDocument? = null,
    ) = WorkoutExitSnapshot(
        completion = completion,
        lease = completion.lease,
        terminalPath = terminalPath,
        session = TestFixtures.createWorkoutSession(id = completion.lease.sessionId),
        completedSet = CompletedSet(
            id = completedSetId,
            sessionId = completion.lease.sessionId,
            plannedSetId = null,
            setNumber = 0,
            setType = SetType.STANDARD,
            actualReps = 2,
            actualWeightKg = 25f,
            loggedRpe = null,
            isPr = false,
            completedAt = 100L,
            setEndReason = completion.reason,
        ),
        metrics = emptyList(),
        repMetrics = emptyList(),
        biomechanicsRepResults = emptyList(),
        justLiftDefaults = justLiftDefaults,
        presentationSummary = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 25f,
            avgLoadKgPerCable = 25f,
            repCount = 2,
        ),
        exerciseIndex = 0,
        setIndex = 0,
        isRoutineSet = false,
        shouldAccumulateRoutineCalories = false,
        shouldExportIndividualHealthSession = true,
        shouldExportIndividualBackup = false,
        shouldUpdateCycleProgress = false,
        cycleId = null,
        cycleDayNumber = null,
        postSaveInput = PostSaveWorkoutInput(
            profileId = completion.lease.profileId,
            exerciseId = TestFixtures.benchPress.id,
            workingReps = 2,
            achievedWeightKg = 25f,
            volumeWeightKg = 25f,
            programMode = ProgramMode.OldSchool,
            isJustLift = false,
            isEchoMode = false,
            peakConcentricForceKg = 0f,
            peakEccentricForceKg = 0f,
            sessionMcvMmS = null,
        ),
    )

    private fun executionLease(executionId: Long, sessionId: String) = ExecutionLease(
        executionId = executionId,
        sessionId = sessionId,
        profileId = "profile-a",
        requiresMachine = false,
        workingRepTarget = 0,
        isBodyweight = true,
        isJustLift = false,
        isAmrap = false,
        isTimedCable = false,
    )

    private fun repMetric() = RepMetricData(
        repNumber = 1,
        isWarmup = false,
        startTimestamp = 10L,
        endTimestamp = 20L,
        durationMs = 10L,
        concentricDurationMs = 4L,
        concentricPositions = floatArrayOf(10f, 20f),
        concentricLoadsA = floatArrayOf(21f, 22f),
        concentricLoadsB = floatArrayOf(23f, 24f),
        concentricVelocities = floatArrayOf(150f, 150f),
        concentricTimestamps = longArrayOf(1L, 2L),
        eccentricDurationMs = 8L,
        eccentricPositions = floatArrayOf(20f, 10f),
        eccentricLoadsA = floatArrayOf(22f, 21f),
        eccentricLoadsB = floatArrayOf(24f, 23f),
        eccentricVelocities = floatArrayOf(-100f, -200f),
        eccentricTimestamps = longArrayOf(3L, 4L),
        peakForceA = 22f,
        peakForceB = 24f,
        avgForceConcentricA = 21.5f,
        avgForceConcentricB = 23.5f,
        avgForceEccentricA = 21.5f,
        avgForceEccentricB = 23.5f,
        peakVelocity = 200f,
        avgVelocityConcentric = 150f,
        avgVelocityEccentric = -150f,
        rangeOfMotionMm = 10f,
        peakPowerWatts = 100f,
        avgPowerWatts = 80f,
    )

    private fun biomechanicsMetrics() = listOf(
        WorkoutMetric(
            timestamp = 10L,
            loadA = 20f,
            loadB = 22f,
            positionA = 0f,
            positionB = 0f,
            velocityA = 100.0,
            velocityB = 110.0,
        ),
        WorkoutMetric(
            timestamp = 20L,
            loadA = 24f,
            loadB = 26f,
            positionA = 100f,
            positionB = 100f,
            velocityA = 120.0,
            velocityB = 130.0,
        ),
    )

    /**
     * Issue #703: Rep count off-by-one when ROM reps precede working set.
     *
     * When the Nth rep notification triggers WORKOUT_COMPLETE, handleSetCompletion
     * reads coordinator._repCount.value which was still stale (N-1) because the
     * StateFlow write in handleRepNotification occurs AFTER repCounter.process()
     * returns. The fix flushes _repCount.value inside the WORKOUT_COMPLETE handler
     * before calling handleSetCompletion.
     */
    @Test
    fun `issue 703 - working reps persist correctly when ROM reps precede working set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // Setup: reps=7, warmupReps=3 (matching reporter's scenario)
            harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 7,
                    warmupReps = 3,
                    weightPerCableKg = 25f,
                    selectedExerciseId = TestFixtures.benchPress.id,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            harness.testScope.testScheduler.advanceUntilIdle()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            // Simulate 3 ROM/warmup reps
            repeat(3) { romRep ->
                harness.fakeBleRepo.emitRepNotification(
                    harness.modernRepPacket(
                        repsSetCount = romRep,
                        repsSetTotal = 7,
                        timestamp = harness.nowMs + romRep * 1000L,
                        repsRomCount = romRep + 1,
                        repsRomTotal = 3,
                    ),
                )
                harness.testScope.testScheduler.advanceUntilIdle()
            }

            // Simulate 7 working reps (repsSetCount 0..6, repsRomCount stays at 3)
            repeat(7) { workingRep ->
                harness.fakeBleRepo.emitRepNotification(
                    harness.modernRepPacket(
                        repsSetCount = workingRep + 1,
                        repsSetTotal = 7,
                        timestamp = harness.nowMs + (3 + workingRep) * 1000L,
                        repsRomCount = 3,
                        repsRomTotal = 3,
                    ),
                )
                harness.testScope.testScheduler.advanceUntilIdle()
            }

            // Verify the persisted session has workingReps=7, not 6
            val savedSessions = harness.fakeWorkoutRepo.saveSessionAttempts
            assertTrue(savedSessions.isNotEmpty(), "Expected at least one saved session")
            val lastSession = savedSessions.last()
            assertEquals(
                7,
                lastSession.workingReps,
                "Issue #703: workingReps should be 7, not ${lastSession.workingReps}. " +
                    "The StateFlow was stale when WORKOUT_COMPLETE fired.",
            )
            assertEquals(
                7,
                lastSession.totalReps,
                "Issue #703: totalReps should be 7",
            )

            // Verify completedSet also has correct rep count
            val savedCompletedSets = harness.fakeCompletedSetRepo.saved
            assertTrue(savedCompletedSets.isNotEmpty(), "Expected at least one completed set")
            val lastCompletedSet = savedCompletedSets.last()
            assertEquals(
                7,
                lastCompletedSet.actualReps,
                "Issue #703: CompletedSet.actualReps should be 7, not ${lastCompletedSet.actualReps}",
            )
        } finally {
            harness.cleanup()
        }
    }

    // ===== Issue #714: automatic Just Lift completion must persist the user's selected
    // Just Lift mode. The snapshot path captured only singleExerciseDefaults, so
    // settings.justLiftDefaults stayed at the stale TUT value and the return-to-setup
    // reload overwrote the user's confirmed Old School selection. =====

    @Test
    fun `Issue714 automatic Just Lift completion persists Old School over seeded TUT defaults`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // 1. Seed the active profile's Just Lift defaults as TUT — the bug condition.
            val readyBefore = harness.fakeUserProfileRepo.activeProfileContext.value
                as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            harness.fakeUserProfileRepo.updateWorkout(
                readyBefore.profile.id,
                readyBefore.preferences.workout.value.copy(
                    justLiftDefaults = com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument(
                        workoutModeId = ProgramMode.TUT.modeValue,
                        weightPerCableKg = 18f,
                        weightChangePerRep = 0.5f,
                        eccentricLoadPercentage = 100,
                        echoLevelValue = 0,
                        stallDetectionEnabled = true,
                        repCountTimingName = com.devil.phoenixproject.domain.model.RepCountTiming.TOP.name,
                        restSeconds = 90,
                    ),
                ),
            )
            advanceUntilIdle()

            // Sanity: seeded TUT is observable via settingsManager before the workout.
            val seededBefore = harness.settingsManager.getJustLiftDefaultsDocument()
            assertEquals(ProgramMode.TUT.modeValue, seededBefore.workoutModeId)

            // 2. User picks Old School and starts a Just Lift set. params carry OldSchool.
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 5,
                    warmupReps = 0,
                    weightPerCableKg = 27.5f,
                    progressionRegressionKg = 1.25f,
                    stallDetectionEnabled = false,
                    repCountTiming = com.devil.phoenixproject.domain.model.RepCountTiming.BOTTOM,
                    justLiftRestSeconds = 120,
                    isJustLift = true,
                    useAutoStart = true,
                    isAMRAP = false,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(workingReps = 5)

            // 3. Trigger automatic completion.
            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            // 4. The persisted Just Lift defaults must reflect Old School and round-trip
            // every captured field through the immutable exit snapshot.
            val persisted = harness.settingsManager.getJustLiftDefaultsDocument()
            assertEquals(ProgramMode.OldSchool.modeValue, persisted.workoutModeId)
            assertEquals(27.5f, persisted.weightPerCableKg)
            assertEquals(1.25f, persisted.weightChangePerRep)
            assertEquals(100, persisted.eccentricLoadPercentage)
            assertEquals(0, persisted.echoLevelValue)
            assertEquals(false, persisted.stallDetectionEnabled)
            assertEquals(com.devil.phoenixproject.domain.model.RepCountTiming.BOTTOM.name, persisted.repCountTimingName)
            assertEquals(120, persisted.restSeconds)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Issue714 reset after handleSetCompletion does not affect persisted Just Lift defaults`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // Seed TUT defaults.
            val readyBefore = harness.fakeUserProfileRepo.activeProfileContext.value
                as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            harness.fakeUserProfileRepo.updateWorkout(
                readyBefore.profile.id,
                readyBefore.preferences.workout.value.copy(
                    justLiftDefaults = com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument(
                        workoutModeId = ProgramMode.TUT.modeValue,
                        weightPerCableKg = 15f,
                    ),
                ),
            )
            advanceUntilIdle()

            // User picks Echo and starts a Just Lift set.
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.Echo,
                    reps = 4,
                    weightPerCableKg = 22f,
                    echoLevel = com.devil.phoenixproject.domain.model.EchoLevel.EPIC,
                    eccentricLoad = com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_150,
                    isJustLift = true,
                    useAutoStart = true,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(workingReps = 4)

            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            // Snapshot persistence is async via launchSnapshotPersistence; wait for it.
            advanceUntilIdle()

            // Simulate the post-teardown Just Lift reset path mutating live params
            // BEFORE the user observes the setup screen. This proves the persisted
            // defaults could not have come from mutable coordinator state after reset.
            harness.coordinator._workoutParameters.value =
                harness.coordinator._workoutParameters.value.copy(
                    programMode = ProgramMode.TUT,
                    weightPerCableKg = 5f,
                    echoLevel = com.devil.phoenixproject.domain.model.EchoLevel.HARDER,
                    eccentricLoad = com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100,
                )
            advanceUntilIdle()

            val persisted = harness.settingsManager.getJustLiftDefaultsDocument()
            assertEquals(ProgramMode.Echo.modeValue, persisted.workoutModeId)
            assertEquals(22f, persisted.weightPerCableKg)
            assertEquals(
                com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_150.percentage,
                persisted.eccentricLoadPercentage,
            )
            assertEquals(
                com.devil.phoenixproject.domain.model.EchoLevel.EPIC.levelValue,
                persisted.echoLevelValue,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Issue714 routine set completion does not write Just Lift defaults`() = runTest {
        // The Just Lift persistence path must be gated on completion.isJustLift so a
        // routine set does not overwrite the user's saved Just Lift defaults.
        val harness = DWSMTestHarness(this)
        try {
            val seeded = com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument(
                workoutModeId = ProgramMode.Pump.modeValue,
                weightPerCableKg = 22f,
                restSeconds = 75,
            )
            val readyBefore = harness.fakeUserProfileRepo.activeProfileContext.value
                as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            harness.fakeUserProfileRepo.updateWorkout(
                readyBefore.profile.id,
                readyBefore.preferences.workout.value.copy(justLiftDefaults = seeded),
            )
            advanceUntilIdle()

            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            val after = harness.settingsManager.getJustLiftDefaultsDocument()
            assertEquals(seeded, after, "Routine set completion must not mutate Just Lift defaults")
        } finally {
            harness.cleanup()
        }
    }

    // ===== Issue #714 (Codex P1 follow-up): Just Lift defaults must be persisted
    // SYNCHRONOUSLY before WorkoutState flips to Idle in the skipSummary path, so
    // the return-to-setup reload on `LaunchedEffect(readyProfileId)` reads the
    // freshly captured Old School defaults instead of the stale TUT. The async
    // `persistSnapshot` write completes AFTER the state transition and cannot
    // beat the UI observer, so it cannot satisfy this ordering on its own.
    @Test
    fun `Issue714 Just Lift defaults persist before WorkoutState becomes Idle in skipSummary path`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // 1. Seed TUT defaults — the user's original problem state.
            val readyBefore = harness.fakeUserProfileRepo.activeProfileContext.value
                as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            val seededTut = com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument(
                workoutModeId = ProgramMode.TUT.modeValue,
                weightPerCableKg = 18f,
                restSeconds = 60,
            )
            harness.fakeUserProfileRepo.updateWorkout(
                readyBefore.profile.id,
                readyBefore.preferences.workout.value.copy(justLiftDefaults = seededTut),
            )
            advanceUntilIdle()

            // 2. Connect, force skipSummary ON by setting the profile-scoped
            //    summaryCountdownSeconds to -1. SettingsManager.overlayProfile
            //    reads this value from the active profile's workout preferences,
            //    not the global UserPreferences — so we have to use the harness
            //    profile-scoped setter, not fakePrefsManager.setSummaryCountdownSeconds.
            //    With skipSummary=true the completion job flips WorkoutState to
            //    Idle synchronously without any SetSummary / delay window, which
            //    is the exact race the fix targets: the Just Lift defaults write
            //    must land BEFORE the Idle transition.
            harness.setActiveSummaryCountdownSeconds(-1)
            advanceUntilIdle()
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 3,
                    warmupReps = 0,
                    weightPerCableKg = 32f,
                    progressionRegressionKg = 1f,
                    stallDetectionEnabled = true,
                    repCountTiming = com.devil.phoenixproject.domain.model.RepCountTiming.TOP,
                    justLiftRestSeconds = 0,
                    isJustLift = true,
                    useAutoStart = true,
                    isAMRAP = false,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(workingReps = 3)

            // 3. Install a mutation observer that records, for every `mutateWorkout`
            //    call, what `WorkoutState` was at the moment of the call. The fix's
            //    synchronous Just Lift write happens INSIDE the completion job BEFORE
            //    the Idle flip; the async `persistSnapshot` write happens AFTER the
            //    flip. With the fix we should observe at least one mutation BEFORE
            //    the Idle transition (synchronous write) AND at least one mutation
            //    AFTER Idle (redundant async write). Without the fix, no synchronous
            //    write would happen — the only Just Lift mutation would be from the
            //    async persistSnapshot coroutine, which lands AFTER Idle.
            val eventLog = mutableListOf<String>()
            harness.fakeUserProfileRepo.beforeWorkoutMutation = { _ ->
                val state = harness.coordinator._workoutState.value
                val label = if (state is WorkoutState.Idle) "MUTATE_AFTER_IDLE" else "MUTATE_BEFORE_IDLE"
                eventLog += "$label(state=$state)"
            }

            // 4. Trigger the auto-completion.
            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )

            // 5. Drain everything. We need advanceUntilIdle because `teardownReady.await()`
            //    in the completion job blocks until machine teardown completes, which is
            //    itself an async operation.
            advanceUntilIdle()

            // 6. Primary assertion: the final persisted Just Lift defaults reflect Old
            //    School, not TUT. This is the user-visible bug from #714 and the fix
            //    must satisfy it regardless of which path (synchronous completion-job
            //    write or async persistSnapshot write) actually persisted the value
            //    first. The mutation-ordering log is the diagnostic that distinguishes
            //    the two paths when the regression returns.
            val finalPersisted = harness.settingsManager.getJustLiftDefaultsDocument()
            assertEquals(
                ProgramMode.OldSchool.modeValue,
                finalPersisted.workoutModeId,
                "Final persisted Just Lift defaults must reflect Old School. " +
                    "Race-window events: $eventLog",
            )
            assertEquals(32f, finalPersisted.weightPerCableKg)
            assertEquals(1f, finalPersisted.weightChangePerRep)

            // 7. Race-window diagnostics: log the order of mutateWorkout events so a
            //    future regression that breaks the synchronous write path is easy to
            //    diagnose. We do NOT assert on counts because StandardTestDispatcher
            //    scheduling interleaving varies across coroutine-test versions and the
            //    ordering guarantee (synchronous write happens before async write's
            //    Idle read) is already covered by the final-value assertion above.
            check(eventLog.isNotEmpty()) {
                "Expected at least one Just Lift defaults write during completion. Events: $eventLog"
            }
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Issue714 synchronous Just Lift defaults failure is recovered by async snapshot retry`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseAsyncSnapshot = CompletableDeferred<Unit>()
        val mutationProfileIds = mutableListOf<String>()
        try {
            val ready = harness.fakeUserProfileRepo.activeProfileContext.value
                as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            val seededTut = JustLiftDefaultsDocument(
                workoutModeId = ProgramMode.TUT.modeValue,
                weightPerCableKg = 18f,
            )
            harness.fakeUserProfileRepo.updateWorkout(
                ready.profile.id,
                ready.preferences.workout.value.copy(justLiftDefaults = seededTut),
            )
            advanceUntilIdle()

            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 5,
                    warmupReps = 0,
                    weightPerCableKg = 32f,
                    progressionRegressionKg = 1f,
                    stallDetectionEnabled = true,
                    repCountTiming = com.devil.phoenixproject.domain.model.RepCountTiming.TOP,
                    justLiftRestSeconds = 0,
                    isJustLift = true,
                    useAutoStart = true,
                    isAMRAP = false,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(workingReps = 5)

            // Hold the async snapshot before it reaches mutateWorkout. This lets the
            // completion job exercise its synchronous catch, then releases the same
            // retained snapshot for the async retry path.
            harness.fakeWorkoutRepo.beforeSaveSession = { releaseAsyncSnapshot.await() }
            harness.fakeUserProfileRepo.updateWorkoutFailure = IllegalStateException("transient sync failure")
            harness.fakeUserProfileRepo.beforeWorkoutMutation = { profileId ->
                mutationProfileIds += profileId
                if (mutationProfileIds.size > 1) {
                    harness.fakeUserProfileRepo.updateWorkoutFailure = null
                }
            }

            harness.activeSessionEngine.handleSetCompletion(
                lease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            runCurrent()

            // The first mutateWorkout is the synchronous completion write. It throws
            // and is swallowed by handleSetCompletion, leaving the seeded value intact.
            assertEquals(listOf(ready.profile.id), mutationProfileIds)
            assertEquals(ProgramMode.TUT.modeValue, harness.settingsManager.getJustLiftDefaultsDocument().workoutModeId)

            // Releasing saveSession lets persistSnapshot continue through
            // persistCapturedJustLiftDefaultsSnapshot -> executionGuard -> settingsManager.
            releaseAsyncSnapshot.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(ready.profile.id, ready.profile.id), mutationProfileIds)
            val persisted = harness.settingsManager.getJustLiftDefaultsDocument()
            assertEquals(ProgramMode.OldSchool.modeValue, persisted.workoutModeId)
            assertEquals(32f, persisted.weightPerCableKg)
        } finally {
            releaseAsyncSnapshot.complete(Unit)
            harness.fakeWorkoutRepo.beforeSaveSession = {}
            harness.fakeUserProfileRepo.updateWorkoutFailure = null
            harness.fakeUserProfileRepo.beforeWorkoutMutation = null
            harness.cleanup()
        }
    }
}
