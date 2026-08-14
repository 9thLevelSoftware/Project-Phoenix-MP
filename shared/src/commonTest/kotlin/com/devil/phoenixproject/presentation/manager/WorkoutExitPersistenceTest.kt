package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.RepCount
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class WorkoutExitPersistenceTest {

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
            harness.coordinator.collectedMetrics.value = biomechanicsMetrics()
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
            harness.coordinator.collectedMetrics.value = rawMetrics
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

            assertEquals(rawMetrics, harness.fakeWorkoutRepo.getMetricsForSessionSync(lease.sessionId))
            assertEquals(
                2,
                harness.fakeWorkoutRepo.saveMetricsAttempts.count { it.first == lease.sessionId },
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
                    val completion = SetExecutionCompletion(lease, reason)
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
        val completionA = SetExecutionCompletion(leaseA, SetEndReason.USER_STOPPED)
        val completionB = SetExecutionCompletion(leaseB, SetEndReason.TIMER_EXPIRED)
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
            harness.coordinator.setRepMetrics.value = listOf(repMetric())
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
                harness.coordinator.collectedMetrics.value = listOf(
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
                    harness.fakeWorkoutRepo.saveMetricsAttempts.count { it.first == sessionId } == 1
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
            harness.coordinator.setRepMetrics.value = listOf(repMetric)
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

    private fun startTrackedCableSet(harness: DWSMTestHarness) {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 3,
                warmupReps = 0,
                weightPerCableKg = 25f,
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
}
