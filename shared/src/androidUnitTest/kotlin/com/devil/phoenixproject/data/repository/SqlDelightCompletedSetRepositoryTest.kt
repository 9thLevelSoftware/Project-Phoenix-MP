package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightCompletedSetRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightCompletedSetRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightCompletedSetRepository(database)
        insertRoutine("routine-1")
        insertRoutineExercise("exercise-1", "routine-1", "Bench Press")
        insertWorkoutSession("session-1", "bench")
    }

    @Test
    fun `savePlannedSet and getPlannedSets returns ordered sets`() = runTest {
        repository.savePlannedSet(plannedSet("set-1", "exercise-1", 2))
        repository.savePlannedSet(plannedSet("set-2", "exercise-1", 1))

        val planned = repository.getPlannedSets("exercise-1")

        assertEquals(2, planned.size)
        assertEquals(1, planned.first().setNumber)
    }

    @Test
    fun `updatePlannedSet updates fields`() = runTest {
        val set = plannedSet("set-1", "exercise-1", 1, targetReps = 8)
        repository.savePlannedSet(set)

        repository.updatePlannedSet(set.copy(targetReps = 10, restSeconds = 90))

        val updated = repository.getPlannedSets("exercise-1").first()
        assertEquals(10, updated.targetReps)
        assertEquals(90, updated.restSeconds)
    }

    @Test
    fun `saveCompletedSet updates RPE and PR flags`() = runTest {
        val completed = completedSet("cset-1", "session-1", setNumber = 1)
        repository.saveCompletedSet(completed)

        repository.updateRpe("cset-1", 8)
        repository.markAsPr("cset-1")

        val updated = repository.getCompletedSets("session-1").first()
        assertEquals(8, updated.loggedRpe)
        assertTrue(updated.isPr)
    }

    @Test
    fun `getCompletedSetsForExercise filters by session exercise`() = runTest {
        repository.saveCompletedSet(completedSet("cset-1", "session-1", setNumber = 1))
        repository.saveCompletedSet(completedSet("cset-2", "session-1", setNumber = 2))

        val sets = repository.getCompletedSetsForExercise("bench")

        assertEquals(2, sets.size)
    }

    private fun plannedSet(
        id: String,
        routineExerciseId: String,
        setNumber: Int,
        targetReps: Int = 10,
        targetWeightKg: Float = 40f,
        restSeconds: Int? = 60
    ) = PlannedSet(
        id = id,
        routineExerciseId = routineExerciseId,
        setNumber = setNumber,
        setType = SetType.STANDARD,
        targetReps = targetReps,
        targetWeightKg = targetWeightKg,
        targetRpe = null,
        restSeconds = restSeconds
    )

    private fun completedSet(
        id: String,
        sessionId: String,
        setNumber: Int
    ) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = setNumber,
        setType = SetType.STANDARD,
        actualReps = 8,
        actualWeightKg = 40f,
        loggedRpe = null,
        isPr = false,
        completedAt = 1000L + setNumber
    )

    private fun insertRoutine(id: String) {
        database.vitruvianDatabaseQueries.insertRoutine(
            id = id,
            name = "Test Routine",
            description = "",
            createdAt = 0L,
            lastUsed = null,
            useCount = 0L
        )
    }

    private fun insertRoutineExercise(id: String, routineId: String, name: String) {
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = id,
            routineId = routineId,
            exerciseName = name,
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "BAR",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = "bench",
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10,10,10",
            weightPerCableKg = 40.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 60L,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0L,
            isAMRAP = 0L,
            supersetId = null,
            orderInSuperset = 0L,
            usePercentOfPR = 0L,
            weightPercentOfPR = 80L,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null
        )
    }

    private fun insertWorkoutSession(id: String, exerciseId: String) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = 0L,
            mode = "OldSchool",
            targetReps = 10L,
            weightPerCableKg = 40.0,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = 0L,
            warmupReps = 0L,
            workingReps = 0L,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = exerciseId,
            exerciseName = "Bench Press",
            routineSessionId = null,
            routineName = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null
        )
    }
}
