package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightUserProfileRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightUserProfileRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightUserProfileRepository(database)
    }

    @Test
    fun `default profile exists on init`() = runTest {
        repository.refreshProfiles()

        val active = repository.activeProfile.value
        val all = repository.allProfiles.value

        assertEquals("default", active?.id)
        assertEquals(1, all.size)
    }

    @Test
    fun `createProfile and setActiveProfile updates active`() = runTest {
        val created = repository.createProfile("Alex", 2)
        repository.setActiveProfile(created.id)

        assertEquals(created.id, repository.activeProfile.value?.id)
        assertTrue(repository.allProfiles.value.any { it.id == created.id })
    }

    @Test
    fun `deleteProfile prevents deleting default and resets active`() = runTest {
        val created = repository.createProfile("Jordan", 1)
        repository.setActiveProfile(created.id)

        val deleteDefault = repository.deleteProfile("default")
        val deleteCreated = repository.deleteProfile(created.id)

        assertFalse(deleteDefault)
        assertTrue(deleteCreated)
        assertEquals("default", repository.activeProfile.value?.id)
    }

    @Test
    fun `deleteProfile recomputes merged gamification stats without duplicate target rows`() = runTest {
        val created = repository.createProfile("Jordan", 1)
        val gamificationRepository = SqlDelightGamificationRepository(database)

        insertWorkoutSession(id = "session-default", totalReps = 10, weightPerCableKg = 20.0, profileId = "default")
        insertWorkoutSession(id = "session-created", totalReps = 12, weightPerCableKg = 25.0, profileId = created.id)

        gamificationRepository.updateStats("default")
        gamificationRepository.updateStats(created.id)

        assertEquals(2, database.vitruvianDatabaseQueries.selectGamificationStatsSync().executeAsList().size)

        val deleted = repository.deleteProfile(created.id)

        assertTrue(deleted)

        val remainingRows = database.vitruvianDatabaseQueries
            .selectGamificationStatsSync()
            .executeAsList()
            .filter { it.profile_id == "default" }
        assertEquals(1, remainingRows.size)

        val merged = database.vitruvianDatabaseQueries.selectGamificationStats("default").executeAsOneOrNull()
        assertNotNull(merged)
        assertEquals(2, merged.totalWorkouts.toInt())
        assertEquals(22, merged.totalReps.toInt())
    }

    private fun insertWorkoutSession(id: String, totalReps: Long, weightPerCableKg: Double, profileId: String) {
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
            profile_id = profileId,
        )
    }
}
