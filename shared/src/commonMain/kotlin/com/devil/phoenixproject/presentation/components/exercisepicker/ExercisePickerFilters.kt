package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.domain.model.EssentialsExercises
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.presentation.components.getEquipmentDatabaseValues

internal data class ExercisePickerFilterState(
    val showFavoritesOnly: Boolean = false,
    val showCustomOnly: Boolean = false,
    val selectedMuscles: Set<String> = emptySet(),
    val selectedEquipment: Set<String> = emptySet(),
    val showPreviouslyCompletedOnly: Boolean = false,
    /** Issue #770: only the built-in [EssentialsExercises] set. */
    val showEssentialsOnly: Boolean = false,
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
            if (equipment == "Bodyweight" && exercise.isBodyweight) {
                true
            } else {
                val databaseValues = getEquipmentDatabaseValues(equipment)
                val exerciseEquipment = exercise.equipment.uppercase().split(",").map { it.trim() }
                databaseValues.any { databaseValue ->
                    databaseValue.uppercase() in exerciseEquipment
                }
            }
        }
    val matchesPreviouslyCompleted = !filters.showPreviouslyCompletedOnly ||
        (exercise.id?.trim()?.takeIf(String::isNotEmpty) in completedExerciseIds)

    val matchesEssentials = !filters.showEssentialsOnly || EssentialsExercises.contains(exercise)

    matchesFavorites && matchesCustom && matchesMuscle && matchesEquipment && matchesPreviouslyCompleted &&
        matchesEssentials
}

/** Issue #850: the recent ids that still name an exercise in [library], newest first. */
internal fun selectableRecentExerciseIds(recentExerciseIds: List<String>, library: List<Exercise>): List<String> {
    val available = library.mapNotNullTo(HashSet()) { it.id?.trim() }
    return recentExerciseIds.filter { it.trim() in available }
}

/**
 * Issue #850: the Recent chip. Keeps only exercises in [recentExerciseIds] and orders them as
 * that list does (newest first), replacing the candidates' own order.
 */
internal fun orderByRecentExercises(exercises: List<Exercise>, recentExerciseIds: List<String>): List<Exercise> {
    val rank = recentExerciseIds.withIndex().associate { (index, id) -> id.trim() to index }
    return exercises
        .mapNotNull { exercise -> exercise.id?.trim()?.let(rank::get)?.let { it to exercise } }
        .sortedBy { (index, _) -> index }
        .map { (_, exercise) -> exercise }
}
