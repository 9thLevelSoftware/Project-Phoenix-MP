package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FP-6: a profile switch while a set is running would split the running
 * workout's writes across two profiles. The switch must be refused, and the
 * refusal must be visible — the sheet stays open carrying an error, never a
 * silent no-op.
 *
 * Every refusal here returns before any coroutine is launched, so these tests
 * need no Main dispatcher.
 */
class ProfileSwitchDuringWorkoutTest {
    private fun viewModelWithOpenSwitcher(): Pair<ProfileSwitcherViewModel, FakeUserProfileRepository> {
        val profiles = FakeUserProfileRepository()
        profiles.seedReadyProfileForTest("a", name = "A")
        profiles.seedReadyProfileForTest("b", name = "B")
        profiles.emitReadyForTest("a")
        val viewModel = ProfileSwitcherViewModel(profiles)
        viewModel.openSwitcher()
        return viewModel to profiles
    }

    @Test
    fun switchIsRefusedWithAVisibleMessageWhileASetIsActive() {
        val (viewModel, profiles) = viewModelWithOpenSwitcher()

        viewModel.switchProfile("b", WorkoutState.Active)

        val state = viewModel.uiState.value
        assertEquals(ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT, state.error)
        assertTrue(state.showSwitcher, "the sheet must stay open so the user sees the refusal")
        assertNull(state.operation, "no switch operation may start")
        assertTrue(profiles.setActiveProfileRequests.isEmpty(), "the repository must not be asked to switch")
    }

    /**
     * GitHub #854 (codex 4078341739): Add Profile activates the new profile, so
     * it is a switch too and must not bypass the mid-workout refusal.
     */
    @Test
    fun createAndActivateIsRefusedWithAVisibleMessageWhileASetIsActive() {
        val (viewModel, profiles) = viewModelWithOpenSwitcher()
        viewModel.openAddDialog()

        viewModel.createAndActivateProfile("New", 3, WorkoutState.Active)

        val state = viewModel.uiState.value
        assertEquals(ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT, state.error)
        assertTrue(state.showAddDialog, "the add dialog must stay open so the user sees the refusal")
        assertNull(state.operation, "no create operation may start")
        assertTrue(profiles.createAndActivateRequests.isEmpty(), "the repository must not be asked to create")
    }

    @Test
    fun switchIsRefusedForEveryNonIdleWorkoutState() {
        listOf(
            WorkoutState.Initializing,
            WorkoutState.Countdown(3),
            WorkoutState.Active,
            WorkoutState.Resting(
                restSecondsRemaining = 30,
                nextExerciseName = "Row",
                isLastExercise = false,
                currentSet = 1,
                totalSets = 3,
            ),
        ).forEach { workoutState ->
            val (viewModel, profiles) = viewModelWithOpenSwitcher()

            viewModel.switchProfile("b", workoutState)

            assertEquals(
                ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT,
                viewModel.uiState.value.error,
                "workoutState=$workoutState",
            )
            assertTrue(profiles.setActiveProfileRequests.isEmpty(), "workoutState=$workoutState")
        }
    }
}
