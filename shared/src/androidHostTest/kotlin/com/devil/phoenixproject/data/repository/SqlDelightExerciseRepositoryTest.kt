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
            archived = 0L,
            isFavorite = isFavorite,
            isCustom = isCustom,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = defaultCableConfig,
            one_rep_max_kg = oneRepMaxKg,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
