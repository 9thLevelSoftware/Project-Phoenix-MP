package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightGamificationRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightGamificationRepository
    private val profileId = "default"

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightGamificationRepository(database)
    }

    @Test
    fun `awardBadge stores earned badge and markBadgeCelebrated updates it`() = runTest {
        val awarded = repository.awardBadge("workouts_1", profileId)
        assertTrue(awarded)

        repository.getEarnedBadges(profileId).test {
            val earned = awaitItem()
            assertEquals(1, earned.size)
            cancelAndIgnoreRemainingEvents()
        }

        repository.markBadgeCelebrated("workouts_1", profileId)
        repository.getUncelebratedBadges(profileId).test {
            val uncelebrated = awaitItem()
            assertTrue(uncelebrated.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateStats calculates workout totals`() = runTest {
        insertWorkoutSession(id = "session-1", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats(profileId)

        repository.getGamificationStats(profileId).test {
            val stats = awaitItem()
            assertEquals(1, stats.totalWorkouts)
            assertEquals(10, stats.totalReps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateStats ignores zero-rep sessions and tracks valid untagged sessions`() = runTest {
        insertWorkoutSession(id = "session-valid-tagged", totalReps = 10, weightPerCableKg = 20.0, exerciseId = "bench")
        insertWorkoutSession(id = "session-valid-untagged", totalReps = 8, weightPerCableKg = 15.0, exerciseId = null)
        insertWorkoutSession(id = "session-invalid", totalReps = 0, weightPerCableKg = 20.0, exerciseId = "squat")

        repository.updateStats(profileId)

        repository.getGamificationStats(profileId).test {
            val stats = awaitItem()
            assertEquals(2, stats.totalWorkouts)
            assertEquals(18, stats.totalReps)
            assertEquals(1, stats.uniqueExercisesUsed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `checkAndAwardBadges awards rep exercise and routine completion badges from valid sessions`() = runTest {
        repeat(5) { index ->
            insertWorkoutSession(
                id = "exercise-session-$index",
                totalReps = 20,
                weightPerCableKg = 20.0,
                exerciseId = "exercise-$index",
            )
        }
        repeat(10) { index ->
            insertWorkoutSession(
                id = "routine-session-$index",
                totalReps = 1,
                weightPerCableKg = 10.0,
                exerciseId = "exercise-0",
                routineSessionId = "routine-run-$index",
            )
        }
        insertWorkoutSession(
            id = "invalid-routine-session",
            totalReps = 0,
            weightPerCableKg = 10.0,
            exerciseId = "invalid-exercise",
            routineSessionId = "invalid-routine-run",
        )

        repository.updateStats(profileId)
        val badges = repository.checkAndAwardBadges(profileId).map { it.id }.toSet()

        assertTrue("reps_100" in badges)
        assertTrue("exercises_5" in badges)
        assertTrue("routines_completed_10" in badges)
    }

    @Test
    fun `checkAndAwardBadges awards first workout badge`() = runTest {
        insertWorkoutSession(id = "session-2", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats(profileId)

        val badges = repository.checkAndAwardBadges(profileId)

        assertTrue(badges.any { it.id == "workouts_1" })
    }

    private fun insertWorkoutSession(
        id: String,
        totalReps: Long,
        weightPerCableKg: Double,
        exerciseId: String? = "bench",
        routineSessionId: String? = null,
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = 1_000_000L,
            mode = "OldSchool",
            targetReps = totalReps,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = totalReps,
            warmupReps = 0L,
            workingReps = totalReps,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = exerciseId,
            exerciseName = exerciseId?.let { "Exercise $it" },
            routineSessionId = routineSessionId,
            routineName = routineSessionId?.let { "Routine $it" },
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
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            routineId = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "default",
            display_multiplier = null,
        )
    }
}
