package com.devil.phoenixproject.data.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplate
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.ExternalRoutineExercise
import com.devil.phoenixproject.domain.model.ExternalRoutineFolder
import com.devil.phoenixproject.domain.model.ExternalRoutineSet
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.testutil.createTestDriver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * AUD-002: SQLite-backed integration tests for all 6 SQLDelight external integration
 * repositories (`SqlDelightExternalActivityRepository`, `SqlDelightExternalRoutineRepository`,
 * `SqlDelightExternalProgramRepository`, `SqlDelightExternalMeasurementRepository`,
 * `SqlDelightExternalExerciseTemplateRepository`, `SqlDelightIntegrationSyncCursorRepository`)
 * with `PRAGMA foreign_keys = ON`.
 */
class SqlDelightExternalIntegrationRepositoriesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var activityRepo: SqlDelightExternalActivityRepository
    private lateinit var routineRepo: SqlDelightExternalRoutineRepository
    private lateinit var programRepo: SqlDelightExternalProgramRepository
    private lateinit var measurementRepo: SqlDelightExternalMeasurementRepository
    private lateinit var templateRepo: SqlDelightExternalExerciseTemplateRepository
    private lateinit var cursorRepo: SqlDelightIntegrationSyncCursorRepository

    @Before
    fun setup() {
        driver = createTestDriver()
        database = PhoenixDatabase(driver)
        activityRepo = SqlDelightExternalActivityRepository(database)
        routineRepo = SqlDelightExternalRoutineRepository(database)
        programRepo = SqlDelightExternalProgramRepository(database)
        measurementRepo = SqlDelightExternalMeasurementRepository(database)
        templateRepo = SqlDelightExternalExerciseTemplateRepository(database)
        cursorRepo = SqlDelightIntegrationSyncCursorRepository(database)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `external activity repository supports CRUD, conflict key preservation, tombstones, and statuses`() = runTest {
        val a1 = ExternalActivity(
            id = "act-1",
            externalId = "ext-1",
            provider = IntegrationProvider.HEVY,
            name = "Push Day",
            startedAt = 1_000L,
            durationSeconds = 3_600,
            calories = 320,
            profileId = "p1",
            needsSync = true,
        )
        val a2 = ExternalActivity(
            id = "act-2",
            externalId = "ext-1",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Liftosaur Session",
            startedAt = 2_000L,
            profileId = "p1",
            needsSync = true,
        )
        val otherProfile = ExternalActivity(
            id = "act-3",
            externalId = "ext-1",
            provider = IntegrationProvider.HEVY,
            name = "Other Profile Push",
            startedAt = 3_000L,
            profileId = "p2",
            needsSync = true,
        )
        activityRepo.upsertActivities(listOf(a1, a2, otherProfile))

        // Re-upsert same (provider, externalId, profileId) with a different id and needsSync = false:
        // original id ("act-1") and needsSync = true must survive.
        activityRepo.upsertActivities(
            listOf(
                a1.copy(
                    id = "act-new-ignored",
                    name = "Push Day Updated",
                    needsSync = false,
                ),
            ),
        )

        val p1All = activityRepo.getAll("p1", null).first()
        assertEquals(listOf("act-2", "act-1"), p1All.map { it.id })
        assertEquals("Push Day Updated", p1All.last().name)
        assertTrue(p1All.last().needsSync)

        val p1Hevy = activityRepo.getAll("p1", IntegrationProvider.HEVY).first()
        assertEquals(listOf("act-1"), p1Hevy.map { it.id })

        // Sync-key scoping: clear needsSync only for (HEVY, ext-1, p1), not LIFTOSAUR or p2.
        activityRepo.markSyncedBySyncKeys(
            listOf(ExternalActivitySyncKey("ext-1", IntegrationProvider.HEVY)),
            profileId = "p1",
        )
        assertEquals(listOf("act-2"), activityRepo.getUnsyncedActivities("p1").map { it.id })
        assertEquals(listOf("act-3"), activityRepo.getUnsyncedActivities("p2").map { it.id })

        // markSynced(ids) clears remaining unsynced id.
        activityRepo.markSynced(listOf("act-2"))
        assertTrue(activityRepo.getUnsyncedActivities("p1").isEmpty())

        // Tombstone hides from getAll and getUnsyncedActivities while keeping DB row.
        activityRepo.markDeletedByExternalIds(
            provider = IntegrationProvider.HEVY,
            profileId = "p1",
            externalIds = listOf("ext-1"),
            deletedAt = 9_000L,
            needsSync = true,
        )
        assertEquals(listOf("act-2"), activityRepo.getAll("p1", null).first().map { it.id })
        assertEquals(listOf("act-3"), activityRepo.getAll("p2", null).first().map { it.id })
        assertTrue(activityRepo.getUnsyncedActivities("p1").isEmpty())
        val persistedTombstone = driver.executeQuery(
            null,
            "SELECT deletedAt, needsSync FROM ExternalActivity WHERE id = 'act-1'",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) to cursor.getLong(1) else null,
                )
            },
            0,
        ).value
        assertEquals(9_000L to 1L, persistedTombstone)

        // Provider delete only removes (LIFTOSAUR, p1).
        activityRepo.deleteActivities(IntegrationProvider.LIFTOSAUR, "p1")
        assertTrue(activityRepo.getAll("p1", null).first().isEmpty())
        assertEquals(listOf("act-3"), activityRepo.getAll("p2", null).first().map { it.id })

        // Integration status CRUD + unknown status fallback + profile isolation.
        activityRepo.updateIntegrationStatus(
            provider = IntegrationProvider.HEVY,
            status = ConnectionStatus.CONNECTED,
            profileId = "p1",
            lastSyncAt = 5_000L,
            errorMessage = null,
        )
        activityRepo.updateIntegrationStatus(
            provider = IntegrationProvider.HEVY,
            status = ConnectionStatus.ERROR,
            profileId = "p2",
            lastSyncAt = 6_000L,
            errorMessage = "boom",
        )
        assertEquals(ConnectionStatus.CONNECTED, activityRepo.getIntegrationStatus(IntegrationProvider.HEVY, "p1").first()?.status)
        assertEquals(ConnectionStatus.ERROR, activityRepo.getIntegrationStatus(IntegrationProvider.HEVY, "p2").first()?.status)
        assertEquals(1, activityRepo.getAllIntegrationStatuses("p1").first().size)

        // Unknown status string falls back to DISCONNECTED.
        driver.execute(
            null,
            "INSERT OR REPLACE INTO IntegrationStatus(provider, status, lastSyncAt, errorMessage, profileId) VALUES ('strong', 'BOGUS', 1, NULL, 'p1')",
            0,
        )
        assertEquals(ConnectionStatus.DISCONNECTED, activityRepo.getIntegrationStatus(IntegrationProvider.STRONG, "p1").first()?.status)
    }

    @Test
    fun `external routine repository handles nested FK children, conflict updates, folders, and provider scoping`() = runTest {
        val folder = ExternalRoutineFolder(
            id = "folder-1",
            externalId = "f-ext-1",
            provider = IntegrationProvider.HEVY,
            title = "Upper/Lower",
            index = 1,
            profileId = "p1",
        )
        val otherFolder = folder.copy(id = "folder-2", profileId = "p2")
        routineRepo.upsertFolders(listOf(folder, otherFolder))

        val routine = ExternalRoutine(
            id = "r-1",
            externalId = "r-ext-1",
            provider = IntegrationProvider.HEVY,
            title = "Upper A",
            folderExternalId = "f-ext-1",
            folderName = "Upper/Lower",
            profileId = "p1",
            needsSync = true,
            exercises = listOf(
                ExternalRoutineExercise(
                    id = "re-2",
                    externalRoutineId = "r-1",
                    title = "Row",
                    primaryMuscleGroups = listOf("Back", "Biceps"),
                    secondaryMuscleGroups = listOf("Core"),
                    orderIndex = 1,
                    sets = listOf(
                        ExternalRoutineSet(id = "s-2", externalRoutineExerciseId = "re-2", index = 0, weightKg = 50.0, reps = 10),
                    ),
                ),
                ExternalRoutineExercise(
                    id = "re-1",
                    externalRoutineId = "r-1",
                    title = "Bench",
                    primaryMuscleGroups = listOf("Chest"),
                    orderIndex = 0,
                    sets = listOf(
                        ExternalRoutineSet(
                            id = "s-1",
                            externalRoutineExerciseId = "re-1",
                            index = 1,
                            setType = "WORKING",
                            weightKg = 80.0,
                            reps = 8,
                            minReps = 6,
                            maxReps = 10,
                            restSeconds = 90,
                            rpe = 8.5,
                        ),
                        ExternalRoutineSet(
                            id = "s-0",
                            externalRoutineExerciseId = "re-1",
                            index = 0,
                            setType = "WARMUP",
                            durationSeconds = 30,
                            distanceMeters = 100.0,
                        ),
                    ),
                ),
            ),
        )
        val otherRoutine = routine.copy(
            id = "r-other",
            profileId = "p2",
            exercises = emptyList(),
        )
        routineRepo.upsertRoutines(listOf(routine, otherRoutine))

        val initialP1Routines = routineRepo.observeRoutines("p1", IntegrationProvider.HEVY).first()
        assertEquals(1, initialP1Routines.size)
        val initialLoaded = initialP1Routines.single()
        assertEquals("r-1", initialLoaded.id)
        assertEquals("Upper A", initialLoaded.title)
        assertEquals(listOf("Bench", "Row"), initialLoaded.exercises.map { it.title })
        assertEquals(listOf("s-0", "s-1"), initialLoaded.exercises[0].sets.map { it.id })
        assertEquals(listOf("Back", "Biceps"), initialLoaded.exercises[1].primaryMuscleGroups)
        assertEquals(listOf("Core"), initialLoaded.exercises[1].secondaryMuscleGroups)

        // Re-upsert same sync key with a new routine id and needsSync = false:
        // localRoutine.id ("r-1") and needsSync = true must be preserved and child rows replaced cleanly under FK ON.
        routineRepo.upsertRoutines(
            listOf(
                routine.copy(
                    id = "r-new-ignored",
                    title = "Upper A v2",
                    needsSync = false,
                    exercises = listOf(
                        ExternalRoutineExercise(
                            id = "re-3",
                            externalRoutineId = "r-new-ignored",
                            title = "Incline Press",
                            primaryMuscleGroups = listOf("Chest"),
                            orderIndex = 0,
                            sets = listOf(
                                ExternalRoutineSet(id = "s-3", externalRoutineExerciseId = "re-3", index = 0, weightKg = 70.0, reps = 8),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val p1Routines = routineRepo.observeRoutines("p1", IntegrationProvider.HEVY).first()
        assertEquals(1, p1Routines.size)
        val loaded = p1Routines.single()
        assertEquals("r-1", loaded.id)
        assertTrue(loaded.needsSync)
        assertEquals("Upper A v2", loaded.title)
        assertEquals(listOf("Incline Press"), loaded.exercises.map { it.title })
        assertEquals("r-1", loaded.exercises.single().externalRoutineId)
        assertEquals(1, loaded.exercises.single().sets.size)

        // Delete provider routines for p1 only; p2 remains intact.
        routineRepo.deleteProviderRoutines(IntegrationProvider.HEVY, "p1")
        assertTrue(routineRepo.observeRoutines("p1", null).first().isEmpty())
        assertTrue(routineRepo.observeFolders("p1", null).first().isEmpty())
        assertEquals(listOf("r-other"), routineRepo.observeRoutines("p2", null).first().map { it.id })
        assertEquals(listOf("folder-2"), routineRepo.observeFolders("p2", null).first().map { it.id })
    }

    @Test
    fun `external program repository preserves parent id and FK-linked stats on conflict`() = runTest {
        val program = ExternalProgram(
            id = "prog-1",
            externalId = "ext-p1",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "GZCLP",
            isCurrent = true,
            scriptText = "Bench / 5x3",
            profileId = "p1",
            needsSync = false,
        )
        val otherProgram = program.copy(id = "prog-p2", profileId = "p2")
        programRepo.upsertPrograms(listOf(program, otherProgram))
        programRepo.upsertStats(
            listOf(
                ExternalProgramStats(
                    id = "stats-1",
                    externalProgramId = "prog-1",
                    days = 4,
                    approximateMinutes = 55,
                    setCount = 20,
                    muscleGroupBreakdownJson = """{"Chest":8}""",
                    computedAt = 1_000L,
                ),
            ),
        )

        // Re-upsert same (provider, externalId, profileId) with a different id:
        // parent id ("prog-1") must survive so ON DELETE CASCADE does NOT wipe ExternalProgramStats.
        programRepo.upsertPrograms(
            listOf(
                program.copy(
                    id = "prog-2-ignored",
                    name = "GZCLP Renamed",
                ),
            ),
        )
        programRepo.updateProgramText("prog-1", "Bench / 5x3+", markNeedsSync = true)

        val current = programRepo.observeCurrentProgram("p1", IntegrationProvider.LIFTOSAUR).first()
        assertNotNull(current)
        assertEquals("prog-1", current.id)
        assertEquals("GZCLP Renamed", current.name)
        assertEquals("Bench / 5x3+", current.scriptText)
        assertTrue(current.needsSync)

        val stats = programRepo.observeProgramStats("p1", IntegrationProvider.LIFTOSAUR).first()
        assertEquals(4, stats["prog-1"]?.days)
        assertEquals(20, stats["prog-1"]?.setCount)

        assertTrue(programRepo.findPrograms(IntegrationProvider.LIFTOSAUR, emptyList(), "p1").isEmpty())
        assertEquals(listOf("prog-1"), programRepo.findPrograms(IntegrationProvider.LIFTOSAUR, listOf("ext-p1"), "p1").map { it.id })

        programRepo.deleteProviderPrograms(IntegrationProvider.LIFTOSAUR, "p1")
        assertTrue(programRepo.observePrograms("p1", null).first().isEmpty())
        assertTrue(programRepo.observeProgramStats("p1", null).first().isEmpty())
        assertEquals(listOf("prog-p2"), programRepo.observePrograms("p2", null).first().map { it.id })
    }

    @Test
    fun `external measurement repository isolates profiles and replaces on sync key`() = runTest {
        val m1 = ExternalBodyMeasurement(
            id = "m-1",
            externalId = "ext-m1",
            provider = IntegrationProvider.HEVY,
            measurementType = "weight_kg",
            value = 80.5,
            unit = "kg",
            measuredAt = 1_000L,
            profileId = "p1",
        )
        val m2 = m1.copy(id = "m-2", externalId = "ext-m2", measuredAt = 2_000L, value = 80.0)
        val mOther = m1.copy(id = "m-3", profileId = "p2")
        measurementRepo.upsertMeasurements(listOf(m1, m2, mOther))

        measurementRepo.upsertMeasurements(listOf(m1.copy(id = "m-1-updated", value = 81.0)))

        val p1 = measurementRepo.observeMeasurements("p1", IntegrationProvider.HEVY).first()
        assertEquals(listOf(2_000L, 1_000L), p1.map { it.measuredAt })
        assertEquals(81.0, p1.last().value)

        val byType = measurementRepo.observeMeasurementsByType("p1", "weight_kg").first()
        assertEquals(2, byType.size)

        measurementRepo.deleteProviderMeasurements(IntegrationProvider.HEVY, "p1")
        assertTrue(measurementRepo.observeMeasurements("p1", null).first().isEmpty())
        assertEquals(1, measurementRepo.observeMeasurements("p2", null).first().size)
    }

    @Test
    fun `external exercise template repository tracks counts and deletes templates and mappings scoped by provider and profile`() = runTest {
        templateRepo.upsertTemplates(
            listOf(
                ExternalExerciseTemplate(
                    id = "t-1",
                    externalId = "ext-t1",
                    provider = IntegrationProvider.HEVY,
                    title = "Bench",
                    primaryMuscleGroups = listOf("Chest"),
                    profileId = "p1",
                ),
                ExternalExerciseTemplate(
                    id = "t-2",
                    externalId = "ext-t2",
                    provider = IntegrationProvider.LIFTOSAUR,
                    title = "Squat",
                    profileId = "p1",
                ),
                ExternalExerciseTemplate(
                    id = "t-3",
                    externalId = "ext-t1",
                    provider = IntegrationProvider.HEVY,
                    title = "Bench P2",
                    profileId = "p2",
                ),
            ),
        )

        // Seed ExternalExerciseTemplateMapping directly to verify provider/profile-scoped cleanup.
        driver.execute(
            null,
            """
            INSERT INTO ExternalExerciseTemplateMapping(id, provider, externalTemplateId, localExerciseId, profileId, createdAt, updatedAt)
            VALUES
                ('map-p1-hevy', 'hevy', 'ext-t1', 'local-bench', 'p1', 100, 100),
                ('map-p2-hevy', 'hevy', 'ext-t1', 'local-bench', 'p2', 100, 100)
            """.trimIndent(),
            0,
        )

        val counts = templateRepo.observeTemplateCounts("p1").first()
        assertEquals(1, counts[IntegrationProvider.HEVY])
        assertEquals(1, counts[IntegrationProvider.LIFTOSAUR])

        templateRepo.deleteProviderTemplates(IntegrationProvider.HEVY, "p1")
        val afterCounts = templateRepo.observeTemplateCounts("p1").first()
        assertNull(afterCounts[IntegrationProvider.HEVY])
        assertEquals(1, afterCounts[IntegrationProvider.LIFTOSAUR])
        assertEquals(1, templateRepo.observeTemplateCounts("p2").first()[IntegrationProvider.HEVY])

        // Verify mapping row deleted for (hevy, p1) only, leaving (hevy, p2).
        var remainingMappings = emptyList<String>()
        driver.executeQuery(
            null,
            "SELECT id FROM ExternalExerciseTemplateMapping",
            { cursor ->
                val list = mutableListOf<String>()
                while (cursor.next().value) list += cursor.getString(0).orEmpty()
                remainingMappings = list
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            0,
        )
        assertEquals(listOf("map-p2-hevy"), remainingMappings)
    }

    @Test
    fun `integration sync cursor repository supports upsert, null cursorValue, and provider profile isolation`() = runTest {
        cursorRepo.upsertCursor(
            IntegrationSyncCursor(
                provider = IntegrationProvider.HEVY,
                profileId = "p1",
                cursorType = "workouts",
                cursorValue = "page-1",
                updatedAt = 100L,
            ),
        )
        cursorRepo.upsertCursor(
            IntegrationSyncCursor(
                provider = IntegrationProvider.HEVY,
                profileId = "p1",
                cursorType = "workouts",
                cursorValue = null,
                updatedAt = 200L,
            ),
        )
        cursorRepo.upsertCursor(
            IntegrationSyncCursor(
                provider = IntegrationProvider.LIFTOSAUR,
                profileId = "p1",
                cursorType = "workouts",
                cursorValue = "page-l",
                updatedAt = 100L,
            ),
        )
        cursorRepo.upsertCursor(
            IntegrationSyncCursor(
                provider = IntegrationProvider.HEVY,
                profileId = "p2",
                cursorType = "workouts",
                cursorValue = "page-p2",
                updatedAt = 100L,
            ),
        )

        val p1Hevy = cursorRepo.getCursor(IntegrationProvider.HEVY, "p1", "workouts")
        assertNotNull(p1Hevy)
        assertNull(p1Hevy.cursorValue)
        assertEquals(200L, p1Hevy.updatedAt)

        cursorRepo.deleteProviderCursors(IntegrationProvider.HEVY, "p1")
        assertNull(cursorRepo.getCursor(IntegrationProvider.HEVY, "p1", "workouts"))
        assertFalse(cursorRepo.getCursor(IntegrationProvider.LIFTOSAUR, "p1", "workouts") == null)
        assertEquals("page-p2", cursorRepo.getCursor(IntegrationProvider.HEVY, "p2", "workouts")?.cursorValue)
    }
}
