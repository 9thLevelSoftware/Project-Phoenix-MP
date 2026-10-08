package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.createTestSchema
import com.devil.phoenixproject.testutil.seedExercise
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Issue #1162 REOPEN-2 — DESIRED-behavior regression suite (extended from the
 * recreation probe that asserted the defect).
 *
 * Production SQLDelight host harness: real [SqlDelightWorkoutRepository] /
 * [SqlDelightSyncRepository] entry points over an in-memory (and one
 * file-backed) database with foreign keys ON. No test-only shims are used for
 * the behavior under test.
 *
 * Contract under test (bounded RCA + architecture review on issue #1162):
 * 1. already-split alias rows reconcile before the FIRST offline list emission,
 *    with no save and no named pull; repeated reads, empty pulls and a real
 *    file-backed reopen stay idempotent; distinct same-name routines, opaque
 *    ids and other profiles stay separate; the stored keeper PK survives;
 * 2. maintenance preserves children, planned-set/conflicting programming and
 *    cycle references (CycleDay.routine_id only); a tombstoned component keeps
 *    the tombstoned row with deletedAt preserved and never resurrects
 *    (equal-time tombstones win); maintenance failure leaves rows visible;
 * 3. a valid owned server deletion removes the active identity and atomically
 *    retains a complete local-only recovery snapshot (insert-if-absent; repeat
 *    delivery cannot overwrite it or refresh its expiry); unknown/foreign ids do
 *    nothing; a snapshot failure rolls the deletion back (the pull page fails and
 *    its checkpoint does not advance); cycle_routine_* template deletions are
 *    never surfaced as user routines;
 * 4. restore-as-copy uses fresh routine/child UUIDs in the rightful profile, the
 *    deleted original stays deleted across sync, and no cycle/history relink
 *    happens; expired snapshots are pruned and profile purge removes them.
 *
 * Live Cloud Sync / device evidence remains a QA step (RCA acceptance criterion
 * 5) and is out of scope for this host suite. Reporter-level missing-routine
 * attribution stays UNPROVED — nothing here claims incident resolution.
 */
class Issue1162Reopen2ProbeTest {

    private val lowerUuid = "abcdefab-1234-4abc-8def-abcdef123456"
    private val upperUuid = "ABCDEFAB-1234-4ABC-8DEF-ABCDEF123456"
    private val otherUuid = "99999999-8888-7777-6666-555544443333"

    private fun seedOwnerProfile(
        database: PhoenixDatabase,
        profileId: String = "active-profile",
        ownerUserId: String = "owner-user",
    ) {
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile(
            id = profileId,
            name = "Active",
            colorIndex = 0L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )
        queries.linkProfileToSupabase(
            supabase_user_id = ownerUserId,
            last_auth_at = 1_700_000_000_000,
            id = profileId,
        )
    }

    /**
     * Recovery access is authorized by the authenticated portal identity and the
     * active profile at execution time (final audit R4). The probe scenarios run
     * as the signed-in owner of the seeded active profile.
     */
    private fun newWorkoutRepository(database: PhoenixDatabase) =
        SqlDelightWorkoutRepository(
            db = database,
            exerciseRepository = FakeExerciseRepository(),
            signedInPortalUserId = { "owner-user" },
            activeProfileId = { "active-profile" },
        )

    private fun newSyncRepository(
        database: PhoenixDatabase,
        profileId: String = "active-profile",
        ownerUserId: String = "owner-user",
    ): SqlDelightSyncRepository {
        val profiles = FakeUserProfileRepository()
        profiles.setActiveProfileForTest(id = profileId)
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile(
            id = profileId,
            name = "Active",
            colorIndex = 0L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )
        queries.linkProfileToSupabase(
            supabase_user_id = ownerUserId,
            last_auth_at = 1_700_000_000_000,
            id = profileId,
        )
        return SqlDelightSyncRepository(database, profiles)
    }

    private fun seedRoutine(
        database: PhoenixDatabase,
        id: String,
        name: String = "Push Day",
        profileId: String = "active-profile",
        updatedAt: Long = 1_700_000_000_000,
        deletedAt: Long? = null,
    ) {
        val queries = database.phoenixDatabaseQueries
        queries.insertRoutine(
            id = id,
            name = name,
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 2L,
            profile_id = profileId,
            groupId = null,
            deletedAt = null,
        )
        queries.updateRoutineById(name = name, description = "", updatedAt = updatedAt, id = id)
        if (deletedAt != null) {
            queries.softDeleteRoutine(deletedAt = deletedAt, updatedAt = updatedAt, id = id)
        }
    }

    /** One exercise with one planned-set slot under [routineId]. */
    private fun seedExerciseWithSlot(
        database: PhoenixDatabase,
        exerciseRowId: String,
        routineId: String,
        plannedSetId: String,
        setNumber: Long = 1L,
    ) {
        val queries = database.phoenixDatabaseQueries
        queries.insertRoutineExercise(
            id = exerciseRowId,
            routineId = routineId,
            exerciseName = "Bench Press",
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "BAR",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = "bench-id",
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
            scalingBasis = null,
            isBodyweight = null,
            dropSetEnabled = 0,
            dropSetMinWeightKg = null,
        )
        queries.insertPlannedSet(
            id = plannedSetId,
            routine_exercise_id = exerciseRowId,
            set_number = setNumber,
            set_type = "STANDARD",
            target_reps = 5L,
            target_weight_kg = 60.0,
            target_rpe = null,
            rest_seconds = 90L,
        )
    }

    private fun seedCycleDayRef(database: PhoenixDatabase, routineId: String) {
        val queries = database.phoenixDatabaseQueries
        queries.insertTrainingCycle(
            id = "cycle-1",
            name = "Block",
            description = null,
            created_at = 1_700_000_000_000,
            is_active = 1L,
            profile_id = "active-profile",
            template_id = null,
            week_number = 1L,
            updatedAt = 1_700_000_000_000,
        )
        queries.insertCycleDay(
            id = "day-1",
            cycle_id = "cycle-1",
            day_number = 1L,
            name = "Day 1",
            routine_id = routineId,
            is_rest_day = 0L,
            echo_level = null,
            eccentric_load_percent = null,
            weight_progression_percent = null,
            rep_modifier = null,
            rest_time_override_seconds = null,
        )
    }

    // ==================== 1. EAGER MAINTENANCE BEFORE FIRST LIST ====================

    @Test
    fun `aliases reconcile before the first offline list emission without save or pull`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        seedOwnerProfile(database)
        seedRoutine(database, lowerUuid, updatedAt = 1_700_000_000_000)
        seedExerciseWithSlot(database, "ex-lower", lowerUuid, "ps-lower", setNumber = 1L)
        // The alias row: a case-variant spelling of the SAME identity, with a
        // distinct child and a planned-set slot that COLLIDES (set 1) — the
        // conflicting programming must be retained in the recovery snapshot.
        seedRoutine(database, upperUuid, updatedAt = 1_700_000_000_000)
        seedExerciseWithSlot(database, "ex-upper", upperUuid, "ps-upper", setNumber = 1L)

        val repository = newWorkoutRepository(database)

        // FIRST emission: no saveRoutine, no named pull, no network.
        val routines = repository.getAllRoutines("active-profile").first()
        assertEquals(1, routines.size, "already-split aliases must be one card on the first list read")
        val keptId = routines.single().id
        assertTrue(keptId == lowerUuid || keptId == upperUuid, "stored keeper PK must be preserved, was $keptId")
        // Both children survive the coalesce (distinct children reparent with PKs kept).
        assertEquals(2, routines.single().exercises.size)

        // Conflicting programming (the loser's colliding planned-set slot) is
        // retained in the local-only recovery snapshot, shown in the preview.
        val recoveries = repository.listRoutineRecoveries("active-profile", "owner-user")
        assertTrue(recoveries.isNotEmpty(), "coalesce must retain a recovery snapshot")
        val preview = recoveries.flatMap { it.exercises }.flatMap { it.plannedSets }
        assertTrue(preview.any { it.setNumber == 1L }, "dropped planned-set slots must be previewable")

        // Idempotent: repeated reads and a repeated maintenance run change nothing.
        repository.getAllRoutines("active-profile").first()
        repository.runRoutineIdentityMaintenance("active-profile")
        repository.runRoutineIdentityMaintenance("active-profile")
        assertEquals(1, repository.getAllRoutines("active-profile").first().size)
        assertEquals(1, database.phoenixDatabaseQueries.selectAllRoutines(profileId = "active-profile").executeAsList().size)
    }

    @Test
    fun `file backed database reopen keeps maintenance idempotent`() = runBlocking {
        val file = File.createTempFile("issue1162-reopen2", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            // File-backed connections pool; create the schema directly instead of
            // createTestSchema (which asserts a per-connection FK pragma read-back).
            PhoenixDatabase.Schema.create(driver)
            val database = PhoenixDatabase(driver)
            database.seedExercise()
            seedRoutine(database, lowerUuid)
            seedRoutine(database, upperUuid)
            newWorkoutRepository(database).runRoutineIdentityMaintenance("active-profile")
            assertEquals(1, database.phoenixDatabaseQueries.selectAllRoutines(profileId = "active-profile").executeAsList().size)
            driver.close()

            // Real close/reopen of a file-backed database: the second maintenance
            // pass must not touch anything again.
            val reopenDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            val reopened = PhoenixDatabase(reopenDriver)
            val result = newWorkoutRepository(reopened).runRoutineIdentityMaintenance("active-profile")
            assertEquals(0, result.aliasesRemoved, "reopen maintenance must be idempotent")
            assertEquals(1, reopened.phoenixDatabaseQueries.selectAllRoutines(profileId = "active-profile").executeAsList().size)
            reopenDriver.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `distinct same-name routines opaque ids and other profiles stay separate`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        // Two DISTINCT identities sharing one name (legitimate), one opaque id.
        seedRoutine(database, lowerUuid, name = "Push Day")
        seedRoutine(database, otherUuid, name = "Push Day")
        seedRoutine(database, "legacy-opaque-id", name = "Old Split")
        // A case-variant alias pair in ANOTHER profile must not be touched.
        seedRoutine(database, lowerUuid.uppercase(), name = "Leg Day", profileId = "other-profile")

        val repository = newWorkoutRepository(database)
        val result = repository.runRoutineIdentityMaintenance("active-profile")
        assertEquals(0, result.componentsReconciled, "no same-profile component has aliases")

        assertEquals(
            setOf(lowerUuid, otherUuid, "legacy-opaque-id"),
            database.phoenixDatabaseQueries.selectAllRoutines(profileId = "active-profile").executeAsList()
                .map { it.id }.toSet(),
        )
        assertEquals(
            1,
            database.phoenixDatabaseQueries.selectAllRoutines(profileId = "other-profile").executeAsList().size,
            "maintenance is per selected profile and never touches other profiles",
        )
    }

    @Test
    fun `tombstoned component keeps the tombstone and never resurrects`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        // Equal timestamps: the equal-time tombstone must win (delete wins ties).
        seedRoutine(database, lowerUuid, updatedAt = 1_700_000_000_000, deletedAt = 1_700_000_000_000)
        seedRoutine(database, upperUuid, updatedAt = 1_700_000_000_000)

        val repository = newWorkoutRepository(database)
        val result = repository.runRoutineIdentityMaintenance("active-profile")
        assertEquals(1, result.tombstoneComponents)

        val rows = database.phoenixDatabaseQueries
            .selectAllRoutinesByProfileIncludingDeleted("active-profile").executeAsList()
        assertEquals(1, rows.size, "the live alias is removed into the tombstoned component")
        assertEquals(lowerUuid, rows.single().id, "the tombstoned row is the keeper")
        assertEquals(1_700_000_000_000, rows.single().deletedAt, "deletedAt is preserved, never cleared")
        assertEquals(0, repository.getAllRoutines("active-profile").first().size, "a tombstone is never resurrected")
    }

    @Test
    fun `maintenance failure rolls back and leaves the original rows visible`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createTestSchema(driver)
        val database = PhoenixDatabase(driver)
        database.seedExercise()
        seedRoutine(database, lowerUuid)
        seedRoutine(database, upperUuid)
        // Make the snapshot write fail (the coalesce would be destructive).
        driver.execute(null, "DROP TABLE RoutineRecovery", 0)

        val result = newWorkoutRepository(database).runRoutineIdentityMaintenance("active-profile")
        assertTrue(result.failed, "a snapshot failure must surface a diagnostic")
        assertEquals(
            2,
            database.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted("active-profile").executeAsList().size,
            "a failed run rolls back and leaves the original rows visible",
        )
    }

    // ==================== 2. SERVER DELETION RECOVERY ====================

    @Test
    fun `server deletion retains a complete snapshot and repeated delivery is idempotent`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        seedRoutine(database, lowerUuid)
        seedExerciseWithSlot(database, "ex-1", lowerUuid, "ps-1")
        seedCycleDayRef(database, lowerUuid)
        val syncRepository = newSyncRepository(database)

        val result = syncRepository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf(upperUuid), // UUID-equivalent spelling of the local row
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )
        assertEquals(listOf(lowerUuid), result.deletedRoutineIds)
        assertTrue(
            database.phoenixDatabaseQueries.selectRoutineById(lowerUuid).executeAsOneOrNull() == null,
            "delete-wins removal from the active list is preserved",
        )

        // Complete source graph retained: parent + child + planned set + cycle refs.
        val repository = newWorkoutRepository(database)
        val items = repository.listRoutineRecoveries("active-profile", "owner-user")
        assertEquals(1, items.size)
        assertEquals("Push Day", items.single().routineName)
        assertTrue(items.single().exercises.flatMap { it.plannedSets }.any { it.setNumber == 1L })

        val recoveryRows = database.phoenixDatabaseQueries
            .selectRoutineRecoveriesByProfile(
                profileId = "active-profile",
                portalUserId = "owner-user",
                now = 1_700_000_000_000,
            ).executeAsList()
        assertEquals(1, recoveryRows.size)
        val createdAt = recoveryRows.single().created_at
        val expiresAt = recoveryRows.single().expires_at

        // Repeated delivery of the same deletion id: nothing overwritten, no
        // expiry refresh.
        syncRepository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf(lowerUuid),
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )
        val after = database.phoenixDatabaseQueries
            .selectRoutineRecoveriesByProfile(
                profileId = "active-profile",
                portalUserId = "owner-user",
                now = 1_700_000_000_000,
            ).executeAsList()
        assertEquals(1, after.size, "repeat delivery must not create or overwrite a snapshot")
        assertEquals(createdAt, after.single().created_at)
        assertEquals(expiresAt, after.single().expires_at)

        // Unknown ids do nothing.
        val unknown = syncRepository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("does-not-exist"),
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )
        assertEquals(0, unknown.deletedRoutineIds.size)
    }

    @Test
    fun `foreign owner deletions do nothing and snapshot failure rolls the deletion back`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        seedRoutine(database, lowerUuid)
        val syncRepository = newSyncRepository(database, ownerUserId = "owner-user")

        // Foreign owner: the profile is linked to owner-user, so another account's
        // deletion must not touch it.
        syncRepository.applyServerDeletions(
            ownerUserId = "someone-else",
            routineIds = listOf(lowerUuid),
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )
        assertTrue(database.phoenixDatabaseQueries.selectRoutineById(lowerUuid).executeAsOneOrNull() != null)

        // Snapshot failure (table gone): the destructive delete must roll back and
        // the exception must propagate so the pull page fails (checkpoint stays).
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createTestSchema(driver)
        val failingDb = PhoenixDatabase(driver)
        failingDb.seedExercise()
        seedRoutine(failingDb, lowerUuid)
        newSyncRepository(failingDb).applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf(lowerUuid),
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )
        driver.execute(null, "DROP TABLE RoutineRecovery", 0)
        seedRoutine(failingDb, otherUuid)
        val threw = runCatching {
            runBlocking {
                newSyncRepository(failingDb).applyServerDeletions(
                    ownerUserId = "owner-user",
                    routineIds = listOf(otherUuid),
                    cycleIds = emptyList(),
                    lastSync = 0L,
                    syncProfileId = "active-profile",
                )
            }
        }.isFailure
        assertTrue(threw, "a snapshot failure must fail the deletion/page")
        assertTrue(
            failingDb.phoenixDatabaseQueries.selectRoutineById(otherUuid).executeAsOneOrNull() != null,
            "the destructive delete rolls back with the failed snapshot",
        )
    }

    @Test
    fun `template deletions are never surfaced as user routines`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        val templateId = "cycle_routine_template-1"
        seedRoutine(database, templateId, name = "Template")
        val syncRepository = newSyncRepository(database)
        syncRepository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf(templateId),
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )
        assertEquals(
            0,
            newWorkoutRepository(database).listRoutineRecoveries("active-profile", "owner-user").size,
            "cycle_routine_* template deletions are retained but never shown as user routines",
        )
    }

    // ==================== 3. RESTORE AS COPY ====================

    @Test
    fun `restore as copy uses fresh identities keeps the original deleted and relinks nothing`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        seedRoutine(database, lowerUuid)
        seedExerciseWithSlot(database, "ex-1", lowerUuid, "ps-1")
        seedCycleDayRef(database, lowerUuid)
        val syncRepository = newSyncRepository(database)
        syncRepository.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf(lowerUuid),
            cycleIds = emptyList(),
            lastSync = 0L,
            syncProfileId = "active-profile",
        )

        val repository = newWorkoutRepository(database)
        val item = repository.listRoutineRecoveries("active-profile", "owner-user").single()
        val restoredId = repository.restoreRoutineRecoveryAsCopy(
            recoveryId = item.recoveryId,
            graphIndex = item.graphIndex,
            profileId = "active-profile",
            portalUserId = "owner-user",
        )
        val restoredRoutineId = assertNotNull(restoredId, "restore-as-copy must write the copy")
        assertNotEquals(lowerUuid, restoredRoutineId, "the server-deleted identity is never reused")

        val restored = database.phoenixDatabaseQueries.selectRoutineById(restoredRoutineId).executeAsOne()
        assertEquals("active-profile", restored.profile_id)
        assertEquals("Push Day (Restored)", restored.name)
        val restoredExercises = database.phoenixDatabaseQueries.selectExercisesByRoutine(restoredRoutineId).executeAsList()
        assertEquals(1, restoredExercises.size)
        assertNotEquals("ex-1", restoredExercises.single().id, "child UUIDs are fresh")
        val restoredSets = database.phoenixDatabaseQueries
            .selectPlannedSetsByRoutineExercise(restoredExercises.single().id).executeAsList()
        assertEquals(1, restoredSets.size, "programming is preserved by restore-as-copy")
        assertNotEquals("ps-1", restoredSets.single().id, "planned-set UUIDs are fresh")

        // Original stays deleted across sync; nothing reconnects cycle links.
        assertTrue(database.phoenixDatabaseQueries.selectRoutineById(lowerUuid).executeAsOneOrNull() == null)
        assertEquals(
            null,
            database.phoenixDatabaseQueries.selectCycleDayById("day-1").executeAsOne().routine_id,
            "restore never silently reconnects cycle references",
        )

        // The snapshot is kept (restore is not automatic and does not consume the
        // retained evidence).
        assertEquals(1, repository.listRoutineRecoveries("active-profile", "owner-user").size)
    }

    // ==================== 4. RETENTION / PURGE ====================

    @Test
    fun `expired snapshots are pruned and profile purge removes recoveries`() = runBlocking {
        val database = createTestDatabase()
        database.seedExercise()
        seedOwnerProfile(database)
        seedRoutine(database, lowerUuid)
        val queries = database.phoenixDatabaseQueries
        val repository = newWorkoutRepository(database)
        val store = RoutineRecoveryStore(queries)
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        // An expired snapshot (retained 31 days ago) plus a fresh one.
        val old = queries.selectAllRoutinesByProfileIncludingDeleted("active-profile").executeAsList()
        store.retainRoutineGraphs(
            rows = old,
            canonicalIdentity = lowerUuid,
            portalUserId = "owner-user",
            profileId = "active-profile",
            reason = RoutineRecoveryReasons.SERVER_DELETE,
            source = "test",
            incomingIdentity = lowerUuid,
            appliedAt = now - 31 * day,
        )
        seedRoutine(database, otherUuid, name = "Pull Day")
        val fresh = queries.selectRoutineById(otherUuid).executeAsOne()
        store.retainRoutineGraphs(
            rows = listOf(fresh),
            canonicalIdentity = otherUuid,
            portalUserId = "owner-user",
            profileId = "active-profile",
            reason = RoutineRecoveryReasons.SERVER_DELETE,
            source = "test",
            incomingIdentity = otherUuid,
            appliedAt = now - day,
        )

        // The 30-day window is pruned on maintenance; the fresh one survives.
        // Both source rows were deleted, so their graphs are recoverable items.
        queries.softDeleteRoutine(deletedAt = now, updatedAt = now, id = lowerUuid)
        queries.softDeleteRoutine(deletedAt = now, updatedAt = now, id = otherUuid)
        repository.runRoutineIdentityMaintenance("active-profile")
        val visible = repository.listRoutineRecoveries("active-profile", "owner-user").map { it.routineName }.toSet()
        assertEquals(setOf("Pull Day"), visible, "expired snapshots are pruned, fresh ones are kept")

        // Account/profile erasure purges the recovery table with the profile.
        queries.purgeProfileOwnedRows("active-profile")
        assertEquals(
            0,
            queries.selectRoutineRecoveriesByProfile(
                profileId = "active-profile",
                portalUserId = "owner-user",
                now = now,
            ).executeAsList().size,
            "purgeProfileOwnedRows removes RoutineRecovery rows for the profile",
        )
    }
}

private fun PhoenixDatabase.seedExercise() {
    seedExercise(
        id = "bench-id",
        name = "Bench Press",
        muscleGroup = "Chest",
        equipment = "BAR",
    )
}
