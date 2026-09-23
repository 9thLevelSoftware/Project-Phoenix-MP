package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PullCycleDayDto
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.PortalCycleProgressStateSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.data.sync.PulledWorkoutDeletionDto
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `dirty workout snapshot expands full parent and concurrent edit survives ack`() = runTest {
        insertHistoricalSession(
            id = "component-a",
            timestamp = 100L,
            exerciseId = "bench",
            exerciseName = "Bench",
            workingReps = 5L,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
            routineSessionId = "portal-parent",
        )
        insertHistoricalSession(
            id = "component-b",
            timestamp = 101L,
            exerciseId = "row",
            exerciseName = "Row",
            workingReps = 5L,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
            routineSessionId = "portal-parent",
        )

        val initial = repository.getDirtyWorkoutSnapshot("active-profile")
        repository.acknowledgeWorkoutSnapshot(initial, setOf("portal-parent"))
        database.phoenixDatabaseQueries.markWorkoutComponentDirty("component-a")

        val inFlight = repository.getDirtyWorkoutSnapshot("active-profile")
        assertEquals(setOf("component-a", "component-b"), inFlight.sessions.mapTo(linkedSetOf()) { it.id })
        database.phoenixDatabaseQueries.markWorkoutComponentDirty("component-a")
        repository.acknowledgeWorkoutSnapshot(inFlight, setOf("portal-parent"))

        val componentA = database.phoenixDatabaseQueries.selectSessionById("component-a").executeAsOne()
        assertTrue(componentA.local_sync_generation > componentA.synced_sync_generation)
        val retry = repository.getDirtyWorkoutSnapshot("active-profile")
        assertEquals(setOf("component-a", "component-b"), retry.sessions.mapTo(linkedSetOf()) { it.id })
    }

    @Test
    fun `notes only local edit dirties complete workout parent`() = runTest {
        listOf("component-a" to "bench", "component-b" to "row").forEachIndexed { index, pair ->
            insertHistoricalSession(
                id = pair.first,
                timestamp = 100L + index,
                exerciseId = pair.second,
                exerciseName = pair.second,
                workingReps = 5L,
                peakConcentricA = null,
                peakConcentricB = null,
                peakEccentricA = null,
                peakEccentricB = null,
                profileId = "active-profile",
                routineSessionId = "portal-parent",
            )
        }
        repository.saveLocalSessionNotes("portal-parent", "snapshot notes", 150L)
        val inFlight = repository.getDirtyWorkoutSnapshot("active-profile")

        repository.saveLocalSessionNotes("portal-parent", "concurrent notes", 200L)
        repository.acknowledgeWorkoutSnapshot(inFlight, setOf("portal-parent"))

        assertEquals("snapshot notes", inFlight.sessionNotesByPortalId.getValue("portal-parent").notes)
        assertEquals(
            "concurrent notes",
            database.phoenixDatabaseQueries.getSessionNotes("portal-parent").executeAsOne().notes,
        )
        val retry = repository.getDirtyWorkoutSnapshot("active-profile")
        assertEquals(setOf("component-a", "component-b"), retry.sessions.mapTo(linkedSetOf()) { it.id })
    }

    @Test
    fun `pulled workout tombstone is replay safe and blocks live resurrection`() = runTest {
        val deletion = PulledWorkoutDeletionDto(
            mutationId = "delete-1",
            profileId = "active-profile",
            scope = WorkoutDeletionScope.WORKOUT,
            portalSessionId = "portal-parent",
            deletedAt = "2026-09-20T12:00:00Z",
        )
        val staleLiveComponent = WorkoutSession(
            id = "component-a",
            timestamp = 100L,
            mode = "OldSchool",
            reps = 5,
            weightPerCableKg = 20f,
            totalReps = 5,
            workingReps = 5,
            exerciseId = "bench",
            exerciseName = "Bench",
            routineSessionId = "portal-parent",
            profileId = "active-profile",
        )

        repeat(2) {
            repository.mergeAllPullData(
                ownerUserId = "owner-1",
                workoutDeletions = listOf(deletion),
                sessions = listOf(staleLiveComponent),
                routines = emptyList(),
                cycles = emptyList(),
                badges = emptyList(),
                gamificationStats = null,
                personalRecords = emptyList(),
                lastSync = 0L,
                profileId = "active-profile",
            )
        }

        assertNull(database.phoenixDatabaseQueries.selectSessionById("component-a").executeAsOneOrNull())
        val retained = database.phoenixDatabaseQueries
            .selectWorkoutDeletionByMutationId("delete-1")
            .executeAsOne()
        assertEquals("owner-1", retained.owner_user_id)
        assertEquals("REMOTE", retained.source)

        assertFailsWith<IllegalStateException> {
            repository.mergeAllPullData(
                ownerUserId = "owner-1",
                workoutDeletions = listOf(deletion.copy(portalSessionId = "different-parent")),
                sessions = emptyList(),
                routines = emptyList(),
                cycles = emptyList(),
                badges = emptyList(),
                gamificationStats = null,
                personalRecords = emptyList(),
                lastSync = 0L,
                profileId = "active-profile",
            )
        }
    }

    @Test
    fun `account A workout tombstone retains ledger without deleting account B target`() = runTest {
        database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-b", 1L, "default")
        insertHistoricalSession(
            id = "foreign-component",
            timestamp = 100L,
            exerciseId = "bench",
            exerciseName = "Bench",
            workingReps = 5L,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "default",
            routineSessionId = "shared-parent",
        )

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = listOf(
                PulledWorkoutDeletionDto(
                    mutationId = "foreign-delete",
                    profileId = "default",
                    scope = WorkoutDeletionScope.WORKOUT,
                    portalSessionId = "shared-parent",
                    deletedAt = "2026-09-20T12:00:00Z",
                ),
            ),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        assertNotNull(database.phoenixDatabaseQueries.selectSessionById("foreign-component").executeAsOneOrNull())
        assertEquals(
            "owner-a",
            database.phoenixDatabaseQueries
                .selectWorkoutDeletionByMutationId("foreign-delete")
                .executeAsOne()
                .owner_user_id,
        )
    }

    @Test
    fun `component tombstone with mismatched portal parent does not delete target`() = runTest {
        database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", 1L, "default")
        insertHistoricalSession(
            id = "component-a",
            timestamp = 100L,
            exerciseId = "bench",
            exerciseName = "Bench",
            workingReps = 5L,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "default",
            routineSessionId = "actual-parent",
        )

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = listOf(
                PulledWorkoutDeletionDto(
                    mutationId = "wrong-parent-delete",
                    profileId = "default",
                    scope = WorkoutDeletionScope.COMPONENT,
                    portalSessionId = "different-parent",
                    componentSessionId = "component-a",
                    deletedAt = "2026-09-20T12:00:00Z",
                ),
            ),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        assertNotNull(database.phoenixDatabaseQueries.selectSessionById("component-a").executeAsOneOrNull())
        assertNotNull(
            database.phoenixDatabaseQueries
                .selectWorkoutDeletionByMutationId("wrong-parent-delete")
                .executeAsOneOrNull(),
        )
    }

    @Test
    fun `workout tombstone deletes only matching owner when portal parent collides`() = runTest {
        database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", 1L, "default")
        database.phoenixDatabaseQueries.insertProfile("profile-b", "B", 2L, 1L, 0L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-b", 1L, "profile-b")
        insertHistoricalSession(
            id = "component-owner-a",
            timestamp = 100L,
            exerciseId = "bench",
            exerciseName = "Bench",
            workingReps = 5L,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "default",
            routineSessionId = "shared-parent",
        )
        insertHistoricalSession(
            id = "component-owner-b",
            timestamp = 101L,
            exerciseId = "row",
            exerciseName = "Row",
            workingReps = 5L,
            peakConcentricA = null,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "profile-b",
            routineSessionId = "shared-parent",
        )

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = listOf(
                PulledWorkoutDeletionDto(
                    mutationId = "owner-a-workout-delete",
                    profileId = "default",
                    scope = WorkoutDeletionScope.WORKOUT,
                    portalSessionId = "shared-parent",
                    deletedAt = "2026-09-20T12:00:00Z",
                ),
            ),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        assertNull(database.phoenixDatabaseQueries.selectSessionById("component-owner-a").executeAsOneOrNull())
        assertNotNull(database.phoenixDatabaseQueries.selectSessionById("component-owner-b").executeAsOneOrNull())
    }

    @Test
    fun `component plus server derived workout tombstone removes final sibling and retains both ledgers`() = runTest {
        database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", 1L, "default")
        listOf("component-a" to "bench", "component-b" to "row").forEachIndexed { index, component ->
            insertHistoricalSession(
                id = component.first,
                timestamp = 100L + index,
                exerciseId = component.second,
                exerciseName = component.second,
                workingReps = 5L,
                peakConcentricA = null,
                peakConcentricB = null,
                peakEccentricA = null,
                peakEccentricB = null,
                profileId = "default",
                routineSessionId = "portal-parent",
            )
        }

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = listOf(
                PulledWorkoutDeletionDto(
                    mutationId = "component-delete",
                    profileId = "default",
                    scope = WorkoutDeletionScope.COMPONENT,
                    portalSessionId = "portal-parent",
                    componentSessionId = "component-a",
                    deletedAt = "2026-09-20T12:00:00Z",
                ),
                PulledWorkoutDeletionDto(
                    mutationId = "derived-workout-delete",
                    profileId = "default",
                    scope = WorkoutDeletionScope.WORKOUT,
                    portalSessionId = "portal-parent",
                    deletedAt = "2026-09-20T12:00:00Z",
                ),
            ),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        assertNull(database.phoenixDatabaseQueries.selectSessionById("component-a").executeAsOneOrNull())
        assertNull(database.phoenixDatabaseQueries.selectSessionById("component-b").executeAsOneOrNull())
        assertNotNull(
            database.phoenixDatabaseQueries
                .selectWorkoutDeletionByMutationId("component-delete")
                .executeAsOneOrNull(),
        )
        assertNotNull(
            database.phoenixDatabaseQueries
                .selectWorkoutDeletionByMutationId("derived-workout-delete")
                .executeAsOneOrNull(),
        )
    }

    @Test
    fun `retained ownership claim routes a workout that arrives on a later pull`() = runTest {
        database.phoenixDatabaseQueries.insertProfile("target-profile", "Recovered", 2L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", 1L, "target-profile")
        database.phoenixDatabaseQueries.insertLocalOwnershipClaimIfAbsent(
            ownerUserId = "owner-a",
            entityType = OwnershipEntityType.WORKOUT.name,
            entityId = "portal-parent",
            mutationId = "event-before-row",
            sourceProfileId = "source-profile",
            targetProfileId = "target-profile",
            transferredAt = 10L,
        )
        val incoming = WorkoutSession(
            id = "component-later",
            timestamp = 100L,
            mode = "OldSchool",
            reps = 5,
            weightPerCableKg = 20f,
            totalReps = 5,
            workingReps = 5,
            exerciseId = "bench",
            exerciseName = "Bench",
            routineSessionId = "portal-parent",
            profileId = "source-profile",
        )

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = emptyList(),
            sessions = listOf(incoming),
            routines = emptyList(),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "source-profile",
        )

        assertEquals(
            "target-profile",
            database.phoenixDatabaseQueries.selectSessionById("component-later").executeAsOne().profile_id,
        )
        assertEquals(
            "target-profile",
            database.phoenixDatabaseQueries.selectLocalOwnershipClaim(
                "owner-a",
                OwnershipEntityType.WORKOUT.name,
                "portal-parent",
            ).executeAsOne().target_profile_id,
        )
    }

    @Test
    fun `retained claims route later routine cycle state and personal record roots`() = runTest {
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile("claimed-target", "Claimed", 3L, 1L, 0L)
        queries.linkProfileToSupabase("owner-a", 1L, "claimed-target")
        listOf(
            OwnershipEntityType.ROUTINE to "routine-later",
            OwnershipEntityType.CYCLE to "cycle-later",
            OwnershipEntityType.PERSONAL_RECORD to "pr-later",
        ).forEach { (type, id) ->
            queries.insertLocalOwnershipClaimIfAbsent(
                ownerUserId = "owner-a",
                entityType = type.name,
                entityId = id,
                mutationId = "claim-${type.name}",
                sourceProfileId = "default",
                targetProfileId = "claimed-target",
                transferredAt = 10L,
            )
        }

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = emptyList(),
            sessions = emptyList(),
            routines = listOf(PullRoutineDto(id = "routine-later", name = "Later routine", updatedAt = 20L)),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = "cycle-later",
                    name = "Later cycle",
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(20L).toString(),
                ),
            ),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = listOf(
                PersonalRecordSyncDto(
                    clientId = "pr-later",
                    exerciseId = "bench",
                    exerciseName = "Bench",
                    weight = 50f,
                    reps = 5,
                    oneRepMax = 56f,
                    achievedAt = 15L,
                    workoutMode = "OldSchool",
                    createdAt = 15L,
                    updatedAt = 20L,
                ),
            ),
            lastSync = 0L,
            profileId = "default",
        )

        assertEquals("claimed-target", queries.selectRoutineById("routine-later").executeAsOne().profile_id)
        assertEquals("claimed-target", queries.selectTrainingCycleById("cycle-later").executeAsOne().profile_id)
        assertEquals("claimed-target", queries.selectCycleSyncState("cycle-later").executeAsOne().profile_id)
        assertEquals(
            "claimed-target",
            queries.selectAllRecords("claimed-target").executeAsList().single { it.uuid == "pr-later" }.profile_id,
        )
    }

    @Test
    fun `retained ownership claim rejects a target bound to another account`() = runTest {
        database.phoenixDatabaseQueries.insertProfile("foreign-target", "Foreign", 2L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-b", 1L, "foreign-target")
        database.phoenixDatabaseQueries.insertLocalOwnershipClaimIfAbsent(
            ownerUserId = "owner-a",
            entityType = OwnershipEntityType.WORKOUT.name,
            entityId = "portal-parent",
            mutationId = "foreign-target-event",
            sourceProfileId = "source-profile",
            targetProfileId = "foreign-target",
            transferredAt = 10L,
        )
        val incoming = WorkoutSession(
            id = "component-later",
            timestamp = 100L,
            mode = "OldSchool",
            reps = 5,
            weightPerCableKg = 20f,
            totalReps = 5,
            workingReps = 5,
            exerciseId = "bench",
            exerciseName = "Bench",
            routineSessionId = "portal-parent",
            profileId = "source-profile",
        )

        assertFailsWith<IllegalStateException> {
            repository.mergeAllPullData(
                ownerUserId = "owner-a",
                workoutDeletions = emptyList(),
                sessions = listOf(incoming),
                routines = emptyList(),
                cycles = emptyList(),
                badges = emptyList(),
                gamificationStats = null,
                personalRecords = emptyList(),
                lastSync = 0L,
                profileId = "source-profile",
            )
        }

        assertNull(database.phoenixDatabaseQueries.selectSessionById("component-later").executeAsOneOrNull())
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
    fun `mergeSessions updates portal origin without deleting metric children`() = runTest {
        repository.mergeSessions(listOf(syncSession("portal-child", updatedAt = 200L, duration = 20L)))
        database.phoenixDatabaseQueries.insertMetric(
            sessionId = "portal-child",
            timestamp = 10L,
            position = 1.0,
            positionB = null,
            velocity = null,
            velocityB = null,
            load = null,
            loadB = null,
            power = null,
            status = 0L,
        )

        repository.mergeSessions(listOf(syncSession("portal-child", updatedAt = 300L, duration = 30L)))

        val session = database.phoenixDatabaseQueries.selectSessionById("portal-child").executeAsOne()
        assertEquals(30L, session.duration)
        assertEquals(1, database.phoenixDatabaseQueries.selectMetricsBySession("portal-child").executeAsList().size)
    }

    @Test
    fun `mergeSessions rejects stale portal replay`() = runTest {
        repository.mergeSessions(listOf(syncSession("portal-stale", updatedAt = 300L, duration = 30L)))

        repository.mergeSessions(listOf(syncSession("portal-stale", updatedAt = 200L, duration = 20L)))

        val session = database.phoenixDatabaseQueries.selectSessionById("portal-stale").executeAsOne()
        assertEquals(30L, session.duration)
        assertEquals(300L, session.updatedAt)
    }

    @Test
    fun `mergeSessions preserves local origin capture and ownership`() = runTest {
        insertHistoricalSession(
            id = "local-capture",
            timestamp = 100L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            workingReps = 8,
            peakConcentricA = 42.0,
            peakConcentricB = null,
            peakEccentricA = null,
            peakEccentricB = null,
            profileId = "active-profile",
        )
        database.phoenixDatabaseQueries.updateSessionTimestamp(250L, "local-capture")

        repository.mergeSessions(
            listOf(
                syncSession(
                    id = "local-capture",
                    updatedAt = 300L,
                    duration = 1L,
                    weightPerCableKg = 1f,
                ),
            ),
        )

        val session = database.phoenixDatabaseQueries.selectSessionById("local-capture").executeAsOne()
        assertEquals(60_000L, session.duration)
        assertEquals(20.0, session.weightPerCableKg)
        assertEquals(42.0, session.peakForceConcentricA)
        assertEquals("active-profile", session.profile_id)
        assertEquals(250L, session.updatedAt)
    }

    @Test
    fun `mergeSessions never resurrects a retained tombstone`() = runTest {
        repository.mergeSessions(
            listOf(syncSession("portal-deleted", updatedAt = 200L, duration = 20L, deletedAt = 190L)),
        )

        repository.mergeSessions(
            listOf(syncSession("portal-deleted", updatedAt = 300L, duration = 30L, deletedAt = null)),
        )

        val session = database.phoenixDatabaseQueries.selectSessionById("portal-deleted").executeAsOne()
        assertEquals(190L, session.deletedAt)
        assertEquals(20L, session.duration)
        assertEquals(200L, session.updatedAt)
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
    fun `findExerciseId and getExerciseMuscleGroup resolve a pre-rename name before an archived name match`() = runTest {
        // The renamed active catalogue row as the #857 importer overlay writes it, plus an
        // archived legacy row that still carries the pre-rename name under a stale muscle group.
        listOf(
            "One_Leg_Barbell_Squat" to ("Bulgarian Split Squat" to "Legs"),
            "arch-ols" to ("One Leg Barbell Squat" to "General"),
        ).forEach { (id, nameAndGroup) ->
            val (rowName, group) = nameAndGroup
            database.phoenixDatabaseQueries.insertExercise(
                id,
                rowName,
                rowName,
                null,
                0L,
                group,
                group,
                null,
                "BARBELL",
                null,
                null,
                null,
                null,
                null,
                0.0,
                if (id == "arch-ols") 1L else 0L,
                0L,
                0L,
                0L,
                null,
                if (id == "arch-ols") null else "One Leg Barbell Squat",
                "DOUBLE",
                null,
                null,
                isBodyweight = null,
            )
        }

        // The archived exact-name row must not win the muscle-specific match over the renamed
        // active row's alias, or pulled sessions link to the obsolete identity (#857).
        assertEquals("One_Leg_Barbell_Squat", repository.findExerciseId("One Leg Barbell Squat", "General", null))
        assertEquals("One_Leg_Barbell_Squat", repository.findExerciseId("One Leg Barbell Squat", null, null))
        // Case-insensitive portal name variation resolves through the alias too.
        assertEquals("One_Leg_Barbell_Squat", repository.findExerciseId("one leg barbell squat", null, null))
        // Push-side lookup keeps the renamed row's muscle group instead of the archived row's
        // stale one, so a dirty legacy session does not upload muscle group "General".
        assertEquals("Legs", repository.getExerciseMuscleGroup(null, "One Leg Barbell Squat"))
        assertEquals("Legs", repository.getExerciseMuscleGroup(null, "Bulgarian Split Squat"))
        // A usable id stays authoritative over any name or alias match.
        assertEquals("arch-ols", repository.findExerciseId("One Leg Barbell Squat", "General", "arch-ols"))
        assertNull(repository.findExerciseId("Totally Unknown", null, null))
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
    fun `mergePortalRoutines accepts supported durations and quarantines malformed present values`() = runTest {
        val portalRoutine = PullRoutineDto(
            id = "routine-duration-bounds",
            name = "Duration bounds",
            updatedAt = 1_700_000_000_200,
            exercises = listOf(
                PullRoutineExerciseDto(id = "duration-min", name = "Min", durationSeconds = 10),
                PullRoutineExerciseDto(id = "duration-max", name = "Max", durationSeconds = 300),
                PullRoutineExerciseDto(id = "duration-short", name = "Short", durationSeconds = 9),
                PullRoutineExerciseDto(id = "duration-long", name = "Long", durationSeconds = 301),
            ),
        )
        repository.mergePortalRoutines(
            routines = listOf(portalRoutine),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val rows = database.phoenixDatabaseQueries.selectExercisesByRoutine("routine-duration-bounds")
            .executeAsList().associateBy { it.id }
        assertEquals(10L, rows.getValue("duration-min").duration)
        assertEquals(300L, rows.getValue("duration-max").duration)
        assertEquals(1L, rows.getValue("duration-min").durationSyncKnown)
        assertEquals(1L, rows.getValue("duration-max").durationSyncKnown)
        assertNull(rows.getValue("duration-short").duration)
        assertNull(rows.getValue("duration-long").duration)
        assertEquals(2L, rows.getValue("duration-short").durationSyncKnown)
        assertEquals(2L, rows.getValue("duration-long").durationSyncKnown)
        assertEquals(emptyList(), repository.getRoutineIdsNeedingDurationBackfill("active-profile"))

        val outbound = repository.getFullRoutinesModifiedSince(0L, "active-profile").single()
        val wireExercises = PortalSyncAdapter.toPortalRoutine(outbound, "user").exercises.associateBy { it.id }
        assertNull(wireExercises.getValue("duration-short").durationSeconds)
        assertNull(wireExercises.getValue("duration-long").durationSeconds)

        // A repeated malformed full-pull response remains quarantined and does not restart backfill.
        repository.mergePortalRoutines(
            routines = listOf(portalRoutine),
            lastSync = 0L,
            profileId = "active-profile",
        )
        assertEquals(emptyList(), repository.getRoutineIdsNeedingDurationBackfill("active-profile"))

        // A later corrected value can replace quarantine even while the locally newer parent wins.
        repository.mergePortalRoutines(
            routines = listOf(
                portalRoutine.copy(
                    exercises = portalRoutine.exercises.map { exercise ->
                        when (exercise.id) {
                            "duration-short" -> exercise.copy(durationSeconds = 60)
                            "duration-long" -> exercise.copy(durationSeconds = 120)
                            else -> exercise
                        }
                    },
                ),
            ),
            lastSync = 0L,
            profileId = "active-profile",
        )
        val correctedRows = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-duration-bounds")
            .executeAsList()
            .associateBy { it.id }
        assertEquals(60L, correctedRows.getValue("duration-short").duration)
        assertEquals(120L, correctedRows.getValue("duration-long").duration)
        assertEquals(1L, correctedRows.getValue("duration-short").durationSyncKnown)
        assertEquals(1L, correctedRows.getValue("duration-long").durationSyncKnown)
    }

    @Test
    fun `legacy unknown duration makes its unchanged parent eligible without dirtying it`() = runTest {
        insertLocalRoutine("routine-duration-backfill")
        database.phoenixDatabaseQueries.updateRoutineById(
            name = "Local routine-duration-backfill",
            description = "",
            updatedAt = 100L,
            id = "routine-duration-backfill",
        )
        insertLocalRoutineExercise(
            id = "duration-backfill",
            routineId = "routine-duration-backfill",
            duration = 45,
            durationSyncKnown = 0,
        )

        val outbound = repository.getFullRoutinesModifiedSince(1_000L, "active-profile").single()

        assertEquals("routine-duration-backfill", outbound.id)
        assertEquals(45, outbound.exercises.single().duration)
        assertEquals(false, outbound.exercises.single().durationSyncKnown)
        assertEquals(
            "45",
            PortalSyncAdapter.toPortalRoutine(outbound, "user").exercises.single().durationSeconds?.content,
            "a supported legacy duration must be sent even while its backfill marker is unknown",
        )
        assertEquals(
            listOf("routine-duration-backfill"),
            repository.getRoutineIdsNeedingDurationBackfill("active-profile"),
        )
        assertEquals(
            100L,
            database.phoenixDatabaseQueries.selectRoutineById("routine-duration-backfill").executeAsOne().updatedAt,
            "backfill eligibility must not dirty the parent routine",
        )
    }

    @Test
    fun `local wins merge still hydrates only unknown portal durations`() = runTest {
        insertLocalRoutine("routine-duration-local-wins")
        database.phoenixDatabaseQueries.updateRoutineById(
            name = "Local name",
            description = "local description",
            updatedAt = 300L,
            id = "routine-duration-local-wins",
        )
        insertLocalRoutineExercise(
            id = "duration-value",
            routineId = "routine-duration-local-wins",
            duration = null,
            durationSyncKnown = 0,
        )
        insertLocalRoutineExercise(
            id = "duration-clear",
            routineId = "routine-duration-local-wins",
            duration = 30,
            durationSyncKnown = 0,
        )
        insertLocalRoutineExercise(
            id = "duration-null",
            routineId = "routine-duration-local-wins",
            duration = null,
            durationSyncKnown = 0,
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-duration-local-wins",
                    name = "Portal name",
                    updatedAt = 200L,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "duration-value",
                            durationSeconds = 45,
                            durationSecondsPresent = true,
                        ),
                        PullRoutineExerciseDto(
                            id = "duration-clear",
                            durationSeconds = null,
                            durationSecondsPresent = true,
                        ),
                        PullRoutineExerciseDto(
                            id = "duration-null",
                            durationSeconds = null,
                            durationSecondsPresent = true,
                        ),
                    ),
                ),
            ),
            lastSync = 100L,
            profileId = "active-profile",
        )

        val routine = database.phoenixDatabaseQueries
            .selectRoutineById("routine-duration-local-wins")
            .executeAsOne()
        val rows = database.phoenixDatabaseQueries
            .selectExercisesByRoutine("routine-duration-local-wins")
            .executeAsList()
            .associateBy { it.id }
        assertEquals("Local name", routine.name)
        assertEquals("local description", routine.description)
        assertEquals(300L, routine.updatedAt)
        assertEquals(45L, rows.getValue("duration-value").duration)
        assertEquals(1L, rows.getValue("duration-value").durationSyncKnown)
        assertEquals(30L, rows.getValue("duration-clear").duration)
        assertEquals(0L, rows.getValue("duration-clear").durationSyncKnown)
        assertNull(rows.getValue("duration-null").duration)
        assertEquals(1L, rows.getValue("duration-null").durationSyncKnown)
    }

    @Test
    fun `LWW rejected duration backfill accepts the authoritative portal null`() = runTest {
        val routineId = "routine-duration-server-wins"
        val exerciseId = "duration-server-wins"
        insertLocalRoutine(routineId)
        database.phoenixDatabaseQueries.updateRoutineById(
            name = "Local edit",
            description = "",
            updatedAt = 300L,
            id = routineId,
        )
        insertLocalRoutineExercise(
            id = exerciseId,
            routineId = routineId,
            duration = 45,
            durationSyncKnown = 0,
        )

        repository.mergeAllPullData(
            sessions = emptyList(),
            routines = listOf(
                PullRoutineDto(
                    id = routineId,
                    name = "Portal edit",
                    updatedAt = 200L,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = exerciseId,
                            name = "Deadlift",
                            durationSeconds = null,
                            durationSecondsPresent = true,
                        ),
                    ),
                ),
            ),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 100L,
            profileId = "active-profile",
            serverWinsRoutineIds = setOf(routineId),
        )

        val routine = database.phoenixDatabaseQueries.selectRoutineById(routineId).executeAsOne()
        val exercise = database.phoenixDatabaseQueries.selectExercisesByRoutine(routineId).executeAsOne()
        assertEquals("Portal edit", routine.name)
        assertNull(exercise.duration, "a rejected push must converge to the portal's explicit null")
        assertEquals(1L, exercise.durationSyncKnown)
    }

    @Test
    fun `out of range portal duration preserves a supported local duration`() = runTest {
        insertLocalRoutine("routine-duration-preserve")
        insertLocalRoutineExercise(
            id = "duration-preserve",
            routineId = "routine-duration-preserve",
            duration = 45,
            durationSyncKnown = 0,
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "routine-duration-preserve",
                    name = "Duration preserve",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "duration-preserve",
                            name = "Deadlift",
                            durationSeconds = Int.MAX_VALUE,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )

        val row = database.phoenixDatabaseQueries.selectExercisesByRoutine("routine-duration-preserve")
            .executeAsList().single()
        assertEquals(45L, row.duration)
        assertEquals(2L, row.durationSyncKnown)
        assertEquals(emptyList(), repository.getRoutineIdsNeedingDurationBackfill("active-profile"))
        val outbound = repository.getFullRoutinesModifiedSince(0L, "active-profile").single()
        assertEquals(
            "45",
            PortalSyncAdapter.toPortalRoutine(outbound, "user").exercises.single().durationSeconds?.content,
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
        queries.insertTrainingCycle(
            "cycle-link",
            "Cycle",
            null,
            1_700_000_000_000,
            1,
            "active-profile",
            null,
            1,
            1_700_000_000_000,
        )
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
        duration: Long? = null,
        durationSyncKnown: Long = 0,
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
            duration = duration,
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
        database.phoenixDatabaseQueries.updateRoutineExerciseDurationSyncKnown(durationSyncKnown, id)
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

    @Test
    fun `newer canonical cycle snapshots exact dirty aggregate before full overwrite`() = runTest {
        val cycleId = "cycle-atomic-draft"
        val cycleRepository = SqlDelightTrainingCycleRepository(database)
        cycleRepository.saveCycle(
            TrainingCycle.create(
                id = cycleId,
                name = "Local cycle",
                days = listOf(
                    CycleDay.create(
                        id = "local-day",
                        cycleId = cycleId,
                        dayNumber = 1,
                        name = "Local day",
                        echoLevel = EchoLevel.EPIC,
                        eccentricLoadPercent = 125,
                    ),
                ),
            ),
        )
        val initialProgress = cycleRepository.initializeProgress(cycleId)
        cycleRepository.updateCycleProgress(
            initialProgress.copy(
                currentDayNumber = 4,
                lastCompletedDate = 40L,
                lastAdvancedAt = 41L,
                completedDays = setOf(1, 3),
                missedDays = setOf(2),
                rotationCount = 2,
            ),
        )
        cycleRepository.saveCycleProgression(
            CycleProgression(
                cycleId = cycleId,
                frequencyCycles = 3,
                weightIncreasePercent = 2.5f,
                echoLevelIncrease = true,
                eccentricLoadIncreasePercent = 5,
            ),
        )
        val localUpdatedAt = assertNotNull(cycleRepository.getCycleById(cycleId)?.updatedAt)

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = emptyList(),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = cycleId,
                    name = "Server cycle",
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(localUpdatedAt + 100L).toString(),
                    progressStatePresent = true,
                    progressState = PortalCycleProgressStateSyncDto(
                        currentDayNumber = 2,
                        cycleStartDate = 200L,
                        completedDays = listOf(1),
                        missedDays = emptyList(),
                        rotationCount = 7,
                    ),
                    days = listOf(
                        PullCycleDayDto(
                            id = "remote-day",
                            cycleId = cycleId,
                            dayNumber = 1,
                            notes = "Remote day",
                            echoLevelPresent = true,
                            echoLevel = EchoLevel.HARD.name,
                            eccentricLoadPercentPresent = true,
                            eccentricLoadPercent = 50,
                        ),
                    ),
                ),
            ),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        val freshRepository = SqlDelightTrainingCycleRepository(database)
        val draft = freshRepository.getCycleConflictDrafts("default").single()
        assertEquals(EchoLevel.EPIC, draft.cycle.days.single().echoLevel)
        assertEquals(125, draft.cycle.days.single().eccentricLoadPercent)
        val recoveredId = assertNotNull(freshRepository.saveCycleDraftAsCopy(draft.id))
        assertEquals(setOf(1, 3), assertNotNull(freshRepository.getCycleProgress(recoveredId)).completedDays)
        assertEquals(3, assertNotNull(freshRepository.getCycleProgression(recoveredId)).frequencyCycles)

        assertEquals(EchoLevel.HARD, freshRepository.getCycleById(cycleId)?.days?.single()?.echoLevel)
        assertEquals(7, freshRepository.getCycleProgress(cycleId)?.rotationCount)
    }

    @Test
    fun `authoritative null progression clears while legacy omission preserves`() = runTest {
        val cycleId = "cycle-progression-clear"
        val cycleRepository = SqlDelightTrainingCycleRepository(database)
        cycleRepository.saveCycle(TrainingCycle.create(id = cycleId, name = "Local cycle"))
        cycleRepository.saveCycleProgression(
            CycleProgression(
                cycleId = cycleId,
                frequencyCycles = 3,
                weightIncreasePercent = 2.5f,
                echoLevelIncrease = true,
                eccentricLoadIncreasePercent = 5,
            ),
        )
        val localUpdatedAt = assertNotNull(cycleRepository.getCycleById(cycleId)?.updatedAt)

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = emptyList(),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = cycleId,
                    name = "Legacy portal cycle",
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(localUpdatedAt + 100L).toString(),
                ),
            ),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        assertNotNull(
            cycleRepository.getCycleProgression(cycleId),
            "A legacy pull without presence metadata must preserve local progression",
        )

        repository.mergeAllPullData(
            ownerUserId = "owner-a",
            workoutDeletions = emptyList(),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = cycleId,
                    name = "Canonical portal cycle",
                    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(localUpdatedAt + 200L).toString(),
                    progressionSettingsPresent = true,
                    progressionSettings = null,
                ),
            ),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )

        assertNull(cycleRepository.getCycleProgression(cycleId))
    }

    private fun syncSession(
        id: String,
        updatedAt: Long,
        duration: Long,
        weightPerCableKg: Float = 25f,
        deletedAt: Long? = null,
    ) = WorkoutSessionSyncDto(
        clientId = id,
        serverId = "server-$id",
        timestamp = 100L,
        mode = "OldSchool",
        targetReps = 8,
        weightPerCableKg = weightPerCableKg,
        duration = duration,
        totalReps = 8,
        exerciseId = "bench",
        exerciseName = "Bench Press",
        deletedAt = deletedAt,
        createdAt = 100L,
        updatedAt = updatedAt,
    )

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
        routineSessionId: String? = null,
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
            routineSessionId = routineSessionId,
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

    // ===== Server-reported deletions (PR 16 deletedRoutineIds / deletedCycleIds) =====

    private suspend fun seedRoutineAndCycles() {
        database.phoenixDatabaseQueries.insertProfile(
            id = "active-profile",
            name = "Active",
            colorIndex = 0L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )
        database.phoenixDatabaseQueries.linkProfileToSupabase(
            supabase_user_id = "owner-user",
            last_auth_at = 1_700_000_000_000,
            id = "active-profile",
        )
        val queries = database.phoenixDatabaseQueries
        // CycleDay.routine_id has an enforced FK. Seed the local-only template
        // routines before the pulled cycle graph that references them.
        for (templateId in listOf("cycle_routine_only-y", "cycle_routine_shared")) {
            queries.insertRoutine(
                id = templateId,
                name = "Template $templateId",
                description = "",
                createdAt = 1_700_000_000_000,
                lastUsed = null,
                useCount = 0,
                profile_id = "active-profile",
                groupId = null,
                deletedAt = null,
            )
        }
        repository.mergeAllPullData(
            sessions = emptyList(),
            routines = listOf(
                PullRoutineDto(
                    id = "routine-x",
                    userId = "user",
                    name = "Deleted on portal",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-x-1",
                            routineId = "routine-x",
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            orderIndex = 0,
                            reps = 8,
                            weight = 20f,
                        ),
                        PullRoutineExerciseDto(
                            id = "rex-x-2",
                            routineId = "routine-x",
                            name = "Row",
                            muscleGroup = "Back",
                            orderIndex = 1,
                            reps = 10,
                            weight = 15f,
                        ),
                    ),
                ),
                PullRoutineDto(
                    id = "local-legacy",
                    userId = "user",
                    name = "Legacy local id",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-legacy-1",
                            routineId = "local-legacy",
                            name = "Curl",
                            muscleGroup = "Arms",
                            orderIndex = 0,
                            reps = 12,
                            weight = 10f,
                        ),
                    ),
                ),
                PullRoutineDto(
                    id = "routine-keep",
                    userId = "user",
                    name = "Kept",
                    updatedAt = 1_700_000_000_200,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "rex-keep-1",
                            routineId = "routine-keep",
                            name = "Squat",
                            muscleGroup = "Legs",
                            orderIndex = 0,
                            reps = 5,
                            weight = 40f,
                        ),
                    ),
                ),
            ),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = "cycle-keep",
                    name = "Uses deleted routine",
                    days = listOf(
                        PullCycleDayDto(id = "day-keep-1", cycleId = "cycle-keep", dayNumber = 1, routineId = "routine-x"),
                        PullCycleDayDto(id = "day-keep-2", cycleId = "cycle-keep", dayNumber = 2, routineId = "routine-keep"),
                        PullCycleDayDto(id = "day-keep-3", cycleId = "cycle-keep", dayNumber = 3, routineId = "cycle_routine_shared"),
                    ),
                ),
                PullTrainingCycleDto(
                    id = "cycle-y",
                    name = "Deleted on portal",
                    progressionSettings = """{"frequencyCycles":"2"}""",
                    days = listOf(
                        PullCycleDayDto(id = "day-y-1", cycleId = "cycle-y", dayNumber = 1, routineId = "routine-keep"),
                        PullCycleDayDto(id = "day-y-2", cycleId = "cycle-y", dayNumber = 2, routineId = "cycle_routine_only-y"),
                        PullCycleDayDto(id = "day-y-3", cycleId = "cycle-y", dayNumber = 3, routineId = "cycle_routine_shared"),
                    ),
                ),
            ),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 1_700_000_000_100,
            profileId = "active-profile",
        )
        // Legacy row: local id differs from the id the server knows (serverId column).
        queries.updateRoutineServerId("routine-srv", "local-legacy")
        // Children that FK cascades would remove, but the test driver runs with foreign_keys off.
        queries.insertPlannedSet(
            id = "planned-x-1",
            routine_exercise_id = "rex-x-1",
            set_number = 1,
            set_type = "STANDARD",
            target_reps = 8,
            target_weight_kg = 20.0,
            target_rpe = null,
            rest_seconds = 60,
        )
        queries.insertSuperset(
            id = "superset-x",
            routineId = "routine-x",
            name = "Pair",
            colorIndex = 0,
            restBetweenSeconds = 10,
            orderIndex = 0,
        )
        database.phoenixDatabaseQueries.insertCycleProgress(
            id = "progress-y",
            cycle_id = "cycle-y",
            current_day_number = 1,
            last_completed_date = null,
            cycle_start_date = 1_700_000_000_000,
            last_advanced_at = null,
            completed_days = null,
            missed_days = null,
            rotation_count = 0,
        )
    }

    @Test
    fun `applyServerDeletions removes routine with exercises and cycle with children`() = runTest {
        seedRoutineAndCycles()
        val queries = database.phoenixDatabaseQueries
        assertEquals(2, queries.selectExercisesByRoutine("routine-x").executeAsList().size)
        assertEquals(1, queries.selectPlannedSetsByRoutineExercise("rex-x-1").executeAsList().size)
        assertEquals(1, queries.selectSupersetsByRoutine("routine-x").executeAsList().size)
        assertNotNull(queries.selectCycleProgressByCycle("cycle-y").executeAsOneOrNull())
        assertNotNull(queries.selectCycleProgression("cycle-y").executeAsOneOrNull())

        val result = repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("routine-x", "never-held-routine"),
            cycleIds = listOf("cycle-y", "never-held-cycle"),
            lastSync = 1_700_000_000_300,
        )

        assertEquals(listOf("routine-x"), result.deletedRoutineIds)
        assertEquals(listOf("cycle-y"), result.deletedCycleIds)
        assertTrue(result.discardedRoutineEditIds.isEmpty())
        // cycle-y had a CycleProgress row -> reported as an in-progress cycle loss.
        assertEquals(listOf("cycle-y"), result.deletedActiveCycleIds)

        // Routine X and its exercises, planned sets and supersets are gone, with no
        // soft-delete tombstone left to push.
        assertNull(queries.selectRoutineById("routine-x").executeAsOneOrNull())
        assertTrue(queries.selectExercisesByRoutine("routine-x").executeAsList().isEmpty())
        assertTrue(queries.selectPlannedSetsByRoutineExercise("rex-x-1").executeAsList().isEmpty())
        assertTrue(queries.selectSupersetsByRoutine("routine-x").executeAsList().isEmpty())
        assertTrue(repository.getDeletedRoutineIdsSince(0L, "active-profile").isEmpty())

        // Cycle Y and its days/progress/progression are gone, no tombstone to push.
        assertNull(queries.selectTrainingCycleById("cycle-y").executeAsOneOrNull())
        assertTrue(queries.selectCycleDaysByCycle("cycle-y").executeAsList().isEmpty())
        assertNull(queries.selectCycleProgressByCycle("cycle-y").executeAsOneOrNull())
        assertNull(queries.selectCycleProgression("cycle-y").executeAsOneOrNull())
        assertTrue(repository.getDeletedCycleIdsSince(0L, "active-profile").isEmpty())

        // Unrelated routine untouched; the surviving cycle keeps its day with the reference nulled.
        assertEquals(1, queries.selectExercisesByRoutine("routine-keep").executeAsList().size)
        val keptDays = queries.selectCycleDaysByCycle("cycle-keep").executeAsList()
        assertEquals(3, keptDays.size)
        assertNull(keptDays.single { it.day_number == 1L }.routine_id)
        assertEquals("routine-keep", keptDays.single { it.day_number == 2L }.routine_id)

        // Template routine used only by the deleted cycle is removed; the one another
        // cycle still references stays.
        assertEquals(listOf("cycle_routine_only-y"), result.deletedTemplateRoutineIds)
        assertNull(queries.selectRoutineById("cycle_routine_only-y").executeAsOneOrNull())
        assertNotNull(queries.selectRoutineById("cycle_routine_shared").executeAsOneOrNull())
        assertEquals("cycle_routine_shared", keptDays.single { it.day_number == 3L }.routine_id)
    }

    @Test
    fun `applyServerDeletions matches legacy routine by serverId`() = runTest {
        seedRoutineAndCycles()
        val queries = database.phoenixDatabaseQueries

        val result = repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("routine-srv"),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_300,
        )

        assertEquals(listOf("local-legacy"), result.deletedRoutineIds)
        assertNull(queries.selectRoutineById("local-legacy").executeAsOneOrNull())
        assertTrue(queries.selectExercisesByRoutine("local-legacy").executeAsList().isEmpty())
        assertNotNull(queries.selectRoutineById("routine-x").executeAsOneOrNull())
    }

    @Test
    fun `applyServerDeletions keeps workout history that names the deleted routine`() = runTest {
        seedRoutineAndCycles()
        insertHistoricalSession(
            id = "session-uses-routine-x",
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
        database.phoenixDatabaseQueries.updateSessionRoutineId("routine-x", "session-uses-routine-x")

        repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("routine-x"),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_300,
        )

        val session = database.phoenixDatabaseQueries.selectSessionById("session-uses-routine-x").executeAsOneOrNull()
        assertNotNull(session, "Deleting a routine must never remove workout history")
    }

    @Test
    fun `applyServerDeletions does not classify discarded edits when lastSync is zero`() = runTest {
        seedRoutineAndCycles()

        val result = repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("routine-x"),
            cycleIds = emptyList(),
            lastSync = 0L,
        )

        assertEquals(listOf("routine-x"), result.deletedRoutineIds)
        assertTrue(result.discardedRoutineEditIds.isEmpty())
    }

    @Test
    fun `applyServerDeletions reports inactive cycle edited after last sync without progress`() = runTest {
        seedRoutineAndCycles()
        val queries = database.phoenixDatabaseQueries
        queries.touchTrainingCycleUpdatedAt(
            updatedAt = 1_700_000_000_900,
            cycleId = "cycle-keep",
        )
        assertEquals(0L, queries.selectTrainingCycleById("cycle-keep").executeAsOne().is_active)
        assertNull(queries.selectCycleProgressByCycle("cycle-keep").executeAsOneOrNull())

        val result = repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = emptyList(),
            cycleIds = listOf("cycle-keep"),
            lastSync = 1_700_000_000_500,
        )

        assertEquals(listOf("cycle-keep"), result.discardedCycleEditIds)
        assertTrue(result.deletedActiveCycleIds.isEmpty())
        assertNull(queries.selectTrainingCycleById("cycle-keep").executeAsOneOrNull())
    }

    @Test
    fun `applyServerDeletions with no ids changes nothing`() = runTest {
        seedRoutineAndCycles()
        val queries = database.phoenixDatabaseQueries

        val result = repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = emptyList(),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_300,
        )

        assertTrue(result.deletedRoutineIds.isEmpty() && result.deletedCycleIds.isEmpty())
        assertNotNull(queries.selectRoutineById("routine-x").executeAsOneOrNull())
        assertEquals(2, queries.selectExercisesByRoutine("routine-x").executeAsList().size)
        assertNotNull(queries.selectTrainingCycleById("cycle-y").executeAsOneOrNull())
        assertEquals(
            "routine-x",
            queries.selectCycleDaysByCycle("cycle-keep").executeAsList().single { it.day_number == 1L }.routine_id,
        )
    }

    @Test
    fun `applyServerDeletions deletes routine with unsynced local edit and reports it`() = runTest {
        // updatedAt after lastSync = a local edit not yet pushed; delete still wins.
        seedRoutineAndCycles()
        database.phoenixDatabaseQueries.updateRoutineById(
            name = "Edited locally",
            description = "",
            updatedAt = 1_700_000_000_900,
            id = "routine-x",
        )

        val result = repository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("routine-x"),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_500,
        )

        assertEquals(listOf("routine-x"), result.discardedRoutineEditIds)
        assertNull(database.phoenixDatabaseQueries.selectRoutineById("routine-x").executeAsOneOrNull())
    }
}
