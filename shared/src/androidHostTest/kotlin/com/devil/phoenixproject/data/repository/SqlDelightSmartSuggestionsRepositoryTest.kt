package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.volumeKg
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightSmartSuggestionsRepositoryTest {

    private lateinit var database: PhoenixDatabase
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

    @Test
    fun `getExerciseWeightHistory includes assessment sessions with total reps`() = runTest {
        insertWorkoutSession(
            id = "session-assessment",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 1_000L,
            weightPerCableKg = 13.0,
            heaviestLiftKg = null,
            profileId = "default",
            totalReps = 9L,
            workingReps = 0L,
            routineName = SqlDelightAssessmentRepository.ASSESSMENT_ROUTINE_NAME,
        )
        insertWorkoutSession(
            id = "session-empty",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 2_000L,
            weightPerCableKg = 15.0,
            heaviestLiftKg = null,
            profileId = "default",
            totalReps = 0L,
            workingReps = 0L,
        )

        val history = repository.getExerciseWeightHistory("default")

        assertEquals(1, history.size)
        assertEquals(listOf(13.0f), history.map { it.weightPerCableKg })
    }

    @Test
    fun `getExerciseWeightHistory suppresses unmeasured echo rows and never falls back to the echo seed`() = runTest {
        // Issue #1182 (R4): the configured Echo seed is a placeholder, not an achieved load.
        // Post-#1182 unmeasured sentinel row -> suppressed.
        insertWorkoutSession(
            id = "echo-sentinel",
            exerciseId = "row",
            exerciseName = "Row",
            timestamp = 1_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 0.0,
            profileId = "default",
            mode = "Echo",
        )
        // Legacy placeholder row (fallback == seed, no telemetry evidence) -> suppressed.
        insertWorkoutSession(
            id = "echo-legacy-placeholder",
            exerciseId = "row",
            exerciseName = "Row",
            timestamp = 2_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 5.0,
            profileId = "default",
            mode = "Echo",
        )
        // Non-Echo rows keep the programmed-weight fallback (unchanged contract).
        insertWorkoutSession(
            id = "non-echo-fallback",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            timestamp = 3_000L,
            weightPerCableKg = 7.5,
            heaviestLiftKg = null,
            profileId = "default",
        )

        val history = repository.getExerciseWeightHistory("default")

        assertEquals(listOf(7.5f), history.map { it.weightPerCableKg })
    }

    @Test
    fun `getExerciseWeightHistory keeps measured echo rows including measured equal to configured with evidence`() = runTest {
        insertWorkoutSession(
            id = "echo-measured",
            exerciseId = "row",
            exerciseName = "Row",
            timestamp = 1_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 22.5,
            profileId = "default",
            mode = "Echo",
        )
        insertWorkoutSession(
            id = "echo-equal-with-evidence",
            exerciseId = "row",
            exerciseName = "Row",
            timestamp = 2_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 5.0,
            profileId = "default",
            mode = "Echo",
            peakForceConcentricA = 9.4,
        )

        val history = repository.getExerciseWeightHistory("default")

        assertEquals(listOf(22.5f, 5.0f), history.map { it.weightPerCableKg })
    }

    @Test
    fun `getSessionSummariesSince carries echo provenance for volume routing`() = runTest {
        // Issue #1182 (R4): smart-suggestion volume uses the already-stored measured volume
        // for Echo, gated on measured provenance - never weightPerCableKg * reps off the seed.
        insertWorkoutSession(
            id = "echo-measured-vol",
            exerciseId = "row",
            exerciseName = "Row",
            timestamp = 1_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 80.0,
            profileId = "default",
            mode = "Echo",
            workingReps = 9L,
            totalVolumeKg = 1_440.0,
            peakForceConcentricA = 88.0,
        )
        insertWorkoutSession(
            id = "echo-unmeasured-vol",
            exerciseId = "squat",
            exerciseName = "Squat",
            timestamp = 2_000L,
            weightPerCableKg = 5.0,
            heaviestLiftKg = 0.0,
            profileId = "default",
            mode = "Echo",
            workingReps = 9L,
            totalVolumeKg = 90.0,
        )

        val summaries = repository.getSessionSummariesSince(0L, "default")

        val measured = summaries.single { it.exerciseId == "row" }
        assertTrue(measured.isEcho)
        assertEquals(80f, measured.measuredPeakKg)
        assertEquals(1_440f, measured.measuredTotalVolumeKg)
        assertTrue(measured.hasForceTelemetry)
        assertEquals(1_440f, measured.volumeKg, "Echo volume is the already-stored measured volume")

        val unmeasured = summaries.single { it.exerciseId == "squat" }
        assertTrue(unmeasured.isEcho)
        assertNull(unmeasured.volumeKg, "an unmeasured Echo session contributes NO volume - never the configured seed")
    }

    private fun insertWorkoutSession(
        id: String,
        exerciseId: String,
        exerciseName: String,
        timestamp: Long,
        weightPerCableKg: Double,
        heaviestLiftKg: Double?,
        profileId: String,
        totalReps: Long = 8L,
        workingReps: Long = totalReps,
        routineName: String? = null,
        mode: String = "Old School",
        totalVolumeKg: Double? = null,
        peakForceConcentricA: Double? = null,
    ) {
        database.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = mode,
            targetReps = 8L,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = totalReps,
            warmupReps = 0L,
            workingReps = workingReps,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 0L,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            routineSessionId = null,
            routineName = routineName,
            routineId = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = peakForceConcentricA,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = heaviestLiftKg,
            totalVolumeKg = totalVolumeKg,
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
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }
}
