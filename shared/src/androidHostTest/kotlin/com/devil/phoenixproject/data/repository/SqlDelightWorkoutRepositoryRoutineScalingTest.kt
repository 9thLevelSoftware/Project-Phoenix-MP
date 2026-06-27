package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Round-trip test for RoutineExercise.scalingBasis persistence (issue #517 Phase 3).
 * Verifies that the new nullable TEXT column survives a save → read cycle via the
 * repository, including the ScalingBasis enum serialisation/deserialisation.
 */
class SqlDelightWorkoutRepositoryRoutineScalingTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var exerciseRepository: FakeExerciseRepository
    private lateinit var repository: SqlDelightWorkoutRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        exerciseRepository = FakeExerciseRepository()
        repository = SqlDelightWorkoutRepository(database, exerciseRepository)
    }

    @Test
    fun `routine exercise persists and reads back scalingBasis ESTIMATED_1RM`() = runTest {
        val exerciseId = "bench-press-scaling"
        val exercise = Exercise(
            id = exerciseId,
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "Cable",
        )
        exerciseRepository.addExercise(exercise)

        val routineId = "routine-scaling-basis"
        val routineExercise = RoutineExercise(
            id = "rex-scaling-basis",
            exercise = exercise,
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 50f,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
        )
        val routine = Routine(
            id = routineId,
            name = "Scaling Basis Test Routine",
            exercises = listOf(routineExercise),
        )

        repository.saveRoutine(routine)

        val loaded = repository.getRoutineById(routineId)
        assertNotNull(loaded, "Routine should be found after save")
        assertEquals(1, loaded.exercises.size, "Routine should have 1 exercise")

        val loadedExercise = loaded.exercises.first { it.id == routineExercise.id }
        assertEquals(ScalingBasis.ESTIMATED_1RM, loadedExercise.scalingBasis,
            "scalingBasis should round-trip as ESTIMATED_1RM")
    }

    @Test
    fun `routine exercise with null scalingBasis reads back as null`() = runTest {
        val exerciseId = "squat-scaling-null"
        val exercise = Exercise(
            id = exerciseId,
            name = "Squat",
            muscleGroup = "Legs",
            equipment = "Cable",
        )
        exerciseRepository.addExercise(exercise)

        val routineId = "routine-scaling-null"
        val routineExercise = RoutineExercise(
            id = "rex-scaling-null",
            exercise = exercise,
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 30f,
            scalingBasis = null,
        )
        val routine = Routine(
            id = routineId,
            name = "Null Scaling Basis Routine",
            exercises = listOf(routineExercise),
        )

        repository.saveRoutine(routine)

        val loaded = repository.getRoutineById(routineId)
        assertNotNull(loaded, "Routine should be found after save")
        val loadedExercise = loaded.exercises.first { it.id == routineExercise.id }
        assertEquals(null, loadedExercise.scalingBasis,
            "null scalingBasis should round-trip as null")
    }
}
