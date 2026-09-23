package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.ExerciseCableIntent
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightExerciseRepositoryTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var importer: ExerciseImporter
    private lateinit var repository: SqlDelightExerciseRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        importer = ExerciseImporter(database)
        repository = SqlDelightExerciseRepository(
            database,
            importer,
            com.devil.phoenixproject.testutil.FakePreferencesManager(),
        )
    }

    @Test
    fun `searchExercises filters by name and muscle group`() = runTest {
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")
        insertExercise(id = "ex-2", name = "Squat", muscleGroup = "Legs", equipment = "BAR")

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
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")

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
    fun `custom exercise sync preserves user metadata and image children`() = runTest {
        insertExercise(
            id = "custom-shared",
            name = "Local Name",
            muscleGroup = "Chest",
            equipment = "HANDLES",
            isCustom = 1L,
            isFavorite = 1L,
            oneRepMaxKg = 55.0,
        )
        database.phoenixDatabaseQueries.insertImage(
            exerciseId = "custom-shared",
            url = "https://example.com/custom.jpg",
            sortOrder = 0L,
        )
        val syncRepository = SqlDelightSyncRepository(
            database,
            com.devil.phoenixproject.testutil.FakeUserProfileRepository(),
        )

        syncRepository.mergeCustomExercises(
            listOf(
                CustomExerciseSyncDto(
                    clientId = "custom-shared",
                    serverId = "server-custom-shared",
                    name = "Portal Name",
                    displayName = "Portal Display",
                    muscleGroup = "Shoulders",
                    equipment = "BAR",
                    defaultCableConfig = "DOUBLE",
                    createdAt = 10L,
                    updatedAt = 20L,
                ),
            ),
        )

        val after = database.phoenixDatabaseQueries.selectExerciseById("custom-shared").executeAsOne()
        assertEquals("Portal Name", after.name)
        assertEquals(1L, after.isFavorite)
        assertEquals(55.0, after.one_rep_max_kg)
        assertEquals(
            listOf("https://example.com/custom.jpg"),
            database.phoenixDatabaseQueries.selectImagesByExercise("custom-shared").executeAsList().map { it.url },
        )
    }

    @Test
    fun `getImages returns exercise demonstration stills`() = runTest {
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "barbell")
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
        insertExercise(
            id = "dual-explicit",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            sidedness = "bilateral",
        )
        insertExercise(
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
        insertExercise(
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
        assertEquals(9, plank.timesPerformed)
        val row = database.phoenixDatabaseQueries.selectExerciseById("Plank").executeAsOne()
        assertEquals(42.5, row.one_rep_max_kg)
        assertEquals(1_700_000_000_000L, row.lastPerformed)
        assertEquals("Hold a straight line.", row.description)
        assertEquals("BODYWEIGHT", plank.equipment)
    }

    @Test
    fun `import fails when every catalogue id is already a custom exercise`() = runTest {
        insertExercise(
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
        insertExercise(
            id = "legacy-plank",
            name = "Plank",
            muscleGroup = "Core",
            equipment = "BODYWEIGHT",
            archived = 1L,
        )
        insertExercise(
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
        insertExercise(
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
        assertEquals(4, exercise.timesPerformed)
        assertEquals(
            100.0,
            database.phoenixDatabaseQueries.selectExerciseById(replacement).executeAsOne().one_rep_max_kg,
        )

        importer.remapLegacyCatalogueIds()
        val afterSecondPass = repository.getExerciseById(replacement)
        assertNotNull(afterSecondPass)
        assertEquals(4, afterSecondPass.timesPerformed)
        assertEquals(true, afterSecondPass.isFavorite)
        assertEquals(
            100.0,
            database.phoenixDatabaseQueries.selectExerciseById(replacement).executeAsOne().one_rep_max_kg,
        )
    }

    @Test
    fun `remap maps duplicate bench press catalogue id`() = runTest {
        insertExercise(
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
        insertExercise(
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
        insertExercise(
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
        insertExercise(
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
        insertExercise(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertPr(exerciseId = "ZZ92N8QsBdp6HCh3", exerciseName = "Bench Press", weight = 80.0, oneRepMax = 90.0)
        insertExercise(
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
        insertExercise(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertPr(exerciseId = "ZZ92N8QsBdp6HCh3", exerciseName = "Bench Press", weight = 140.0, oneRepMax = 155.0)
        insertExercise(
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
        insertExercise(
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
        insertExercise(
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
        insertExercise(
            id = "ZZ92N8QsBdp6HCh3",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            archived = 1L,
        )
        insertExercise(
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

    @Test
    fun `catalogue overlay renames one leg barbell squat and keeps search and sort intact`() = runTest {
        val result = importer.importFromFreeExerciseJson(
            """
            [
              { "id": "Bradford_Rocky_Presses", "name": "Bradford/Rocky Presses", "equipment": "barbell", "primaryMuscles": ["shoulders"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] },
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] },
              { "id": "Butt_Lift_Bridge", "name": "Butt Lift (Bridge)", "equipment": "body only", "primaryMuscles": ["glutes"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] },
              { "id": "Split_Squat_with_Dumbbells", "name": "Split Squat with Dumbbells", "equipment": "dumbbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] }
            ]
            """.trimIndent(),
        )
        assertTrue(result.isSuccess)

        // Acceptance 7: picker title (name) matches the cycle template label; catalogue id unchanged.
        val row = database.phoenixDatabaseQueries.selectExerciseById("One_Leg_Barbell_Squat").executeAsOne()
        assertEquals("One_Leg_Barbell_Squat", row.id)
        assertEquals("Bulgarian Split Squat", row.name)
        assertEquals("Bulgarian Split Squat", row.displayName)
        assertEquals("One Leg Barbell Squat", row.aliases)

        // Acceptance 1: sorts in the B section between Bradford/Rocky Presses and Butt Lift (Bridge).
        val orderedNames = database.phoenixDatabaseQueries.selectAllExercises().executeAsList().map { it.name }
        val bradford = orderedNames.indexOf("Bradford/Rocky Presses")
        val bulgarian = orderedNames.indexOf("Bulgarian Split Squat")
        val buttLift = orderedNames.indexOf("Butt Lift (Bridge)")
        assertTrue(bradford >= 0 && bulgarian > bradford, "Bulgarian Split Squat must follow Bradford/Rocky Presses")
        assertTrue(buttLift > bulgarian, "Bulgarian Split Squat must precede Butt Lift (Bridge)")

        // Acceptance 2: search matches the new name and still matches the pre-rename alias.
        repository.searchExercises("Bulgarian").test {
            assertTrue(awaitItem().any { it.id == "One_Leg_Barbell_Squat" })
            cancelAndIgnoreRemainingEvents()
        }
        repository.searchExercises("bulgarian split squat").test {
            assertTrue(awaitItem().any { it.id == "One_Leg_Barbell_Squat" })
            cancelAndIgnoreRemainingEvents()
        }
        repository.searchExercises("One Leg Barbell Squat").test {
            assertTrue(awaitItem().any { it.id == "One_Leg_Barbell_Squat" })
            cancelAndIgnoreRemainingEvents()
        }

        // Acceptance 3: 'split squat' includes this row (via its new name) and Split_Squat_with_Dumbbells.
        repository.searchExercises("split squat").test {
            val results = awaitItem()
            assertTrue(results.any { it.id == "One_Leg_Barbell_Squat" })
            assertTrue(results.any { it.id == "Split_Squat_with_Dumbbells" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reimport keeps overlay name and aliases and preserves user fields`() = runTest {
        // Seed an older row carrying user data and the pre-rename name.
        insertExercise(
            id = "One_Leg_Barbell_Squat",
            name = "One Leg Barbell Squat",
            muscleGroup = "Legs",
            equipment = "BARBELL",
            isFavorite = 1L,
            oneRepMaxKg = 42.5,
            timesPerformed = 9L,
            lastPerformed = 1_700_000_000_000L,
        )

        val json = """
            [
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": ["Hold dumbbells."], "category": "strength", "images": [] }
            ]
        """.trimIndent()

        assertTrue(importer.importFromFreeExerciseJson(json).isSuccess)
        // A second pass exercises updateCatalogExercise again (the re-import path).
        assertTrue(importer.importFromFreeExerciseJson(json).isSuccess)

        val row = database.phoenixDatabaseQueries.selectExerciseById("One_Leg_Barbell_Squat").executeAsOne()
        // Acceptance 4: not reset to the JSON name ("One Leg Barbell Squat") or to null on re-import.
        assertEquals("Bulgarian Split Squat", row.name)
        assertEquals("Bulgarian Split Squat", row.displayName)
        assertEquals("One Leg Barbell Squat", row.aliases)
        // User-owned fields preserved by updateCatalogExercise.
        assertEquals(1L, row.isFavorite)
        assertEquals(9L, row.timesPerformed)
        assertEquals(42.5, row.one_rep_max_kg)
        assertEquals(1_700_000_000_000L, row.lastPerformed)
    }

    @Test
    fun `findByName resolves the pre-rename name to the renamed catalogue row`() = runTest {
        importer.importFromFreeExerciseJson(
            """
            [
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] }
            ]
            """.trimIndent(),
        )

        // The renamed row resolves under both its new name and its pre-rename name (via aliases),
        // so the routine self-heal path cannot auto-create a duplicate custom exercise (#857).
        assertEquals("One_Leg_Barbell_Squat", repository.findByName("Bulgarian Split Squat")?.id)
        assertEquals("One_Leg_Barbell_Squat", repository.findByName("One Leg Barbell Squat")?.id)
    }

    @Test
    fun `findByIdOrName resolves the pre-rename name to the renamed row before an archived name match`() = runTest {
        importer.importFromFreeExerciseJson(
            """
            [
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] }
            ]
            """.trimIndent(),
        )
        insertExercise(id = "arch-ols", name = "One Leg Barbell Squat", muscleGroup = "Legs", equipment = "BAR", archived = 1L)

        // The archived row that still carries the pre-rename name must not win the exact-name
        // strategy over the renamed active row's alias, or template resolution forks exercise
        // identity (#857).
        assertEquals("One_Leg_Barbell_Squat", repository.findByIdOrName(null, "One Leg Barbell Squat")?.id)
        // A stale id plus the stored pre-rename name resolves the same way.
        assertEquals("One_Leg_Barbell_Squat", repository.findByIdOrName("gone-id", "One Leg Barbell Squat")?.id)
        // A usable id stays authoritative over any name or alias match.
        assertEquals("arch-ols", repository.findByIdOrName("arch-ols", "One Leg Barbell Squat")?.id)
    }

    @Test
    fun `active custom exercise sharing the pre-rename name wins over the stock row alias`() = runTest {
        importer.importFromFreeExerciseJson(
            """
            [
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] }
            ]
            """.trimIndent(),
        )
        insertExercise(id = "custom-1", name = "One Leg Barbell Squat", muscleGroup = "Back", equipment = "BAR", isCustom = 1L)

        // #857 review follow-up: custom creation permits duplicate names, so an active custom row
        // named like the stock row's pre-rename name must win over that row's alias.
        assertEquals("custom-1", repository.findByName("One Leg Barbell Squat")?.id)
        assertEquals("custom-1", repository.findByIdOrName(null, "One Leg Barbell Squat")?.id)
        assertEquals("custom-1", repository.findByIdOrName("gone-id", "One Leg Barbell Squat")?.id)
        // The renamed stock row stays reachable under its new name.
        assertEquals("One_Leg_Barbell_Squat", repository.findByName("Bulgarian Split Squat")?.id)
    }

    @Test
    fun `archived rows named bulgarian split squat and one leg barbell squat both remap onto the renamed row`() = runTest {
        importer.importFromFreeExerciseJson(
            """
            [
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] }
            ]
            """.trimIndent(),
        )
        insertExercise(id = "arch-bss", name = "Bulgarian Split Squat", muscleGroup = "Legs", equipment = "BAR", archived = 1L)
        insertExercise(id = "arch-ols", name = "One Leg Barbell Squat", muscleGroup = "Legs", equipment = "BAR", archived = 1L)
        insertPr(exerciseId = "arch-bss", exerciseName = "Bulgarian Split Squat", weight = 50.0, workoutMode = "OldSchool")
        insertPr(exerciseId = "arch-ols", exerciseName = "One Leg Barbell Squat", weight = 60.0, workoutMode = "NewSchool")

        importer.remapLegacyCatalogueIds()

        // Acceptance 6: both historical names fold onto the single renamed catalogue row.
        val remapped = database.phoenixDatabaseQueries.selectPersonalRecordsByExerciseId("One_Leg_Barbell_Squat").executeAsList()
        assertEquals(2, remapped.size)
        assertEquals(0, database.phoenixDatabaseQueries.selectPersonalRecordsByExerciseId("arch-bss").executeAsList().size)
        assertEquals(0, database.phoenixDatabaseQueries.selectPersonalRecordsByExerciseId("arch-ols").executeAsList().size)
    }

    @Test
    fun `bundled catalog source token is bumped for #857`() = runTest {
        // Acceptance 5's regression lock: this bump is what makes an install holding the
        // pre-#857 value run the importer exactly once on its next launch.
        assertEquals("free-exercise-db@unlicense-1+issue-857", ExerciseImporter.BUNDLED_CATALOG_SOURCE)
    }

    @Test
    fun `import gate skips the importer once the #857 catalog source token is stored`() = runTest {
        // Acceptance 5, exercised against the real gate in SqlDelightExerciseRepository.importExercises()
        // rather than a preferences round-trip: with the stored token equal to BUNDLED_CATALOG_SOURCE
        // the importer is skipped entirely, so the catalogue survives untouched and no further token
        // bump or re-import is ever needed. (The gate's open side runs ExerciseImporter.importExercises(),
        // whose bundled-JSON resource read cannot execute in android host tests, and the importer's
        // re-import behaviour is covered by `reimport keeps overlay name and aliases and preserves
        // user fields`.)
        importer.importFromFreeExerciseJson(
            """
            [
              { "id": "One_Leg_Barbell_Squat", "name": "One Leg Barbell Squat", "equipment": "barbell", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [], "instructions": [], "category": "strength", "images": [] }
            ]
            """.trimIndent(),
        )
        // Locally edit the row a re-import would rewrite, so an unexpected importer run is visible.
        database.phoenixDatabaseQueries.updateCatalogExercise(
            name = "Locally Edited Name",
            displayName = "Locally Edited Name",
            description = null,
            muscleGroup = "Legs",
            muscleGroups = "Legs",
            muscles = null,
            equipment = "BARBELL",
            movement = "strength",
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            aliases = "One Leg Barbell Squat",
            defaultCableConfig = "EITHER",
            isBodyweight = 0L,
            id = "One_Leg_Barbell_Squat",
        )

        val prefs = com.devil.phoenixproject.testutil.FakePreferencesManager()
        prefs.setExerciseCatalogSource(ExerciseImporter.BUNDLED_CATALOG_SOURCE)
        val gated = SqlDelightExerciseRepository(database, ExerciseImporter(database), prefs)

        assertTrue(gated.importExercises().isSuccess)
        assertEquals(ExerciseImporter.BUNDLED_CATALOG_SOURCE, prefs.getExerciseCatalogSource())
        assertEquals(
            "Locally Edited Name",
            database.phoenixDatabaseQueries.selectExerciseById("One_Leg_Barbell_Squat").executeAsOne().name,
        )
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

    private fun insertExercise(
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
        database.phoenixDatabaseQueries.insertExercise(
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
            one_rep_max_kg = oneRepMaxKg,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
