package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.database.Routine as RoutineRow
import com.devil.phoenixproject.data.sync.PullCycleDayDto
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Issue #1162 — senior merge-gate R1/R2/R3 rework regressions.
 *
 * The three senior-gate diagnostics live in [Issue1162SeniorGateTest]. This
 * matrix covers the remediation's remaining required cases: concurrent first
 * collectors waiting for one maintenance run (R1); transactional source-graph
 * retention at every destructive resolver boundary — named-write save, pull
 * merge, cycle-day resolution — including dropped conflict-programming slots,
 * snapshot-failure rollback and propagation, and repeated-delivery
 * insert-if-absent (R2); and execution-time expiry enforcement with zero writes
 * (R3). No test here relies on an earlier list read.
 */
class Issue1162SeniorGateReworkTest {
    private val lower = "abcdefab-1234-4abc-8def-abcdef123456"
    private val upper = "ABCDEFAB-1234-4ABC-8DEF-ABCDEF123456"
    private val upperChild = "1111AAAA-2222-3333-4444-555566667777"
    private val profile = "active-profile"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun seed(db: PhoenixDatabase, id: String, updatedAt: Long = 1L) {
        val queries = db.phoenixDatabaseQueries
        queries.insertRoutine(
            id = id, name = "Push Day", description = "", createdAt = 1L,
            lastUsed = null, useCount = 0L, profile_id = profile, groupId = null, deletedAt = null,
        )
        queries.updateRoutineById(name = "Push Day", description = "", updatedAt = updatedAt, id = id)
    }

    private fun seedTwinChildWithSlot(db: PhoenixDatabase, childId: String, routineId: String, plannedSetId: String) {
        val queries = db.phoenixDatabaseQueries
        queries.insertRoutineExercise(
            id = childId, routineId = routineId,
            exerciseName = "Deadlift", exerciseMuscleGroup = "Back", exerciseEquipment = "Cable",
            exerciseDefaultCableConfig = "DOUBLE", exerciseId = null, cableConfig = "DOUBLE",
            orderIndex = 0, setReps = "5", weightPerCableKg = 60.0, setWeights = "",
            mode = "OldSchool", eccentricLoad = 100, echoLevel = 1, progressionKg = 0.0,
            restSeconds = 90, duration = null, setRestSeconds = "[]", perSetRestTime = 0,
            isAMRAP = 0, supersetId = null, orderInSuperset = 0, usePercentOfPR = 0,
            weightPercentOfPR = 80, prTypeForScaling = "MAX_WEIGHT", setWeightsPercentOfPR = null,
            stallDetectionEnabled = 1, stopAtTop = 0, repCountTiming = "TOP", setEchoLevels = "",
            warmupSets = "", defaultRackItemIds = "[]", rackBehaviorOverrides = "{}",
            scalingBasis = null, isBodyweight = null, dropSetEnabled = 0L, dropSetMinWeightKg = null,
        )
        // Colliding set_number 1 on both aliases: one slot is dropped at coalesce and
        // must survive only in the recovery snapshot (conflict programming).
        queries.insertPlannedSet(plannedSetId, childId, 1, "STANDARD", 5, 60.0, null, 90)
    }

    private fun activeProfile(db: PhoenixDatabase, ownerUserId: String = "owner-user") {
        val queries = db.phoenixDatabaseQueries
        queries.insertProfile(id = profile, name = "Active", colorIndex = 0L, createdAt = 1L, isActive = 1L)
        queries.linkProfileToSupabase(supabase_user_id = ownerUserId, last_auth_at = 1L, id = profile)
    }

    private fun workoutRepository(db: PhoenixDatabase, store: RoutineRecoveryStore = RoutineRecoveryStore(db.phoenixDatabaseQueries)) =
        SqlDelightWorkoutRepository(db, FakeExerciseRepository(), store)

    private fun syncRepository(db: PhoenixDatabase, store: RoutineRecoveryStore = RoutineRecoveryStore(db.phoenixDatabaseQueries)): SqlDelightSyncRepository {
        val profiles = FakeUserProfileRepository()
        profiles.setActiveProfileForTest(id = profile)
        activeProfile(db)
        return SqlDelightSyncRepository(db, profiles, routineRecoveryStore = store)
    }

    private fun recoveries(db: PhoenixDatabase, portalUserId: String = "owner-user") =
        db.phoenixDatabaseQueries.selectRoutineRecoveriesByProfile(profile, portalUserId, 1L).executeAsList()

    private fun decode(row: com.devil.phoenixproject.database.RoutineRecovery): RoutineRecoveryPayload =
        json.decodeFromString(RoutineRecoveryPayload.serializer(), row.payload_json)

    // ===== R1: maintenance scheduling =====

    /**
     * R1: concurrent first collectors all wait for the single maintenance run —
     * nobody observes the pre-maintenance duplicate rows.
     */
    @Test
    fun concurrentFirstCollectorsAllObserveTheReconciledList() = runTest {
        val db = createTestDatabase()
        seed(db, lower, updatedAt = 5L)
        seed(db, upper, updatedAt = 5L)
        val repository = workoutRepository(db)

        val first = async { repository.getAllRoutines(profile).first() }
        val second = async { repository.getAllRoutines(profile).first() }
        assertEquals(1, first.await().size, "first collector sees the reconciled list")
        assertEquals(1, second.await().size, "second collector waits for the same run")
    }

    // ===== R2: retention at every destructive resolver boundary =====

    /**
     * R2 (save/import named-write boundary): a save that resolves split aliases
     * retains the complete source graph — both alias rows AND the colliding
     * planned-set slot the coalesce drops — before the destructive merge.
     */
    @Test
    fun namedWriteCoalesceRetainsSourceGraphIncludingDroppedConflictSlots() = runTest {
        val db = createTestDatabase()
        activeProfile(db)
        seed(db, upper, updatedAt = 5L)
        seed(db, lower, updatedAt = 4L)
        seedTwinChildWithSlot(db, upperChild, upper, "planned-kept")
        seedTwinChildWithSlot(db, upperChild.lowercase(), lower, "planned-dropped")
        val repository = workoutRepository(db)

        repository.saveRoutine(Routine(id = lower, name = "Push Day", profileId = profile))

        assertEquals(1, db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().size)
        val retained = recoveries(db).single()
        assertEquals(RoutineRecoveryReasons.ALIAS_COALESCE, retained.reason)
        val payload = decode(retained)
        assertEquals("named_write", payload.provenance.source)
        assertEquals(setOf(upper, lower), payload.routines.map { it.routine.id }.toSet())
        val snapshottedSlots = payload.routines.flatMap { graph -> graph.plannedSets.map { it.id } }.toSet()
        assertTrue(
            snapshottedSlots.containsAll(setOf("planned-kept", "planned-dropped")),
            "the dropped conflicting planned-set slot is retained in the snapshot: $snapshottedSlots",
        )
    }

    /** R2 (pull-merge boundary): mergePortalRoutines coalescing aliases retains the source graph. */
    @Test
    fun pullMergeCoalesceRetainsSourceGraph() = runTest {
        val db = createTestDatabase()
        seed(db, upper, updatedAt = 5L)
        seed(db, lower, updatedAt = 4L)
        val sync = syncRepository(db)

        sync.mergePortalRoutines(
            listOf(PullRoutineDto(id = lower, userId = "owner-user", name = "Push Day", updatedAt = 10L, exercises = emptyList())),
            lastSync = 1L,
            profileId = profile,
        )

        assertEquals(1, db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().size)
        val payload = decode(recoveries(db).single())
        assertEquals("pull", payload.provenance.source)
        assertEquals(setOf(upper, lower), payload.routines.map { it.routine.id }.toSet())
    }

    /** R2 (cycle-resolution boundary): a pulled cycle day reference coalescing aliases retains the source graph. */
    @Test
    fun cycleDayResolutionCoalesceRetainsSourceGraph() = runTest {
        val db = createTestDatabase()
        seed(db, upper, updatedAt = 5L)
        seed(db, lower, updatedAt = 4L)
        val sync = syncRepository(db)

        sync.mergeAllPullData(
            ownerUserId = "owner-user",
            workoutDeletions = emptyList(),
            sessions = emptyList(),
            routines = emptyList(),
            cycles = listOf(
                PullTrainingCycleDto(
                    id = "cycle-portal",
                    name = "Block",
                    updatedAt = "2026-01-01T00:00:00.000Z",
                    days = listOf(
                        PullCycleDayDto(id = "day-1", cycleId = "cycle-portal", dayNumber = 1, routineId = lower),
                    ),
                ),
            ),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 1L,
            profileId = profile,
        )

        assertEquals(1, db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().size)
        val payload = decode(recoveries(db).single())
        assertEquals("pull_cycle", payload.provenance.source)
        assertEquals(setOf(upper, lower), payload.routines.map { it.routine.id }.toSet())
        val cycleDay = db.phoenixDatabaseQueries.selectCycleDaysByCycle("cycle-portal").executeAsList().single()
        val keptId = db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsOne().id
        assertEquals(keptId, cycleDay.routine_id, "the cycle day points at the kept primary key")
    }

    /** Snapshot failure rolls back the named-write destructive action entirely. */
    @Test
    fun snapshotFailureRollsBackNamedWriteCoalesce() = runTest {
        val db = createTestDatabase()
        seed(db, upper, updatedAt = 5L)
        seed(db, lower, updatedAt = 4L)
        val repository = workoutRepository(db, FailingRecoveryStore(db.phoenixDatabaseQueries))

        assertFailsWith<IllegalStateException> {
            repository.saveRoutine(Routine(id = lower, name = "Push Day", profileId = profile))
        }
        assertEquals(
            setOf(upper, lower),
            db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().map { it.id }.toSet(),
            "a snapshot failure rolls the whole coalesce back — no alias is removed",
        )
    }

    /** Snapshot failure rolls back the pull-merge coalesce and propagates to the sync page. */
    @Test
    fun snapshotFailureRollsBackPullMergeCoalesceAndPropagates() = runTest {
        val db = createTestDatabase()
        seed(db, upper, updatedAt = 5L)
        seed(db, lower, updatedAt = 4L)
        val sync = syncRepository(db, FailingRecoveryStore(db.phoenixDatabaseQueries))

        assertFailsWith<IllegalStateException> {
            sync.mergePortalRoutines(
                listOf(PullRoutineDto(id = lower, userId = "owner-user", name = "Push Day", updatedAt = 10L, exercises = emptyList())),
                lastSync = 1L,
                profileId = profile,
            )
        }
        assertEquals(
            setOf(upper, lower),
            db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().map { it.id }.toSet(),
            "the sync page fails with the alias rows untouched (checkpoint must not advance)",
        )
    }

    /** Repeat delivery: insert-if-absent — no overwrite of a complete snapshot, no expiry refresh. */
    @Test
    fun repeatRetentionKeepsFirstSnapshotAndExpiry() {
        val db = createTestDatabase()
        seed(db, lower)
        val queries = db.phoenixDatabaseQueries
        val store = RoutineRecoveryStore(queries)
        val row = queries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().single()
        db.transaction {
            store.retainRoutineGraphs(
                rows = listOf(row), canonicalIdentity = lower, portalUserId = "owner-user",
                profileId = profile, reason = RoutineRecoveryReasons.SERVER_DELETE,
                source = "pull", incomingIdentity = lower, appliedAt = 1_000L,
            )
        }
        val first = recoveries(db).single()
        db.transaction {
            store.retainRoutineGraphs(
                rows = listOf(row), canonicalIdentity = lower, portalUserId = "owner-user",
                profileId = profile, reason = RoutineRecoveryReasons.SERVER_DELETE,
                source = "pull", incomingIdentity = lower, appliedAt = 9_999_999L,
            )
        }
        val rows = recoveries(db)
        assertEquals(1, rows.size, "a repeated delivery never overwrites or duplicates the snapshot")
        assertEquals(1_000L, rows.single().created_at)
        assertEquals(1_000L + 30L * 24 * 60 * 60 * 1000, rows.single().expires_at, "expiry is never refreshed")
        assertEquals(first.payload_json, rows.single().payload_json, "the complete first snapshot is never overwritten")
    }

    // ===== R3: execution-time expiry enforcement =====

    /**
     * R3: a snapshot is restorable exactly while it is listable. A stale cached
     * selection past expiry restores nothing (zero routine/child writes), while
     * the inclusive boundary itself stays restorable.
     */
    @Test
    fun expiredSnapshotRestoreRejectsWithZeroWrites() {
        val db = createTestDatabase()
        seed(db, lower)
        val queries = db.phoenixDatabaseQueries
        val store = RoutineRecoveryStore(queries)
        db.transaction {
            store.retainRoutineGraphs(
                rows = queries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList(),
                canonicalIdentity = lower, portalUserId = "owner-user", profileId = profile,
                reason = RoutineRecoveryReasons.SERVER_DELETE, source = "pull",
                incomingIdentity = lower, appliedAt = 1L,
            )
            queries.deleteRoutineById(lower)
        }
        val retained = recoveries(db).single()
        val routinesBefore = queries.selectAllRoutinesSync().executeAsList().size

        val restoredExpired = db.transactionWithResult {
            store.restoreAsCopy(retained.id, 0, profile, "owner-user", retained.expires_at + 1L)
        }
        assertNull(restoredExpired, "restore must enforce expiry even when a sheet cached the selection")
        assertEquals(routinesBefore, queries.selectAllRoutinesSync().executeAsList().size, "zero new routine writes")
        assertEquals(0, queries.selectExercisesByRoutine(lower).executeAsList().size, "zero new child writes")

        val restoredAtBoundary = db.transactionWithResult {
            store.restoreAsCopy(retained.id, 0, profile, "owner-user", retained.expires_at)
        }
        assertNotNull(restoredAtBoundary, "restorable exactly while listable (expires_at >= now)")
        assertNotNull(queries.selectRoutineById(restoredAtBoundary).executeAsOneOrNull())
    }
}

/** Recovery store that fails every snapshot write (merge gate R2 failure-path seam). */
private class FailingRecoveryStore(queries: PhoenixDatabaseQueries) : RoutineRecoveryStore(queries) {
    override fun retainRoutineGraphs(
        rows: List<RoutineRow>,
        canonicalIdentity: String,
        portalUserId: String,
        profileId: String,
        reason: String,
        source: String,
        incomingIdentity: String?,
        appliedAt: Long,
    ): Unit = throw IllegalStateException("snapshot write failed")
}
