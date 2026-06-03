package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqlDelightSyncRepositoryTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var repository: SqlDelightSyncRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        userProfileRepository = FakeUserProfileRepository()
        userProfileRepository.setActiveProfileForTest(id = "active-profile")
        repository = SqlDelightSyncRepository(database, userProfileRepository)
    }

    @Test
    fun `mergeSessions uses active profile id`() = runTest {
        repository.mergeSessions(
            sessions = listOf(
                WorkoutSessionSyncDto(
                    clientId = "session-profile-b",
                    serverId = "server-session-profile-b",
                    timestamp = 1_700_000_000_000,
                    mode = "Old School",
                    targetReps = 8,
                    weightPerCableKg = 42.5f,
                    duration = 120,
                    totalReps = 24,
                    exerciseId = "bench",
                    exerciseName = "Bench Press",
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_100,
                ),
            ),
        )

        val session = database.vitruvianDatabaseQueries
            .selectSessionById("session-profile-b")
            .executeAsOneOrNull()

        assertNotNull(session)
        assertEquals("active-profile", session.profile_id)
    }

    @Test
    fun `mergePRs uses active profile id`() = runTest {
        repository.mergePRs(
            records = listOf(
                PersonalRecordSyncDto(
                    clientId = "pr-profile-b",
                    serverId = "server-pr-profile-b",
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 85f,
                    reps = 5,
                    oneRepMax = 99.17f,
                    achievedAt = 1_700_000_000_000,
                    workoutMode = "Old School",
                    prType = PRType.MAX_WEIGHT.name,
                    volume = 425f,
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_100,
                ),
            ),
        )

        val profileRecord = database.vitruvianDatabaseQueries.selectPR(
            exerciseId = "deadlift",
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "active-profile",
        ).executeAsOneOrNull()

        assertNotNull(profileRecord)
        assertEquals("active-profile", profileRecord.profile_id)
    }

    @Test
    fun `getExerciseMuscleGroup resolves by id then name and is null for unknown`() = runTest {
        // Seed one catalog exercise. Positional args follow the insertExercise
        // column order: id, name, displayName, description, created, muscleGroup,
        // muscleGroups, muscles, equipment, movement, sidedness, grip, gripWidth,
        // minRepRange, popularity, archived, isFavorite, isCustom, timesPerformed,
        // lastPerformed, aliases, defaultCableConfig, one_rep_max_kg.
        database.vitruvianDatabaseQueries.insertExercise(
            "bench-press",
            "Bench Press",
            "Bench Press",
            null,
            0L,
            "Chest",
            "Chest",
            null,
            "BAR",
            null,
            null,
            null,
            null,
            null,
            0.0,
            0L,
            0L,
            0L,
            0L,
            null,
            null,
            "DUAL",
            null,
        )

        // Strategy 0: resolves by catalog id (unambiguous), ignoring name
        assertEquals("Chest", repository.getExerciseMuscleGroup("bench-press", "Mislabeled"))
        // Strategy 1: resolves by exact name when id is absent
        assertEquals("Chest", repository.getExerciseMuscleGroup(null, "Bench Press"))
        // Strategy 2: resolves case-insensitively by name
        assertEquals("Chest", repository.getExerciseMuscleGroup(null, "bench press"))
        // Miss: unknown exercise -> null so the caller defaults to "General"
        assertEquals(null, repository.getExerciseMuscleGroup("nope", "Totally Unknown"))
        // Miss: null/blank inputs -> null
        assertEquals(null, repository.getExerciseMuscleGroup(null, null))
    }

    @Test
    fun `backfillPhaseSpecificPRs creates phase records and preserves better existing phase PRs`() = runTest {
        insertHistoricalSession(
            id = "historical-bicep-curl",
            timestamp = 1_700_000_000_000,
            exerciseId = "bicep-curl",
            exerciseName = "Bicep Curl",
            workingReps = 8,
            peakConcentricA = 20.0,
            peakConcentricB = 18.0,
            peakEccentricA = 42.0,
            peakEccentricB = 39.0,
            profileId = "active-profile",
        )
        val prRepository = SqlDelightPersonalRecordRepository(database)
        prRepository.updatePhaseSpecificPRs(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            timestamp = 1_600_000_000_000,
            reps = 50,
            peakConcentricForceKg = 30f,
            peakEccentricForceKg = 0f,
            profileId = "active-profile",
            cableCount = 2,
        )

        val firstBackfillCount = repository.backfillPhaseSpecificPRs("active-profile")
        val secondBackfillCount = repository.backfillPhaseSpecificPRs("active-profile")

        assertEquals(2, firstBackfillCount, "Only the missing eccentric weight and volume PRs should be created")
        assertEquals(0, secondBackfillCount, "Backfill should be idempotent")
        val concentricWeight = database.vitruvianDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            phase = WorkoutPhase.CONCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()
        val concentricVolume = database.vitruvianDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_VOLUME.name,
            phase = WorkoutPhase.CONCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()
        val eccentricWeight = database.vitruvianDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            phase = WorkoutPhase.ECCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()
        val eccentricVolume = database.vitruvianDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_VOLUME.name,
            phase = WorkoutPhase.ECCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()

        assertEquals(30.0, concentricWeight.weight)
        assertEquals(1500.0, concentricVolume.volume)
        assertEquals(42.0, eccentricWeight.weight)
        assertEquals(336.0, eccentricVolume.volume)
    }

    @Test
    fun `mergeRoutines uses active profile id`() = runTest {
        repository.mergeRoutines(
            routines = listOf(
                RoutineSyncDto(
                    clientId = "routine-profile-b",
                    serverId = "server-routine-profile-b",
                    name = "Pull Day",
                    description = "Synced routine",
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_100,
                ),
            ),
        )

        val routine = database.vitruvianDatabaseQueries
            .selectRoutineById("routine-profile-b")
            .executeAsOneOrNull()

        assertNotNull(routine)
        assertEquals("active-profile", routine.profile_id)
    }

    private fun insertHistoricalSession(
        id: String,
        timestamp: Long,
        exerciseId: String,
        exerciseName: String,
        workingReps: Long,
        peakConcentricA: Double?,
        peakConcentricB: Double?,
        peakEccentricA: Double?,
        peakEccentricB: Double?,
        profileId: String,
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = "OldSchool",
            targetReps = workingReps,
            weightPerCableKg = 20.0,
            progressionKg = 0.0,
            duration = 60_000L,
            totalReps = workingReps,
            warmupReps = 0L,
            workingReps = workingReps,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            routineSessionId = null,
            routineName = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = peakConcentricA,
            peakForceConcentricB = peakConcentricB,
            peakForceEccentricA = peakEccentricA,
            peakForceEccentricB = peakEccentricB,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = 2L,
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
            display_multiplier = 2L,
        )
    }
}
