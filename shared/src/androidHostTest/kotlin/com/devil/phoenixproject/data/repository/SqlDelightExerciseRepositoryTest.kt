package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightExerciseRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightExerciseRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightExerciseRepository(database, ExerciseImporter(database))
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
                equipment = ""
            )
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
    fun `getVideos returns exercise videos`() = runTest {
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")
        database.vitruvianDatabaseQueries.insertVideo(
            exerciseId = "ex-1",
            angle = "front",
            videoUrl = "https://example.com/video.mp4",
            thumbnailUrl = "https://example.com/thumb.jpg",
            isTutorial = 0L
        )

        val videos = repository.getVideos("ex-1")

        assertEquals(1, videos.size)
        assertEquals("front", videos.first().angle)
    }

    private fun insertExercise(
        id: String,
        name: String,
        muscleGroup: String,
        equipment: String,
        defaultCableConfig: String = "DOUBLE",
        isFavorite: Long = 0L,
        isCustom: Long = 0L,
        oneRepMaxKg: Double? = null
    ) {
        database.vitruvianDatabaseQueries.insertExercise(
            id = id,
            name = name,
            description = null,
            created = 0L,
            muscleGroup = muscleGroup,
            muscleGroups = muscleGroup,
            muscles = null,
            equipment = equipment,
            movement = null,
            sidedness = null,
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
            one_rep_max_kg = oneRepMaxKg
        )
    }
}
