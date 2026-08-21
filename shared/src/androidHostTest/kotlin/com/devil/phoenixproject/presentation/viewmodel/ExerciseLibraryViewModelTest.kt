package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.ExerciseImageEntity
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ExerciseLibraryViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var repository: FakeExerciseRepository
    private lateinit var viewModel: ExerciseLibraryViewModel

    @Before
    fun setup() {
        repository = FakeExerciseRepository()
        viewModel = ExerciseLibraryViewModel(repository)
    }

    @Test
    fun `loads exercises and filters by search query`() = runTest {
        repository.addExercise(
            Exercise(
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
        )
        repository.addExercise(
            Exercise(
                name = "Squat",
                muscleGroup = "Legs",
                muscleGroups = "Legs",
                equipment = "BAR",
            ),
        )

        advanceUntilIdle()
        viewModel.setSearchQuery("bench")
        advanceUntilIdle()

        assertEquals(2, viewModel.exercises.value.size)
        assertEquals(1, viewModel.filteredExercises.value.size)
        assertEquals("Bench Press", viewModel.filteredExercises.value.first().name)
    }

    @Test
    fun `filters by muscle group`() = runTest {
        repository.addExercise(
            Exercise(
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
        )
        repository.addExercise(
            Exercise(
                name = "Squat",
                muscleGroup = "Legs",
                muscleGroups = "Legs",
                equipment = "BAR",
            ),
        )

        advanceUntilIdle()
        viewModel.setMuscleGroupFilter("Legs")
        advanceUntilIdle()

        assertEquals(1, viewModel.filteredExercises.value.size)
        assertEquals("Squat", viewModel.filteredExercises.value.first().name)
    }

    @Test
    fun `loadImagesForExercise caches results`() = runTest {
        val exercise = Exercise(
            id = "ex-1",
            name = "Bench Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            equipment = "BAR",
        )
        repository.addExercise(exercise)
        repository.addImages(
            exerciseId = "ex-1",
            imageList = listOf(
                ExerciseImageEntity(
                    id = 1,
                    exerciseId = "ex-1",
                    url = "https://example.com/image.jpg",
                    sortOrder = 0,
                ),
            ),
        )

        viewModel.loadImagesForExercise("ex-1")
        advanceUntilIdle()

        assertEquals(1, viewModel.getImages("ex-1").size)
    }
}
