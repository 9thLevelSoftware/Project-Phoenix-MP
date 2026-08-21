package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.VitruvianDatabase
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

    private lateinit var database: VitruvianDatabase
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
    fun `updateOneRepMax is exposed by getExercisesWithOneRepMax`() = runTest {
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")

        repository.updateOneRepMax("ex-1", 120f)

        repository.getExercisesWithOneRepMax().test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals(120f, results.first().oneRepMaxKg)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getImages returns exercise demonstration stills`() = runTest {
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "barbell")
        database.vitruvianDatabaseQueries.insertImage(
            exerciseId = "ex-1",
            url = "https://example.com/0.jpg",
            sortOrder = 0L,
        )
        database.vitruvianDatabaseQueries.insertImage(
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
        assertEquals(42.5f, plank.oneRepMaxKg)
        assertEquals(9, plank.timesPerformed)
        val row = database.vitruvianDatabaseQueries.selectExerciseById("Plank").executeAsOne()
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

        val byName = database.vitruvianDatabaseQueries.findExerciseByName("Plank").executeAsOne()
        val byMuscle = database.vitruvianDatabaseQueries
            .findExerciseByNameAndMuscle("Plank", "Core")
            .executeAsOne()
        val byCase = database.vitruvianDatabaseQueries
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
        database.vitruvianDatabaseQueries.insertRecord(
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
        database.vitruvianDatabaseQueries.insertSession(
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
        val session = database.vitruvianDatabaseQueries.selectSessionById("session-legacy-bench").executeAsOne()
        assertEquals(replacement, session.exerciseId)
        val prs = database.vitruvianDatabaseQueries.selectAllPRsForExercise(replacement, "default").executeAsList()
        assertEquals(1, prs.size)
        assertEquals(80.0, prs.single().weight)
        val exercise = repository.getExerciseById(replacement)
        assertNotNull(exercise)
        assertEquals(true, exercise.isFavorite)
        assertEquals(100.0f, exercise.oneRepMaxKg)
        assertEquals(4, exercise.timesPerformed)

        importer.remapLegacyCatalogueIds()
        val afterSecondPass = repository.getExerciseById(replacement)
        assertNotNull(afterSecondPass)
        assertEquals(4, afterSecondPass.timesPerformed)
        assertEquals(true, afterSecondPass.isFavorite)
        assertEquals(100.0f, afterSecondPass.oneRepMaxKg)
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
        database.vitruvianDatabaseQueries.insertExercise(
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
