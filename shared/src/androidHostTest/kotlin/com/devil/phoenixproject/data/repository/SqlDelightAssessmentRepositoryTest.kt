package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightAssessmentRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var exerciseRepository: SqlDelightExerciseRepository
    private lateinit var workoutRepository: SqlDelightWorkoutRepository
    private lateinit var repository: SqlDelightAssessmentRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = SqlDelightExerciseRepository(database, com.devil.phoenixproject.data.local.ExerciseImporter(database))
        workoutRepository = SqlDelightWorkoutRepository(database, exerciseRepository)
        repository = SqlDelightAssessmentRepository(database, workoutRepository, exerciseRepository)
        insertExercise(id = "bench-press", name = "Bench Press")
    }

    @Test
    fun `saveAssessmentSession stores per-cable 1RM from total assessment estimate`() = runTest {
        repository.saveAssessmentSession(
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            userOverrideKg = null,
            totalReps = 9,
            durationMs = 60_000L,
            weightPerCableKg = 30f,
        )

        val exercise = exerciseRepository.getExerciseById("bench-press")
        assertEquals(50f, exercise?.oneRepMaxKg, "Total 100kg assessment must persist as 50kg per cable")
    }

    @Test
    fun `saveAssessmentSession converts user override total to per-cable 1RM`() = runTest {
        repository.saveAssessmentSession(
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            userOverrideKg = 120f,
            totalReps = 9,
            durationMs = 60_000L,
            weightPerCableKg = 30f,
        )

        val exercise = exerciseRepository.getExerciseById("bench-press")
        assertEquals(60f, exercise?.oneRepMaxKg, "Total 120kg override must persist as 60kg per cable")
    }

    private fun insertExercise(id: String, name: String) {
        database.vitruvianDatabaseQueries.insertExercise(
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
