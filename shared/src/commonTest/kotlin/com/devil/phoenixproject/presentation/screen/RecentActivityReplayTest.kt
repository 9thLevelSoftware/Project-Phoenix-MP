package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentActivityReplayTest {
    @Test
    fun replayExerciseId_returnsNonBlankExerciseId() {
        val session = WorkoutSession(exerciseId = "bench-press")

        assertEquals("bench-press", session.replayExerciseId())
    }

    @Test
    fun replayExerciseId_returnsNullForBlankExerciseId() {
        val session = WorkoutSession(exerciseId = "   ")

        assertNull(session.replayExerciseId())
    }

    @Test
    fun replayExerciseId_returnsNullForMissingExerciseId() {
        val session = WorkoutSession(exerciseId = null)

        assertNull(session.replayExerciseId())
    }
}
