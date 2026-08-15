package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
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
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DropSetCompletionCaptureTest {
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
            assertEquals(7, completion.actualReps)
            assertEquals(true, completion.isBodyweight)
            assertEquals(ProgramMode.OldSchool, completion.programMode)
            assertEquals(0f, completion.configuredStartWeightPerCableKg)
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

    private fun startPreparedRoutine(harness: DWSMTestHarness, connectMachine: Boolean = true): ExecutionLease {
        if (connectMachine) harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private fun cableRoutine(weight: Float, duration: Int? = null) = Routine(
        id = "routine",
        name = "Routine",
        exercises = listOf(
            RoutineExercise(
                id = "bench-occurrence",
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(8),
                weightPerCableKg = weight,
                duration = duration,
                progressionKg = 1.5f,
                setRestSeconds = listOf(0),
            ),
        ),
    )
}
