package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseTest {

    @Test
    fun `resolveDefaultCableConfig returns SINGLE for single handle equipment`() {
        val exercise = Exercise(
            name = "Single Arm Row",
            muscleGroup = "Back",
            equipment = "SINGLE_HANDLE",
            defaultCableConfig = CableConfiguration.DOUBLE
        )

        assertEquals(CableConfiguration.SINGLE, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `resolveDefaultCableConfig returns SINGLE for ankle strap equipment`() {
        val exercise = Exercise(
            name = "Leg Curl",
            muscleGroup = "Legs",
            equipment = "ANKLE_STRAP",
            defaultCableConfig = CableConfiguration.DOUBLE
        )

        assertEquals(CableConfiguration.SINGLE, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `resolveDefaultCableConfig returns SINGLE for straps equipment`() {
        val exercise = Exercise(
            name = "Face Pull",
            muscleGroup = "Shoulders",
            equipment = "STRAPS",
            defaultCableConfig = CableConfiguration.DOUBLE
        )

        assertEquals(CableConfiguration.SINGLE, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `resolveDefaultCableConfig returns default config for bilateral equipment`() {
        val exercise = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR",
            defaultCableConfig = CableConfiguration.DOUBLE
        )

        assertEquals(CableConfiguration.DOUBLE, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `resolveDefaultCableConfig handles mixed equipment with single cable item`() {
        val exercise = Exercise(
            name = "Mixed Equipment Exercise",
            muscleGroup = "Back",
            equipment = "BAR, SINGLE_HANDLE, ROPE",
            defaultCableConfig = CableConfiguration.DOUBLE
        )

        // Should return SINGLE because SINGLE_HANDLE is in the equipment list
        assertEquals(CableConfiguration.SINGLE, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `resolveDefaultCableConfig is case insensitive`() {
        val exercise = Exercise(
            name = "Test Exercise",
            muscleGroup = "Back",
            equipment = "single_handle",
            defaultCableConfig = CableConfiguration.DOUBLE
        )

        assertEquals(CableConfiguration.SINGLE, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `resolveDefaultCableConfig returns default for empty equipment`() {
        val exercise = Exercise(
            name = "Custom Exercise",
            muscleGroup = "Full Body",
            equipment = "",
            defaultCableConfig = CableConfiguration.EITHER
        )

        assertEquals(CableConfiguration.EITHER, exercise.resolveDefaultCableConfig())
    }

    @Test
    fun `muscleGroups defaults to muscleGroup for backward compatibility`() {
        val exercise = Exercise(
            name = "Test Exercise",
            muscleGroup = "Chest"
        )

        assertEquals("Chest", exercise.muscleGroups)
    }

    @Test
    fun `muscleGroups can be set independently`() {
        val exercise = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders"
        )

        assertEquals("Chest,Triceps,Shoulders", exercise.muscleGroups)
    }

    @Test
    fun `displayName returns exercise name`() {
        val exercise = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest"
        )

        assertEquals("Bench Press", exercise.displayName)
    }

    @Test
    fun `default values are set correctly`() {
        val exercise = Exercise(
            name = "Test",
            muscleGroup = "Test"
        )

        assertEquals(CableConfiguration.DOUBLE, exercise.defaultCableConfig)
        assertEquals("", exercise.equipment)
        assertEquals(null, exercise.id)
        assertEquals(false, exercise.isFavorite)
        assertEquals(false, exercise.isCustom)
        assertEquals(0, exercise.timesPerformed)
        assertEquals(null, exercise.oneRepMaxKg)
    }
}
