package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightPersonalRecordRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightPersonalRecordRepository

    @Before
    fun setup() {
        database = createTestDatabase()
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
        val exercise = database.vitruvianDatabaseQueries.selectExerciseById(
            "bench",
        ).executeAsOneOrNull()

        assertEquals(60f, weightPr?.weightPerCableKg)
        assertEquals(300f, weightPr?.volume)
        assertEquals(50f, volumePr?.weightPerCableKg)
        assertEquals(250f, volumePr?.volume)
        assertEquals(
            OneRepMaxCalculator.epley(60f, 5).toDouble(),
            exercise?.one_rep_max_kg,
        )
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
        database.vitruvianDatabaseQueries.insertRecord(
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
        )

        val canonical = repository.getWeightPR("bench", "Old School", profileId = "default")
        val legacy = repository.getWeightPR("bench", "OldSchool", profileId = "default")

        assertEquals(55f, canonical?.weightPerCableKg)
        assertEquals(canonical?.id, legacy?.id)
        assertNull(
            database.vitruvianDatabaseQueries.selectPR(
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
     * Strategy: Install a SQLite trigger that makes the 1RM-sync UPDATE fail AFTER
     * the weight-PR and volume-PR upserts have already executed inside the same
     * transaction. If the transaction is truly atomic, the PR upserts are rolled
     * back and the database remains clean.
     */
    @Test
    fun `Issue 319 transaction rollback prevents partial PR writes when downstream write fails`() = runTest {
        // Create a dedicated database with driver reference for raw SQL trigger injection
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(driver)
        val testDb = VitruvianDatabase(driver)
        val testRepo = SqlDelightPersonalRecordRepository(testDb)

        testDb.vitruvianDatabaseQueries.insertExercise(
            id = "squat", name = "Squat", description = null,
            created = 0L, muscleGroup = "Legs", muscleGroups = "Legs",
            muscles = null, equipment = "BAR", movement = null,
            sidedness = null, grip = null, gripWidth = null,
            minRepRange = null, popularity = 0.0, archived = 0L,
            isFavorite = 0L, isCustom = 0L, timesPerformed = 0L,
            lastPerformed = null, aliases = null, defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
        )

        // Trigger fires on the 1RM sync (third write in the transaction),
        // AFTER weight-PR and volume-PR upserts have already executed.
        driver.execute(
            null,
            "CREATE TRIGGER fail_1rm_update BEFORE UPDATE OF one_rep_max_kg ON Exercise " +
                "BEGIN SELECT RAISE(ABORT, 'Issue 319: simulated 1RM sync failure'); END",
            0,
        )

        // This call beats both weight and volume PRs (first-ever for this exercise),
        // so the transaction will: upsert weight PR → upsert volume PR → update 1RM (BOOM).
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

        // Exercise 1RM should remain null (trigger prevented the UPDATE)
        val exercise = testDb.vitruvianDatabaseQueries.selectExerciseById("squat").executeAsOneOrNull()
        assertNull(exercise?.one_rep_max_kg, "Exercise 1RM should still be null after rollback")

        // Positive control: remove trigger, verify the exact same call now succeeds
        driver.execute(null, "DROP TRIGGER fail_1rm_update", 0)

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
    }

    private fun insertExercise(id: String, name: String) {
        database.vitruvianDatabaseQueries.insertExercise(
            id = id,
            name = name,
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
        )
    }
}
