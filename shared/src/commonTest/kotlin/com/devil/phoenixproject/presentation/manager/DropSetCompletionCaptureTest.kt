package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DropSetCompletionCaptureTest {
    @Test
    fun capturesEveryEligibilityFieldAcrossTerminalOrigins() = runTest {
        val cases = listOf(
            CompletionCase("automatic-target", SetEndReason.TARGET_REPS_REACHED, CompletionOrigin.AUTO_TARGET, targetReps = 3, actualReps = 3),
            CompletionCase("automatic-stall", SetEndReason.STALL_FAILURE, actualReps = 4),
            CompletionCase("vbt", SetEndReason.VBT_AUTO_END, targetReps = 10, actualReps = 4),
            CompletionCase("manual-stop", SetEndReason.USER_STOPPED, CompletionOrigin.MANUAL_STOP, targetReps = 7, actualReps = 2),
            CompletionCase("timed-cable", SetEndReason.TIMER_EXPIRED, timed = true, targetReps = 9, actualReps = 3),
            CompletionCase("semantic-amrap", SetEndReason.STALL_FAILURE, amrap = true, targetReps = 8, actualReps = 5),
            CompletionCase(
                "echo",
                SetEndReason.STALL_FAILURE,
                mode = ProgramMode.Echo,
                echo = true,
                targetReps = 6,
                actualReps = 2,
            ),
            CompletionCase("just-lift", SetEndReason.STALL_FAILURE, justLift = true, targetReps = 5, actualReps = 2),
            CompletionCase("warmup", SetEndReason.STALL_FAILURE, warmup = true, targetReps = 4, actualReps = 1),
        )

        cases.forEachIndexed { index, case ->
            val result = captureCase(case, index)
            assertEquals(result.expected, result.claimed, case.name)
        }
    }

    @Test
    fun consecutiveExecutionsRetainCompleteDisjointActivationFacts() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routineA = cableRoutine(
                routineId = "routine-a",
                occurrenceId = "occurrence-a",
                weight = 30f,
                reps = 6,
                progression = 1f,
            )
            prepareRoutine(harness, routineA)
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    weightPerCableKg = 33f,
                    progressionRegressionKg = 1f,
                ),
            )
            val leaseA = startPreparedRoutine(harness)
            val routineSessionA = assertNotNull(harness.coordinator.currentRoutineSessionId)
            harness.coordinator._repCount.value = RepCount(workingReps = 2, totalReps = 2)
            harness.activeSessionEngine.handleSetCompletion(leaseA, SetEndReason.STALL_FAILURE)
            val completionA = assertNotNull(harness.activeSessionEngine.executionGuard.claimedCompletion(leaseA))
            val expectedA = expectedCompletion(
                lease = leaseA,
                reason = SetEndReason.STALL_FAILURE,
                routine = routineA,
                routineSessionId = routineSessionA,
                plannedSetType = SetType.STANDARD,
                mode = ProgramMode.OldSchool,
                programmedBase = 30f,
                configuredStart = 33f,
                progression = 1f,
                actualReps = 2,
                targetReps = 6,
            )

            harness.activeSessionEngine.resetForNewWorkout()
            advanceUntilIdle()
            val routineB = cableRoutine(
                routineId = "routine-b",
                occurrenceId = "occurrence-b",
                weight = 50f,
                reps = 9,
                progression = 2.5f,
                isAmrap = true,
            )
            prepareRoutine(harness, routineB)
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    programMode = ProgramMode.Echo,
                    weightPerCableKg = 44f,
                    progressionRegressionKg = 2.5f,
                    isAMRAP = true,
                ),
            )
            val leaseB = startPreparedRoutine(harness)
            val routineSessionB = assertNotNull(harness.coordinator.currentRoutineSessionId)
            harness.coordinator._repCount.value = RepCount(workingReps = 5, totalReps = 5)
            harness.activeSessionEngine.handleSetCompletion(leaseB, SetEndReason.VBT_AUTO_END)
            val completionB = assertNotNull(harness.activeSessionEngine.executionGuard.claimedCompletion(leaseB))
            val expectedB = expectedCompletion(
                lease = leaseB,
                reason = SetEndReason.VBT_AUTO_END,
                routine = routineB,
                routineSessionId = routineSessionB,
                plannedSetType = SetType.AMRAP,
                mode = ProgramMode.Echo,
                programmedBase = 50f,
                configuredStart = 44f,
                progression = 2.5f,
                actualReps = 5,
                targetReps = null,
                isEcho = true,
                isAmrap = true,
            )

            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    weightPerCableKg = 99f,
                    progressionRegressionKg = 9f,
                    programMode = ProgramMode.Pump,
                ),
            )
            harness.coordinator._repCount.value = RepCount(workingReps = 99, totalReps = 99)

            assertEquals(expectedA, completionA)
            assertEquals(expectedB, completionB)
            assertNotEquals(completionA.routineIdentity, completionB.routineIdentity)
            assertNotEquals(completionA.programmedBaseWeightPerCableKg, completionB.programmedBaseWeightPerCableKg)
            assertNotEquals(completionA.configuredStartWeightPerCableKg, completionB.configuredStartWeightPerCableKg)
            assertNotEquals(completionA.targetReps, completionB.targetReps)
            assertNotEquals(completionA.actualReps, completionB.actualReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun capturesProgrammedBaseAndAdjustedConfiguredStartBeforeRackOrLaterMutation() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = cableRoutine(weight = 50f)
            prepareRoutine(harness, routine)
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    weightPerCableKg = 55f,
                    progressionRegressionKg = 1.5f,
                    externalAddedLoadKg = 12f,
                    counterweightKg = 3f,
                ),
            )
            val lease = startPreparedRoutine(harness)

            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    programMode = ProgramMode.Pump,
                    weightPerCableKg = 90f,
                    progressionRegressionKg = 9f,
                ),
            )
            harness.coordinator._repCount.value = RepCount(workingReps = 4, totalReps = 4)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)

            val completion = assertNotNull(harness.activeSessionEngine.executionGuard.claimedCompletion(lease))
            assertEquals(50f, completion.programmedBaseWeightPerCableKg)
            assertEquals(55f, completion.configuredStartWeightPerCableKg)
            assertEquals(1.5f, completion.progressionKg)
            assertEquals(ProgramMode.OldSchool, completion.programMode)
            assertEquals(4, completion.actualReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun matchingPlannedSetTypeWinsOverTimedTransportAmrap() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = cableRoutine(weight = 25f, duration = 30)
            harness.fakeCompletedSetRepo.savePlannedSet(
                PlannedSet(
                    id = "planned-amrap",
                    routineExerciseId = routine.exercises.single().id,
                    setNumber = 0,
                    setType = SetType.AMRAP,
                    targetReps = null,
                    targetWeightKg = 25f,
                    targetRpe = null,
                    restSeconds = 0,
                ),
            )
            prepareRoutine(harness, routine)
            val lease = startPreparedRoutine(harness)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TIMER_EXPIRED)

            val completion = assertNotNull(harness.activeSessionEngine.executionGuard.claimedCompletion(lease))
            assertEquals(SetType.AMRAP, completion.plannedSetType)
            assertEquals("planned-amrap", completion.routineIdentity?.plannedSetId)
            assertEquals(SetType.AMRAP, completion.routineIdentity?.logicalSetKey?.setKind)
            assertEquals(true, completion.isTimed)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun semanticStandardTimedCableDoesNotBecomeAmrapFromTransport() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareRoutine(harness, cableRoutine(weight = 25f, duration = 30))
            val lease = startPreparedRoutine(harness)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TIMER_EXPIRED)

            val completion = assertNotNull(harness.activeSessionEngine.executionGuard.claimedCompletion(lease))
            assertEquals(SetType.STANDARD, completion.plannedSetType)
            assertFalse(completion.isAmrap)
            assertEquals(true, completion.isTimed)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun bodyweightCompletionCapturesConfirmedFinalReps() = runTest {
        lateinit var harness: DWSMTestHarness
        var completionImmediatelyBeforeFirstClaim: SetExecutionCompletion? = null
        harness = DWSMTestHarness(
            testScope = this,
            beforeBodyweightCompletionClaim = { _, _ ->
                completionImmediatelyBeforeFirstClaim = harness.activeSessionEngine.executionGuard
                    .claimedCompletion(harness.activeSessionEngine.currentExecutionLeaseForTest())
            },
        )
        try {
            val bodyweight = RoutineExercise(
                id = "push-up-occurrence",
                exercise = Exercise(
                    id = "push-up",
                    name = "Push Up",
                    muscleGroup = "Chest",
                    equipment = "Bodyweight",
                ),
                orderIndex = 0,
                setReps = listOf(10),
                weightPerCableKg = 0f,
                duration = 1,
                setRestSeconds = listOf(0),
            )
            prepareRoutine(harness, Routine("bodyweight-routine", "Bodyweight", exercises = listOf(bodyweight)))
            val lease = startPreparedRoutine(harness, connectMachine = false)
            val routineSessionId = assertNotNull(harness.coordinator.currentRoutineSessionId)
            advanceUntilIdle()
            assertIs<WorkoutState.BodyweightRepEntry>(harness.coordinator._workoutState.value)
            val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.coordinator._workoutState.value)

            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    programMode = ProgramMode.Pump,
                    weightPerCableKg = 90f,
                ),
            )
            harness.dwsm.confirmBodyweightSetResult(7, entry.selectedVariant)
            runCurrent()

            val completion = assertNotNull(harness.activeSessionEngine.executionGuard.claimedCompletion(lease))
            assertNull(completionImmediatelyBeforeFirstClaim)
            assertEquals(
                expectedCompletion(
                    lease = lease,
                    reason = SetEndReason.TIMER_EXPIRED,
                    routine = Routine("bodyweight-routine", "Bodyweight", exercises = listOf(bodyweight)),
                    routineSessionId = routineSessionId,
                    plannedSetType = SetType.STANDARD,
                    mode = ProgramMode.OldSchool,
                    programmedBase = 0f,
                    configuredStart = 0f,
                    progression = 0f,
                    actualReps = 7,
                    targetReps = null,
                    isBodyweight = true,
                    isTimed = true,
                    isCable = false,
                ),
                completion,
            )
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun prepareRoutine(harness: DWSMTestHarness, routine: Routine) {
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
    }

    private fun startPreparedRoutine(
        harness: DWSMTestHarness,
        connectMachine: Boolean = true,
        isJustLift: Boolean = false,
    ): ExecutionLease {
        if (connectMachine) harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = isJustLift)
        harness.testScope.testScheduler.advanceUntilIdle()
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private fun cableRoutine(
        weight: Float,
        duration: Int? = null,
        routineId: String = "routine",
        occurrenceId: String = "bench-occurrence",
        reps: Int = 8,
        progression: Float = 1.5f,
        isAmrap: Boolean = false,
    ) = Routine(
        id = routineId,
        name = "Routine",
        exercises = listOf(
            RoutineExercise(
                id = occurrenceId,
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(reps),
                weightPerCableKg = weight,
                duration = duration,
                progressionKg = progression,
                isAMRAP = isAmrap,
                setRestSeconds = listOf(0),
            ),
        ),
    )

    private suspend fun TestScope.captureCase(case: CompletionCase, index: Int): CaptureResult {
        lateinit var harness: DWSMTestHarness
        var claimedAtFirstClaim: SetExecutionCompletion? = null
        var capturedLease: ExecutionLease? = null
        harness = DWSMTestHarness(
            testScope = this,
            afterCompletionClaim = { executionId, sessionId, _ ->
                val lease = capturedLease
                if (lease != null && lease.executionId == executionId && lease.sessionId == sessionId) {
                    claimedAtFirstClaim = harness.activeSessionEngine.executionGuard.claimedCompletion(lease)
                }
            },
        )
        try {
            val routine = cableRoutine(
                routineId = "routine-$index",
                occurrenceId = "occurrence-$index",
                weight = 20f + index,
                duration = 30.takeIf { case.timed },
                reps = case.targetReps,
                progression = 0.5f + index,
                isAmrap = case.amrap,
            )
            prepareRoutine(harness, routine)
            if (case.warmup) harness.coordinator._currentWarmupSetIndex.value = 0
            val configuredStart = 30f + index
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator._workoutParameters.value.copy(
                    programMode = case.mode,
                    reps = case.targetReps,
                    weightPerCableKg = configuredStart,
                    progressionRegressionKg = 0.5f + index,
                    isAMRAP = case.amrap,
                    isJustLift = case.justLift,
                ),
            )
            val lease = startPreparedRoutine(harness, isJustLift = case.justLift)
            capturedLease = lease
            val routineSessionId = assertNotNull(harness.coordinator.currentRoutineSessionId)
            harness.coordinator._repCount.value = RepCount(workingReps = case.actualReps, totalReps = case.actualReps)

            when (case.origin) {
                CompletionOrigin.DIRECT -> harness.activeSessionEngine.handleSetCompletion(lease, case.reason)
                CompletionOrigin.AUTO_TARGET -> harness.repCounter.onRepEvent?.invoke(
                    RepEvent(RepType.WORKOUT_COMPLETE, warmupCount = 0, workingCount = case.actualReps),
                )
                CompletionOrigin.MANUAL_STOP -> harness.dwsm.stopWorkout(exitingWorkout = false)
            }
            testScheduler.advanceUntilIdle()

            val plannedSetType = if (case.amrap) SetType.AMRAP else SetType.STANDARD
            val expected = expectedCompletion(
                lease = lease,
                reason = case.reason,
                routine = routine,
                routineSessionId = routineSessionId,
                plannedSetType = plannedSetType,
                mode = case.mode,
                programmedBase = 20f + index,
                configuredStart = configuredStart,
                progression = 0.5f + index,
                actualReps = case.actualReps,
                targetReps = case.targetReps.takeUnless { case.amrap || case.timed },
                isWarmup = case.warmup,
                isEcho = case.echo,
                isJustLift = case.justLift,
                isTimed = case.timed,
                isAmrap = case.amrap,
            )
            return CaptureResult(expected, assertNotNull(claimedAtFirstClaim, case.name))
        } finally {
            harness.cleanup()
        }
    }

    private fun expectedCompletion(
        lease: ExecutionLease,
        reason: SetEndReason,
        routine: Routine,
        routineSessionId: String,
        plannedSetType: SetType,
        mode: ProgramMode,
        programmedBase: Float,
        configuredStart: Float,
        progression: Float,
        actualReps: Int,
        targetReps: Int?,
        isWarmup: Boolean = false,
        isEcho: Boolean = false,
        isJustLift: Boolean = false,
        isBodyweight: Boolean = false,
        isTimed: Boolean = false,
        isAmrap: Boolean = false,
        isCable: Boolean = true,
    ): SetExecutionCompletion {
        val occurrence = routine.exercises.single()
        val logicalSetKey = LogicalSetKey(routineSessionId, occurrence.id, 0, plannedSetType)
        return SetExecutionCompletion(
            lease = lease,
            reason = reason,
            routineIdentity = RoutineExecutionIdentity(
                profileId = lease.profileId,
                routineId = routine.id,
                routineSessionId = routineSessionId,
                routineExerciseId = occurrence.id,
                logicalSetKey = logicalSetKey,
                plannedSetId = null,
                exerciseIndex = 0,
                setIndex = 0,
            ),
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetType = plannedSetType,
            programMode = mode,
            programmedBaseWeightPerCableKg = programmedBase,
            configuredStartWeightPerCableKg = configuredStart,
            progressionKg = progression,
            actualReps = actualReps,
            targetReps = targetReps,
            isWarmup = isWarmup,
            isEcho = isEcho,
            isJustLift = isJustLift,
            isBodyweight = isBodyweight,
            isTimed = isTimed,
            isAmrap = isAmrap,
            isCableExercise = isCable,
        )
    }

    private enum class CompletionOrigin { DIRECT, AUTO_TARGET, MANUAL_STOP }

    private data class CompletionCase(
        val name: String,
        val reason: SetEndReason,
        val origin: CompletionOrigin = CompletionOrigin.DIRECT,
        val mode: ProgramMode = ProgramMode.OldSchool,
        val targetReps: Int = 8,
        val actualReps: Int,
        val timed: Boolean = false,
        val amrap: Boolean = false,
        val echo: Boolean = false,
        val justLift: Boolean = false,
        val warmup: Boolean = false,
    )

    private data class CaptureResult(val expected: SetExecutionCompletion, val claimed: SetExecutionCompletion)
}
