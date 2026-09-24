package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.domain.model.Exercise
import kotlin.test.Test
import kotlin.test.assertEquals

/** Issue #774: the picker must not crash on an exercise with a blank name. */
class GroupExercisesByInitialTest {

    private fun exercise(id: String, name: String) = Exercise(
        id = id,
        name = name,
        muscleGroup = "Chest",
        muscleGroups = "Chest",
        equipment = "",
    )

    @Test
    fun blankAndWhitespaceNamesGetTheirOwnSectionInsteadOfThrowing() {
        val grouped = groupExercisesByInitial(
            listOf(
                exercise("a", "Bench Press"),
                exercise("b", ""),
                exercise("c", "   "),
                exercise("d", "curl"),
            ),
        )

        assertEquals(listOf(UNNAMED_EXERCISE_SECTION, 'B', 'C'), grouped.keys.toList())
        assertEquals(listOf("b", "c"), grouped.getValue(UNNAMED_EXERCISE_SECTION).map { it.id })
        assertEquals(listOf("d"), grouped.getValue('C').map { it.id })
    }

    @Test
    fun leadingWhitespaceDoesNotChangeTheSection() {
        val grouped = groupExercisesByInitial(listOf(exercise("a", "  squat")))

        assertEquals(listOf('S'), grouped.keys.toList())
    }
}
