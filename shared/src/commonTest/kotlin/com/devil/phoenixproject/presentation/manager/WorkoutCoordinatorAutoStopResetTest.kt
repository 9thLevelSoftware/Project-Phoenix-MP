package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.AutoStopUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * F7 (stall-detection audit): WorkoutCoordinator.resetAutoStopState() is the single
 * source of truth for auto-stop/stall/defer resets. Both ActiveSessionEngine and
 * RoutineFlowManager delegate to it — previously each maintained a hand-copied list
 * and the RoutineFlowManager copy could not clear the (then engine-private)
 * verbal-cue defer deadline.
 */
class WorkoutCoordinatorAutoStopResetTest {

    @Test
    fun `resetAutoStopState clears every auto-stop, stall, and defer field`() {
        val coordinator = WorkoutCoordinator()

        coordinator.autoStopStartTime = 12_345L
        coordinator.autoStopTriggered = true
        coordinator.autoStopStopRequested = true
        coordinator.stallStartTime = 67_890L
        coordinator.isCurrentlyStalled = true
        coordinator.stallArmedByDeload = true
        coordinator.deferAutoStopDeadlineMs = 99_999L
        coordinator._autoStopState.value = AutoStopUiState(
            isActive = true,
            progress = 0.5f,
            secondsRemaining = 3,
        )

        coordinator.resetAutoStopState()

        assertEquals(null, coordinator.autoStopStartTime)
        assertFalse(coordinator.autoStopTriggered)
        assertFalse(coordinator.autoStopStopRequested)
        assertEquals(null, coordinator.stallStartTime)
        assertFalse(coordinator.isCurrentlyStalled)
        assertFalse(coordinator.stallArmedByDeload)
        assertEquals(0L, coordinator.deferAutoStopDeadlineMs)
        assertEquals(AutoStopUiState(), coordinator._autoStopState.value)
    }
}
