package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.manager.WorkoutCoordinator
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FP-6: a profile switch while a workout session is live would split that
 * workout's writes across two profiles. The switch must be refused for the whole
 * session, and the refusal must be visible: the sheet stays open carrying an
 * error, never a silent no-op.
 *
 * The ViewModel takes the session-scoped `WorkoutCoordinator.isInWorkoutSession`
 * (EnhancedMainScreen passes it; see ProfileSwitchSessionGuardSourceTest), not
 * `workoutState`, because `workoutState` is Idle between routine sets and during
 * the Just Lift rest countdown while the session is still live (codex 4080108011).
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
    fun switchIsRefusedWithAVisibleMessageWhileASessionIsLive() {
        val (viewModel, profiles) = viewModelWithOpenSwitcher()

        viewModel.switchProfile("b", inWorkoutSession = true)

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
    fun createAndActivateIsRefusedWithAVisibleMessageWhileASessionIsLive() {
        val (viewModel, profiles) = viewModelWithOpenSwitcher()
        viewModel.openAddDialog()

        viewModel.createAndActivateProfile("New", 3, inWorkoutSession = true)

        val state = viewModel.uiState.value
        assertEquals(ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT, state.error)
        assertTrue(state.showAddDialog, "the add dialog must stay open so the user sees the refusal")
        assertNull(state.operation, "no create operation may start")
        assertTrue(profiles.createAndActivateRequests.isEmpty(), "the repository must not be asked to create")
    }

    /** Every active-set state is part of the session. */
    @Test
    fun theSessionSignalCoversEveryActiveSetState() = runTest {
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
            val coordinator = WorkoutCoordinator()
            coordinator._workoutState.value = workoutState
            assertTrue(coordinator.isInWorkoutSession.first(), "workoutState=$workoutState")
        }
    }

    /**
     * GitHub #854 (codex 4080108011): between routine sets the screen sits on
     * SetReady with workoutState Idle. The session is still live, so a switch must
     * be refused; the next set would otherwise take a lease for the new profile.
     */
    @Test
    fun switchIsRefusedOnSetReadyBetweenRoutineSets() = runTest {
        val coordinator = WorkoutCoordinator()
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = 0,
            setIndex = 1,
            adjustedWeight = 25f,
            adjustedReps = 10,
        )
        val (viewModel, profiles) = viewModelWithOpenSwitcher()

        viewModel.switchProfile("b", coordinator.isInWorkoutSession.first())

        assertEquals(ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT, viewModel.uiState.value.error)
        assertTrue(profiles.setActiveProfileRequests.isEmpty())
    }

    /** Same gap for Just Lift: workoutState is Idle while the rest countdown runs. */
    @Test
    fun createIsRefusedDuringTheJustLiftRestCountdown() = runTest {
        val coordinator = WorkoutCoordinator()
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._justLiftRestCountdown.value = 20
        val (viewModel, profiles) = viewModelWithOpenSwitcher()
        viewModel.openAddDialog()

        viewModel.createAndActivateProfile("New", 3, coordinator.isInWorkoutSession.first())

        assertEquals(ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT, viewModel.uiState.value.error)
        assertTrue(profiles.createAndActivateRequests.isEmpty())
    }

    /** Once the session truly ends, nothing blocks the switch. */
    @Test
    fun theSessionSignalClearsOnceTheSessionEnds() = runTest {
        val coordinator = WorkoutCoordinator()
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
        coordinator._justLiftRestCountdown.value = null
        assertFalse(coordinator.isInWorkoutSession.first(), "an ended session must not block switching")
    }
}
