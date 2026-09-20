package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.ExerciseCableIntent
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.testutil.createTestDriver
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightExerciseRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var importer: ExerciseImporter
    private lateinit var repository: SqlDelightExerciseRepository

    @Before
    fun setup() {
        driver = createTestDriver()
        database = PhoenixDatabase(driver)
        importer = ExerciseImporter(database)
        repository = SqlDelightExerciseRepository(
            database,
            importer,
            com.devil.phoenixproject.testutil.FakePreferencesManager(),
        )
    }

    @Test
    fun `searchExercises filters by name and muscle group`() = runTest {
        insertExerciseIfAbsent(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")
        insertExerciseIfAbsent(id = "ex-2", name = "Squat", muscleGroup = "Legs", equipment = "BAR")

        repository.searchExercises("bench").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("Bench Press", results.first().name)
            cancelAndIgnoreRemainingEvents()
        }

        repository.searchExercises("legs").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("Squat", results.first().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleFavorite flips favorite flag`() = runTest {
        insertExerciseIfAbsent(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")

        repository.toggleFavorite("ex-1")
        val updated = repository.getExerciseById("ex-1")

        assertNotNull(updated)
        assertTrue(updated.isFavorite)
    }

    @Test
    fun `createCustomExercise stores custom entry`() = runTest {
        val result = repository.createCustomExercise(
            com.devil.phoenixproject.domain.model.Exercise(
                name = "Custom Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "",
            ),
        )

        val created = result.getOrNull()
        assertNotNull(created?.id)
        assertTrue(created.isCustom)

        repository.getCustomExercises().test {
            val customs = awaitItem()
            assertEquals(1, customs.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a training max is stored per profile and never read by another profile`() = runTest {
        insertExerciseIfAbsent(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")
        seedProfile("alice")
        seedProfile("bob")

        repository.setTrainingMax("ex-1", "alice", 120f, TrainingMaxSource.MANUAL)

        assertEquals(120f, repository.getTrainingMax("ex-1", "alice"))
        assertNull(repository.getTrainingMax("ex-1", "bob"))

        // Clearing removes only that profile's row.
        repository.setTrainingMax("ex-1", "bob", 90f, TrainingMaxSource.MANUAL)
        repository.setTrainingMax("ex-1", "bob", null, TrainingMaxSource.MANUAL)
        assertEquals(120f, repository.getTrainingMax("ex-1", "alice"))
        assertNull(repository.getTrainingMax("ex-1", "bob"))
    }

    @Test
    fun `an unassigned legacy value is offered until any profile claims it`() = runTest {
        insertExerciseIfAbsent(
            id = "ex-1",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            oneRepMaxKg = 110.0,
        )
        seedProfile("alice")
        seedProfile("bob")

        assertEquals(110f, repository.getUnassignedLegacyTrainingMax("ex-1"))

        repository.setTrainingMax("ex-1", "alice", 110f, TrainingMaxSource.CLAIMED_LEGACY)

        // Claimed by alice: it is hers, and it is no longer offered to anyone.
        assertEquals(110f, repository.getTrainingMax("ex-1", "alice"))
        assertNull(repository.getTrainingMax("ex-1", "bob"))
        assertNull(repository.getUnassignedLegacyTrainingMax("ex-1"))
    }

    private fun seedProfile(id: String) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO UserProfile(id, name, colorIndex, createdAt, isActive) " +
                "VALUES ('$id', '$id', 0, 0, 0)",
            0,
        )
    }

    @Test
    fun `getImages returns exercise demonstration stills`() = runTest {
        insertExerciseIfAbsent(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "barbell")
        database.phoenixDatabaseQueries.insertImage(
            exerciseId = "ex-1",
            url = "https://example.com/0.jpg",
            sortOrder = 0L,
        )
        database.phoenixDatabaseQueries.insertImage(
            exerciseId = "ex-1",
            url = "https://example.com/1.jpg",
            sortOrder = 1L,
        )

        val images = repository.getImages("ex-1")

        assertEquals(2, images.size)
        assertEquals("https://example.com/0.jpg", images.first().url)
        assertEquals(1, images.last().sortOrder)
    }

    @Test
    fun `import maps free-exercise-db rows and images`() = runTest {
        val result = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Barbell_Bench_Press_-_Medium_Grip",
                "name": "Barbell Bench Press - Medium Grip",
                "equipment": "barbell",
                "primaryMuscles": ["chest"],
                "secondaryMuscles": ["triceps", "shoulders"],
                "instructions": ["Lie back.", "Press."],
                "category": "strength",
                "images": ["Barbell_Bench_Press_-_Medium_Grip/0.jpg"]
              }
            ]
            """.trimIndent(),
        )

        assertTrue(result.isSuccess)
        val exercise = repository.getExerciseById("Barbell_Bench_Press_-_Medium_Grip")
        assertNotNull(exercise)
        assertEquals("Chest", exercise.muscleGroup)
        assertEquals(false, exercise.isBodyweight)
        assertEquals(ExerciseCableIntent.DUAL, exercise.cableIntent)
        assertEquals(2, exercise.displayMultiplier)
        val images = repository.getImages("Barbell_Bench_Press_-_Medium_Grip")
        assertEquals(1, images.size)
        assertTrue(images.single().url.contains("Barbell_Bench_Press_-_Medium_Grip/0.jpg"))
    }

    @Test
    fun `getExerciseById maps explicit cable intent conservatively`() = runTest {
        insertExerciseIfAbsent(
            id = "dual-explicit",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            sidedness = "bilateral",
        )
        insertExerciseIfAbsent(
            id = "legacy-placeholder",
            name = "Unknown Cable Exercise",
            muscleGroup = "Back",
            equipment = "HANDLES",
            defaultCableConfig = "DOUBLE",
            sidedness = null,
        )

        assertEquals(ExerciseCableIntent.DUAL, repository.getExerciseById("dual-explicit")?.cableIntent)
        assertNull(repository.getExerciseById("legacy-placeholder")?.cableIntent)
    }

    @Test
    fun `import marks body-only rows as bodyweight`() = runTest {
        val result = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Plank",
                "name": "Plank",
                "equipment": "body only",
                "primaryMuscles": ["abdominals"],
                "secondaryMuscles": [],
                "instructions": [],
                "category": "strength",
                "images": []
              }
            ]
            """.trimIndent(),
        )

        assertTrue(result.isSuccess)
        val plank = repository.getExerciseById("Plank")
        assertNotNull(plank)
        assertEquals("Core", plank.muscleGroup)
        assertEquals(true, plank.isBodyweight)
        assertEquals("BODYWEIGHT", plank.equipment)
        assertEquals(ExerciseCableIntent.EITHER, plank.cableIntent)
    }

    @Test
    fun `import leaves non-cable equipment bodyweight derivation unset`() = runTest {
        val result = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Foam_Roll",
                "name": "Foam Roll",
                "equipment": "foam roll",
                "primaryMuscles": ["lower back"],
                "secondaryMuscles": [],
                "instructions": [],
                "category": "stretching",
                "images": []
              }
            ]
            """.trimIndent(),
        )

        assertTrue(result.isSuccess)
        val foamRoll = repository.getExerciseById("Foam_Roll")
        assertNotNull(foamRoll)
        assertEquals("foam roll", foamRoll.equipment)
        assertEquals(true, foamRoll.isBodyweight)
        assertEquals(false, foamRoll.hasCableAccessory)
    }

    @Test
    fun `reimport preserves user-owned catalogue fields`() = runTest {
        insertExerciseIfAbsent(
            id = "Plank",
            name = "Old Plank",
            muscleGroup = "Core",
            equipment = "BODYWEIGHT",
            isFavorite = 1L,
            oneRepMaxKg = 42.5,
            timesPerformed = 9L,
            lastPerformed = 1_700_000_000_000L,
        )

        val result = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Plank",
                "name": "Plank",
                "equipment": "body only",
                "primaryMuscles": ["abdominals"],
                "secondaryMuscles": [],
                "instructions": ["Hold a straight line."],
                "category": "strength",
                "images": []
              }
            ]
            """.trimIndent(),
        )

        assertTrue(result.isSuccess)
        val plank = repository.getExerciseById("Plank")
        assertNotNull(plank)
        assertEquals("Plank", plank.name)
        assertEquals(true, plank.isFavorite)
        assertEquals(42.5, legacyOneRepMaxOf("Plank"))
        assertEquals(9, plank.timesPerformed)
        val row = database.phoenixDatabaseQueries.selectExerciseById("Plank").executeAsOne()
        assertEquals(1_700_000_000_000L, row.lastPerformed)
        assertEquals("Hold a straight line.", row.description)
        assertEquals("BODYWEIGHT", plank.equipment)
    }

    @Test
    fun `import fails when every catalogue id is already a custom exercise`() = runTest {
        insertExerciseIfAbsent(
            id = "Plank",
            name = "My Plank",
            muscleGroup = "Core",
            equipment = "BODYWEIGHT",
            isCustom = 1L,
        )

        val result = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Plank",
                "name": "Plank",
                "equipment": "body only",
                "primaryMuscles": ["abdominals"],
                "secondaryMuscles": [],
                "instructions": [],
                "category": "strength",
                "images": []
              }
            ]
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
        val custom = repository.getExerciseById("Plank")
        assertNotNull(custom)
        assertEquals("My Plank", custom.name)
        assertEquals(true, custom.isCustom)
    }

    @Test
    fun `name fallbacks prefer active rows over archived legacy ids`() = runTest {
        insertExerciseIfAbsent(
            id = "legacy-plank",
            name = "Plank",
            muscleGroup = "Core",
            equipment = "BODYWEIGHT",
            archived = 1L,
        )
        insertExerciseIfAbsent(
            id = "Plank",
            name = "Plank",
            muscleGroup = "Core",
            equipment = "BODYWEIGHT",
            archived = 0L,
        )

        val byName = database.phoenixDatabaseQueries.findExerciseByName("Plank").executeAsOne()
        val byMuscle = database.phoenixDatabaseQueries
            .findExerciseByNameAndMuscle("Plank", "Core")
            .executeAsOne()
        val byCase = database.phoenixDatabaseQueries
            .findExerciseByNameCaseInsensitive("plank")
            .executeAsOne()

        assertEquals("Plank", byName.id)
        assertEquals("Plank", byMuscle.id)
        assertEquals("Plank", byCase.id)
    }

    @Test
    fun `remap moves history and PRs onto replacement catalogue ids`() = runTest {
        insertExerciseIfAbsent(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
            isFavorite = 1L,
            oneRepMaxKg = 100.0,
            timesPerformed = 4L,
        )
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "ZZ92N8QsBdp6HCh3",
            exerciseName = "Bench Press",
            weight = 80.0,
            reps = 5,
            oneRepMax = 90.0,
            achievedAt = 1_700_000_000_000L,
            workoutMode = "OldSchool",
            prType = "MAX_WEIGHT",
            volume = 400.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = 2,
            uuid = null,
        )
        database.phoenixDatabaseQueries.insertSession(
            id = "session-legacy-bench",
            timestamp = 1_700_000_000_000L,
            mode = "OldSchool",
            targetReps = 5,
            weightPerCableKg = 40.0,
            progressionKg = 0.0,
            duration = 60,
            totalReps = 5,
            warmupReps = 0,
            workingReps = 5,
            isJustLift = 0,
            stopAtTop = 0,
            eccentricLoad = 100,
            echoLevel = 1,
            exerciseId = "ZZ92N8QsBdp6HCh3",
            exerciseName = "Bench Press",
            routineSessionId = null,
            routineName = null,
            routineId = null,
            safetyFlags = 0,
            deloadWarningCount = 0,
            romViolationCount = 0,
            spotterActivations = 0,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = 40.0,
            totalVolumeKg = 200.0,
            cableCount = 2,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = 40.0,
            burnoutAvgWeightKg = null,
            peakWeightKg = 40.0,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "default",
            display_multiplier = 2,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )

        val imported = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Barbell_Bench_Press_-_Medium_Grip",
                "name": "Barbell Bench Press - Medium Grip",
                "equipment": "barbell",
                "primaryMuscles": ["chest"],
                "secondaryMuscles": [],
                "instructions": [],
                "category": "strength",
                "images": []
              }
            ]
            """.trimIndent(),
        )
        assertTrue(imported.isSuccess)
        importer.remapLegacyCatalogueIds()

        val replacement = "Barbell_Bench_Press_-_Medium_Grip"
        val session = database.phoenixDatabaseQueries.selectSessionById("session-legacy-bench").executeAsOne()
        assertEquals(replacement, session.exerciseId)
        val prs = database.phoenixDatabaseQueries.selectAllPRsForExercise(replacement, "default").executeAsList()
        assertEquals(1, prs.size)
        assertEquals(80.0, prs.single().weight)
        val exercise = repository.getExerciseById(replacement)
        assertNotNull(exercise)
        assertEquals(true, exercise.isFavorite)
        assertEquals(100.0, legacyOneRepMaxOf(replacement))
        assertEquals(4, exercise.timesPerformed)

        importer.remapLegacyCatalogueIds()
        val afterSecondPass = repository.getExerciseById(replacement)
        assertNotNull(afterSecondPass)
        assertEquals(4, afterSecondPass.timesPerformed)
        assertEquals(true, afterSecondPass.isFavorite)
        assertEquals(100.0, legacyOneRepMaxOf(replacement))
    }

    @Test
    fun `remap maps duplicate bench press catalogue id`() = runTest {
        insertExerciseIfAbsent(
            id = "b5d0f3d1-994b-4589-9d2b-b3f36f1412c7",
            name = "Bench Press ",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
            timesPerformed = 3L,
        )
        insertPr(
            exerciseId = "b5d0f3d1-994b-4589-9d2b-b3f36f1412c7",
            exerciseName = "Bench Press",
            weight = 92.5,
        )

        val imported = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Barbell_Bench_Press_-_Medium_Grip",
                "name": "Barbell Bench Press - Medium Grip",
                "equipment": "barbell",
                "primaryMuscles": ["chest"],
                "secondaryMuscles": [],
                "instructions": [],
                "category": "strength",
                "images": []
              }
            ]
            """.trimIndent(),
        )
        assertTrue(imported.isSuccess)
        importer.remapLegacyCatalogueIds()

        val replacement = "Barbell_Bench_Press_-_Medium_Grip"
        val prs = database.phoenixDatabaseQueries
            .selectAllPRsForExercise(replacement, "default")
            .executeAsList()
        assertEquals(1, prs.size)
        assertEquals(92.5, prs.single().weight)
        val leftover = database.phoenixDatabaseQueries
            .selectPersonalRecordsByExerciseId("b5d0f3d1-994b-4589-9d2b-b3f36f1412c7")
            .executeAsList()
        assertTrue(leftover.isEmpty())
        val exercise = repository.getExerciseById(replacement)
        assertNotNull(exercise)
        assertEquals(3, exercise.timesPerformed)
    }

    @Test
    fun `remap keeps a live PR over a heavier tombstone`() = runTest {
        insertExerciseIfAbsent(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertPr(exerciseId = "ZZ92N8QsBdp6HCh3", exerciseName = "Bench Press", weight = 140.0, oneRepMax = 155.0)
        val tombstone = database.phoenixDatabaseQueries
            .selectPersonalRecordsByExerciseId("ZZ92N8QsBdp6HCh3")
            .executeAsOne()
        database.phoenixDatabaseQueries.softDeletePRById(
            deletedAt = 1_900_000_000_000L,
            updatedAt = 1_900_000_000_000L,
            id = tombstone.id,
            profileId = "default",
        )
        insertExerciseIfAbsent(
            id = "Barbell_Bench_Press_-_Medium_Grip",
            name = "Barbell Bench Press - Medium Grip",
            muscleGroup = "Chest",
            equipment = "BAR",
        )
        insertPr(
            exerciseId = "Barbell_Bench_Press_-_Medium_Grip",
            exerciseName = "Barbell Bench Press - Medium Grip",
            weight = 95.0,
            oneRepMax = 100.0,
        )

        importer.remapLegacyCatalogueIds()

        val prs = database.phoenixDatabaseQueries
            .selectAllPRsForExercise("Barbell_Bench_Press_-_Medium_Grip", "default")
            .executeAsList()
        assertEquals(1, prs.size)
        assertEquals(95.0, prs.single().weight)
        assertEquals(100.0, prs.single().oneRepMax)
        assertEquals(null, prs.single().deletedAt)
        val leftover = database.phoenixDatabaseQueries
            .selectPersonalRecordsByExerciseId("ZZ92N8QsBdp6HCh3")
            .executeAsList()
        assertTrue(leftover.isEmpty())
    }

    @Test
    fun `remap maps renamed rack pull onto rack pulls`() = runTest {
        insertExerciseIfAbsent(
            id = "legacy-rack-pull",
            name = "Rack Pull",
            muscleGroup = "Back",
            equipment = "BAR",
            archived = 1L,
            timesPerformed = 6L,
        )
        insertPr(exerciseId = "legacy-rack-pull", exerciseName = "Rack Pull", weight = 180.0)

        val imported = importer.importFromFreeExerciseJson(
            """
            [
              {
                "id": "Rack_Pulls",
                "name": "Rack Pulls",
                "equipment": "barbell",
                "primaryMuscles": ["hamstrings"],
                "secondaryMuscles": [],
                "instructions": [],
                "category": "strength",
                "images": []
              }
            ]
            """.trimIndent(),
        )
        assertTrue(imported.isSuccess)
        importer.remapLegacyCatalogueIds()

        val prs = database.phoenixDatabaseQueries
            .selectAllPRsForExercise("Rack_Pulls", "default")
            .executeAsList()
        assertEquals(1, prs.size)
        assertEquals(180.0, prs.single().weight)
        val leftover = database.phoenixDatabaseQueries
            .selectPersonalRecordsByExerciseId("legacy-rack-pull")
            .executeAsList()
        assertTrue(leftover.isEmpty())
        val exercise = repository.getExerciseById("Rack_Pulls")
        assertNotNull(exercise)
        assertEquals(6, exercise.timesPerformed)
    }

    @Test
    fun `remap keeps the heavier colliding PR`() = runTest {
        insertExerciseIfAbsent(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertPr(exerciseId = "ZZ92N8QsBdp6HCh3", exerciseName = "Bench Press", weight = 80.0, oneRepMax = 90.0)
        insertExerciseIfAbsent(
            id = "Barbell_Bench_Press_-_Medium_Grip",
            name = "Barbell Bench Press - Medium Grip",
            muscleGroup = "Chest",
            equipment = "BAR",
        )
        insertPr(
            exerciseId = "Barbell_Bench_Press_-_Medium_Grip",
            exerciseName = "Barbell Bench Press - Medium Grip",
            weight = 110.0,
            oneRepMax = 120.0,
        )

        importer.remapLegacyCatalogueIds()

        val prs = database.phoenixDatabaseQueries
            .selectAllPRsForExercise("Barbell_Bench_Press_-_Medium_Grip", "default")
            .executeAsList()
        assertEquals(1, prs.size)
        assertEquals(110.0, prs.single().weight)
        assertEquals(120.0, prs.single().oneRepMax)
        val leftover = database.phoenixDatabaseQueries
            .selectPersonalRecordsByExerciseId("ZZ92N8QsBdp6HCh3")
            .executeAsList()
        assertTrue(leftover.isEmpty())
    }

    @Test
    fun `remap keeps the heavier colliding PR from the legacy row`() = runTest {
        insertExerciseIfAbsent(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertPr(exerciseId = "ZZ92N8QsBdp6HCh3", exerciseName = "Bench Press", weight = 140.0, oneRepMax = 155.0)
        insertExerciseIfAbsent(
            id = "Barbell_Bench_Press_-_Medium_Grip",
            name = "Barbell Bench Press - Medium Grip",
            muscleGroup = "Chest",
            equipment = "BAR",
        )
        insertPr(
            exerciseId = "Barbell_Bench_Press_-_Medium_Grip",
            exerciseName = "Barbell Bench Press - Medium Grip",
            weight = 95.0,
            oneRepMax = 100.0,
        )

        importer.remapLegacyCatalogueIds()

        val prs = database.phoenixDatabaseQueries
            .selectAllPRsForExercise("Barbell_Bench_Press_-_Medium_Grip", "default")
            .executeAsList()
        assertEquals(1, prs.size)
        assertEquals(140.0, prs.single().weight)
        assertEquals(155.0, prs.single().oneRepMax)
    }

    @Test
    fun `remap keeps the larger colliding MAX_VOLUME PR`() = runTest {
        insertExerciseIfAbsent(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertPr(
            exerciseId = "ZZ92N8QsBdp6HCh3",
            exerciseName = "Bench Press",
            weight = 60.0,
            volume = 900.0,
            prType = "MAX_VOLUME",
        )
        insertExerciseIfAbsent(
            id = "Barbell_Bench_Press_-_Medium_Grip",
            name = "Barbell Bench Press - Medium Grip",
            muscleGroup = "Chest",
            equipment = "BAR",
        )
        insertPr(
            exerciseId = "Barbell_Bench_Press_-_Medium_Grip",
            exerciseName = "Barbell Bench Press - Medium Grip",
            weight = 80.0,
            volume = 400.0,
            prType = "MAX_VOLUME",
        )

        importer.remapLegacyCatalogueIds()

        val prs = database.phoenixDatabaseQueries
            .selectAllPRsForExercise("Barbell_Bench_Press_-_Medium_Grip", "default")
            .executeAsList()
        assertEquals(1, prs.size)
        assertEquals("MAX_VOLUME", prs.single().prType)
        assertEquals(900.0, prs.single().volume)
    }

    @Test
    fun `remap merges colliding personal MVT samples`() = runTest {
        insertExerciseIfAbsent(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertExerciseIfAbsent(
            id = "Barbell_Bench_Press_-_Medium_Grip",
            name = "Barbell Bench Press - Medium Grip",
            muscleGroup = "Chest",
            equipment = "BAR",
        )
        database.phoenixDatabaseQueries.upsertExerciseMvt(
            exerciseId = "ZZ92N8QsBdp6HCh3",
            profileId = "default",
            personalMvtMs = 400.0,
            sampleCount = 3,
            updatedAt = 1_700_000_000_000L,
        )
        database.phoenixDatabaseQueries.upsertExerciseMvt(
            exerciseId = "Barbell_Bench_Press_-_Medium_Grip",
            profileId = "default",
            personalMvtMs = 200.0,
            sampleCount = 1,
            updatedAt = 1_800_000_000_000L,
        )

        importer.remapLegacyCatalogueIds()

        val merged = database.phoenixDatabaseQueries
            .selectExerciseMvt("Barbell_Bench_Press_-_Medium_Grip", "default")
            .executeAsOne()
        assertEquals(4, merged.sampleCount)
        assertEquals(350.0, merged.personalMvtMs)
        assertEquals(1_800_000_000_000L, merged.updatedAt)
        val leftover = database.phoenixDatabaseQueries
            .selectExerciseMvtByExerciseId("ZZ92N8QsBdp6HCh3")
            .executeAsList()
        assertTrue(leftover.isEmpty())

        importer.remapLegacyCatalogueIds()
        val afterSecondPass = database.phoenixDatabaseQueries
            .selectExerciseMvt("Barbell_Bench_Press_-_Medium_Grip", "default")
            .executeAsOne()
        assertEquals(4, afterSecondPass.sampleCount)
        assertEquals(350.0, afterSecondPass.personalMvtMs)
    }

    private fun insertPr(
        exerciseId: String,
        exerciseName: String,
        weight: Double,
        oneRepMax: Double = weight,
        volume: Double = weight * 5,
        prType: String = "MAX_WEIGHT",
        achievedAt: Long = 1_700_000_000_000L,
        workoutMode: String = "OldSchool",
        phase: String = "COMBINED",
        profileId: String = "default",
        reps: Long = 5,
    ) {
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            weight = weight,
            reps = reps,
            oneRepMax = oneRepMax,
            achievedAt = achievedAt,
            workoutMode = workoutMode,
            prType = prType,
            volume = volume,
            phase = phase,
            profile_id = profileId,
            cable_count = 2,
            uuid = null,
        )
    }

    private fun insertExerciseIfAbsent(
        id: String,
        name: String,
        muscleGroup: String,
        equipment: String,
        defaultCableConfig: String = "DOUBLE",
        sidedness: String? = null,
        isFavorite: Long = 0L,
        isCustom: Long = 0L,
        oneRepMaxKg: Double? = null,
        timesPerformed: Long = 0L,
        lastPerformed: Long? = null,
        archived: Long = 0L,
    ) {
        database.phoenixDatabaseQueries.insertExerciseIfAbsent(
            id = id,
            name = name,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = muscleGroup,
            muscleGroups = muscleGroup,
            muscles = null,
            equipment = equipment,
            movement = null,
            sidedness = sidedness,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = archived,
            isFavorite = isFavorite,
            isCustom = isCustom,
            timesPerformed = timesPerformed,
            lastPerformed = lastPerformed,
            aliases = null,
            defaultCableConfig = defaultCableConfig,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
        // Nothing writes Exercise.one_rep_max_kg any more (migration 49 moved the value to
        // ExerciseTrainingMax), so a legacy value has to be seeded with raw SQL.
        oneRepMaxKg?.let { driver.execute(null, "UPDATE Exercise SET one_rep_max_kg = $it WHERE id = '$id'", 0) }
    }

    private fun legacyOneRepMaxOf(id: String): Double? =
        database.phoenixDatabaseQueries.selectExerciseById(id).executeAsOne().one_rep_max_kg
}
