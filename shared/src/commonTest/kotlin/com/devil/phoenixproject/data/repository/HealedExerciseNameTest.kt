package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/** Issue #774: routine-load healing never creates an exercise with a blank name. */
class HealedExerciseNameTest {

    @Test
    fun storedNameIsKeptTrimmed() {
        assertEquals("Cable Row", healedExerciseName("  Cable Row ", "Seated_Cable_Rows"))
    }

    @Test
    fun blankNameFallsBackToTheStoredExerciseId() {
        assertEquals("Seated_Cable_Rows", healedExerciseName("  ", " Seated_Cable_Rows "))
    }

    @Test
    fun blankNameAndIdFallBackToAFixedLabel() {
        assertEquals(UNKNOWN_EXERCISE_NAME, healedExerciseName("", null))
        assertEquals(UNKNOWN_EXERCISE_NAME, healedExerciseName("", "  "))
    }
}
