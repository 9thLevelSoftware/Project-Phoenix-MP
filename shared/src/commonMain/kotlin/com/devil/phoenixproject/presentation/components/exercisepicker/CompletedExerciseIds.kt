package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.domain.model.WorkoutSession

data class CompletedExerciseIdsState(
    val profileId: String?,
    val ids: Set<String> = emptySet(),
    val isLoading: Boolean,
)

internal fun completedExerciseIdsFromHistory(
    sessions: List<WorkoutSession>,
): Set<String> = sessions.mapNotNullTo(linkedSetOf()) { session ->
    session.exerciseId?.trim()?.takeIf(String::isNotEmpty)
}
