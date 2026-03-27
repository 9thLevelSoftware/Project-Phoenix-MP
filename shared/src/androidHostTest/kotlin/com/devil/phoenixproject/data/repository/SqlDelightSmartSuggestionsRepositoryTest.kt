package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightSmartSuggestionsRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightSmartSuggestionsRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightSmartSuggestionsRepository(database)
    }

    @Test
    fun `getExerciseWeightHistory prefers achieved load and falls back to programmed weight`() = runTest {
        insertWorkoutSession(
            id = "session-achieved",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 1_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 22.5,
            profileId = "default",
        )
        insertWorkoutSession(
            id = "session-fallback",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 2_000L,
            weightPerCableKg = 7.5,
            heaviestLiftKg = null,
            profileId = "default",
        )

        val history = repository.getExerciseWeightHistory("default")

        assertEquals(2, history.size)
        assertEquals(22.5f, history[0].weightPerCableKg)
        assertEquals(7.5f, history[1].weightPerCableKg)
    }

    @Test
    fun `getExerciseWeightHistory remains profile scoped`() = runTest {
        insertWorkoutSession(
            id = "session-default",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 1_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 22.5,
            profileId = "default",
        )
        insertWorkoutSession(
            id = "session-profile-b",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 2_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 40.0,
            profileId = "profile-b",
        )

        val defaultHistory = repository.getExerciseWeightHistory("default")
        val profileBHistory = repository.getExerciseWeightHistory("profile-b")

        assertEquals(listOf(22.5f), defaultHistory.map { it.weightPerCableKg })
        assertEquals(listOf(40.0f), profileBHistory.map { it.weightPerCableKg })
    }

    private fun insertWorkoutSession(
        id: String,
        exerciseId: String,
        exerciseName: String,
        timestamp: Long,
        weightPerCableKg: Double,
        heaviestLiftKg: Double?,
        profileId: String,
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = "Old School",
            targetReps = 8L,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = 8L,
            warmupReps = 0L,
            workingReps = 8L,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 0L,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            routineSessionId = null,
            routineName = null,
            routineId = null,
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
            heaviestLiftKg = heaviestLiftKg,
            totalVolumeKg = null,
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
        )
    }
}
