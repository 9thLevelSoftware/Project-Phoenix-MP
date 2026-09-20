package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.createTestDriver
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightPersonalRecordRepositoryTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightPersonalRecordRepository
    private lateinit var baselineRepository: SqlDelightProfileExerciseBaselineRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 0L, 1L)
        baselineRepository = SqlDelightProfileExerciseBaselineRepository(database)
        repository = SqlDelightPersonalRecordRepository(database)
        insertExercise(id = "bench", name = "Bench Press")
    }

    @Test
    fun `updatePRsIfBetter normalizes legacy mode keys on write and lookup`() = runTest {
        val result = repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 50f,
            volumePRWeightPerCableKg = 50f,
            reps = 5,
            workoutMode = "OldSchool",
            timestamp = 1000L,
            profileId = "default",
        ).getOrThrow()

        assertTrue(result.contains(PRType.MAX_WEIGHT))
        assertTrue(result.contains(PRType.MAX_VOLUME))

        val weightPr = repository.getWeightPR("bench", "Old School", profileId = "default")
        assertEquals(50f, weightPr?.weightPerCableKg)
        assertEquals("Old School", weightPr?.workoutMode)
        assertEquals(
            weightPr?.id,
            repository.getWeightPR("bench", "OldSchool", profileId = "default")?.id,
        )
    }

    @Test
    fun `updatePRsIfBetter uses achieved load for weight PR and conservative load for volume PR`() = runTest {
        baselineRepository.set("default", "bench", 42.25f, updatedAt = 500L)
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 60f,
            volumePRWeightPerCableKg = 50f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 1000L,
            profileId = "default",
        ).getOrThrow()

        val weightPr = repository.getWeightPR("bench", "Old School", profileId = "default")
        val volumePr = repository.getVolumePR("bench", "Old School", profileId = "default")
        val baseline = baselineRepository.get("default", "bench")

        assertEquals(60f, weightPr?.weightPerCableKg)
        assertEquals(300f, weightPr?.volume)
        assertEquals(50f, volumePr?.weightPerCableKg)
        assertEquals(250f, volumePr?.volume)
        assertEquals(42.25f, baseline?.oneRepMaxPerCableKg, "saving a PR must not overwrite the explicit baseline")
        assertEquals(1L, baseline?.revision)
    }

    @Test
    fun `normalizeWorkoutModeKey only canonicalizes exact echo mode`() {
        assertEquals("Echo", normalizeWorkoutModeKey("Echo"))
        assertEquals("EchoLevel3", normalizeWorkoutModeKey("EchoLevel3"))
    }

    @Test
    fun `getBestPR returns highest weight`() = runTest {
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 40f,
            volumePRWeightPerCableKg = 40f,
            reps = 8,
            workoutMode = "OldSchool",
            timestamp = 1000L,
            profileId = "default",
        )
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 60f,
            volumePRWeightPerCableKg = 60f,
            reps = 3,
            workoutMode = "OldSchool",
            timestamp = 2000L,
            profileId = "default",
        )

        val best = repository.getBestPR("bench", profileId = "default")
        assertEquals(60f, best?.weightPerCableKg)
    }

    @Test
    fun `normalized lookup reads legacy mode rows before migration cleanup`() = runTest {
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "bench",
            exerciseName = "Bench Press",
            weight = 55.0,
            reps = 8,
            oneRepMax = OneRepMaxCalculator.epley(55f, 8).toDouble(),
            achievedAt = 1000L,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT.name,
            volume = 440.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = null,
            uuid = null,
        )

        val canonical = repository.getWeightPR("bench", "Old School", profileId = "default")
        val legacy = repository.getWeightPR("bench", "OldSchool", profileId = "default")

        assertEquals(55f, canonical?.weightPerCableKg)
        assertEquals(canonical?.id, legacy?.id)
        assertNull(
            database.phoenixDatabaseQueries.selectPR(
                "bench",
                "Old School",
                PRType.MAX_WEIGHT.name,
                phase = "COMBINED",
                profileId = "default",
            ).executeAsOneOrNull(),
        )
    }

    /**
     * Issue #319: Proves that db.transaction {} in updatePRsIfBetterInternal is atomic.
     *
     * Strategy: Install a SQLite trigger that makes the volume-PR insert fail after
     * the weight-PR upsert has executed in the same transaction. If the transaction
     * is truly atomic, the weight PR is rolled back and the database remains clean.
     */
    @Test
    fun `Issue 319 transaction rollback prevents partial PR writes when downstream write fails`() = runTest {
        // Create a dedicated database with driver reference for raw SQL trigger injection
        val driver = createTestDriver()
        val testDb = PhoenixDatabase(driver)
        testDb.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 0L, 1L)
        val testBaselineRepository = SqlDelightProfileExerciseBaselineRepository(testDb)
        val testRepo = SqlDelightPersonalRecordRepository(testDb)

        testDb.phoenixDatabaseQueries.insertExercise(
            id = "squat", name = "Squat", displayName = null, description = null,
            created = 0L, muscleGroup = "Legs", muscleGroups = "Legs",
            muscles = null, equipment = "BAR", movement = null,
            sidedness = null, grip = null, gripWidth = null,
            minRepRange = null, popularity = 0.0, archived = 0L,
            isFavorite = 0L, isCustom = 0L, timesPerformed = 0L,
            lastPerformed = null, aliases = null, defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null, mvtOverrideMs = null,
            isBodyweight = null,
        )

        // Trigger fires on the second PR write, after the weight PR was inserted.
        driver.execute(
            null,
            "CREATE TRIGGER fail_volume_pr BEFORE INSERT ON PersonalRecord " +
                "WHEN NEW.prType = 'MAX_VOLUME' " +
                "BEGIN SELECT RAISE(ABORT, 'Issue 319: simulated volume PR failure'); END",
            0,
        )

        // This call beats both weight and volume PRs (first-ever for this exercise),
        // so the transaction will: upsert weight PR → upsert volume PR (BOOM).
        val result = testRepo.updatePRsIfBetter(
            exerciseId = "squat",
            weightPRWeightPerCableKg = 80f,
            volumePRWeightPerCableKg = 80f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 1000L,
            profileId = "default",
        )

        // The trigger should have caused the entire transaction to roll back
        assertTrue(result.isFailure, "Expected Result.failure from simulated 1RM sync crash")

        // CRITICAL: Neither PR should exist — both upserts rolled back
        assertNull(
            testRepo.getWeightPR("squat", "Old School", profileId = "default"),
            "Weight PR must not survive a rolled-back transaction",
        )
        assertNull(
            testRepo.getVolumePR("squat", "Old School", profileId = "default"),
            "Volume PR must not survive a rolled-back transaction",
        )

        assertNull(
            testBaselineRepository.get("default", "squat"),
            "Scoped baseline should still be absent after rollback",
        )

        // Positive control: remove trigger, verify the exact same call now succeeds
        driver.execute(null, "DROP TRIGGER fail_volume_pr", 0)

        val successResult = testRepo.updatePRsIfBetter(
            exerciseId = "squat",
            weightPRWeightPerCableKg = 80f,
            volumePRWeightPerCableKg = 80f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 2000L,
            profileId = "default",
        )
        assertTrue(successResult.isSuccess, "Same call should succeed without the trigger")
        assertNotNull(
            testRepo.getWeightPR("squat", "Old School", profileId = "default"),
            "Weight PR should exist after successful write",
        )
        assertNull(
            testBaselineRepository.get("default", "squat"),
            "saving a PR must not create a training baseline",
        )
    }

    @Test
    fun `deleting a PR hides it from product reads and retains a sync tombstone`() = runTest {
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 65f,
            volumePRWeightPerCableKg = 65f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 2_000L,
            profileId = "default",
        ).getOrThrow()
        val weightPr = assertNotNull(repository.getWeightPR("bench", "Old School", "default"))

        repository.deletePR(weightPr.id, "default")

        assertNull(repository.getWeightPR("bench", "Old School", "default"))
        val retainedTombstone = database.phoenixDatabaseQueries
            .selectPRsModifiedSince(0L, profileId = "default")
            .executeAsList()
            .single { it.id == weightPr.id }
        assertNotNull(retainedTombstone.deletedAt)
        assertEquals(retainedTombstone.deletedAt, retainedTombstone.updatedAt)
    }

    @Test
    fun `beating a deleted PR restores its stable sync uuid`() = runTest {
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 65f,
            volumePRWeightPerCableKg = 65f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 2_000L,
            profileId = "default",
        ).getOrThrow()
        val deletedPr = assertNotNull(repository.getWeightPR("bench", "Old School", "default"))
        val stableUuid = assertNotNull(deletedPr.uuid)
        repository.deletePR(deletedPr.id, "default")

        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 70f,
            volumePRWeightPerCableKg = 70f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 3_000L,
            profileId = "default",
        ).getOrThrow()

        val restoredPr = assertNotNull(repository.getWeightPR("bench", "Old School", "default"))
        assertEquals(stableUuid, restoredPr.uuid)
        assertEquals(70f, restoredPr.weightPerCableKg)
    }

    @Test
    fun `a lower valid PR replaces a deleted outlier with its stable sync uuid`() = runTest {
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 100f,
            volumePRWeightPerCableKg = 100f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 2_000L,
            profileId = "default",
        ).getOrThrow()
        val deletedPr = assertNotNull(repository.getWeightPR("bench", "Old School", "default"))
        val stableUuid = assertNotNull(deletedPr.uuid)
        repository.deletePR(deletedPr.id, "default")

        val result = repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 90f,
            volumePRWeightPerCableKg = 90f,
            reps = 5,
            workoutMode = "Old School",
            timestamp = 3_000L,
            profileId = "default",
        ).getOrThrow()

        val replacementPr = assertNotNull(repository.getWeightPR("bench", "Old School", "default"))
        assertTrue(result.contains(PRType.MAX_WEIGHT))
        assertEquals(stableUuid, replacementPr.uuid)
        assertEquals(90f, replacementPr.weightPerCableKg)
    }

    private fun insertExercise(id: String, name: String) {
        database.phoenixDatabaseQueries.insertExercise(
            id = id,
            name = name,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
