package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalPullAdapter
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PullExerciseDto
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.data.sync.PullSetDto
import com.devil.phoenixproject.data.sync.PullWorkoutSessionDto
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
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
    fun `pull of an existing session id never rewrites the row or drops its children`() = runTest {
        // FK-on test DB: a REPLACE of the parent row would cascade-delete every child below.
        val sessionId = "local-session-with-children"
        insertHistoricalSession(
            id = sessionId,
            timestamp = 1_700_000_000_000,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            workingReps = 8,
            peakConcentricA = 40.0,
            peakConcentricB = 41.0,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )
        val q = database.phoenixDatabaseQueries
        q.updateSessionTimestamp(1_700_000_000_500, sessionId)
        q.updateSessionServerId("server-session-1", sessionId)
        q.insertMetric(sessionId, 1_700_000_000_010, 0.5, 0.5, 0.1, 0.1, 20.0, 20.0, 40.0, 0L)
        q.insertMetric(sessionId, 1_700_000_000_020, 0.6, 0.6, 0.2, 0.2, 21.0, 21.0, 42.0, 0L)
        q.insertPhaseStatistics(sessionId, 20.0, 22.0, 0.4, 0.5, 80.0, 90.0, 18.0, 19.0, 0.3, 0.4, 60.0, 70.0, 1_700_000_000_030)
        q.insertRepMetric(
            sessionId, 1L, 0L, 1_700_000_000_000, 1_700_000_002_000, 2_000L,
            1_000L, "[]", "[]", "[]", "[]", "[]",
            1_000L, "[]", "[]", "[]", "[]", "[]",
            40.0, 41.0, 20.0, 21.0, 19.0, 20.0, 0.6, 0.4, 0.3, 500.0, 120.0, 80.0, null, null,
        )
        q.insertCompletedSet(
            "completed-set-1", sessionId, null, null, 1L, "STANDARD", 1L, 8L, 20.0, null, 0L,
            1_700_000_002_000, "UNKNOWN",
        )

        // The portal copy is newer (e.g. a web notes edit) and carries the lossy projection.
        repository.mergeSessionsLww(
            sessions = listOf(
                com.devil.phoenixproject.domain.model.WorkoutSession(
                    id = sessionId,
                    timestamp = 1_700_000_000_000,
                    exerciseId = "incline-bench",
                    exerciseName = "Incline Bench Press",
                    totalReps = 11,
                    warmupReps = 0,
                    workingReps = 11,
                    profileId = "active-profile",
                ),
            ),
            updatedAtBySessionId = mapOf(sessionId to 1_700_000_900_000),
        )

        val session = q.selectSessionById(sessionId).executeAsOne()
        assertEquals("server-session-1", session.serverId)
        assertEquals(8L, session.workingReps)
        assertEquals(1_700_000_000_500, session.updatedAt)
        assertEquals(40.0, session.peakForceConcentricA)
        assertEquals("bench", session.exerciseId, "a row captured here keeps its own exercise tag")
        assertEquals("Bench Press", session.exerciseName)
        assertEquals(2, q.selectMetricsBySession(sessionId).executeAsList().size)
        assertEquals(1, q.selectPhaseStatsBySessionIds(listOf(sessionId)).executeAsList().size)
        assertEquals(1, q.selectRepMetricsBySession(sessionId).executeAsList().size)
        assertEquals(1, q.selectCompletedSetsBySession(sessionId).executeAsList().size)
    }

    @Test
    fun `pull of a new session stores the portal warmup and working reps`() = runTest {
        val pulled = PullWorkoutSessionDto(
            id = "pulled-standalone",
            userId = "user-1",
            startedAt = "2026-03-20T10:00:00Z",
            durationSeconds = 120,
            exerciseCount = 1,
            workoutMode = "OLD_SCHOOL",
            updatedAt = "2026-03-20T10:05:00Z",
            warmupReps = 3,
            workingReps = 8,
            eccentricLoad = 150,
            echoLevel = 3,
            exercises = listOf(
                PullExerciseDto(
                    id = "pulled-standalone",
                    sessionId = "pulled-standalone",
                    name = "Bench Press",
                    orderIndex = 0,
                    sets = listOf(
                        PullSetDto(id = "set-1", exerciseId = "pulled-standalone", setNumber = 1, actualReps = 11, weightKg = 40f),
                    ),
                ),
            ),
        )
        val rows = PortalPullAdapter.toWorkoutSessionsWithLookup(pulled, "active-profile") { _, _, _ -> null }

        repository.mergeSessionsLww(rows, mapOf("pulled-standalone" to 1_774_001_100_000))

        val session = database.phoenixDatabaseQueries.selectSessionById("pulled-standalone").executeAsOne()
        assertEquals(11L, session.totalReps)
        assertEquals(3L, session.warmupReps)
        assertEquals(8L, session.workingReps)
        assertEquals(150L, session.eccentricLoad)
        assertEquals(3L, session.echoLevel)
        assertNull(session.routineSessionId)
        assertEquals(1_774_001_100_000, session.updatedAt)
    }

    @Test
    fun `newer pull carries an exercise re-tag onto a pulled row and changes nothing else`() = runTest {
        val pulled = com.devil.phoenixproject.domain.model.WorkoutSession(
            id = "pulled-just-lift",
            timestamp = 1_700_000_000_000,
            exerciseId = null,
            exerciseName = null,
            isJustLift = true,
            totalReps = 10,
            workingReps = 7,
            warmupReps = 3,
            profileId = "active-profile",
        )
        repository.mergeSessionsLww(listOf(pulled), mapOf(pulled.id to 1_700_000_100_000))

        // Another device tagged the Just Lift session; the portal copy is newer and lossy.
        val retagged = pulled.copy(
            exerciseId = "squat",
            exerciseName = "Back Squat",
            isJustLift = false,
            totalReps = 10,
            workingReps = 10,
            warmupReps = 0,
        )
        repository.mergeSessionsLww(listOf(retagged), mapOf(pulled.id to 1_700_000_200_000))

        val session = database.phoenixDatabaseQueries.selectSessionById(pulled.id).executeAsOne()
        assertEquals("squat", session.exerciseId)
        assertEquals("Back Squat", session.exerciseName)
        assertEquals(1_700_000_200_000, session.updatedAt)
        assertEquals(1L, session.isJustLift)
        assertEquals(7L, session.workingReps)
        assertEquals(3L, session.warmupReps)

        // An older portal copy never reverts the tag.
        repository.mergeSessionsLww(
            listOf(retagged.copy(exerciseId = "deadlift", exerciseName = "Deadlift")),
            mapOf(pulled.id to 1_700_000_150_000),
        )
        assertEquals("squat", database.phoenixDatabaseQueries.selectSessionById(pulled.id).executeAsOne().exerciseId)

        // A newer pull with the same exerciseId is a no-op: the stamp does not move.
        repository.mergeSessionsLww(
            listOf(retagged.copy(exerciseName = "Back Squat (renamed)")),
            mapOf(pulled.id to 1_700_000_300_000),
        )
        val unchanged = database.phoenixDatabaseQueries.selectSessionById(pulled.id).executeAsOne()
        assertEquals("Back Squat", unchanged.exerciseName)
        assertEquals(1_700_000_200_000, unchanged.updatedAt)
    }

    @Test
    fun `newer pull does not re-tag a row that has only local RepMetric children`() = runTest {
        val id = seedUntaggedPulledRow("rep-metric-only")
        database.phoenixDatabaseQueries.insertRepMetric(
            id, 1L, 0L, 1_700_000_000_000, 1_700_000_002_000, 2_000L,
            1_000L, "[]", "[]", "[]", "[]", "[]",
            1_000L, "[]", "[]", "[]", "[]", "[]",
            40.0, 41.0, 20.0, 21.0, 19.0, 20.0, 0.6, 0.4, 0.3, 500.0, 120.0, 80.0, null, null,
        )

        pullRetag(id)

        assertNull(database.phoenixDatabaseQueries.selectSessionById(id).executeAsOne().exerciseId)
    }

    @Test
    fun `newer pull does not re-tag a row that has only local CompletedSet children`() = runTest {
        val id = seedUntaggedPulledRow("completed-set-only")
        database.phoenixDatabaseQueries.insertCompletedSet(
            "completed-set-only-1", id, null, null, 1L, "STANDARD", 1L, 8L, 20.0, null, 0L,
            1_700_000_002_000, "UNKNOWN",
        )

        pullRetag(id)

        assertNull(database.phoenixDatabaseQueries.selectSessionById(id).executeAsOne().exerciseId)
    }

    @Test
    fun `newer pull does not re-tag a childless row this device captured`() = runTest {
        // No RepMetric/CompletedSet rows and no stamp, so only the PulledWorkoutSession
        // marker (absent here) separates this row from one the pull created.
        val id = "locally-captured-childless"
        insertHistoricalSession(
            id = id,
            timestamp = 1_700_000_000_000,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            workingReps = 8,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )

        pullRetag(id)

        val session = database.phoenixDatabaseQueries.selectSessionById(id).executeAsOne()
        assertEquals("bench", session.exerciseId)
        assertEquals("Bench Press", session.exerciseName)
        assertNull(session.updatedAt)
    }

    @Test
    fun `a workout deleted on this device is not resurrected by a later pull`() = runTest {
        val id = "deleted-workout"
        insertHistoricalSession(
            id = id,
            timestamp = 1_700_000_000_000,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            workingReps = 8,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )
        workoutRepository().deleteSession(id)

        // The portal still has the workout and sends it back, with a newer stamp and
        // (after a profile deletion re-scope) possibly under another profile.
        repository.mergeSessionsLww(
            listOf(pulledSession(id, profileId = "active-profile")),
            mapOf(id to 1_700_000_900_000),
        )
        repository.mergeSessionsLww(
            listOf(pulledSession(id, profileId = "default")),
            mapOf(id to 1_700_001_900_000),
        )

        assertNull(database.phoenixDatabaseQueries.selectSessionById(id).executeAsOneOrNull())
    }

    @Test
    fun `the push gather skips pulled sessions by origin, not by stamp`() = runTest {
        val pulledId = "pulled-row"
        val pulledWithLocalSets = "pulled-row-with-local-sets"
        val localId = "locally-captured-row"
        repository.mergeSessionsLww(
            listOf(pulledSession(pulledId), pulledSession(pulledWithLocalSets)),
            mapOf(pulledId to 1_700_000_100_000, pulledWithLocalSets to 1_700_000_100_000),
        )
        insertHistoricalSession(
            id = localId,
            timestamp = 1_700_000_000_000,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            workingReps = 8,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )
        // Rep data recorded here makes a pulled id ours to push again.
        database.phoenixDatabaseQueries.insertCompletedSet(
            "pulled-row-set-1", pulledWithLocalSets, null, null, 1L, "STANDARD", 1L, 8L, 20.0, null, 0L,
            1_700_000_002_000, "UNKNOWN",
        )
        // A later local edit (or a portal stamp ahead of the clock) bumps updatedAt.
        database.phoenixDatabaseQueries.updateSessionTimestamp(1_800_000_000_000, pulledId)

        val pushed = repository.getWorkoutSessionsModifiedSince(0L, "active-profile").map { it.id }

        assertEquals(listOf(localId, pulledWithLocalSets), pushed.sorted())
    }

    private fun workoutRepository(): SqlDelightWorkoutRepository = SqlDelightWorkoutRepository(
        database,
        SqlDelightExerciseRepository(database, ExerciseImporter(database), FakePreferencesManager()),
    )

    private fun pulledSession(
        id: String,
        profileId: String = "active-profile",
    ) = com.devil.phoenixproject.domain.model.WorkoutSession(
        id = id,
        timestamp = 1_700_000_000_000,
        exerciseId = "bench",
        exerciseName = "Bench Press",
        totalReps = 8,
        workingReps = 8,
        profileId = profileId,
    )

    private suspend fun seedUntaggedPulledRow(id: String): String {
        repository.mergeSessionsLww(
            listOf(
                com.devil.phoenixproject.domain.model.WorkoutSession(
                    id = id,
                    timestamp = 1_700_000_000_000,
                    isJustLift = true,
                    totalReps = 8,
                    workingReps = 8,
                    profileId = "active-profile",
                ),
            ),
            mapOf(id to 1_700_000_100_000),
        )
        return id
    }

    private suspend fun pullRetag(id: String) {
        repository.mergeSessionsLww(
            listOf(
                com.devil.phoenixproject.domain.model.WorkoutSession(
                    id = id,
                    timestamp = 1_700_000_000_000,
                    exerciseId = "squat",
                    exerciseName = "Back Squat",
                    totalReps = 8,
                    workingReps = 8,
                    profileId = "active-profile",
                ),
            ),
            mapOf(id to 1_700_000_200_000),
        )
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
    fun `pulling the same routine twice keeps local-only exercise settings and cycle links`() = runTest {
        val queries = database.phoenixDatabaseQueries
        insertLocalRoutine("routine-twice")
        insertLocalRoutineExercise(
            id = "rex-twice",
            routineId = "routine-twice",
            progressionKg = 2.5,
            prTypeForScaling = "MAX_VOLUME",
            setWeightsPercentOfPR = "[70,80,90]",
            scalingBasis = "ESTIMATED_1RM",
        )
        queries.insertPlannedSet("planned-twice", "rex-twice", 1, "STANDARD", 5, 60.0, null, 90)
        insertCycleDayFor("routine-twice")
        val portalRoutine = PullRoutineDto(
            id = "routine-twice",
            name = "Twice Remote",
            updatedAt = 1_700_000_000_200,
            exercises = listOf(
                PullRoutineExerciseDto(id = "rex-twice", routineId = "routine-twice", name = "Deadlift", reps = 5, weight = 65f, prPercentage = 80f),
            ),
        )

        repeat(2) {
            repository.mergeAllPullData(
                sessions = emptyList(),
                routines = listOf(portalRoutine),
                cycles = emptyList(),
                badges = emptyList(),
                gamificationStats = null,
                personalRecords = emptyList(),
                lastSync = 1_700_000_000_300,
                profileId = "active-profile",
            )
        }

        val exercise = queries.selectExercisesByRoutine("routine-twice").executeAsList().single()
        assertEquals(65.0, exercise.weightPerCableKg)
        assertEquals(2.5, exercise.progressionKg)
        assertEquals("MAX_VOLUME", exercise.prTypeForScaling)
        assertEquals("[70,80,90]", exercise.setWeightsPercentOfPR)
        assertEquals("ESTIMATED_1RM", exercise.scalingBasis)
        assertEquals("Twice Remote", queries.selectRoutineById("routine-twice").executeAsOne().name)
        assertEquals("routine-twice", queries.selectCycleDaysByCycle("cycle-link").executeAsList().single().routine_id)
        assertEquals(1, queries.selectPlannedSetsByRoutineExercise("rex-twice").executeAsList().size)
    }

    @Test
    fun `pull drops the local per-set percent list when the base percent of PR changed elsewhere`() = runTest {
        // Device A deloaded 80% -> 70% (its per-set list became [70,70,70]); the push only carries
        // the base %. Device B must not keep loading its stale [80,80,80] per-set list.
        insertLocalRoutine("routine-deload")
        insertLocalRoutineExercise(id = "rex-deload", routineId = "routine-deload", setWeightsPercentOfPR = "[80,80,80]")

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-deload",
                    name = "Deload",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(id = "rex-deload", routineId = "routine-deload", name = "Deadlift", reps = 5, prPercentage = 70f),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_300,
            profileId = "active-profile",
        )

        val exercise = database.phoenixDatabaseQueries.selectExercisesByRoutine("routine-deload").executeAsList().single()
        assertEquals(1L, exercise.usePercentOfPR)
        assertEquals(70L, exercise.weightPercentOfPR)
        assertNull(exercise.setWeightsPercentOfPR)
    }

    @Test
    fun `pull diffs routine exercises by id and keeps local superset names`() = runTest {
        val queries = database.phoenixDatabaseQueries
        insertLocalRoutine("routine-diff")
        queries.insertSuperset("ss-kept", "routine-diff", "Arms finisher", 0, 45, 0)
        queries.insertSuperset("ss-dropped", "routine-diff", "Old pair", 1, 10, 1)
        insertLocalRoutineExercise(id = "rex-kept", routineId = "routine-diff", supersetId = "ss-kept")
        insertLocalRoutineExercise(id = "rex-removed", routineId = "routine-diff", supersetId = "ss-dropped")

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-diff",
                    name = "Diff",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(id = "rex-kept", routineId = "routine-diff", name = "Curl", supersetId = "ss-kept", supersetColor = "pink"),
                        PullRoutineExerciseDto(id = "rex-new", routineId = "routine-diff", name = "Row", orderIndex = 1),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_300,
            profileId = "active-profile",
        )

        assertEquals(listOf("rex-kept", "rex-new"), queries.selectExercisesByRoutine("routine-diff").executeAsList().map { it.id })
        val superset = queries.selectSupersetsByRoutine("routine-diff").executeAsList().single()
        assertEquals("ss-kept", superset.id)
        assertEquals("Arms finisher", superset.name)
        assertEquals(45L, superset.restBetweenSeconds)
        assertEquals(1L, superset.colorIndex)
    }

    @Test
    fun `pull re-enabling percent of PR clears a per-set list stored while the mode was off`() = runTest {
        // A pull without prPercentage stores the fallback base 80 and turns the mode off; the
        // per-set list is kept but unused. Re-enabling at 80 must not revive that stale list.
        insertLocalRoutine("routine-reenable")
        insertLocalRoutineExercise(id = "rex-reenable", routineId = "routine-reenable", usePercentOfPR = 0, setWeightsPercentOfPR = "[90,90,90]")

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-reenable",
                    name = "Re-enable",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(id = "rex-reenable", routineId = "routine-reenable", name = "Deadlift", reps = 5, prPercentage = 80f),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_300,
            profileId = "active-profile",
        )

        val exercise = database.phoenixDatabaseQueries.selectExercisesByRoutine("routine-reenable").executeAsList().single()
        assertEquals(1L, exercise.usePercentOfPR)
        assertEquals(80L, exercise.weightPercentOfPR)
        assertNull(exercise.setWeightsPercentOfPR)
    }

    @Test
    fun `pull leaves a locally soft-deleted routine deleted`() = runTest {
        val queries = database.phoenixDatabaseQueries
        insertLocalRoutine("routine-deleted")
        insertLocalRoutineExercise(id = "rex-deleted", routineId = "routine-deleted")
        // As production does: the tombstone stamps updatedAt = deletedAt, here older than lastSync
        // (a tombstone that was never pushed, e.g. deleted under another profile).
        queries.softDeleteRoutine(deletedAt = 1_700_000_000_050, updatedAt = 1_700_000_000_050, id = "routine-deleted")
        val portalRoutine = PullRoutineDto(
            id = "routine-deleted",
            name = "Resurrected?",
            updatedAt = 1_700_000_000_200,
            exercises = listOf(PullRoutineExerciseDto(id = "rex-deleted", routineId = "routine-deleted", name = "Deadlift", weight = 90f)),
        )

        repository.mergePortalRoutines(listOf(portalRoutine), lastSync = 1_700_000_000_300, profileId = "active-profile")
        repository.mergeAllPullData(
            sessions = emptyList(),
            routines = listOf(portalRoutine),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 1_700_000_000_300,
            profileId = "active-profile",
        )

        val routine = queries.selectRoutineById("routine-deleted").executeAsOne()
        assertEquals(1_700_000_000_050, routine.deletedAt)
        assertEquals("Local routine-deleted", routine.name)
        assertEquals(60.0, queries.selectExercisesByRoutine("routine-deleted").executeAsList().single().weightPerCableKg)
    }

    private fun insertLocalRoutine(id: String) {
        database.phoenixDatabaseQueries.insertRoutine(
            id = id,
            name = "Local $id",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0,
            profile_id = "active-profile",
            groupId = null,
            deletedAt = null,
        )
    }

    private fun insertCycleDayFor(routineId: String) {
        val queries = database.phoenixDatabaseQueries
        queries.insertTrainingCycle("cycle-link", "Cycle", null, 1_700_000_000_000, 1, "active-profile", null, 1)
        queries.insertCycleDay("cycle-day-link", "cycle-link", 1, "Day 1", routineId, 0, null, null, null, null, null)
    }

    private fun insertLocalRoutineExercise(
        id: String,
        routineId: String,
        progressionKg: Double = 0.0,
        prTypeForScaling: String = "MAX_WEIGHT",
        setWeightsPercentOfPR: String? = null,
        scalingBasis: String? = null,
        supersetId: String? = null,
        usePercentOfPR: Long = 1,
    ) {
        database.phoenixDatabaseQueries.insertRoutineExercise(
            id = id,
            routineId = routineId,
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
            progressionKg = progressionKg,
            restSeconds = 90,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0,
            isAMRAP = 0,
            supersetId = supersetId,
            orderInSuperset = 0,
            usePercentOfPR = usePercentOfPR,
            weightPercentOfPR = 80,
            prTypeForScaling = prTypeForScaling,
            setWeightsPercentOfPR = setWeightsPercentOfPR,
            stallDetectionEnabled = 1,
            stopAtTop = 0,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
            defaultRackItemIds = "[]",
            rackBehaviorOverrides = "{}",
            scalingBasis = scalingBasis,
            isBodyweight = null,
            dropSetEnabled = 0L,
            dropSetMinWeightKg = null,
        )
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
