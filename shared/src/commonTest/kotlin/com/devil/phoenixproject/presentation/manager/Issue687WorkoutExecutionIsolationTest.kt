package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Issue687WorkoutExecutionIsolationTest {
    @Test
    fun `issue 687 reporter workflow remains on execution B at zero reps`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        val releasePersistence = CompletableDeferred<Unit>()
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest(PROFILE_A)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            startRoutineSet(harness, cableRoutine(ROUTINE_A, PROFILE_A))
            harness.coordinator._repCount.value = RepCount(workingReps = 2)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutoverA = requireNotNull(leaseA.activationCutoverTimestampMs)
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }
            harness.fakeWorkoutRepo.beforeSaveSession = { releasePersistence.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            harness.fakeUserProfileRepo.seedReadyProfileForTest(PROFILE_B)
            assertEquals(PROFILE_B, harness.fakeUserProfileRepo.activeProfile.value?.id)
            runCurrent()
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.size)

            advanceTimeBy(1_000)
            releaseReset.complete(Result.success(Unit))
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)

            startRoutineSet(harness, cableRoutine(ROUTINE_B, PROFILE_B))
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutoverB = requireNotNull(leaseB.activationCutoverTimestampMs)
            assertTrue(cutoverA + 1 < cutoverB, "The delayed A packet must be pre-cutover for B")
            assertNotEquals(leaseA.sessionId, leaseB.sessionId)

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 3,
                    timestamp = cutoverA + 1,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
            assertEquals(PROFILE_B, leaseB.profileId)
            assertEquals(leaseB, harness.activeSessionEngine.currentExecutionLeaseForTest())

            releasePersistence.complete(Unit)
            advanceUntilIdle()
            val savedA = harness.fakeWorkoutRepo.saveSessionAttempts.single { it.id == leaseA.sessionId }
            assertEquals(PROFILE_A, savedA.profileId)
            assertEquals(ROUTINE_A, savedA.routineName)
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == leaseA.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == leaseA.sessionId })
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            releaseReset.complete(Result.success(Unit))
            releasePersistence.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `post cutover terminal packet is rejected before current execution evidence`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutover = requireNotNull(lease.activationCutoverTimestampMs)

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 3,
                    timestamp = cutover + 1,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
            assertEquals(RepFreshnessState.AwaitingEvidence, harness.activeSessionEngine.executionGuard.repFreshnessGate.stateFor(lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `modern nonterminal progress arms B before normal terminal completion`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutover = requireNotNull(lease.activationCutoverTimestampMs)

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 1,
                    repsSetTotal = 3,
                    timestamp = cutover + 1,
                ),
            )
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(1, harness.coordinator.repCount.value.workingReps)
            assertEquals(RepFreshnessState.Armed, harness.activeSessionEngine.executionGuard.repFreshnessGate.stateFor(lease))

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 3,
                    timestamp = cutover + 2,
                ),
            )
            runCurrent()

            assertEquals(3, harness.coordinator.repCount.value.workingReps)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `movement arms fixed one rep execution before terminal completion`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 1)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutover = requireNotNull(lease.activationCutoverTimestampMs)

            harness.fakeBleRepo.setHandleState(HandleState.Moving)
            runCurrent()
            assertEquals(RepFreshnessState.Armed, harness.activeSessionEngine.executionGuard.repFreshnessGate.stateFor(lease))

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 1,
                    repsSetTotal = 1,
                    timestamp = cutover + 1,
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
    fun `legacy carried counters baseline before one subsequent delta is counted`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutover = requireNotNull(lease.activationCutoverTimestampMs)

            harness.fakeBleRepo.emitRepNotification(
                harness.legacyRepPacket(
                    topCounter = 7,
                    completeCounter = 7,
                    timestamp = cutover + 1,
                ),
            )
            runCurrent()
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
            assertEquals(RepFreshnessState.LegacyBaseline(7, 7), harness.activeSessionEngine.executionGuard.repFreshnessGate.stateFor(lease))

            harness.fakeBleRepo.emitRepNotification(
                harness.legacyRepPacket(
                    topCounter = 8,
                    completeCounter = 7,
                    timestamp = cutover + 2,
                ),
            )
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(1, harness.coordinator.repCount.value.warmupReps)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
            assertEquals(RepFreshnessState.Armed, harness.activeSessionEngine.executionGuard.repFreshnessGate.stateFor(lease))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `automatic completion claim wins over End Workout exactly once`() = runTest {
        val harness = DWSMTestHarness(this)
        val releasePersistence = CompletableDeferred<Unit>()
        try {
            startCableSet(harness, targetReps = 3, completedReps = 2)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { releasePersistence.await() }

            harness.activeSessionEngine.handleSetCompletion(

                harness.activeSessionEngine.currentExecutionLeaseForTest(),

                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

            )
            runCurrent()
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })

            releasePersistence.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            releasePersistence.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout claim wins over delayed automatic completion exactly once`() = runTest {
        val harness = DWSMTestHarness(this)
        val releasePersistence = CompletableDeferred<Unit>()
        try {
            startCableSet(harness, targetReps = 3, completedReps = 2)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { releasePersistence.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })

            harness.activeSessionEngine.handleSetCompletion(
                lease,
                com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,
            )
            runCurrent()
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })

            releasePersistence.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId })
            assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == lease.sessionId })
        } finally {
            releasePersistence.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `auto completion A reason cannot bleed into B while A persistence is suspended`() = runTest {
        val harness = DWSMTestHarness(this)
        val releasePersistenceA = CompletableDeferred<Unit>()
        try {
            startCableSet(harness, targetReps = 8, completedReps = 2)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { session ->
                if (session.id == leaseA.sessionId) releasePersistenceA.await()
            }

            harness.activeSessionEngine.handleSetCompletion(leaseA, SetEndReason.STALL_FAILURE)
            runCurrent()
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)

            startCableSet(harness, targetReps = 8, completedReps = 2)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(leaseB, SetEndReason.VBT_AUTO_END)
            runCurrent()

            releasePersistenceA.complete(Unit)
            advanceUntilIdle()

            assertNotEquals(leaseA.sessionId, leaseB.sessionId)
            assertEquals(
                SetEndReason.STALL_FAILURE,
                harness.fakeCompletedSetRepo.getCompletedSets(leaseA.sessionId).single().setEndReason,
            )
            assertEquals(
                SetEndReason.VBT_AUTO_END,
                harness.fakeCompletedSetRepo.getCompletedSets(leaseB.sessionId).single().setEndReason,
            )
        } finally {
            releasePersistenceA.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `End Workout A reason cannot bleed into B while A persistence is suspended`() = runTest {
        val harness = DWSMTestHarness(this)
        val releasePersistenceA = CompletableDeferred<Unit>()
        try {
            startCableSet(harness, targetReps = 8, completedReps = 2)
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeWorkoutRepo.beforeSaveSession = { session ->
                if (session.id == leaseA.sessionId) releasePersistenceA.await()
            }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)

            startCableSet(harness, targetReps = 8, completedReps = 2)
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(leaseA, SetEndReason.STALL_FAILURE)
            harness.activeSessionEngine.handleSetCompletion(leaseB, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()

            releasePersistenceA.complete(Unit)
            advanceUntilIdle()

            assertNotEquals(leaseA.sessionId, leaseB.sessionId)
            assertEquals(
                SetEndReason.USER_STOPPED,
                harness.fakeCompletedSetRepo.getCompletedSets(leaseA.sessionId).single().setEndReason,
            )
            assertEquals(
                SetEndReason.TARGET_REPS_REACHED,
                harness.fakeCompletedSetRepo.getCompletedSets(leaseB.sessionId).single().setEndReason,
            )
        } finally {
            releasePersistenceA.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `public start entry proxy for MainViewModel and future voice callers rejects during teardown`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        val logs = ConnectionLogRepository.instance
        try {
            logs.clearAll()
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            val commandCount = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertTrue(
                logs.getLogsByEventType(LogEventType.WORKOUT_EXECUTION).any {
                    it.message == "Workout start rejected" && it.details == "reason=TEARING_DOWN"
                },
            )
        } finally {
            releaseReset.complete(Result.success(Unit))
            logs.clearAll()
            harness.cleanup()
        }
    }

    @Test
    fun `manual stop success publishes summary after the exact lease reset`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
            assertEquals(lease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `End reset success permits a fresh direct start and clears stopping state`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 3)
            val endedLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertFalse(harness.dwsm.isStoppingWorkout)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertNotEquals(endedLease.executionId, harness.activeSessionEngine.currentExecutionLeaseForTest().executionId)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `handle auto start is disarmed while teardown is blocked`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.setActiveProfilePreferences(UserPreferences(autoStartCountdownSeconds = 3))
            startCableSet(harness, targetReps = 3)
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(useAutoStart = true),
            )
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            val commandCount = harness.fakeBleRepo.commandsReceived.size

            harness.fakeBleRepo.setHandleState(HandleState.Grabbed)
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.coordinator.autoStartJob)
            assertNull(harness.coordinator.autoStartCountdown.value)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `motion start evidence cannot bypass a blocked public start`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            harness.setActiveProfilePreferences(UserPreferences(motionStartEnabled = true))
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }
            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            val commandCount = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.startWorkout(skipCountdown = false)
            harness.fakeBleRepo.emitMetric(
                WorkoutMetric(
                    timestamp = harness.nowMs,
                    loadA = 20f,
                    loadB = 20f,
                    positionA = 100f,
                    positionB = 100f,
                ),
            )
            advanceTimeBy(2_000)
            runCurrent()

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertFalse(harness.coordinator.motionStartHoldProgress.value == 1f)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `RESET success alone reopens the machine gate`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseReset = CompletableDeferred<Result<Unit>>()
        try {
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { releaseReset.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            releaseReset.complete(Result.success(Unit))
            runCurrent()

            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
        } finally {
            releaseReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `RESET failure remains fail closed`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { Result.failure(IllegalStateException("RESET failed")) }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            val commandCount = harness.fakeBleRepo.commandsReceived.size
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            assertEquals(commandCount, harness.fakeBleRepo.commandsReceived.size)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `RESET timeout after five virtual seconds remains fail closed`() = runTest {
        val harness = DWSMTestHarness(this)
        val neverReset = CompletableDeferred<Result<Unit>>()
        try {
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { neverReset.await() }

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            advanceTimeBy(BleConstants.GATT_OPERATION_TIMEOUT_MS)
            runCurrent()

            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
        } finally {
            neverReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `Retry uses attempt two and reopens only after RESET success`() = runTest {
        val harness = DWSMTestHarness(this)
        val retryReset = CompletableDeferred<Result<Unit>>()
        try {
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { Result.failure(IllegalStateException("initial RESET failed")) }
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)

            harness.fakeBleRepo.stopWorkoutBlock = { retryReset.await() }
            harness.dwsm.retryMachineTeardown()
            runCurrent()

            val tearingDown = assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(2, tearingDown.attempt)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)

            retryReset.complete(Result.success(Unit))
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
        } finally {
            retryReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `Reconnect performs recovery RESET before reopening the gate`() = runTest {
        val harness = DWSMTestHarness(this)
        val postConnectReset = CompletableDeferred<Result<Unit>>()
        try {
            startCableSet(harness, targetReps = 3)
            harness.fakeBleRepo.stopWorkoutBlock = { Result.failure(IllegalStateException("initial RESET failed")) }
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            harness.fakeBleRepo.simulateDisconnect()
            harness.fakeBleRepo.stopWorkoutBlock = { postConnectReset.await() }

            harness.dwsm.reconnectWorkoutTeardown(harness.bleConnectionManager)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
            assertEquals(1, harness.fakeBleRepo.reconnectCallCount)
            assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            postConnectReset.complete(Result.success(Unit))
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
        } finally {
            postConnectReset.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `structured isolation logs carry authority fields without workout or profile values`() = runTest {
        val harness = DWSMTestHarness(this)
        val logs = ConnectionLogRepository.instance
        try {
            logs.clearAll()
            harness.fakeUserProfileRepo.seedReadyProfileForTest(PRIVATE_PROFILE)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            startRoutineSet(harness, privateRoutine())
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val cutover = requireNotNull(lease.activationCutoverTimestampMs)
            harness.fakeBleRepo.emitMetric(
                WorkoutMetric(
                    timestamp = harness.nowMs,
                    loadA = PRIVATE_LOAD_TOKEN.toFloat(),
                    loadB = PRIVATE_LOAD_TOKEN.toFloat(),
                    positionA = PRIVATE_METRIC_TOKEN.toFloat(),
                    positionB = PRIVATE_METRIC_TOKEN.toFloat(),
                ),
            )
            runCurrent()

            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 3,
                    repsSetTotal = 3,
                    timestamp = cutover + 1,
                    topCounter = 17,
                    completeCounter = 16,
                ),
            )
            runCurrent()
            harness.coordinator._repCount.value = RepCount(workingReps = 2)
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()

            val execution = logs.getLogsByEventType(LogEventType.WORKOUT_EXECUTION)
                .filter { it.details?.contains("sessionId=${lease.sessionId}") == true }
            val teardown = logs.getLogsByEventType(LogEventType.WORKOUT_TEARDOWN)
                .filter { it.details?.contains("sessionId=${lease.sessionId}") == true }
            val rejectedRep = logs.getLogsByEventType(LogEventType.WORKOUT_REP_REJECTED)
                .filter { it.details?.contains("sessionId=${lease.sessionId}") == true }
            val persistence = logs.getLogsByEventType(LogEventType.WORKOUT_PERSISTENCE)
                .filter { it.details?.contains("sessionId=${lease.sessionId}") == true }

            assertTrue(execution.any { it.details.hasFields("executionId=", "transition=begun") })
            assertTrue(execution.any { it.details.hasFields("transition=invalidated", "reason=END_WORKOUT") })
            assertTrue(teardown.any { it.details.hasFields("executionId=", "transition=begun", "attempt=1") })
            assertTrue(teardown.any { it.details.hasFields("reason=END_WORKOUT", "elapsedMs=") })
            assertTrue(
                rejectedRep.any {
                    it.details.hasFields(
                        "executionId=",
                        "reason=TERMINAL_BEFORE_EVIDENCE",
                        "repsSetCount=3",
                        "repsSetTotal=3",
                        "legacy=false",
                    )
                },
            )
            assertTrue(persistence.any { it.details.hasFields("transition=claimed", "path=END_WORKOUT") })
            assertTrue(persistence.any { it.details.hasFields("transition=persisted") })

            val isolationDetails = (execution + teardown + rejectedRep + persistence)
                .joinToString(separator = "\n") { it.details.orEmpty() }
            listOf(
                PRIVATE_PROFILE,
                PRIVATE_ROUTINE_NAME,
                PRIVATE_EXERCISE_NAME,
                PRIVATE_EXERCISE_ID,
                PRIVATE_LOAD_TOKEN,
                PRIVATE_METRIC_TOKEN,
            ).forEach { privateValue ->
                assertFalse(isolationDetails.contains(privateValue), "Structured logs leaked $privateValue")
            }
        } finally {
            logs.clearAll()
            harness.cleanup()
        }
    }

    private fun startRoutineSet(harness: DWSMTestHarness, routine: Routine) {
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startSetFromReady()
        harness.testScope.testScheduler.advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
    }

    private fun startCableSet(
        harness: DWSMTestHarness,
        targetReps: Int,
        completedReps: Int = 0,
    ) {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = targetReps,
                warmupReps = 0,
                weightPerCableKg = 25f,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = completedReps)
        assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
    }

    private fun cableRoutine(id: String, profileId: String): Routine = Routine(
        id = id,
        name = id,
        profileId = profileId,
        exercises = listOf(
            RoutineExercise(
                id = "$id-set",
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(3),
                weightPerCableKg = 25f,
                setRestSeconds = listOf(0),
            ),
        ),
    )

    private fun privateRoutine(): Routine {
        val exercise = TestFixtures.benchPress.copy(
            name = PRIVATE_EXERCISE_NAME,
            id = PRIVATE_EXERCISE_ID,
        )
        return Routine(
            id = "private-routine-id-687",
            name = PRIVATE_ROUTINE_NAME,
            profileId = PRIVATE_PROFILE,
            exercises = listOf(
                RoutineExercise(
                    id = "private-routine-set-687",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(3),
                    weightPerCableKg = 37.25f,
                    setRestSeconds = listOf(0),
                ),
            ),
        )
    }

    private fun String?.hasFields(vararg fields: String): Boolean = this != null && fields.all(::contains)

    private companion object {
        const val PROFILE_A = "profile-a"
        const val PROFILE_B = "profile-b"
        const val ROUTINE_A = "routine-a"
        const val ROUTINE_B = "routine-b"
        const val PRIVATE_PROFILE = "private-profile-687"
        const val PRIVATE_ROUTINE_NAME = "Private-Routine-687"
        const val PRIVATE_EXERCISE_NAME = "Private-Exercise-687"
        const val PRIVATE_EXERCISE_ID = "private-exercise-id-687"
        const val PRIVATE_LOAD_TOKEN = "37.25"
        const val PRIVATE_METRIC_TOKEN = "9876.5"
    }
}
