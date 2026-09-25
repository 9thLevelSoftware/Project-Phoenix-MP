package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadAdjustment
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RoutineResumeExactLoaderRaceTest {
    @Test
    fun `daily Resume loader losing its action token during PR resolution leaves exact B configuration untouched`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseResolution = CompletableDeferred<Unit>()
        try {
            advanceUntilIdle()
            val expected = harness.installSentinelB()
            val candidateA = percentOfPrRoutine("daily-a")
            harness.seedPr(candidateA)

            val resolutionEntered = CompletableDeferred<Unit>()
            harness.fakePRRepo.beforeGetBestWeightPRReturn = { _, _, _, _ ->
                resolutionEntered.complete(Unit)
                releaseResolution.await()
            }
            val actionToken = 41L
            var currentActionToken = actionToken

            val result = async {
                harness.dwsm.loadRoutineForResumeAsync(candidateA) {
                    currentActionToken == actionToken
                }
            }
            runCurrent()
            resolutionEntered.await()

            currentActionToken = 42L
            releaseResolution.complete(Unit)

            assertFalse(result.await())
            harness.assertSentinelB(expected)
        } finally {
            releaseResolution.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `cycle Resume loader losing its action token during PR resolution cannot publish A cycle over B`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseResolution = CompletableDeferred<Unit>()
        try {
            advanceUntilIdle()
            val expected = harness.installSentinelB()
            val candidateA = percentOfPrRoutine("cycle-a")
            harness.seedPr(candidateA)

            val resolutionEntered = CompletableDeferred<Unit>()
            harness.fakePRRepo.beforeGetBestWeightPRReturn = { _, _, _, _ ->
                resolutionEntered.complete(Unit)
                releaseResolution.await()
            }
            val actionToken = 73L
            var currentActionToken = actionToken

            val result = async {
                harness.dwsm.loadRoutineFromCycleForResumeAsync(
                    routine = candidateA,
                    cycleId = "cycle-a",
                    dayNumber = 2,
                    publicationStillCurrent = { currentActionToken == actionToken },
                )
            }
            runCurrent()
            resolutionEntered.await()

            currentActionToken = 74L
            releaseResolution.complete(Unit)

            assertFalse(result.await())
            harness.assertSentinelB(expected)
        } finally {
            releaseResolution.complete(Unit)
            harness.cleanup()
        }
    }

    private data class SentinelConfiguration(
        val routine: Routine,
        val routineId: String,
        val routineName: String,
        val routineSessionId: String,
        val launchOrigin: RoutineLaunchOrigin,
        val cycleId: String,
        val cycleDayNumber: Int,
        val parameters: WorkoutParameters,
        val rackItemIds: List<String>,
        val rackBehaviorOverrides: Map<String, RackItemBehavior>,
        val rackAdjustment: RackLoadAdjustment,
        val rackItemsJson: String,
        val configurationInputEpoch: Long,
    )

    private fun DWSMTestHarness.installSentinelB(): SentinelConfiguration {
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 2,
            weightKg = 31f,
            repsPerSet = 9,
        ).copy(
            id = "sentinel-routine-b",
            name = "Sentinel Routine B",
        )
        val rackItem = RackItem(
            id = "sentinel-rack-b",
            name = "Sentinel Rack B",
            weightKg = 6f,
            behavior = RackItemBehavior.COUNTERWEIGHT,
        )
        val rackItemIds = listOf(rackItem.id)
        val rackBehaviorOverrides = mapOf(rackItem.id to RackItemBehavior.COUNTERWEIGHT)
        val rackAdjustment = RackLoadAdjustment(
            selectedItems = listOf(rackItem),
            counterweightKg = 6f,
            displayLoadKg = 6f,
            adjustedMachineWeightPerCableKg = 25f,
        )
        val parameters = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 13,
            weightPerCableKg = 31f,
            activeRackItemIds = rackItemIds,
            counterweightKg = 6f,
            progressionRegressionKg = 1.5f,
            warmupReps = 2,
        )
        val routineSessionId = "sentinel-session-b"
        val launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES
        val cycleId = "sentinel-cycle-b"
        val cycleDayNumber = 7
        val rackItemsJson = "[{\"id\":\"sentinel-rack-b\"}]"

        activeSessionEngine.mutateConfigurationInputs {
            coordinator._loadedRoutine.value = routine
            coordinator.currentRoutineId = routine.id
            coordinator.currentRoutineName = routine.name
            coordinator.currentRoutineSessionId = routineSessionId
            coordinator.routineLaunchOrigin = launchOrigin
            coordinator.activeCycleId = cycleId
            coordinator.activeCycleDayNumber = cycleDayNumber
            coordinator._currentExerciseIndex.value = 0
            coordinator._currentSetIndex.value = 1
            coordinator._workoutParameters.value = parameters
            coordinator._activeRackItemIds.value = rackItemIds
            coordinator._activeRackBehaviorOverrides.value = rackBehaviorOverrides
            coordinator._currentRackLoadAdjustment.value = rackAdjustment
            coordinator.currentRackItemsJson = rackItemsJson
        }

        return SentinelConfiguration(
            routine = routine,
            routineId = routine.id,
            routineName = routine.name,
            routineSessionId = routineSessionId,
            launchOrigin = launchOrigin,
            cycleId = cycleId,
            cycleDayNumber = cycleDayNumber,
            parameters = parameters,
            rackItemIds = rackItemIds,
            rackBehaviorOverrides = rackBehaviorOverrides,
            rackAdjustment = rackAdjustment,
            rackItemsJson = rackItemsJson,
            configurationInputEpoch = activeSessionEngine.executionGuard.captureConfigurationInputEpoch(),
        )
    }

    private fun DWSMTestHarness.assertSentinelB(expected: SentinelConfiguration) {
        assertEquals(expected.routine, coordinator.loadedRoutine.value)
        assertEquals(expected.routineId, coordinator.currentRoutineId)
        assertEquals(expected.routineName, coordinator.currentRoutineName)
        assertEquals(expected.routineSessionId, coordinator.currentRoutineSessionId)
        assertEquals(expected.launchOrigin, coordinator.routineLaunchOrigin)
        assertEquals(expected.cycleId, coordinator.activeCycleId)
        assertEquals(expected.cycleDayNumber, coordinator.activeCycleDayNumber)
        assertEquals(expected.parameters, coordinator.workoutParameters.value)
        assertEquals(expected.rackItemIds, coordinator.activeRackItemIds.value)
        assertEquals(expected.rackBehaviorOverrides, coordinator.activeRackBehaviorOverrides.value)
        assertEquals(expected.rackAdjustment, coordinator.currentRackLoadAdjustment.value)
        assertEquals(expected.rackItemsJson, coordinator.currentRackItemsJson)
        assertEquals(
            expected.configurationInputEpoch,
            activeSessionEngine.executionGuard.captureConfigurationInputEpoch(),
        )
    }

    private fun DWSMTestHarness.seedPr(routine: Routine) {
        val exercise = routine.exercises.single().exercise
        fakePRRepo.addRecord(
            PersonalRecord(
                id = 901L,
                exerciseId = requireNotNull(exercise.id),
                exerciseName = exercise.name,
                weightPerCableKg = 50f,
                reps = 5,
                oneRepMax = 56f,
                timestamp = 8_000L,
                workoutMode = ProgramMode.OldSchool.displayName,
                prType = PRType.MAX_WEIGHT,
                volume = 250f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = routine.profileId,
            ),
        )
    }

    private fun percentOfPrRoutine(id: String): Routine {
        val base = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 2,
            weightKg = 11f,
            repsPerSet = 8,
        )
        return base.copy(
            id = id,
            name = "Candidate $id",
            exercises = listOf(
                base.exercises.single().copy(
                    id = "$id-exercise",
                    usePercentOfPR = true,
                    weightPercentOfPR = 80,
                    setWeightsPercentOfPR = listOf(80, 80),
                    setWeightsPerCableKg = listOf(11f, 11f),
                ),
            ),
        )
    }
}
