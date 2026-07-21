package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.presentation.components.getEquipmentDatabaseValues

internal data class ExercisePickerFilterState(
    val showFavoritesOnly: Boolean = false,
    val showCustomOnly: Boolean = false,
    val selectedMuscles: Set<String> = emptySet(),
    val selectedEquipment: Set<String> = emptySet(),
    val showPreviouslyCompletedOnly: Boolean = false,
)

/**
 * Applies every exercise-picker narrowing predicate to the repository-selected candidates.
 * The source list controls search semantics and ordering; this helper only removes entries.
 */
internal fun filterExercisePickerCandidates(
    candidates: List<Exercise>,
    filters: ExercisePickerFilterState,
    completedExerciseIds: Set<String> = emptySet(),
): List<Exercise> = candidates.filter { exercise ->
    val matchesFavorites = !filters.showFavoritesOnly || exercise.isFavorite
    val matchesCustom = !filters.showCustomOnly || exercise.isCustom
    val matchesMuscle = filters.selectedMuscles.isEmpty() ||
        filters.selectedMuscles.any { muscle ->
            exercise.muscleGroups.contains(muscle, ignoreCase = true)
        }
    val matchesEquipment = filters.selectedEquipment.isEmpty() ||
        filters.selectedEquipment.any { equipment ->
            val databaseValues = getEquipmentDatabaseValues(equipment)
            val exerciseEquipment = exercise.equipment.uppercase().split(",").map { it.trim() }
            databaseValues.any { databaseValue ->
                databaseValue.uppercase() in exerciseEquipment
            }
        }
    val matchesPreviouslyCompleted = !filters.showPreviouslyCompletedOnly ||
        (exercise.id?.trim()?.takeIf(String::isNotEmpty) in completedExerciseIds)

    matchesFavorites && matchesCustom && matchesMuscle && matchesEquipment && matchesPreviouslyCompleted
}
