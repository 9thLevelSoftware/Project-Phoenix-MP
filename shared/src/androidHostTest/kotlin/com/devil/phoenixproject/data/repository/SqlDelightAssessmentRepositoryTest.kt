package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Regression guard: VBT assessment estimates 1RM in total weight (both cables) but
 * [Exercise.oneRepMaxKg] is stored per-cable. [SqlDelightAssessmentRepository.saveAssessmentSession]
 * must divide by two before calling [ExerciseRepository.updateOneRepMax].
 */
class SqlDelightAssessmentRepositoryTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var exerciseRepository: SqlDelightExerciseRepository
    private lateinit var workoutRepository: SqlDelightWorkoutRepository
    private lateinit var repository: SqlDelightAssessmentRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = SqlDelightExerciseRepository(database, com.devil.phoenixproject.data.local.ExerciseImporter(database))
        workoutRepository = SqlDelightWorkoutRepository(database, exerciseRepository)
        repository = SqlDelightAssessmentRepository(database, workoutRepository, exerciseRepository)
    }

    @Test
    fun `saveAssessmentSession stores per-cable 1RM from total estimate`() = runTest {
        insertExercise("ex-bench", "Bench Press")

        repository.saveAssessmentSession(
            exerciseId = "ex-bench",
            exerciseName = "Bench Press",
            estimatedOneRepMaxKg = 100f,
            loadVelocityDataJson = "[]",
            userOverrideKg = null,
            totalReps = 15,
            durationMs = 60_000L,
            weightPerCableKg = 30f,
            profileId = "default",
        )

        val exercise = exerciseRepository.getExerciseById("ex-bench")
        assertNotNull(exercise)
        assertEquals(50f, exercise.oneRepMaxKg, "100kg total estimate must be stored as 50kg per cable")
    }

    @Test
    fun `saveAssessmentSession stores per-cable 1RM from total user override`() = runTest {
        insertExercise("ex-squat", "Squat")

        repository.saveAssessmentSession(
            exerciseId = "ex-squat",
            exerciseName = "Squat",
            estimatedOneRepMaxKg = 90f,
            loadVelocityDataJson = "[]",
            userOverrideKg = 120f,
            totalReps = 12,
            durationMs = 45_000L,
            weightPerCableKg = 40f,
            profileId = "default",
        )

        val exercise = exerciseRepository.getExerciseById("ex-squat")
        assertNotNull(exercise)
        assertEquals(60f, exercise.oneRepMaxKg, "120kg total override must be stored as 60kg per cable")
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
        )
    }
}
