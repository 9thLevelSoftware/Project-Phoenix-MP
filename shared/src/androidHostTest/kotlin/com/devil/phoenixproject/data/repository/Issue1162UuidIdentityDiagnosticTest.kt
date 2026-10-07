package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Issue #1162 — routine UUID identity regression suite (desired single-identity
 * behavior; it replaces the retained diagnostic that asserted the defect).
 *
 * Production SQLDelight host harness: real [SqlDelightWorkoutRepository] /
 * [SqlDelightSyncRepository] entry points (save -> outbound adapter -> lowercase
 * server projection -> pull/merge, alias reconciliation, server deletions)
 * against an in-memory database with foreign keys ON, as both production drivers
 * run. No test-only shims are used for the behavior under test.
 *
 * Contract under test (bounded RCA + architecture review on issue #1162):
 * 1. one logical routine for uppercase/lowercase/mixed-case UUID ids, idempotent
 *    across repeated saves and pulls, without any seeded serverId;
 * 2. existing split aliases reconcile non-destructively (children, planned sets,
 *    group/usage metadata, cycle links survive; newer local edits win);
 * 3. applyServerDeletions matches UUID-equivalent id and serverId spellings,
 *    owner-scoped, deleting each matched row exactly once;
 * 4. distinct UUIDs with equal names stay separate; opaque ids are unchanged;
 * 5. a local tombstone is not resurrected by a case-variant id.
 *
 * Live Cloud Sync / device evidence remains a QA step (RCA acceptance criterion
 * 5) and is out of scope for this host suite.
 */
class Issue1162UuidIdentityDiagnosticTest {

    private val upperUuid = "ABCDEFAB-1234-4ABC-8DEF-ABCDEF123456"
    private val mixedUuid = "AbCdEfAb-1234-4AbC-8dEf-AbCdEf123456"
    private val otherUuid = "99999999-8888-7777-6666-555544443333"
    private val upperChild = "1111AAAA-2222-3333-4444-555566667777"
    private val secondChild = "2222AAAA-2222-3333-4444-555566667777"
    private val thirdChild = "3333AAAA-2222-3333-4444-555566667777"

    private fun newDb() = createTestDatabase()

    private fun newWorkoutRepository(database: com.devil.phoenixproject.database.PhoenixDatabase) =
        SqlDelightWorkoutRepository(
            database,
            SqlDelightExerciseRepository(database, ExerciseImporter(database), FakePreferencesManager()),
        )

    private fun newSyncRepository(database: com.devil.phoenixproject.database.PhoenixDatabase): SqlDelightSyncRepository {
        val profiles = FakeUserProfileRepository()
        profiles.setActiveProfileForTest(id = "active-profile")
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile(
            id = "active-profile",
            name = "Active",
            colorIndex = 0L,
            createdAt = 1_700_000_000_000,
            isActive = 1L,
        )
        queries.linkProfileToSupabase(
            supabase_user_id = "owner-user",
            last_auth_at = 1_700_000_000_000,
            id = "active-profile",
        )
        return SqlDelightSyncRepository(database, profiles)
    }

    private fun seedRoutine(
        database: com.devil.phoenixproject.database.PhoenixDatabase,
        id: String,
        name: String,
        profileId: String = "active-profile",
        createdAt: Long = 1_700_000_000_000,
        updatedAt: Long = 1_700_000_000_000,
        deletedAt: Long? = null,
        useCount: Long = 0L,
        lastUsed: Long? = null,
    ) {
        val queries = database.phoenixDatabaseQueries
        queries.insertRoutine(
            id = id,
            name = name,
            description = "",
            createdAt = createdAt,
            lastUsed = lastUsed,
            useCount = useCount,
            profile_id = profileId,
            groupId = null,
            deletedAt = null,
        )
        queries.updateRoutineById(name = name, description = "", updatedAt = updatedAt, id = id)
        if (deletedAt != null) {
            queries.softDeleteRoutine(deletedAt = deletedAt, updatedAt = updatedAt, id = id)
        }
    }

    private fun seedRoutineExercise(
        database: com.devil.phoenixproject.database.PhoenixDatabase,
        id: String,
        routineId: String,
        progressionKg: Double = 0.0,
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

    private fun seedCycleDayReference(
        database: com.devil.phoenixproject.database.PhoenixDatabase,
        routineId: String,
    ) {
        val queries = database.phoenixDatabaseQueries
        queries.insertTrainingCycle(
            "cycle-1",
            "Cycle",
            null,
            1_700_000_000_000,
            1,
            "active-profile",
            null,
            1,
            1_700_000_000_000,
        )
        queries.insertCycleDay("day-1", "cycle-1", 1, "Day 1", routineId, 0, null, null, null, null, null)
    }

    /**
     * Acceptance 1: production save -> outbound adapter -> lowercase server
     * projection -> production pull/merge yields exactly one logical routine for
     * uppercase/lowercase/mixed-case UUID ids, with repeated saves and pulls
     * idempotent and no seeded serverId. The stored primary key is preserved and
     * a later save under the wire spelling resolves to it instead of creating a
     * canonical alias.
     */
    @Test
    fun appCreatedUuidCaseRoundTripYieldsOneLogicalRoutine() = runTest {
        for (savedId in listOf(upperUuid, upperUuid.lowercase(), mixedUuid)) {
            val database = newDb()
            val workout = newWorkoutRepository(database)
            val sync = newSyncRepository(database)

            val model = Routine(id = savedId, name = "Push Day", profileId = "active-profile")
            workout.saveRoutine(model)

            // Outbound wire identity is the canonical lowercase UUID text.
            val outbound = PortalSyncAdapter.toPortalRoutine(model, "owner-user")
            assertEquals(savedId.lowercase(), outbound.id)

            // Lowercase server projection round-trips through the pull merge.
            val pulled = PullRoutineDto(
                id = outbound.id,
                userId = "owner-user",
                name = "Push Day",
                updatedAt = 1_700_000_000_200,
                exercises = emptyList(),
            )
            sync.mergePortalRoutines(listOf(pulled), lastSync = Long.MAX_VALUE, profileId = "active-profile")

            var rows = database.phoenixDatabaseQueries.selectAllRoutines("active-profile").executeAsList()
            assertEquals(1, rows.size, "case=$savedId: one logical routine after pull merge")
            assertEquals(savedId, rows.single().id, "case=$savedId: stored primary key preserved")
            assertNull(rows.single().serverId, "case=$savedId: no serverId seeded")

            // Repeated pull and repeated save under the wire spelling stay idempotent.
            sync.mergePortalRoutines(listOf(pulled), lastSync = Long.MAX_VALUE, profileId = "active-profile")
            workout.saveRoutine(
                Routine(id = savedId.lowercase(), name = "Push Day", profileId = "active-profile"),
            )
            rows = database.phoenixDatabaseQueries.selectAllRoutines("active-profile").executeAsList()
            assertEquals(1, rows.size, "case=$savedId: idempotent across repeated pull/save")
            assertEquals(savedId, rows.single().id, "case=$savedId: save resolved to the existing primary key")
        }
    }

    /**
     * Acceptance 2 (merge half): a pull whose routine and child ids are case
     * variants of the local rows updates the existing local child row — primary
     * key, planned sets and local-only columns survive — instead of the exact-id
     * delete dropping the child.
     */
    @Test
    fun pullMergeUpdatesUuidEquivalentChildInPlaceKeepingPlannedSets() = runTest {
        val database = newDb()
        val sync = newSyncRepository(database)
        val queries = database.phoenixDatabaseQueries

        seedRoutine(database, upperUuid, "Push Day", updatedAt = 1_700_000_000_000)
        seedRoutineExercise(database, upperChild, upperUuid, progressionKg = 5.0)
        queries.insertPlannedSet("planned-1", upperChild, 1, "STANDARD", 5, 60.0, null, 90)

        sync.mergePortalRoutines(
            listOf(
                PullRoutineDto(
                    id = upperUuid.lowercase(),
                    userId = "owner-user",
                    name = "Push Day Renamed",
                    updatedAt = 1_700_000_000_100,
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = upperChild.lowercase(),
                            routineId = upperUuid.lowercase(),
                            name = "Bench Press",
                            muscleGroup = "Chest",
                            orderIndex = 0,
                            reps = 8,
                            weight = 25f,
                        ),
                    ),
                ),
            ),
            lastSync = 1_700_000_000_050,
            profileId = "active-profile",
        )

        val rows = queries.selectAllRoutines("active-profile").executeAsList()
        assertEquals(1, rows.size, "one routine row after merge")
        assertEquals(upperUuid, rows.single().id, "stored routine primary key preserved")
        assertEquals("Push Day Renamed", rows.single().name)

        val children = queries.selectExercisesByRoutine(upperUuid).executeAsList()
        assertEquals(1, children.size, "the case-variant child updates in place (no delete + re-insert)")
        assertEquals(upperChild, children.single().id, "stored child primary key preserved")
        assertEquals("Bench Press", children.single().exerciseName, "incoming content applied")
        assertEquals(5.0, children.single().progressionKg, "local-only column preserved on matched child")
        assertEquals(
            listOf("planned-1"),
            queries.selectPlannedSetsByRoutineExercise(upperChild).executeAsList().map { it.id },
            "planned-set parents survive the case-variant merge",
        )
    }

    /**
     * Acceptance 2 (alias half): already-split UUID aliases reconcile
     * non-destructively into the recency-keeping row. Distinct children move with
     * their primary keys, planned sets migrate onto the kept twin child, group/
     * usage metadata merges, and CycleDay references follow the kept primary key.
     */
    @Test
    fun splitUuidAliasesReconcilePreservingChildrenPlannedSetsAndCycleLinks() = runTest {
        val database = newDb()
        val sync = newSyncRepository(database)
        val queries = database.phoenixDatabaseQueries

        // Split pair for one logical routine: the lowercase alias is newer (keeper).
        seedRoutine(
            database, upperUuid, "Old Name",
            createdAt = 1_700_000_000_000, updatedAt = 1_700_000_000_100,
            useCount = 2L, lastUsed = 1_700_000_000_050,
        )
        seedRoutine(
            database, upperUuid.lowercase(), "Newer Name",
            createdAt = 1_700_000_000_010, updatedAt = 1_700_000_000_200,
            useCount = 1L,
        )
        seedRoutineExercise(database, upperChild, upperUuid, progressionKg = 5.0)
        seedRoutineExercise(database, secondChild, upperUuid)
        seedRoutineExercise(database, upperChild.lowercase(), upperUuid.lowercase())
        seedRoutineExercise(database, thirdChild, upperUuid.lowercase())
        queries.insertPlannedSet("planned-a", upperChild, 1, "STANDARD", 5, 60.0, null, 90)
        queries.insertPlannedSet("planned-b", upperChild.lowercase(), 2, "STANDARD", 5, 60.0, null, 90)
        seedCycleDayReference(database, upperUuid)

        sync.mergePortalRoutines(
            listOf(
                PullRoutineDto(
                    id = upperUuid.lowercase(),
                    userId = "owner-user",
                    name = "Newer Name",
                    updatedAt = 1_700_000_000_300,
                    exercises = emptyList(),
                ),
            ),
            lastSync = 1_700_000_000_250,
            profileId = "active-profile",
        )

        val rows = queries.selectAllRoutines("active-profile").executeAsList()
        assertEquals(1, rows.size, "split aliases coalesce into one logical routine")
        val kept = rows.single()
        assertEquals(upperUuid.lowercase(), kept.id, "the recency-keeping row's primary key survives")
        assertEquals(1_700_000_000_000, kept.createdAt, "earliest creation preserved")
        assertEquals(1_700_000_000_050, kept.lastUsed, "latest use preserved")
        assertEquals(3L, kept.useCount, "usage metadata merges")

        val children = queries.selectExercisesByRoutine(kept.id).executeAsList()
        assertEquals(3, children.size, "distinct children preserved; the UUID twin merged to one row")
        val keptTwin = children.single { it.id == upperChild.lowercase() }
        assertEquals(
            0.0, keptTwin.progressionKg,
            "the kept twin's intentional default settings survive; the alias twin's " +
                "non-default columns never overwrite them",
        )
        assertTrue(children.any { it.id == secondChild }, "re-parented child keeps its primary key")
        assertTrue(children.any { it.id == thirdChild }, "kept row's distinct child preserved")
        assertEquals(
            setOf(1L, 2L),
            queries.selectPlannedSetsByRoutineExercise(upperChild.lowercase()).executeAsList()
                .mapTo(HashSet()) { it.set_number },
            "planned sets from both aliases survive on the kept twin child",
        )

        val cycleDays = queries.selectCycleDaysByCycle("cycle-1").executeAsList()
        assertEquals(kept.id, cycleDays.single().routine_id, "CycleDay references follow the kept routine primary key")
    }

    /**
     * Acceptance 3: applyServerDeletions matches mixed-case UUID-equivalent ids
     * and legacy serverId aliases, deletes each matched owner row exactly once
     * with its children, and leaves unrelated or other-profile rows intact.
     */
    @Test
    fun applyServerDeletionsMatchesUuidEquivalentIdsAndServerIdsOwnerScoped() = runTest {
        val database = newDb()
        val sync = newSyncRepository(database)
        val queries = database.phoenixDatabaseQueries

        // Owner's rows: a case-split pair for one identity, a legacy serverId row,
        // and an unrelated routine.
        seedRoutine(database, upperUuid, "Push Day")
        seedRoutine(database, mixedUuid, "Push Day Alias")
        seedRoutine(database, "legacy-local", "Legacy Day")
        queries.updateRoutineServerId("legacy-server", "legacy-local")
        seedRoutine(database, otherUuid, "Legs")
        seedRoutineExercise(database, upperChild, upperUuid)
        // Another profile (different account) holding a case variant of the same UUID.
        queries.insertProfile(
            id = "other-profile",
            name = "Other",
            colorIndex = 1L,
            createdAt = 1_700_000_000_000,
            isActive = 0L,
        )
        queries.linkProfileToSupabase(
            supabase_user_id = "other-user",
            last_auth_at = 1_700_000_000_000,
            id = "other-profile",
        )
        seedRoutine(database, upperUuid.lowercase(), "Other Profile Push Day", profileId = "other-profile")

        // Empty and unrelated deletion lists are no-ops.
        val emptyResult = sync.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = emptyList(),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_000,
        )
        assertTrue(emptyResult.deletedRoutineIds.isEmpty())
        val unrelatedResult = sync.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf("no-such-routine"),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_000,
        )
        assertTrue(unrelatedResult.deletedRoutineIds.isEmpty())

        val result = sync.applyServerDeletions(
            ownerUserId = "owner-user",
            routineIds = listOf(upperUuid.lowercase(), "legacy-server"),
            cycleIds = emptyList(),
            lastSync = 1_700_000_000_000,
        )

        assertEquals(
            setOf(upperUuid, mixedUuid, "legacy-local"),
            result.deletedRoutineIds.toSet(),
            "every owner-matched alias row is reported as an affected local id",
        )
        assertEquals(result.deletedRoutineIds.size, result.deletedRoutineIds.distinct().size, "each row deleted exactly once")
        assertTrue(queries.selectExercisesByRoutine(upperUuid).executeAsList().isEmpty(), "children deleted with the parent")

        assertEquals(
            listOf(otherUuid),
            queries.selectAllRoutines("active-profile").executeAsList().map { it.id },
            "the unrelated owner row stays intact",
        )
        assertNotNull(
            queries.selectRoutineById(upperUuid.lowercase()).executeAsOneOrNull(),
            "another profile's case-variant row is never touched by owner-scoped deletion",
        )
    }

    /**
     * Acceptance 4: distinct UUIDs with equal names stay separate, and opaque ids
     * (non-8-4-4-4-12 text) are never case-normalized on the wire or in storage.
     */
    @Test
    fun distinctUuidsWithEqualNamesStaySeparateAndOpaqueIdsAreUnchanged() = runTest {
        val database = newDb()
        val workout = newWorkoutRepository(database)
        val sync = newSyncRepository(database)
        val queries = database.phoenixDatabaseQueries

        val opaqueMixedCase = "LegacyPushDay"
        workout.saveRoutine(Routine(id = upperUuid, name = "Push Day", profileId = "active-profile"))
        workout.saveRoutine(Routine(id = otherUuid, name = "Push Day", profileId = "active-profile"))
        workout.saveRoutine(Routine(id = opaqueMixedCase, name = "Imported Day", profileId = "active-profile"))

        // Opaque ids pass through the wire unchanged (no lowercasing).
        val opaqueOutbound = PortalSyncAdapter.toPortalRoutine(
            Routine(id = opaqueMixedCase, name = "Imported Day", profileId = "active-profile"),
            "owner-user",
        )
        assertEquals(opaqueMixedCase, opaqueOutbound.id)

        sync.mergePortalRoutines(
            listOf(
                PullRoutineDto(
                    id = upperUuid.lowercase(),
                    userId = "owner-user",
                    name = "Push Day",
                    updatedAt = 1_700_000_000_100,
                    exercises = emptyList(),
                ),
            ),
            lastSync = 1_700_000_000_050,
            profileId = "active-profile",
        )

        val rows = queries.selectAllRoutines("active-profile").executeAsList()
        assertEquals(3, rows.size, "equal names with distinct identities stay separate")
        assertTrue(rows.any { it.id == upperUuid })
        assertTrue(rows.any { it.id == otherUuid })
        assertTrue(rows.any { it.id == opaqueMixedCase }, "opaque id text is unchanged in storage")
    }

    /**
     * Acceptance 2 (tombstone half): a locally soft-deleted routine is not
     * resurrected by a case-variant id arriving from the pull.
     */
    @Test
    fun localTombstoneIsNotResurrectedByCaseVariant() = runTest {
        val database = newDb()
        val sync = newSyncRepository(database)
        val queries = database.phoenixDatabaseQueries

        seedRoutine(database, upperUuid, "Push Day", deletedAt = 1_700_000_000_500)

        sync.mergePortalRoutines(
            listOf(
                PullRoutineDto(
                    id = upperUuid.lowercase(),
                    userId = "owner-user",
                    name = "Push Day",
                    updatedAt = 1_700_000_000_600,
                    exercises = emptyList(),
                ),
            ),
            lastSync = 1_700_000_000_000,
            profileId = "active-profile",
        )

        assertEquals(
            emptyList(),
            queries.selectAllRoutines("active-profile").executeAsList().map { it.id },
            "the tombstone stays effective",
        )
        assertEquals(
            1,
            queries.selectAllRoutinesSync().executeAsList().size,
            "no canonical-alias row is inserted beside the tombstone",
        )
    }

    // ===== Save/update child preservation (merge gate R1) =====

    private fun seedSuperset(database: com.devil.phoenixproject.database.PhoenixDatabase, id: String, routineId: String) {
        database.phoenixDatabaseQueries.insertSuperset(
            id = id,
            routineId = routineId,
            name = "Pair",
            colorIndex = 0L,
            restBetweenSeconds = 60L,
            orderIndex = 0L,
        )
    }

    private fun seedWorkoutSession(database: com.devil.phoenixproject.database.PhoenixDatabase, id: String) {
        database.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = 1_700_000_000_100L,
            mode = "OldSchool",
            targetReps = 5L,
            weightPerCableKg = 60.0,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = 5L,
            warmupReps = 0L,
            workingReps = 5L,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = null,
            exerciseName = "Deadlift",
            routineSessionId = null,
            routineName = null,
            routineId = null,
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
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "active-profile",
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }

    private fun seedCompletedLink(
        database: com.devil.phoenixproject.database.PhoenixDatabase,
        completedSetId: String,
        plannedSetId: String,
        routineExerciseId: String,
    ) {
        seedWorkoutSession(database, "session-$completedSetId")
        database.phoenixDatabaseQueries.insertCompletedSet(
            id = completedSetId,
            session_id = "session-$completedSetId",
            planned_set_id = plannedSetId,
            routine_exercise_id = routineExerciseId,
            set_number = 1L,
            set_type = "STANDARD",
            attempt_number = 1L,
            actual_reps = 5L,
            actual_weight_kg = 60.0,
            logged_rpe = null,
            is_pr = 0L,
            completed_at = 1_700_000_000_100L,
            set_end_reason = "COMPLETED",
        )
    }

    /** One model child per seeded row, with the UUID-case alias spelling of its id. */
    private fun aliasModelChild(id: String, name: String = "Deadlift", supersetId: String? = null) = RoutineExercise(
        id = id.lowercase(),
        exercise = Exercise(name = name, muscleGroup = "Back", equipment = "Cable"),
        orderIndex = 0,
        setReps = listOf(5),
        weightPerCableKg = 60f,
        setWeightsPerCableKg = listOf(60f),
        setRestSeconds = listOf(90),
        supersetId = supersetId,
    )

    /**
     * The save/update child-preservation fixture: routine [upperUuid] with superset
     * [thirdChild], child [upperChild] (planned set + completed-set link) and child
     * [secondChild] (planned set). Both planned sets and the completed link must
     * survive a write whose model carries only UUID-case aliases of the stored ids.
     */
    private fun seedChildPreservationFixture(database: com.devil.phoenixproject.database.PhoenixDatabase) {
        val queries = database.phoenixDatabaseQueries
        seedRoutine(database, upperUuid, "Push Day")
        seedSuperset(database, thirdChild, upperUuid)
        seedRoutineExercise(database, upperChild, upperUuid, progressionKg = 5.0)
        seedRoutineExercise(database, secondChild, upperUuid)
        queries.updateRoutineExerciseDurationSyncKnown(2L, upperChild)
        queries.insertPlannedSet("planned-1", upperChild, 1, "STANDARD", 5, 60.0, null, 90)
        queries.insertPlannedSet("planned-2", secondChild, 1, "STANDARD", 5, 60.0, null, 90)
        seedCompletedLink(database, completedSetId = "completed-1", plannedSetId = "planned-1", routineExerciseId = upperChild)
    }

    private fun assertChildRowsPreserved(
        database: com.devil.phoenixproject.database.PhoenixDatabase,
        expectedExerciseName: String,
    ) {
        val queries = database.phoenixDatabaseQueries
        val routineRows = queries.selectAllRoutines("active-profile").executeAsList()
        assertEquals(1, routineRows.size, "one logical routine after the write")
        assertEquals(upperUuid, routineRows.single().id, "stored routine primary key preserved")

        val supersets = queries.selectSupersetsByRoutine(upperUuid).executeAsList()
        assertEquals(listOf(thirdChild), supersets.map { it.id }, "stored superset primary key preserved")

        val children = queries.selectExercisesByRoutine(upperUuid).executeAsList()
        assertEquals(listOf(upperChild, secondChild), children.map { it.id }, "stored child primary keys preserved")
        assertEquals(
            expectedExerciseName,
            children.single { it.id == upperChild }.exerciseName,
            "the UUID-case model alias updated the existing child row in place",
        )
        assertEquals(
            thirdChild,
            children.single { it.id == upperChild }.supersetId,
            "the model superset alias resolved to the stored superset primary key",
        )
        assertEquals(
            2L,
            children.single { it.id == upperChild }.durationSyncKnown,
            "sync-state columns the model never carries survive the in-place update",
        )

        assertEquals(
            listOf("planned-1"),
            queries.selectPlannedSetsByRoutineExercise(upperChild).executeAsList().map { it.id },
            "planned sets survive the write (no cascade through a delete + re-insert)",
        )
        assertEquals(
            listOf("planned-2"),
            queries.selectPlannedSetsByRoutineExercise(secondChild).executeAsList().map { it.id },
            "planned sets of every kept child survive",
        )
        assertEquals(
            "planned-1",
            queries.selectCompletedSetsBySession("session-completed-1").executeAsOne().planned_set_id,
            "the completed-set planned link is not NULLed",
        )
    }

    /**
     * Merge gate R1 (save half): saveRoutine with UUID-case model aliases updates the
     * existing child rows in place. The old delete-and-rebuild fired the child foreign
     * keys (PlannedSet ON DELETE CASCADE, CompletedSet.planned_set_id ON DELETE SET
     * NULL) before the same primary keys came back, and a same-PK re-insert cannot
     * undo either action.
     */
    @Test
    fun saveRoutineKeepsChildRowsPlannedSetsAndCompletedLinksForUuidCaseAliases() = runTest {
        val database = newDb()
        val workout = newWorkoutRepository(database)
        seedChildPreservationFixture(database)

        workout.saveRoutine(
            Routine(
                id = upperUuid.lowercase(),
                name = "Push Day",
                profileId = "active-profile",
                supersets = listOf(Superset(id = thirdChild.lowercase(), routineId = upperUuid.lowercase(), name = "Pair")),
                exercises = listOf(
                    aliasModelChild(upperChild, name = "Deadlift Renamed", supersetId = thirdChild.lowercase()),
                    aliasModelChild(secondChild),
                ),
            ),
        )

        assertChildRowsPreserved(database, expectedExerciseName = "Deadlift Renamed")
    }

    /**
     * Merge gate R1 (update half): updateRoutine keeps the same child rows, planned
     * sets and completed-set planned links when the model children are UUID-case
     * aliases of the stored primary keys.
     */
    @Test
    fun updateRoutineKeepsChildRowsPlannedSetsAndCompletedLinksForUuidCaseAliases() = runTest {
        val database = newDb()
        val workout = newWorkoutRepository(database)
        seedChildPreservationFixture(database)

        workout.updateRoutine(
            Routine(
                id = upperUuid.lowercase(),
                name = "Push Day Renamed",
                profileId = "active-profile",
                supersets = listOf(Superset(id = thirdChild.lowercase(), routineId = upperUuid.lowercase(), name = "Pair")),
                exercises = listOf(
                    aliasModelChild(upperChild, name = "Deadlift Renamed", supersetId = thirdChild.lowercase()),
                    aliasModelChild(secondChild),
                ),
            ),
        )

        assertChildRowsPreserved(database, expectedExerciseName = "Deadlift Renamed")
        assertEquals(
            "Push Day Renamed",
            database.phoenixDatabaseQueries.selectRoutineById(upperUuid).executeAsOne().name,
            "the update landed on the resolved stored primary key",
        )
    }

    /**
     * Merge gate R1 (removal half): only children the model genuinely removed are
     * deleted — the kept child keeps its row and planned sets while the removed
     * child's rows go away with it, and a brand-new model child is inserted.
     */
    @Test
    fun saveRoutineDeletesOnlyChildrenTheModelRemoved() = runTest {
        val database = newDb()
        val workout = newWorkoutRepository(database)
        val queries = database.phoenixDatabaseQueries
        seedChildPreservationFixture(database)

        workout.saveRoutine(
            Routine(
                id = upperUuid,
                name = "Push Day",
                profileId = "active-profile",
                supersets = listOf(Superset(id = thirdChild, routineId = upperUuid, name = "Pair")),
                exercises = listOf(
                    aliasModelChild(upperChild, supersetId = thirdChild),
                    aliasModelChild(otherUuid, name = "Cable Row"),
                ),
            ),
        )

        val children = queries.selectExercisesByRoutine(upperUuid).executeAsList()
        assertEquals(
            listOf(upperChild, otherUuid.lowercase()).sorted(),
            children.map { it.id }.sorted(),
            "the kept child stays on its stored primary key and only the new model child is inserted",
        )
        assertNull(
            queries.selectRoutineExerciseById(secondChild).executeAsOneOrNull(),
            "the genuinely removed child is deleted",
        )
        assertEquals(
            listOf("planned-1"),
            queries.selectPlannedSetsByRoutineExercise(upperChild).executeAsList().map { it.id },
            "the kept child's planned sets survive",
        )
        assertTrue(
            queries.selectPlannedSetsByRoutineExercise(secondChild).executeAsList().isEmpty(),
            "the removed child's planned sets go away with it",
        )
    }

    // ===== Claim/adopt routing (merge gate R3) =====

    /**
     * Merge gate R3: mergeAllPullData claim/adopt routing. A retained claim on the
     * canonical identity routes the owner's uppercase-alias row to the claim target
     * (same-account permitted adoption), while another account's row holding the exact
     * canonical text — which the owner-filtered identity reads exclude — is never
     * adopted, merged into or otherwise mutated: parent, children, dependents and
     * profile all stay as they are.
     */
    @Test
    fun retainedClaimRoutesOwnerAliasWithoutTouchingAnotherAccountsCanonicalRow() = runTest {
        val database = newDb()
        val sync = newSyncRepository(database)
        val queries = database.phoenixDatabaseQueries
        // Same-account claim target, plus another account's profile.
        queries.insertProfile("claimed-target", "Claimed", 1L, 1_700_000_000_000, 0L)
        queries.linkProfileToSupabase("owner-user", 1_700_000_000_000, "claimed-target")
        queries.insertProfile("other-profile", "Other", 2L, 1_700_000_000_000, 0L)
        queries.linkProfileToSupabase("other-user", 1_700_000_000_000, "other-profile")

        // The owner's stored row: the uppercase-alias spelling, with a child and a planned set.
        seedRoutine(database, upperUuid, "Owner alias", updatedAt = 0L)
        seedRoutineExercise(database, upperChild, upperUuid)
        queries.insertPlannedSet("owner-planned", upperChild, 1, "STANDARD", 5, 60.0, null, 90)
        // Another account's row: the exact canonical lowercase text of the same identity.
        seedRoutine(database, upperUuid.lowercase(), "Foreign canonical", profileId = "other-profile", updatedAt = 0L)
        seedRoutineExercise(database, secondChild, upperUuid.lowercase())
        queries.insertPlannedSet("foreign-planned", secondChild, 1, "STANDARD", 5, 60.0, null, 90)

        queries.insertLocalOwnershipClaimIfAbsent(
            ownerUserId = "owner-user",
            entityType = OwnershipEntityType.ROUTINE.name,
            entityId = upperUuid.lowercase(),
            mutationId = "claim-routine-alias",
            sourceProfileId = "active-profile",
            targetProfileId = "claimed-target",
            transferredAt = 10L,
        )

        sync.mergeAllPullData(
            ownerUserId = "owner-user",
            workoutDeletions = emptyList(),
            sessions = emptyList(),
            routines = listOf(PullRoutineDto(id = upperUuid.lowercase(), name = "Owner alias pulled", updatedAt = 20L)),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 1L,
            profileId = "active-profile",
        )

        val ownerRow = queries.selectRoutineById(upperUuid).executeAsOne()
        assertEquals("claimed-target", ownerRow.profile_id, "the retained claim routes the owner's alias row")
        assertEquals("Owner alias pulled", ownerRow.name, "the pull merge lands on the owner's row")

        val foreignRow = queries.selectRoutineById(upperUuid.lowercase()).executeAsOne()
        assertEquals("other-profile", foreignRow.profile_id, "another account's row is never adopted")
        assertEquals("Foreign canonical", foreignRow.name, "another account's parent row is not merged into")
        assertEquals(
            listOf(secondChild),
            queries.selectExercisesByRoutine(upperUuid.lowercase()).executeAsList().map { it.id },
            "another account's children are unchanged",
        )
        assertEquals(
            listOf("foreign-planned"),
            queries.selectPlannedSetsByRoutineExercise(secondChild).executeAsList().map { it.id },
            "another account's dependent rows are unchanged",
        )
        assertEquals(
            "other-user",
            queries.getProfileById("other-profile").executeAsOne().supabase_user_id,
            "the foreign profile is unchanged",
        )
    }

    // ===== Eager maintenance + recovery snapshots (architecture follow-up) =====

    /**
     * Acceptance 1 (arch review follow-up): already-split alias rows reconcile
     * before the FIRST offline list emission — no save, no named pull.
     */
    @Test
    fun firstOfflineListReadReconcilesSplitAliasesWithoutAWrite() = runTest {
        val database = newDb()
        val repository = newWorkoutRepository(database)
        seedRoutine(database, upperUuid, "Split", updatedAt = 5L)
        seedRoutine(database, upperUuid.lowercase(), "Split", updatedAt = 5L)

        val routines = repository.getAllRoutines("active-profile").first()
        assertEquals(1, routines.size, "one card on the first read without any write")
    }

    /**
     * Acceptance 2 (arch review follow-up): equal-time tombstones cannot
     * resurrect — the tombstoned row is kept with deletedAt preserved and the
     * live alias is removed into it.
     */
    @Test
    fun equalTimeTombstoneKeepsTheIdentityDeleted() = runTest {
        val database = newDb()
        val repository = newWorkoutRepository(database)
        seedRoutine(database, upperUuid, "Deleted", updatedAt = 5L, deletedAt = 5L)
        seedRoutine(database, upperUuid.lowercase(), "Live alias", updatedAt = 5L)

        val result = repository.runRoutineIdentityMaintenance("active-profile")
        assertEquals(1, result.tombstoneComponents)

        val row = database.phoenixDatabaseQueries.selectRoutineById(upperUuid).executeAsOne()
        assertEquals(5L, row.deletedAt, "deletedAt is preserved, never cleared")
        assertEquals(0, repository.getAllRoutines("active-profile").first().size, "never resurrected")
    }
}
