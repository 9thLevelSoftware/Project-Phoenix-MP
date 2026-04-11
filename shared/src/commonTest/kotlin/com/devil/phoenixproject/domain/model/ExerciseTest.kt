package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseTest {

    @Test
    fun `muscleGroups defaults to muscleGroup for backward compatibility`() {
        val exercise = Exercise(
            name = "Test Exercise",
            muscleGroup = "Chest",
        )

        assertEquals("Chest", exercise.muscleGroups)
    }

    @Test
    fun `muscleGroups can be set independently`() {
        val exercise = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
        )

        assertEquals("Chest,Triceps,Shoulders", exercise.muscleGroups)
    }

    @Test
    fun `displayName returns exercise name`() {
        val exercise = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest",
        )

        assertEquals("Bench Press", exercise.displayName)
    }

    @Test
    fun `default values are set correctly`() {
        val exercise = Exercise(
            name = "Test",
            muscleGroup = "Test",
        )

        assertEquals("", exercise.equipment)
        assertEquals(null, exercise.id)
        assertEquals(false, exercise.isFavorite)
        assertEquals(false, exercise.isCustom)
        assertEquals(0, exercise.timesPerformed)
        assertEquals(null, exercise.oneRepMaxKg)
    }
}
