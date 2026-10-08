package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.presentation.viewmodel.AiRoutineEditorLoad.ExpiredDraft
import com.devil.phoenixproject.presentation.viewmodel.AiRoutineEditorLoad.ExistingRoutine
import com.devil.phoenixproject.presentation.viewmodel.AiRoutineEditorLoad.NewRoutine
import com.devil.phoenixproject.presentation.viewmodel.AiRoutineEditorLoad.StagedDraft
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Issue #1223 FE-C behavioral tests for the RoutineEditorScreen load decision and
 * the first-write-on-Save rule:
 *
 * - the generated-draft holder is consumed BEFORE the repository lookup, and a
 *   consumed staged draft skips both the lookup and the "new" branch;
 * - a missing holder + missing id yields the expired-draft recovery state with
 *   Save disabled (never autosave, never restore from persistence);
 * - a staged draft writes exactly once via the injected repository and only on
 *   the explicit Save step (the editor's single `viewModel.saveRoutine` call).
 */
class AiRoutineEditorDraftLoadTest {

    private fun draft(id: String = "draft-1", profileId: String = "profile-a") = Routine(
        id = id,
        name = "AI workout",
        profileId = profileId,
    )

    @Test
    fun `staged draft is consumed before the repository lookup and skips it entirely`() = runTest {
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft())
        val lookups = mutableListOf<String>()

        val load = loadRoutineEditorState(
            routineId = "draft-1",
            consumeGeneratedRoutine = holder::consumeGeneratedRoutine,
            getRoutineById = { id -> lookups += id; null },
        )

        val staged = assertIs<StagedDraft>(load)
        assertEquals("draft-1", staged.routine.id)
        assertEquals("profile-a", staged.routine.profileId)
        assertTrue(lookups.isEmpty(), "a consumed staged draft must skip getRoutineById")
    }

    @Test
    fun `missing holder and missing id yields the expired recovery state with Save disabled`() = runTest {
        val holder = AiRoutineDraftHolder()
        val lookups = mutableListOf<String>()

        val load = loadRoutineEditorState(
            routineId = "draft-1",
            consumeGeneratedRoutine = holder::consumeGeneratedRoutine,
            getRoutineById = { id -> lookups += id; null },
        )

        assertEquals(ExpiredDraft, load)
        assertEquals(listOf("draft-1"), lookups, "the repository lookup still runs after a holder miss")
        assertFalse(
            generatedDraftSaveEnabled(
                expiredDraft = true,
                stagedDraftProfileId = null,
                currentProfileId = "profile-a",
            ),
            "Save must be disabled in the expired-draft recovery state",
        )
    }

    @Test
    fun `existing routine resolves from the repository when no draft is staged`() = runTest {
        val holder = AiRoutineDraftHolder()
        val stored = Routine(id = "r-1", name = "Saved", profileId = "profile-a")

        val load = loadRoutineEditorState(
            routineId = "r-1",
            consumeGeneratedRoutine = holder::consumeGeneratedRoutine,
            getRoutineById = { id -> stored.takeIf { it.id == id } },
        )

        val existing = assertIs<ExistingRoutine>(load)
        assertEquals("Saved", existing.routine.name)
    }

    @Test
    fun `the new branch skips both the holder consume and the repository lookup`() = runTest {
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft())
        val lookups = mutableListOf<String>()

        val load = loadRoutineEditorState(
            routineId = "new",
            consumeGeneratedRoutine = { id -> lookups += "consume:$id"; holder.consumeGeneratedRoutine(id) },
            getRoutineById = { id -> lookups += "lookup:$id"; null },
        )

        assertEquals(NewRoutine, load)
        assertTrue(lookups.isEmpty(), "\"new\" must not consume the holder or query the repository")
    }

    @Test
    fun `a staged draft saves exactly once via the injected repository and only on explicit Save`() = runTest {
        val repository = WorkoutRepositoryWriteCounter(FakeWorkoutRepository())
        val holder = AiRoutineDraftHolder()
        holder.stageGeneratedRoutine(draft())

        val load = loadRoutineEditorState(
            routineId = "draft-1",
            consumeGeneratedRoutine = holder::consumeGeneratedRoutine,
            getRoutineById = repository::getRoutineById,
        )
        val staged = assertIs<StagedDraft>(load)

        // Loading/consuming the draft writes nothing: the user's editor Save is
        // the first and only write.
        assertEquals(0, repository.saveCalls, "no autosave on load")

        assertTrue(
            generatedDraftSaveEnabled(
                expiredDraft = false,
                stagedDraftProfileId = staged.routine.profileId,
                currentProfileId = "profile-a",
            ),
        )

        // Explicit Save — the editor's single `viewModel.saveRoutine(...)` funnels
        // into the same injected WorkoutRepository surface.
        repository.saveRoutine(staged.routine)
        assertEquals(1, repository.saveCalls, "exactly one write on explicit Save")
        assertEquals("draft-1", repository.getRoutineById("draft-1")?.id)

        // The holder is one-shot: nothing can write the staged draft twice.
        assertNull(holder.consumeGeneratedRoutine("draft-1"))
    }

    @Test
    fun `error and discard paths never reach the repository write surface`() = runTest {
        val repository = WorkoutRepositoryWriteCounter(FakeWorkoutRepository())
        val holder = AiRoutineDraftHolder()

        // Failed generation, rejected late response, discard, back: all of them
        // end with no staged draft and no load — and therefore no write.
        holder.invalidate()
        val load = loadRoutineEditorState(
            routineId = "draft-1",
            consumeGeneratedRoutine = holder::consumeGeneratedRoutine,
            getRoutineById = repository::getRoutineById,
        )

        assertEquals(ExpiredDraft, load)
        assertEquals(0, repository.saveCalls)
        assertNull(repository.getRoutineById("draft-1"))
    }
}

/**
 * Counts writes on the injected [WorkoutRepository] surface (the exact seam the
 * editor's `viewModel.saveRoutine` funnels into via RoutineFlowManager). Shared
 * with [AiRoutineGenerationCoordinatorTest] in this package.
 */
internal class WorkoutRepositoryWriteCounter(
    private val delegate: com.devil.phoenixproject.data.repository.WorkoutRepository = FakeWorkoutRepository(),
) : com.devil.phoenixproject.data.repository.WorkoutRepository by delegate {

    var saveCalls = 0
        private set

    override suspend fun saveRoutine(routine: Routine) {
        saveCalls += 1
        delegate.saveRoutine(routine)
    }
}
