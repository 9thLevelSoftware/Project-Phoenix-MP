package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals

class ExercisePickerFiltersTest {
    private fun exercise(
        id: String?,
        name: String = id ?: "unnamed",
        favorite: Boolean = false,
        custom: Boolean = false,
        muscleGroups: String = "Legs",
        equipment: String = "BAR",
    ) = Exercise(
        id = id,
        name = name,
        muscleGroup = muscleGroups.substringBefore(','),
        muscleGroups = muscleGroups,
        equipment = equipment,
        isFavorite = favorite,
        isCustom = custom,
    )

    @Test
    fun disabledPreviouslyCompletedFilterPreservesCandidateOrder() {
        val candidates = listOf(
            exercise("squat"),
            exercise("bench"),
            exercise("row"),
        )

        val result = filterExercisePickerCandidates(
            candidates = candidates,
            filters = ExercisePickerFilterState(),
            completedExerciseIds = setOf("row"),
        )

        assertEquals(candidates, result)
    }

    @Test
    fun allEnabledFiltersIntersectWithoutReorderingCandidates() {
        val matching = exercise(
            id = " squat ",
            favorite = true,
            custom = true,
            muscleGroups = "Legs, Core",
            equipment = "BAR, BENCH",
        )
        val wrongCompleted = exercise(
            id = "bench",
            favorite = true,
            custom = true,
            muscleGroups = "Legs",
            equipment = "BAR",
        )
        val wrongFavorite = exercise(
            id = "deadlift",
            favorite = false,
            custom = true,
            muscleGroups = "Legs",
            equipment = "BAR",
        )
        val blankId = exercise(
            id = " ",
            favorite = true,
            custom = true,
            muscleGroups = "Legs",
            equipment = "BAR",
        )

        val result = filterExercisePickerCandidates(
            candidates = listOf(wrongCompleted, matching, wrongFavorite, blankId),
            filters = ExercisePickerFilterState(
                showFavoritesOnly = true,
                showCustomOnly = true,
                selectedMuscles = setOf("Legs"),
                selectedEquipment = setOf("Long Bar"),
                showPreviouslyCompletedOnly = true,
            ),
            completedExerciseIds = setOf("squat"),
        )

        assertEquals(listOf(matching), result)
    }

    @Test
    fun completedIdsTrimValuesAndIgnoreBlankOrMissingTags() {
        val completedIds = completedExerciseIdsFromHistory(
            listOf(
                WorkoutSession(exerciseId = " squat "),
                WorkoutSession(exerciseId = null),
                WorkoutSession(exerciseId = ""),
                WorkoutSession(exerciseId = "squat"),
                WorkoutSession(exerciseId = "bench"),
            ),
        )

        assertEquals(linkedSetOf("squat", "bench"), completedIds)
    }
}
