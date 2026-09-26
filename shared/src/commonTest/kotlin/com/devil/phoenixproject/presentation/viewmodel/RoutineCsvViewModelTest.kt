package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import com.devil.phoenixproject.domain.csv.RoutineCsvImportAction
import com.devil.phoenixproject.domain.csv.RoutineCsvImportMode
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #772: preview first, write only on confirm, never a plan the user did not see. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineCsvViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var workouts: FakeWorkoutRepository
    private lateinit var viewModel: RoutineCsvViewModel
    private val bench = Exercise(id = "bench-id", name = "Bench Press", muscleGroup = "Chest")
    private val profileId = "default"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workouts = FakeWorkoutRepository()
        val exercises = FakeExerciseRepository().apply { addExercise(bench) }
        val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest() }
        viewModel = RoutineCsvViewModel(workouts, exercises, profiles, nowMs = { 1_000L })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun csv(vararg rows: String) =
        (listOf(RoutineCsvFormat.VERSION_LINE, RoutineCsvFormat.COLUMNS.joinToString(",")) + rows).joinToString("\n")

    private fun existing(name: String) = Routine(
        id = "existing-$name",
        name = name,
        profileId = profileId,
        exercises = listOf(
            RoutineExercise(id = "e", exercise = bench, orderIndex = 0, setReps = listOf(5), weightPerCableKg = 50f),
        ),
    )

    @Test
    fun previewWritesNothingAndConfirmWritesTheRoutine() = runTest(dispatcher) {
        viewModel.previewImport(csv(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"))
        advanceUntilIdle()

        val preview = assertIs<RoutineCsvImportUiState.Preview>(viewModel.importState.value)
        assertEquals(RoutineCsvImportAction.CREATE, preview.plan.routines.single().action)
        assertTrue(workouts.getAllRoutines(profileId).first().isEmpty())

        viewModel.confirmImport()
        advanceUntilIdle()

        assertEquals(1, assertIs<RoutineCsvImportUiState.Imported>(viewModel.importState.value).routineCount)
        assertEquals("Push", workouts.getAllRoutines(profileId).first().single().name)
    }

    @Test
    fun aMatchingRoutineStartsOnCopiesAndOverwriteIsAnExplicitChoice() = runTest(dispatcher) {
        workouts.addRoutine(existing("Push"))
        viewModel.previewImport(csv(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"))
        advanceUntilIdle()

        val preview = assertIs<RoutineCsvImportUiState.Preview>(viewModel.importState.value)
        assertEquals(RoutineCsvImportMode.CREATE_COPIES, preview.plan.mode)
        assertEquals("Push (Copy)", preview.plan.routines.single().name)

        viewModel.selectMode(RoutineCsvImportMode.OVERWRITE_MATCHING)
        advanceUntilIdle()
        viewModel.confirmImport()
        advanceUntilIdle()

        val stored = workouts.getAllRoutines(profileId).first().single()
        assertEquals("existing-Push", stored.id)
        assertEquals(8, stored.exercises.single().setReps.single())
    }

    @Test
    fun confirmingBeforeTheNewlyPickedModeIsPlannedWritesNothing() = runTest(dispatcher) {
        workouts.addRoutine(existing("Push"))
        viewModel.previewImport(csv(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"))
        advanceUntilIdle()
        viewModel.selectMode(RoutineCsvImportMode.OVERWRITE_MATCHING)
        advanceUntilIdle()

        // Back to copies, and Import tapped before that plan is built.
        viewModel.selectMode(RoutineCsvImportMode.CREATE_COPIES)
        viewModel.selectMode(RoutineCsvImportMode.OVERWRITE_MATCHING)
        viewModel.selectMode(RoutineCsvImportMode.CREATE_COPIES)
        assertEquals(RoutineCsvImportMode.CREATE_COPIES, assertIs<RoutineCsvImportUiState.Preview>(viewModel.importState.value).replanningTo)
        viewModel.confirmImport()
        advanceUntilIdle()

        val preview = assertIs<RoutineCsvImportUiState.Preview>(viewModel.importState.value)
        assertEquals(RoutineCsvImportMode.CREATE_COPIES, preview.plan.mode, "the last pick wins")
        assertNull(preview.replanningTo)
        assertEquals(5, workouts.getRoutineById("existing-Push")?.exercises?.single()?.setReps?.single(), "nothing was overwritten")
        assertEquals(1, workouts.getAllRoutines(profileId).first().size, "nothing was written")
    }

    @Test
    fun aChangeSinceThePreviewShowsTheRefreshedPlanInsteadOfWriting() = runTest(dispatcher) {
        viewModel.previewImport(csv(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"))
        advanceUntilIdle()
        workouts.addRoutine(existing("Push")) // created elsewhere after the preview

        viewModel.confirmImport()
        advanceUntilIdle()

        val refreshed = assertIs<RoutineCsvImportUiState.Preview>(viewModel.importState.value)
        assertTrue(refreshed.refreshed)
        assertEquals(1, workouts.getAllRoutines(profileId).first().size, "nothing was written")
    }

    @Test
    fun aSameNamedRoutineThatReplacedTheTargetIsNotOverwrittenWithoutANewPreview() = runTest(dispatcher) {
        workouts.addRoutine(existing("Push"))
        viewModel.previewImport(csv(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"))
        advanceUntilIdle()
        viewModel.selectMode(RoutineCsvImportMode.OVERWRITE_MATCHING)
        advanceUntilIdle()
        // Sync deletes the previewed target and brings in another routine with the same name.
        workouts.deleteRoutine("existing-Push")
        workouts.addRoutine(existing("Push").copy(id = "replacement"))

        viewModel.confirmImport()
        advanceUntilIdle()

        val refreshed = assertIs<RoutineCsvImportUiState.Preview>(viewModel.importState.value)
        assertTrue(refreshed.refreshed)
        assertEquals("replacement", refreshed.plan.routines.single().targetRoutineId)
        assertEquals(5, workouts.getRoutineById("replacement")?.exercises?.single()?.setReps?.single(), "nothing was written")
    }

    @Test
    fun aFileOverTheSizeLimitIsReportedWithoutParsing() = runTest(dispatcher) {
        viewModel.onFileTooLarge()

        val unreadable = assertIs<RoutineCsvImportUiState.Unreadable>(viewModel.importState.value)
        assertEquals(RoutineCsvFormat.TOO_LARGE_MESSAGE, unreadable.issues.single().message)
    }

    @Test
    fun anUnreadableFileIsReportedAndDismissClearsIt() = runTest(dispatcher) {
        viewModel.previewImport("not a routine file")
        advanceUntilIdle()
        assertIs<RoutineCsvImportUiState.Unreadable>(viewModel.importState.value)

        viewModel.dismissImport()
        assertNull(viewModel.importState.value)
    }

    @Test
    fun exportReportsWhatWouldBeLost() = runTest(dispatcher) {
        val routine = existing("Push")
        viewModel.exportRoutine(routine)
        advanceUntilIdle()
        assertIs<RoutineCsvExportUiState.Ready>(viewModel.exportState.value)

        // Issue #896: advanced settings export in v2, so only a routine no CSV row can hold is
        // still refused — here an exercise without sets.
        viewModel.exportRoutine(
            routine.copy(exercises = routine.exercises.map { it.copy(setReps = emptyList(), setWeightsPerCableKg = emptyList()) }),
        )
        advanceUntilIdle()
        val blocked = assertIs<RoutineCsvExportUiState.Blocked>(viewModel.exportState.value)
        assertEquals("Push", blocked.routineName)
    }

    @Test
    fun advancedSettingsExportInsteadOfBeingRefused() = runTest(dispatcher) {
        val routine = existing("Push")
        viewModel.exportRoutine(routine.copy(exercises = routine.exercises.map { it.copy(stopAtTop = true) }))
        advanceUntilIdle()
        assertIs<RoutineCsvExportUiState.Ready>(viewModel.exportState.value)
    }
}
