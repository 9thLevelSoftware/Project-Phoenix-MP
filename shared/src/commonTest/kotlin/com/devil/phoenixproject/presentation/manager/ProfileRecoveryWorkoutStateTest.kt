package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRecoveryWorkoutStateTest {
    @Test
    fun `only live or resumable workout states block profile recovery`() {
        assertFalse(isWorkoutRecoveryBlocking(WorkoutState.Idle))
        assertFalse(isWorkoutRecoveryBlocking(WorkoutState.Completed))
        assertFalse(isWorkoutRecoveryBlocking(WorkoutState.RoutineComplete))
        assertFalse(isWorkoutRecoveryBlocking(WorkoutState.Error("failed")))
        assertTrue(isWorkoutRecoveryBlocking(WorkoutState.Initializing))
        assertTrue(isWorkoutRecoveryBlocking(WorkoutState.Countdown(3)))
        assertTrue(isWorkoutRecoveryBlocking(WorkoutState.Active))
        assertTrue(isWorkoutRecoveryBlocking(WorkoutState.Paused))
    }
}
