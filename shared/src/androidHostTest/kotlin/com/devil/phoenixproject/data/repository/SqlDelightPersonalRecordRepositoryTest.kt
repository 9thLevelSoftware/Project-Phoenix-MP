package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `updatePRsIfBetter inserts weight and volume PRs`() = runTest {
        val result = repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPerCableKg = 50f,
            reps = 5,
            workoutMode = "OldSchool",
            timestamp = 1000L
        ).getOrThrow()

        assertTrue(result.contains(PRType.MAX_WEIGHT))
        assertTrue(result.contains(PRType.MAX_VOLUME))

        val weightPr = repository.getWeightPR("bench", "OldSchool")
        assertEquals(50f, weightPr?.weightPerCableKg)
    }

    @Test
    fun `updatePRsIfBetter updates volume without replacing weight`() = runTest {
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPerCableKg = 50f,
            reps = 5,
            workoutMode = "OldSchool",
            timestamp = 1000L
        )

        val result = repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPerCableKg = 45f,
            reps = 10,
            workoutMode = "OldSchool",
            timestamp = 2000L
        ).getOrThrow()

        assertTrue(result.contains(PRType.MAX_VOLUME))
        val bestWeight = repository.getBestWeightPR("bench")
        assertEquals(50f, bestWeight?.weightPerCableKg)
    }

    @Test
    fun `getBestPR returns highest weight`() = runTest {
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPerCableKg = 40f,
            reps = 8,
            workoutMode = "OldSchool",
            timestamp = 1000L
        )
        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPerCableKg = 60f,
            reps = 3,
            workoutMode = "OldSchool",
            timestamp = 2000L
        )

        val best = repository.getBestPR("bench")
        assertEquals(60f, best?.weightPerCableKg)
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
            one_rep_max_kg = null
        )
    }
}
