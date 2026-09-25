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

    @Test
    fun bodyweightFilterMatchesExplicitClassificationNotJustToken() {
        val bodyOnly = exercise("plank", equipment = "body only").copy(isBodyweightOverride = true)
        val cable = exercise("row", equipment = "HANDLES").copy(isBodyweightOverride = false)
        val token = exercise("push-up", equipment = "BODYWEIGHT").copy(isBodyweightOverride = true)

        val result = filterExercisePickerCandidates(
            candidates = listOf(bodyOnly, cable, token),
            filters = ExercisePickerFilterState(selectedEquipment = setOf("Bodyweight")),
        )

        assertEquals(listOf(bodyOnly, token), result)
    }

    @Test
    fun cableFilterMatchesImportedCableToken() {
        val facePull = exercise("face-pull", equipment = "cable")
        val handles = exercise("row", equipment = "HANDLES")
        val bodyweight = exercise("plank", equipment = "BODYWEIGHT").copy(isBodyweightOverride = true)

        val result = filterExercisePickerCandidates(
            candidates = listOf(facePull, handles, bodyweight),
            filters = ExercisePickerFilterState(selectedEquipment = setOf("Cable")),
        )

        assertEquals(listOf(facePull), result)
    }

    @Test
    fun essentialsFilterKeepsOnlyTheBuiltInSetInCandidateOrder() {
        val candidates = listOf(
            exercise("Barbell_Squat"),
            exercise("Some_Obscure_Movement"),
            exercise(" Barbell_Curl "),
            exercise(null, name = "No id"),
            exercise("Barbell_Bench_Press_-_Medium_Grip"),
        )

        val result = filterExercisePickerCandidates(
            candidates = candidates,
            filters = ExercisePickerFilterState(showEssentialsOnly = true),
        )

        assertEquals(
            listOf("Barbell_Squat", " Barbell_Curl ", "Barbell_Bench_Press_-_Medium_Grip"),
            result.map { it.id },
        )
    }

    @Test
    fun essentialsIntersectsWithTheOtherFilters() {
        val favouriteEssential = exercise("Barbell_Curl", favorite = true, muscleGroups = "Arms")
        val plainEssential = exercise("Barbell_Squat", muscleGroups = "Legs")
        val favouriteOther = exercise("Some_Obscure_Movement", favorite = true, muscleGroups = "Arms")

        val result = filterExercisePickerCandidates(
            candidates = listOf(favouriteEssential, plainEssential, favouriteOther),
            filters = ExercisePickerFilterState(showFavoritesOnly = true, showEssentialsOnly = true),
        )

        assertEquals(listOf(favouriteEssential), result)
    }

    // Issue #883: the Belt chip must be an exact-token filter over real BELT rows. Widening it
    // onto barbell/other/machine would re-introduce the false matches the fix must avoid.
    @Test
    fun beltChipMatchesBeltTokenRowsOnly() {
        val beltSquat = exercise("Belt_Squat", name = "Belt Squat", equipment = "belt")
        val sumoBeltSquat = exercise("Sumo_Belt_Squat", name = "Sumo Belt Squat", equipment = "BELT")
        val barbellSquat = exercise("Barbell_Squat", name = "Barbell Squat", equipment = "barbell")
        val weightedSquat = exercise("Weighted_Squat", name = "Weighted Squat", equipment = "other")
        val machineSquat = exercise("Squat_Machine", name = "Machine Squat", equipment = "machine")
        val cableRow = exercise("face-pull", equipment = "cable")

        val result = filterExercisePickerCandidates(
            candidates = listOf(beltSquat, sumoBeltSquat, barbellSquat, weightedSquat, machineSquat, cableRow),
            filters = ExercisePickerFilterState(selectedEquipment = setOf("Belt")),
        )

        assertEquals(listOf(beltSquat, sumoBeltSquat), result)
    }

    @Test
    fun beltChipMatchesBeltInMultiTokenEquipmentLabels() {
        val beltAndBench = exercise("Belt_Squat_Pulses", name = "Belt Squat Pulses", equipment = "BENCH, BELT")
        val handlesOnly = exercise("row", equipment = "HANDLES")

        val result = filterExercisePickerCandidates(
            candidates = listOf(beltAndBench, handlesOnly),
            filters = ExercisePickerFilterState(selectedEquipment = setOf("Belt")),
        )

        assertEquals(listOf(beltAndBench), result)
    }
}

