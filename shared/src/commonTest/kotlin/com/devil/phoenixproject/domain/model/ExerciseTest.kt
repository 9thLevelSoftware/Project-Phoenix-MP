package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `preferredCableCount is nullable for unresolved cable metadata`() {
        val unknown = Exercise(
            name = "Unknown Cable Exercise",
            muscleGroup = "Back",
            cableIntent = null,
        )
        val either = Exercise(
            name = "Either Cable Exercise",
            muscleGroup = "Back",
            cableIntent = ExerciseCableIntent.EITHER,
        )

        assertNull(unknown.preferredCableCount)
        assertNull(either.preferredCableCount)
        assertEquals(1, unknown.displayCableCount)
        assertEquals(1, either.displayCableCount)
    }

    @Test
    fun `preferredCableCount uses explicit metadata or user override`() {
        val single = Exercise(
            name = "Single Cable Exercise",
            muscleGroup = "Back",
            cableIntent = ExerciseCableIntent.SINGLE,
        )
        val dual = Exercise(
            name = "Dual Cable Exercise",
            muscleGroup = "Back",
            cableIntent = ExerciseCableIntent.DUAL,
        )
        val userOverride = Exercise(
            name = "User Override Exercise",
            muscleGroup = "Back",
            cableIntent = ExerciseCableIntent.SINGLE,
            userCableCount = 2,
        )

        assertEquals(1, single.preferredCableCount)
        assertEquals(2, dual.preferredCableCount)
        assertEquals(2, userOverride.preferredCableCount)
    }

}
