package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.data.preferences.RecentJustLiftExerciseStore
import com.devil.phoenixproject.domain.model.WorkoutSession

data class CompletedExerciseIdsState(
    val profileId: String?,
    val ids: Set<String> = emptySet(),
    val isLoading: Boolean,
)

/**
 * Exercise IDs tagged on Just Lift sessions, newest first. Seeds the Recent chip (#850) for a
 * profile that has no recorded list yet, e.g. after updating from a build without one.
 */
internal fun recentJustLiftExerciseIdsFromHistory(sessions: List<WorkoutSession>): List<String> =
    RecentJustLiftExerciseStore.normalize(
        sessions
            .filter { it.isJustLift }
            .sortedByDescending { it.timestamp }
            .mapNotNull { it.exerciseId },
    )

internal fun completedExerciseIdsFromHistory(
    sessions: List<WorkoutSession>,
): Set<String> = sessions.mapNotNullTo(linkedSetOf()) { session ->
    session.exerciseId?.trim()?.takeIf(String::isNotEmpty)
}
