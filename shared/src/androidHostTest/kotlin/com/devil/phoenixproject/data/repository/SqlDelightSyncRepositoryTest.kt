package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalWireJson
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.data.sync.encodePortalSyncPayload
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test

class SqlDelightSyncRepositoryTest {

    private lateinit var database: com.devil.phoenixproject.database.PhoenixDatabase
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

        val session = database.phoenixDatabaseQueries
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
        database.phoenixDatabaseQueries.updateSessionServerId("server-rack-session", "local-rack-session")

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

        val session = database.phoenixDatabaseQueries
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

        val profileRecord = database.phoenixDatabaseQueries.selectPR(
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
        database.phoenixDatabaseQueries.insertRecord(
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
            uuid = null,
        )

        val records = repository.getFullPRsModifiedSince(0L, "active-profile")

        val record = assertNotNull(records.singleOrNull())
        assertEquals("active-profile", record.profileId)
        assertEquals(2, record.cableCount)
        assertEquals(WorkoutPhase.CONCENTRIC, record.phase)
    }

    @Test
    fun `mergePersonalRecords keeps a newer tombstone and rejects stale active replay`() = runTest {
        val prId = "12345678-1234-4abc-8def-1234567890cc"
        fun syncDto(updatedAt: Long, deletedAt: Long?, weight: Float = 85f) = PersonalRecordSyncDto(
            clientId = prId,
            serverId = prId,
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = weight,
            reps = 5,
            oneRepMax = weight * 1.1667f,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = weight * 5,
            createdAt = 1_700_000_000_000L,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
        )

        repository.mergePersonalRecords(listOf(syncDto(100L, null)), "active-profile")
        repository.mergePersonalRecords(listOf(syncDto(200L, 200L, weight = 0f)), "active-profile")
        repository.mergePersonalRecords(listOf(syncDto(150L, null)), "active-profile")

        val row = database.phoenixDatabaseQueries.selectAllRecordsSync().executeAsOne()
        assertEquals(200L, row.updatedAt)
        assertEquals(200L, row.deletedAt)
        assertEquals(85.0, row.weight)
        assertEquals(425.0, row.volume)
    }

    @Test
    fun `mergePersonalRecords lets a newer active update restore a tombstoned row`() = runTest {
        val prId = "12345678-1234-4abc-8def-1234567890ce"
        fun syncDto(weight: Float, updatedAt: Long, deletedAt: Long?) = PersonalRecordSyncDto(
            clientId = prId,
            serverId = prId,
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = weight,
            reps = 5,
            oneRepMax = weight * 1.1667f,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = weight * 5,
            createdAt = 1_700_000_000_000L,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
        )

        repository.mergePersonalRecords(listOf(syncDto(85f, 200L, 200L)), "active-profile")
        repository.mergePersonalRecords(listOf(syncDto(90f, 300L, null)), "active-profile")

        val row = database.phoenixDatabaseQueries.selectAllRecordsSync().executeAsOne()
        assertEquals(300L, row.updatedAt)
        assertNull(row.deletedAt)
        assertEquals(90.0, row.weight)
        assertEquals(450.0, row.volume)
    }

    @Test
    fun `mergePersonalRecords materializes a tombstone received before its active row`() = runTest {
        val prId = "12345678-1234-4abc-8def-1234567890cd"
        repository.mergePersonalRecords(
            listOf(
                PersonalRecordSyncDto(
                    clientId = prId,
                    serverId = prId,
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 85f,
                    reps = 5,
                    oneRepMax = 99.17f,
                    achievedAt = 1_700_000_000_000L,
                    workoutMode = "Old School",
                    prType = PRType.MAX_WEIGHT.name,
                    volume = 425f,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 200L,
                    deletedAt = 200L,
                ),
            ),
            "active-profile",
        )

        val row = database.phoenixDatabaseQueries.selectAllRecordsSync().executeAsOne()
        assertEquals(prId, row.uuid)
        assertEquals(200L, row.updatedAt)
        assertEquals(200L, row.deletedAt)
    }

    @Test
    fun `mergePersonalRecords adopts server uuid on key match even when workoutMode differs`() = runTest {
        val serverUuid = "12345678-1234-4abc-8def-1234567890ab"
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 85.0,
            reps = 5L,
            oneRepMax = 99.17,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 425.0,
            phase = WorkoutPhase.COMBINED.name,
            profile_id = "active-profile",
            cable_count = 2L,
            uuid = null,
        )

        repository.mergePersonalRecords(
            records = listOf(
                PersonalRecordSyncDto(
                    clientId = serverUuid,
                    serverId = serverUuid,
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 85f,
                    reps = 5,
                    oneRepMax = 99.17f,
                    achievedAt = 1_700_000_000_000L,
                    workoutMode = "MAX_WEIGHT",
                    prType = PRType.MAX_WEIGHT.name,
                    phase = WorkoutPhase.COMBINED.name,
                    volume = 425f,
                    cableCount = 2,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_100L,
                ),
            ),
            profileId = "active-profile",
        )

        val rows = database.phoenixDatabaseQueries
            .selectAllRecords(profileId = "active-profile")
            .executeAsList()

        assertEquals(1, rows.size, "UUID adoption should prevent a duplicate row when only workoutMode drifts")
        assertEquals(serverUuid, rows.single().uuid)
        assertEquals("Old School", rows.single().workoutMode)
    }

    @Test
    fun `mergePersonalRecords adopts server uuid onto one duplicate key candidate`() = runTest {
        val serverUuid = "13345678-1234-4abc-8def-1234567890ab"
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 85.0,
            reps = 5L,
            oneRepMax = 99.17,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 425.0,
            phase = WorkoutPhase.COMBINED.name,
            profile_id = "active-profile",
            cable_count = 2L,
            uuid = null,
        )
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 85.0,
            reps = 5L,
            oneRepMax = 99.17,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Echo",
            prType = PRType.MAX_WEIGHT.name,
            volume = 425.0,
            phase = WorkoutPhase.COMBINED.name,
            profile_id = "active-profile",
            cable_count = 2L,
            uuid = null,
        )

        repository.mergePersonalRecords(
            records = listOf(
                PersonalRecordSyncDto(
                    clientId = serverUuid,
                    serverId = serverUuid,
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 85f,
                    reps = 5,
                    oneRepMax = 99.17f,
                    achievedAt = 1_700_000_000_000L,
                    workoutMode = "MAX_WEIGHT",
                    prType = PRType.MAX_WEIGHT.name,
                    phase = WorkoutPhase.COMBINED.name,
                    volume = 425f,
                    cableCount = 2,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_100L,
                ),
            ),
            profileId = "active-profile",
        )

        val rows = database.phoenixDatabaseQueries
            .selectAllRecords(profileId = "active-profile")
            .executeAsList()

        assertEquals(2, rows.size, "Adoption should not stamp the server UUID onto multiple local candidates")
        assertEquals(1, rows.count { it.uuid == serverUuid })
        assertEquals(1, rows.count { it.uuid == null })
    }

    @Test
    fun `mergePersonalRecords inserts unknown server pr with server uuid`() = runTest {
        val serverUuid = "22345678-1234-4abc-8def-1234567890ab"

        repository.mergePersonalRecords(
            records = listOf(
                PersonalRecordSyncDto(
                    clientId = serverUuid,
                    serverId = serverUuid,
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 90f,
                    reps = 3,
                    oneRepMax = 99.17f,
                    achievedAt = 1_700_000_000_500L,
                    workoutMode = "MAX_WEIGHT",
                    prType = PRType.MAX_WEIGHT.name,
                    phase = WorkoutPhase.COMBINED.name,
                    volume = 270f,
                    cableCount = 2,
                    createdAt = 1_700_000_000_500L,
                    updatedAt = 1_700_000_000_600L,
                ),
            ),
            profileId = "active-profile",
        )

        val row = database.phoenixDatabaseQueries
            .selectAllRecords(profileId = "active-profile")
            .executeAsList()
            .single()

        assertEquals(serverUuid, row.uuid)
    }

    @Test
    fun `mergePersonalRecords remerge is idempotent`() = runTest {
        val serverUuid = "32345678-1234-4abc-8def-1234567890ab"
        val dto = PersonalRecordSyncDto(
            clientId = serverUuid,
            serverId = serverUuid,
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 95f,
            reps = 2,
            oneRepMax = 99.17f,
            achievedAt = 1_700_000_001_000L,
            workoutMode = "MAX_WEIGHT",
            prType = PRType.MAX_WEIGHT.name,
            phase = WorkoutPhase.COMBINED.name,
            volume = 190f,
            cableCount = 2,
            createdAt = 1_700_000_001_000L,
            updatedAt = 1_700_000_001_100L,
        )

        repository.mergePersonalRecords(records = listOf(dto), profileId = "active-profile")
        repository.mergePersonalRecords(records = listOf(dto), profileId = "active-profile")

        val rows = database.phoenixDatabaseQueries
            .selectAllRecords(profileId = "active-profile")
            .executeAsList()

        assertEquals(1, rows.size, "Re-merging the same server PR must stay stable")
        assertEquals(serverUuid, rows.single().uuid)
    }

    @Test
    fun `getAllPersonalRecordIds returns canonical uuids only`() = runTest {
        val canonicalUuid = "42345678-1234-4abc-8def-1234567890ab"
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 85.0,
            reps = 5L,
            oneRepMax = 99.17,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 425.0,
            phase = WorkoutPhase.COMBINED.name,
            profile_id = "active-profile",
            cable_count = 2L,
            uuid = canonicalUuid,
        )
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            weight = 80.0,
            reps = 6L,
            oneRepMax = 96.0,
            achievedAt = 1_700_000_000_100L,
            workoutMode = "Old School",
            prType = PRType.MAX_VOLUME.name,
            volume = 480.0,
            phase = WorkoutPhase.COMBINED.name,
            profile_id = "active-profile",
            cable_count = 2L,
            uuid = null,
        )

        assertEquals(listOf(canonicalUuid), repository.getAllPersonalRecordIds("active-profile"))
    }

    @Test
    fun `getExerciseMuscleGroup resolves by id then name and is null for unknown`() = runTest {
        // Seed one catalog exercise. Positional args follow the insertExercise
        // column order: id, name, displayName, description, created, muscleGroup,
        // muscleGroups, muscles, equipment, movement, sidedness, grip, gripWidth,
        // minRepRange, popularity, archived, isFavorite, isCustom, timesPerformed,
        // lastPerformed, aliases, defaultCableConfig, one_rep_max_kg, mvtOverrideMs.
        database.phoenixDatabaseQueries.insertExercise(
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
            isBodyweight = null,
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
        val concentricWeight = database.phoenixDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            phase = WorkoutPhase.CONCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()
        val concentricVolume = database.phoenixDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_VOLUME.name,
            phase = WorkoutPhase.CONCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()
        val eccentricWeight = database.phoenixDatabaseQueries.selectPR(
            exerciseId = "bicep-curl",
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            phase = WorkoutPhase.ECCENTRIC.name,
            profileId = "active-profile",
        ).executeAsOne()
        val eccentricVolume = database.phoenixDatabaseQueries.selectPR(
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

        val routine = database.phoenixDatabaseQueries
            .selectRoutineById("routine-profile-b")
            .executeAsOneOrNull()

        assertNotNull(routine)
        assertEquals("active-profile", routine.profile_id)
    }

    @Test
    fun `mergePortalRoutines preserves local rack defaults for matching routine exercises`() = runTest {
        database.phoenixDatabaseQueries.insertRoutine(
            id = "routine-rack-defaults",
            name = "Rack Defaults",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
            deletedAt = null,
        )
        database.phoenixDatabaseQueries.insertRoutineExercise(
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
            isBodyweight = null,
            dropSetEnabled = 0L,
            dropSetMinWeightKg = null,
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

        val exercise = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-rack-defaults")
            .executeAsList()
            .single()
        assertEquals("""["vest"]""", exercise.defaultRackItemIds)
    }

    // ========== routine exercise durationSeconds (PR 13) ==========

    @Test
    fun `mergePortalRoutines stores pulled durationSeconds and pushes it back`() = runTest {
        repository.mergePortalRoutines(
            routines = listOf(
                decodePullRoutine(
                    """{"id":"routine-timed","name":"Timed Remote","updatedAt":1700000000200,"exercises":[
                    {"id":"rex-timed","routineId":"routine-timed","name":"Plank","orderIndex":0,"durationSeconds":45},
                    {"id":"rex-reps","routineId":"routine-timed","name":"Bench Press","orderIndex":1,"durationSeconds":null}
                    ]}""",
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val rows = routineExerciseRows("routine-timed")
        assertEquals(45L, rows.getValue("rex-timed").duration)
        assertEquals(1L, rows.getValue("rex-timed").durationSyncKnown)
        assertNull(rows.getValue("rex-reps").duration)
        assertEquals(1L, rows.getValue("rex-reps").durationSyncKnown)

        val pushed = pushedRoutineExercises()
        assertEquals(JsonPrimitive(45), pushed.getValue("rex-timed")["durationSeconds"])
        // Known null after a pull: explicit null so the server clears it.
        assertTrue(pushed.getValue("rex-reps").containsKey("durationSeconds"))
        assertEquals(JsonNull, pushed.getValue("rex-reps")["durationSeconds"])
    }

    @Test
    fun `upgraded row with a stale null duration omits durationSeconds on push`() = runTest {
        // A row written by an older build: duration NULL, flag at its migration default.
        insertLegacyRoutineExercise(routineId = "routine-stale", exerciseId = "rex-stale", duration = null)

        val pushed = pushedRoutineExercises()

        assertEquals(0L, routineExerciseRows("routine-stale").getValue("rex-stale").durationSyncKnown)
        assertFalse(pushed.getValue("rex-stale").containsKey("durationSeconds"))
    }

    @Test
    fun `local save that leaves a stale null duration unchanged still omits durationSeconds`() = runTest {
        insertLegacyRoutineExercise(routineId = "routine-stale", exerciseId = "rex-stale", duration = null)
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val routine = assertNotNull(workoutRepository.getRoutineById("routine-stale"))

        workoutRepository.updateRoutine(routine.copy(name = "Renamed"))

        assertFalse(pushedRoutineExercises().getValue("rex-stale").containsKey("durationSeconds"))
    }

    @Test
    fun `local edit of a duration to null pushes an explicit null`() = runTest {
        insertLegacyRoutineExercise(routineId = "routine-edit", exerciseId = "rex-edit", duration = 30L)
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val routine = assertNotNull(workoutRepository.getRoutineById("routine-edit"))
        assertEquals(30, routine.exercises.single().duration)

        workoutRepository.updateRoutine(
            routine.copy(exercises = routine.exercises.map { it.copy(duration = null) }),
        )

        val row = routineExerciseRows("routine-edit").getValue("rex-edit")
        assertNull(row.duration)
        assertEquals(1L, row.durationSyncKnown)
        val pushed = pushedRoutineExercises().getValue("rex-edit")
        assertTrue(pushed.containsKey("durationSeconds"))
        assertEquals(JsonNull, pushed["durationSeconds"])
    }

    @Test
    fun `pull without a durationSeconds key keeps the local duration and its flag`() = runTest {
        insertLegacyRoutineExercise(routineId = "routine-old-server", exerciseId = "rex-old", duration = 30L)

        repository.mergePortalRoutines(
            routines = listOf(
                decodePullRoutine(
                    """{"id":"routine-old-server","name":"Old Server","updatedAt":1700000000200,"exercises":[
                    {"id":"rex-old","routineId":"routine-old-server","name":"Plank","orderIndex":0}
                    ]}""",
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val row = routineExerciseRows("routine-old-server").getValue("rex-old")
        assertEquals(30L, row.duration)
        assertEquals(0L, row.durationSyncKnown)
    }

    @Test
    fun `pulled zero or negative durationSeconds is stored as untimed and pushed as null`() = runTest {
        repository.mergePortalRoutines(
            routines = listOf(
                decodePullRoutine(
                    """{"id":"routine-bad","name":"Bad Durations","updatedAt":1700000000200,"exercises":[
                    {"id":"rex-negative","routineId":"routine-bad","name":"Plank","orderIndex":0,"durationSeconds":-5},
                    {"id":"rex-zero","routineId":"routine-bad","name":"Wall Sit","orderIndex":1,"durationSeconds":0}
                    ]}""",
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val rows = routineExerciseRows("routine-bad")
        assertNull(rows.getValue("rex-negative").duration)
        assertNull(rows.getValue("rex-zero").duration)
        val pushed = pushedRoutineExercises()
        assertEquals(JsonNull, pushed.getValue("rex-negative")["durationSeconds"])
        assertEquals(JsonNull, pushed.getValue("rex-zero")["durationSeconds"])
    }

    private fun decodePullRoutine(raw: String): PullRoutineDto =
        PortalWireJson.decodeFromString(PullRoutineDto.serializer(), raw)

    private fun routineExerciseRows(routineId: String) = database.phoenixDatabaseQueries
        .selectExercisesByRoutine(routineId)
        .executeAsList()
        .associateBy { it.id }

    /** Reads routines back for push and encodes them through the real wire path. */
    private suspend fun pushedRoutineExercises(): Map<String, JsonObject> {
        val routines = repository.getFullRoutinesModifiedSince(0L, "active-profile")
        val raw = encodePortalSyncPayload(
            PortalSyncPayload(
                deviceId = "device-1",
                platform = "android",
                lastSync = 0L,
                routines = routines.map { PortalSyncAdapter.toPortalRoutine(it, "user") },
            ),
        ).raw
        return Json.parseToJsonElement(raw).jsonObject.getValue("routines").jsonArray
            .flatMap { it.jsonObject.getValue("exercises").jsonArray }
            .map { it.jsonObject }
            .associateBy { it.getValue("id").jsonPrimitive.content }
    }

    private fun insertLegacyRoutineExercise(routineId: String, exerciseId: String, duration: Long?) {
        database.phoenixDatabaseQueries.insertRoutine(
            id = routineId,
            name = "Legacy $routineId",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
            deletedAt = null,
        )
        database.phoenixDatabaseQueries.insertRoutineExercise(
            id = exerciseId,
            routineId = routineId,
            exerciseName = "Plank",
            exerciseMuscleGroup = "Core",
            exerciseEquipment = "",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0,
            setReps = "10",
            weightPerCableKg = 0.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100,
            echoLevel = 1,
            progressionKg = 0.0,
            restSeconds = 60,
            duration = duration,
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
            scalingBasis = null,
            isBodyweight = null,
            dropSetEnabled = 0L,
            dropSetMinWeightKg = null,
        )
    }

    @Test
    fun `mergePortalRoutines preserves local scalingBasis across portal pull`() = runTest {
        database.phoenixDatabaseQueries.insertRoutine(
            id = "routine-scaling-basis",
            name = "Scaling Basis",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
            deletedAt = null,
        )
        database.phoenixDatabaseQueries.insertRoutineExercise(
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
            isBodyweight = null,
            dropSetEnabled = 0L,
            dropSetMinWeightKg = null,
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

        val exercise = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-scaling-basis")
            .executeAsList()
            .single()
        assertEquals("ESTIMATED_1RM", exercise.scalingBasis)
    }

    @Test
    fun `mergePortalRoutines preserves omitted drop set config and applies explicit false`() = runTest {
        database.phoenixDatabaseQueries.insertRoutine(
            id = "routine-drop-set",
            name = "Drop Set",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
            deletedAt = null,
        )
        database.phoenixDatabaseQueries.insertRoutineExercise(
            id = "rex-drop-set",
            routineId = "routine-drop-set",
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
            defaultRackItemIds = "[]",
            rackBehaviorOverrides = "{}",
            scalingBasis = null,
            isBodyweight = null,
            dropSetEnabled = 1L,
            dropSetMinWeightKg = 8.0,
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-drop-set",
                    userId = "user",
                    name = "Drop Set Remote",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-drop-set",
                            routineId = "routine-drop-set",
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            orderIndex = 0,
                            reps = 8,
                            weight = 20f,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val preserved = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-drop-set")
            .executeAsList()
            .single()
        assertEquals(1L, preserved.dropSetEnabled)
        assertEquals(8.0, preserved.dropSetMinWeightKg)

        val outbound = repository.getFullRoutinesModifiedSince(0L, "active-profile").single()
        val payload = PortalSyncPayload(
            deviceId = "device-1",
            platform = "android",
            lastSync = 0L,
            routines = listOf(PortalSyncAdapter.toPortalRoutine(outbound, "user")),
        )
        val serialized = kotlinx.serialization.json.Json.encodeToString(
            PortalSyncPayload.serializer(),
            payload,
        )
        assertTrue(serialized.contains("\"dropSetEnabled\":true"))
        assertTrue(serialized.contains("\"dropSetMinWeightKg\":8.0"))

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-drop-set",
                    userId = "user",
                    name = "Drop Set Remote",
                    updatedAt = 1_700_000_000_300,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-drop-set",
                            routineId = "routine-drop-set",
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            orderIndex = 0,
                            reps = 8,
                            weight = 20f,
                            dropSetEnabled = false,
                            dropSetMinWeightKg = null,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_200,
            profileId = "active-profile",
        )

        val disabled = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-drop-set")
            .executeAsList()
            .single()
        assertEquals(0L, disabled.dropSetEnabled)
        assertEquals(null, disabled.dropSetMinWeightKg)
    }

    @Test
    fun `mergePortalRoutines stores isBodyweight flag without corrupting equipment`() = runTest {
        // #635 regression: the pull path used to write a "Bodyweight" sentinel string
        // into exerciseEquipment, which permanently poisoned classification because
        // snapshot Exercises re-derived isBodyweight from the equipment string.
        // Catalog cable lift with empty equipment and an explicit stored flag (Squat)
        database.phoenixDatabaseQueries.insertExercise(
            id = "legacy-squat",
            name = "Squat",
            displayName = "Squat",
            description = null,
            created = 0,
            muscleGroup = "LEGS",
            muscleGroups = "LEGS",
            muscles = null,
            equipment = "",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0,
            isFavorite = 0,
            isCustom = 0,
            timesPerformed = 0,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
            mvtOverrideMs = null,
            isBodyweight = 0,
        )
        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-635",
                    userId = "user",
                    name = "Bodyweight Flag Routine",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-635-bw",
                            routineId = "routine-635",
                            name = "Push Up",
                            muscleGroup = "Chest",
                            orderIndex = 0,
                            reps = 10,
                            weight = 0f,
                            isBodyweight = true,
                        ),
                        PullRoutineExerciseDto(
                            id = "rex-635-cable",
                            routineId = "routine-635",
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            orderIndex = 1,
                            reps = 8,
                            weight = 40f,
                            isBodyweight = false,
                        ),
                        // Older Edge Function payloads omit the field entirely — the
                        // stored flag must stay NULL (derive from catalog equipment),
                        // never explicit cable.
                        PullRoutineExerciseDto(
                            id = "rex-635-omitted",
                            routineId = "routine-635",
                            name = "Plank",
                            muscleGroup = "Core",
                            orderIndex = 2,
                            reps = 1,
                            weight = 0f,
                        ),
                        // Omitted field + catalog exercise with an explicit stored flag:
                        // must inherit the catalog classification (Squat = cable), or the
                        // catalog-blind push snapshot re-derives bodyweight from the
                        // empty equipment string and re-poisons the portal.
                        PullRoutineExerciseDto(
                            id = "rex-635-omitted-catalog",
                            routineId = "routine-635",
                            name = "Squat",
                            muscleGroup = "LEGS",
                            exerciseId = "legacy-squat",
                            orderIndex = 3,
                            reps = 5,
                            weight = 60f,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val exercises = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-635")
            .executeAsList()
            .sortedBy { it.orderIndex }

        val bodyweightRow = exercises[0]
        assertEquals(1L, bodyweightRow.isBodyweight)
        // Equipment must never carry the legacy sentinel
        assertEquals("", bodyweightRow.exerciseEquipment)

        val cableRow = exercises[1]
        assertEquals(0L, cableRow.isBodyweight)
        assertEquals("", cableRow.exerciseEquipment)

        val omittedRow = exercises[2]
        assertEquals(null, omittedRow.isBodyweight)

        val omittedCatalogRow = exercises[3]
        assertEquals(0L, omittedCatalogRow.isBodyweight)
    }

    @Test
    fun `getWorkoutSessionsModifiedSince maps persisted updatedAt for push LWW`() = runTest {
        val startedAt = 1_600_000_000_000L
        val lastEdit = 1_700_000_000_000L
        insertHistoricalSession(
            id = "session-domain-updated-at",
            timestamp = startedAt,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            workingReps = 8,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )
        database.phoenixDatabaseQueries.updateSessionTimestamp(lastEdit, "session-domain-updated-at")

        val sessions = repository.getWorkoutSessionsModifiedSince(0L, "active-profile")
        val session = sessions.single { it.id == "session-domain-updated-at" }
        assertEquals(lastEdit, session.updatedAt)
    }

    @Test
    fun `mergeSessionNotes prefers newer incoming updatedAt over older local startedAt stamp`() = runTest {
        val startedAt = 1_700_000_000_000L
        val portalUpdatedAt = 1_704_067_200_000L
        database.phoenixDatabaseQueries.upsertSessionNotes(
            routineSessionId = "rs-notes-lww",
            notes = "local notes",
            updatedAt = startedAt,
        )

        repository.mergeSessionNotes(
            mapOf(
                "rs-notes-lww" to SessionNotesEntry(
                    notes = "newer portal notes",
                    updatedAtMillis = portalUpdatedAt,
                ),
            ),
        )

        val after = database.phoenixDatabaseQueries.getSessionNotes("rs-notes-lww").executeAsOne()
        assertEquals("newer portal notes", after.notes)
        assertEquals(portalUpdatedAt, after.updatedAt)
    }

    @Test
    fun `mergeSessionNotes rejects incoming startedAt that is older than local updatedAt`() = runTest {
        val startedAt = 1_700_000_000_000L
        val localUpdatedAt = 1_702_000_000_000L
        database.phoenixDatabaseQueries.upsertSessionNotes(
            routineSessionId = "rs-notes-local-newer",
            notes = "local notes edit",
            updatedAt = localUpdatedAt,
        )

        repository.mergeSessionNotes(
            mapOf(
                "rs-notes-local-newer" to SessionNotesEntry(
                    notes = "stale portal notes stamped from startedAt",
                    updatedAtMillis = startedAt,
                ),
            ),
        )

        val after = database.phoenixDatabaseQueries.getSessionNotes("rs-notes-local-newer").executeAsOne()
        assertEquals("local notes edit", after.notes)
        assertEquals(localUpdatedAt, after.updatedAt)
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
        database.phoenixDatabaseQueries.insertSession(
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
