package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.CableConfiguration
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CycleEditorViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var repository: FakeTrainingCycleRepository
    private lateinit var viewModel: CycleEditorViewModel

    @Before
    fun setup() {
        repository = FakeTrainingCycleRepository()
        viewModel = CycleEditorViewModel(repository)
    }

    @Test
    fun `initialize new cycle creates default rest days`() = runTest {
        viewModel.initialize(cycleId = "new", initialDayCount = 3)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.items.size)
        assertTrue(viewModel.uiState.value.items.all { it is CycleItem.Rest })
    }

    @Test
    fun `addWorkoutDay appends item and tracks recent routine`() = runTest {
        viewModel.initialize(cycleId = "new", initialDayCount = 1)
        advanceUntilIdle()

        val routine = Routine(
            id = "routine-1",
            name = "Push",
            exercises = listOf(
                RoutineExercise(
                    id = "rex-1",
                    exercise = Exercise(
                        id = "bench",
                        name = "Bench Press",
                        muscleGroup = "Chest",
                        muscleGroups = "Chest",
                        equipment = "BAR",
                        defaultCableConfig = CableConfiguration.DOUBLE
                    ),
                    cableConfig = CableConfiguration.DOUBLE,
                    orderIndex = 0,
                    programMode = ProgramMode.OldSchool,
                    weightPerCableKg = 20f
                )
            )
        )

        viewModel.addWorkoutDay(routine)

        assertEquals(2, viewModel.uiState.value.items.size)
        assertEquals(1, viewModel.uiState.value.recentRoutineIds.size)
    }

    @Test
    fun `deleteItem and undo restore items`() = runTest {
        viewModel.initialize(cycleId = "new", initialDayCount = 2)
        advanceUntilIdle()

        viewModel.deleteItem(0)
        assertEquals(1, viewModel.uiState.value.items.size)

        viewModel.undoDelete()
        assertEquals(2, viewModel.uiState.value.items.size)
    }

    @Test
    fun `saveCycle persists cycle to repository`() = runTest {
        viewModel.initialize(cycleId = "new", initialDayCount = 2)
        advanceUntilIdle()

        val savedId = viewModel.saveCycle()
        assertNotNull(savedId)

        val stored = repository.getCycleById(savedId)
        assertNotNull(stored)
        assertEquals(2, stored.days.size)
    }
}
