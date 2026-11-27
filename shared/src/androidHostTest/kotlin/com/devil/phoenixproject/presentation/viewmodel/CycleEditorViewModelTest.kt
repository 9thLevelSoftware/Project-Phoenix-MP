package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CycleEditorViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var repository: FakeTrainingCycleRepository
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var viewModel: CycleEditorViewModel

    @Before
    fun setup() {
        repository = FakeTrainingCycleRepository()
        userProfileRepository = FakeUserProfileRepository()
        viewModel = CycleEditorViewModel(repository, userProfileRepository)
    }

    /** Helper to create N rest day template items */
    private fun createRestDayTemplateItems(count: Int): List<CycleItem> = (1..count).map { CycleItem.Rest(id = generateUUID(), dayNumber = it) }

    @Test
    fun `initialize new cycle with template creates rest days`() = runTest {
        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(3))
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.items.size)
        assertTrue(viewModel.uiState.value.items.all { it is CycleItem.Rest })
    }

    @Test
    fun `addWorkoutDay appends item and tracks recent routine`() = runTest {
        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(1))
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
                    ),
                    orderIndex = 0,
                    programMode = ProgramMode.OldSchool,
                    weightPerCableKg = 20f,
                ),
            ),
        )

        viewModel.addWorkoutDay(routine)

        assertEquals(2, viewModel.uiState.value.items.size)
        assertEquals(1, viewModel.uiState.value.recentRoutineIds.size)
    }

    @Test
    fun `deleteItem and undo restore items`() = runTest {
        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(2))
        advanceUntilIdle()

        viewModel.deleteItem(0)
        assertEquals(1, viewModel.uiState.value.items.size)

        viewModel.undoDelete()
        assertEquals(2, viewModel.uiState.value.items.size)
    }

    @Test
    fun `saveCycle persists cycle to repository`() = runTest {
        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(2))
        advanceUntilIdle()

        val savedId = viewModel.saveCycle()
        assertNotNull(savedId)

        val stored = repository.getCycleById(savedId)
        assertNotNull(stored)
        assertEquals(2, stored.days.size)
    }

    /**
     * Issue #364 regression test: Cycles must be saved with the active profile's ID,
     * not the default "default" profile.
     */
    @Test
    fun `saveCycle uses active profile ID from UserProfileRepository`() = runTest {
        // Given: A non-default profile is active
        val customProfileId = "user-profile-123"
        userProfileRepository.setActiveProfileForTest(id = customProfileId)

        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(1))
        advanceUntilIdle()
        viewModel.updateCycleName("My Custom Cycle")

        // When: Saving the cycle
        val savedId = viewModel.saveCycle()
        assertNotNull(savedId)

        // Then: The cycle should have the active profile's ID, not "default"
        val stored = repository.getCycleById(savedId)
        assertNotNull(stored)
        assertEquals(customProfileId, stored.profileId, "Cycle should be owned by the active profile")
        assertEquals("My Custom Cycle", stored.name)
    }

    @Test
    fun `saveCycle defaults to 'default' when no active profile`() = runTest {
        // Given: No active profile is set (activeProfile.value is null)
        // userProfileRepository starts with no active profile

        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(1))
        advanceUntilIdle()

        // When: Saving the cycle
        val savedId = viewModel.saveCycle()
        assertNotNull(savedId)

        // Then: The cycle should fall back to "default" profile
        val stored = repository.getCycleById(savedId)
        assertNotNull(stored)
        assertEquals("default", stored.profileId)
    }

    /**
     * Issue #364 regression test: When editing an existing cycle, the original
     * profile ownership must be preserved, even if a different profile is now active.
     */
    @Test
    fun `editing cycle preserves original profile ownership`() = runTest {
        // Given: An existing cycle owned by "user-a"
        val originalProfileId = "user-a"
        val existingCycleId = "existing-cycle-123"
        val existingCycle = TrainingCycle(
            id = existingCycleId,
            name = "Original Cycle",
            description = "Test",
            days = listOf(
                CycleDay(
                    id = "day-1",
                    cycleId = existingCycleId,
                    dayNumber = 1,
                    name = "Day 1",
                    routineId = null,
                    isRestDay = true,
                ),
            ),
            createdAt = 1000L,
            isActive = false,
            profileId = originalProfileId,
        )
        repository.addCycle(existingCycle)

        // And: A different profile "user-b" is now active
        userProfileRepository.setActiveProfileForTest(id = "user-b")

        // When: Loading and editing the cycle
        viewModel.initialize(cycleId = existingCycleId)
        advanceUntilIdle()
        viewModel.updateCycleName("Renamed Cycle")

        // And: Saving the edited cycle
        val savedId = viewModel.saveCycle()
        assertNotNull(savedId)
        assertEquals(existingCycleId, savedId)

        // Then: The cycle should still be owned by "user-a", NOT "user-b"
        val stored = repository.getCycleById(savedId)
        assertNotNull(stored)
        assertEquals(originalProfileId, stored.profileId, "Editing should preserve original profile ownership")
        assertEquals("Renamed Cycle", stored.name)
    }

    @Test
    fun `editing cycle preserves template id and week number`() = runTest {
        val existingCycleId = "existing-531-cycle"
        val existingCycle = TrainingCycle(
            id = existingCycleId,
            name = "5/3/1 Week 3",
            description = "Original",
            days = listOf(
                CycleDay(
                    id = "day-1",
                    cycleId = existingCycleId,
                    dayNumber = 1,
                    name = "Bench",
                    routineId = "routine-bench",
                    isRestDay = false,
                ),
            ),
            createdAt = 1000L,
            isActive = false,
            profileId = "profile-531",
            templateId = "template_531",
            weekNumber = 3,
        )
        repository.addCycle(existingCycle)

        viewModel.initialize(cycleId = existingCycleId)
        advanceUntilIdle()
        viewModel.updateCycleName("Renamed 5/3/1")

        val savedId = viewModel.saveCycle()
        assertNotNull(savedId)
        assertEquals(existingCycleId, savedId)

        val stored = repository.getCycleById(savedId)
        assertNotNull(stored)
        assertEquals("Renamed 5/3/1", stored.name)
        assertEquals("template_531", stored.templateId)
        assertEquals(3, stored.weekNumber)
    }

    @Test
    fun `originalProfileId is captured when loading existing cycle`() = runTest {
        // Given: An existing cycle owned by a specific profile
        val originalProfileId = "profile-xyz"
        val existingCycle = TrainingCycle(
            id = "cycle-to-edit",
            name = "Test Cycle",
            description = null,
            days = emptyList(),
            createdAt = 1000L,
            isActive = false,
            profileId = originalProfileId,
        )
        repository.addCycle(existingCycle)

        // When: Loading the cycle for editing
        viewModel.initialize(cycleId = "cycle-to-edit")
        advanceUntilIdle()

        // Then: The originalProfileId should be captured in UI state
        assertEquals(originalProfileId, viewModel.uiState.value.originalProfileId)
    }

    @Test
    fun `new cycle does not have originalProfileId set`() = runTest {
        // When: Creating a new cycle
        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(1))
        advanceUntilIdle()

        // Then: originalProfileId should be null (it's a new cycle)
        assertEquals(null, viewModel.uiState.value.originalProfileId)
    }

    @Test
    fun `addWorkoutDay and changeRoutine keep exerciseNames synchronized`() = runTest {
        viewModel.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(0))
        advanceUntilIdle()

        val pushRoutine = Routine(
            id = "routine-push",
            name = "Push",
            exercises = listOf(
                RoutineExercise(
                    id = "rex-1",
                    exercise = Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "BAR"),
                    orderIndex = 0,
                    programMode = ProgramMode.OldSchool,
                    weightPerCableKg = 20f,
                ),
                RoutineExercise(
                    id = "rex-2",
                    exercise = Exercise(id = "fly", name = "Cable Fly", muscleGroup = "Chest", muscleGroups = "Chest", equipment = "HANDLES"),
                    orderIndex = 1,
                    programMode = ProgramMode.OldSchool,
                    weightPerCableKg = 10f,
                ),
            ),
        )
        val pullRoutine = Routine(
            id = "routine-pull",
            name = "Pull",
            exercises = listOf(
                RoutineExercise(
                    id = "rex-3",
                    exercise = Exercise(id = "row", name = "Seated Row", muscleGroup = "Back", muscleGroups = "Back", equipment = "HANDLES"),
                    orderIndex = 0,
                    programMode = ProgramMode.OldSchool,
                    weightPerCableKg = 25f,
                ),
            ),
        )

        viewModel.addWorkoutDay(pushRoutine)
        val added = viewModel.uiState.value.items.single() as CycleItem.Workout
        assertEquals(listOf("Bench Press", "Cable Fly"), added.exerciseNames)

        viewModel.changeRoutine(0, pullRoutine)
        val updated = viewModel.uiState.value.items.single() as CycleItem.Workout
        assertEquals("routine-pull", updated.routineId)
        assertEquals(listOf("Seated Row"), updated.exerciseNames)
    }

    @Test
    fun `concurrent saveCycle calls during getCycleById suspension execute updateCycle once`() = runTest {
        val saveGetGate = CompletableDeferred<Unit>()
        var blockGetCycleById = false
        var updateCalls = 0
        val existingCycle = TrainingCycle(
            id = "cycle-race-1",
            name = "Existing",
            description = null,
            days = listOf(
                CycleDay(
                    id = "day-1",
                    cycleId = "cycle-race-1",
                    dayNumber = 1,
                    name = "Rest",
                    routineId = null,
                    isRestDay = true,
                ),
            ),
            createdAt = 1000L,
            isActive = false,
            profileId = "default",
        )
        repository.addCycle(existingCycle)
        val gatedRepo = object : com.devil.phoenixproject.data.repository.TrainingCycleRepository by repository {
            override suspend fun getCycleById(cycleId: String): TrainingCycle? {
                if (blockGetCycleById) saveGetGate.await()
                return repository.getCycleById(cycleId)
            }

            override suspend fun updateCycle(cycle: TrainingCycle) {
                updateCalls++
                repository.updateCycle(cycle)
            }
        }
        val vm = CycleEditorViewModel(gatedRepo, userProfileRepository)
        vm.initialize(cycleId = "cycle-race-1")
        advanceUntilIdle()

        blockGetCycleById = true
        val first = async { vm.saveCycle() }
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.isSaving)

        val second = async { vm.saveCycle() }
        testScheduler.runCurrent()
        assertNull(second.await())

        saveGetGate.complete(Unit)
        assertEquals("cycle-race-1", first.await())
        assertEquals(1, updateCalls)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `saveCycle rethrows CancellationException and clears isSaving`() = runTest {
        val cancellingRepo = object : com.devil.phoenixproject.data.repository.TrainingCycleRepository by repository {
            override suspend fun saveCycle(cycle: TrainingCycle) {
                throw CancellationException("cancelled")
            }
        }
        val vm = CycleEditorViewModel(cancellingRepo, userProfileRepository)
        vm.initialize(cycleId = "new", templateItems = createRestDayTemplateItems(1))
        advanceUntilIdle()

        var caughtCancellation = false
        try {
            vm.saveCycle()
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
        assertFalse(vm.uiState.value.isSaving)
    }
}
