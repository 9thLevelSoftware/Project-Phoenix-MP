package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

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
    fun `mergeSessions preserves local rack context for existing legacy server session`() = runTest {
        val rackItemsJson = """[{"id":"vest","name":"Weighted vest"}]"""
        insertHistoricalSession(
            id = "local-rack-session",
            timestamp = 1_700_000_000_000,
            exerciseId = "pull-up",
            exerciseName = "Pull Up",
            workingReps = 8,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
            externalAddedLoadKg = 12.5,
            counterweightKg = 3.0,
            rackItemsJson = rackItemsJson,
        )
        database.vitruvianDatabaseQueries.updateSessionServerId("server-rack-session", "local-rack-session")

        repository.mergeSessions(
            sessions = listOf(
                WorkoutSessionSyncDto(
                    clientId = "remote-rack-session",
                    serverId = "server-rack-session",
                    timestamp = 1_700_000_000_100,
                    mode = "Old School",
                    targetReps = 10,
                    weightPerCableKg = 30f,
                    duration = 90,
                    totalReps = 10,
                    exerciseId = "pull-up",
                    exerciseName = "Pull Up",
                    createdAt = 1_700_000_000_100,
                    updatedAt = 1_700_000_000_200,
                ),
            ),
        )

        val session = database.vitruvianDatabaseQueries
            .selectSessionById("local-rack-session")
            .executeAsOne()

        assertEquals(12.5, session.externalAddedLoadKg)
        assertEquals(3.0, session.counterweightKg)
        assertEquals(rackItemsJson, session.rackItemsJson)
        assertEquals(2L, session.display_multiplier)
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
    fun `getFullPRsModifiedSince preserves profile id and cable count`() = runTest {
        database.vitruvianDatabaseQueries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 85.0,
            reps = 5L,
            oneRepMax = 99.17,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT.name,
            volume = 425.0,
            phase = WorkoutPhase.CONCENTRIC.name,
            profile_id = "active-profile",
            cable_count = 2L,
        )

        val records = repository.getFullPRsModifiedSince(0L, "active-profile")

        val record = assertNotNull(records.singleOrNull())
        assertEquals("active-profile", record.profileId)
        assertEquals(2, record.cableCount)
        assertEquals(WorkoutPhase.CONCENTRIC, record.phase)
    }

    @Test
    fun `getExerciseMuscleGroup resolves by id then name and is null for unknown`() = runTest {
        // Seed one catalog exercise. Positional args follow the insertExercise
        // column order: id, name, displayName, description, created, muscleGroup,
        // muscleGroups, muscles, equipment, movement, sidedness, grip, gripWidth,
        // minRepRange, popularity, archived, isFavorite, isCustom, timesPerformed,
        // lastPerformed, aliases, defaultCableConfig, one_rep_max_kg, mvtOverrideMs.
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

        val firstBackfill = repository.backfillPhaseSpecificPRs("active-profile")
        val secondBackfill = repository.backfillPhaseSpecificPRs(
            profileId = "active-profile",
            fromSessionTimestamp = firstBackfill.maxScannedSessionTimestamp ?: 0L,
        )

        assertEquals(2, firstBackfill.changedRows, "Only the missing eccentric weight and volume PRs should be created")
        assertEquals(1_700_000_000_000L, firstBackfill.maxScannedSessionTimestamp)
        assertEquals(0, secondBackfill.changedRows, "Backfill should be idempotent")
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
    fun `findSessionIdsForPersonalRecords resolves sessions outside delta batch`() = runTest {
        insertHistoricalSession(
            id = "historical-bicep-curl",
            timestamp = 1_700_000_000_000L,
            exerciseId = "bicep-curl",
            exerciseName = "Bicep Curl",
            workingReps = 8,
            peakConcentricA = 20.0,
            peakConcentricB = 18.0,
            peakEccentricA = 42.0,
            peakEccentricB = 39.0,
            profileId = "active-profile",
        )
        insertHistoricalSession(
            id = "other-profile-session",
            timestamp = 1_700_000_000_000L,
            exerciseId = "bicep-curl",
            exerciseName = "Bicep Curl",
            workingReps = 8,
            peakConcentricA = 25.0,
            peakConcentricB = 23.0,
            peakEccentricA = 45.0,
            peakEccentricB = 41.0,
            profileId = "other-profile",
        )
        val record = PersonalRecord(
            exerciseId = "bicep-curl",
            exerciseName = "Bicep Curl",
            weightPerCableKg = 42f,
            reps = 8,
            oneRepMax = 42f,
            timestamp = 1_700_000_000_000L,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT,
            volume = 336f,
            phase = WorkoutPhase.ECCENTRIC,
            profileId = "active-profile",
        )

        val sessionIds = repository.findSessionIdsForPersonalRecords(listOf(record), "active-profile")

        assertEquals(
            mapOf("bicep-curl:1700000000000" to "historical-bicep-curl"),
            sessionIds,
        )
    }

    @Test
    fun `backfillPhaseSpecificPRs checkpoints even when no sessions have phase metrics`() = runTest {
        insertHistoricalSession(
            id = "no-phase-metrics",
            timestamp = 1_700_000_000_000L,
            exerciseId = "bicep-curl",
            exerciseName = "Bicep Curl",
            workingReps = 8,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )

        val backfill = repository.backfillPhaseSpecificPRs("active-profile")

        assertEquals(0, backfill.changedRows)
        assertEquals(
            1_700_000_000_000L,
            backfill.maxScannedSessionTimestamp,
            "A profile with no phase metrics should still advance the checkpoint",
        )
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

    @Test
    fun `mergePortalRoutines preserves local rack defaults for matching routine exercises`() = runTest {
        database.vitruvianDatabaseQueries.insertRoutine(
            id = "routine-rack-defaults",
            name = "Rack Defaults",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
        )
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = "rex-rack-defaults",
            routineId = "routine-rack-defaults",
            exerciseName = "Bench Press",
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "Cable",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0,
            setReps = "8",
            weightPerCableKg = 20.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100,
            echoLevel = 1,
            progressionKg = 0.0,
            restSeconds = 60,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0,
            isAMRAP = 0,
            supersetId = null,
            orderInSuperset = 0,
            usePercentOfPR = 0,
            weightPercentOfPR = 80,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null,
            stallDetectionEnabled = 1,
            stopAtTop = 0,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
            defaultRackItemIds = """["vest"]""",
            rackBehaviorOverrides = "{}",
            scalingBasis = null,
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-rack-defaults",
                    userId = "user",
                    name = "Rack Defaults Remote",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-rack-defaults",
                            routineId = "routine-rack-defaults",
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            orderIndex = 0,
                            reps = 8,
                            weight = 25f,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val exercise = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine("routine-rack-defaults")
            .executeAsList()
            .single()
        assertEquals("""["vest"]""", exercise.defaultRackItemIds)
    }

    @Test
    fun `mergePortalRoutines preserves local scalingBasis across portal pull`() = runTest {
        database.vitruvianDatabaseQueries.insertRoutine(
            id = "routine-scaling-basis",
            name = "Scaling Basis",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
        )
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = "rex-scaling-basis",
            routineId = "routine-scaling-basis",
            exerciseName = "Deadlift",
            exerciseMuscleGroup = "Back",
            exerciseEquipment = "Cable",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0,
            setReps = "5",
            weightPerCableKg = 60.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100,
            echoLevel = 1,
            progressionKg = 0.0,
            restSeconds = 90,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0,
            isAMRAP = 0,
            supersetId = null,
            orderInSuperset = 0,
            usePercentOfPR = 0,
            weightPercentOfPR = 80,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null,
            stallDetectionEnabled = 1,
            stopAtTop = 0,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
            defaultRackItemIds = "[]",
            rackBehaviorOverrides = "{}",
            scalingBasis = "ESTIMATED_1RM",
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-scaling-basis",
                    userId = "user",
                    name = "Scaling Basis Remote",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-scaling-basis",
                            routineId = "routine-scaling-basis",
                            name = "Deadlift",
                            muscleGroup = "Back",
                            orderIndex = 0,
                            reps = 5,
                            weight = 65f,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val exercise = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine("routine-scaling-basis")
            .executeAsList()
            .single()
        assertEquals("ESTIMATED_1RM", exercise.scalingBasis)
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
        externalAddedLoadKg: Double = 0.0,
        counterweightKg: Double = 0.0,
        rackItemsJson: String = "[]",
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
            externalAddedLoadKg = externalAddedLoadKg,
            counterweightKg = counterweightKg,
            rackItemsJson = rackItemsJson,
        )
    }
}
