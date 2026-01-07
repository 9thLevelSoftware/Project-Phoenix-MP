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

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightGamificationRepository(database)
    }

    @Test
    fun `awardBadge stores earned badge and markBadgeCelebrated updates it`() = runTest {
        val awarded = repository.awardBadge("workouts_1")
        assertTrue(awarded)

        repository.getEarnedBadges().test {
            val earned = awaitItem()
            assertEquals(1, earned.size)
            cancelAndIgnoreRemainingEvents()
        }

        repository.markBadgeCelebrated("workouts_1")
        repository.getUncelebratedBadges().test {
            val uncelebrated = awaitItem()
            assertTrue(uncelebrated.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateStats calculates workout totals`() = runTest {
        insertWorkoutSession(id = "session-1", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats()

        repository.getGamificationStats().test {
            val stats = awaitItem()
            assertEquals(1, stats.totalWorkouts)
            assertEquals(10, stats.totalReps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `checkAndAwardBadges awards first workout badge`() = runTest {
        insertWorkoutSession(id = "session-2", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats()

        val badges = repository.checkAndAwardBadges()

        assertTrue(badges.any { it.id == "workouts_1" })
    }

    private fun insertWorkoutSession(id: String, totalReps: Long, weightPerCableKg: Double) {
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
            exerciseId = "bench",
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
