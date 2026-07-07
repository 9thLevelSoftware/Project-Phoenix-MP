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

    @Test
    fun `isBodyweight override wins over equipment derivation in both directions`() {
        // #635: Squat ships with equipment=[] but is a cable lift — explicit flag wins
        val emptyEquipmentCableLift = Exercise(
            name = "Squat",
            muscleGroup = "Legs",
            equipment = "",
            isBodyweightOverride = false,
        )
        assertEquals(false, emptyEquipmentCableLift.isBodyweight)

        // Inverse direction: explicit bodyweight despite a cable-accessory tag
        val taggedBodyweight = Exercise(
            name = "Weighted-tag Bodyweight",
            muscleGroup = "Core",
            equipment = "HANDLES",
            isBodyweightOverride = true,
        )
        assertEquals(true, taggedBodyweight.isBodyweight)
    }

    @Test
    fun `isBodyweight falls back to equipment derivation when override is null`() {
        val noEquipment = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            equipment = "",
        )
        assertEquals(true, noEquipment.isBodyweight)

        val cableEquipment = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR,BENCH",
        )
        assertEquals(false, cableEquipment.isBodyweight)

        // BENCH alone is not a cable accessory
        val benchOnly = Exercise(
            name = "Tricep Dips",
            muscleGroup = "Triceps",
            equipment = "BENCH",
        )
        assertEquals(true, benchOnly.isBodyweight)
    }

    @Test
    fun `isBodyweight override does not affect hasCableAccessory or display multiplier`() {
        val exercise = Exercise(
            name = "Squat",
            muscleGroup = "Legs",
            equipment = "",
            cableIntent = ExerciseCableIntent.DUAL,
            isBodyweightOverride = false,
        )

        // Equipment-based properties keep their original derivation semantics
        assertEquals(false, exercise.hasCableAccessory)
        assertEquals(false, exercise.usesUnifiedAttachment)
        assertEquals(1, exercise.displayMultiplier)
        // While classification honors the explicit flag
        assertEquals(false, exercise.isBodyweight)
    }

    @Test
    fun `live unified accessory display multiplier doubles only dual bar or belt exercises`() {
        val dualBar = Exercise(
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "BAR,BENCH,BLACK_CABLES",
            cableIntent = ExerciseCableIntent.DUAL,
        )
        val dualBelt = Exercise(
            name = "Hip Thrust",
            muscleGroup = "Glutes",
            equipment = "BELT,BENCH,BLACK_CABLES",
            cableIntent = ExerciseCableIntent.DUAL,
        )

        assertEquals(2, dualBar.liveUnifiedAccessoryDisplayMultiplier())
        assertEquals(2, dualBelt.liveUnifiedAccessoryDisplayMultiplier())
    }

    @Test
    fun `live unified accessory display multiplier does not double individual attachments`() {
        listOf("HANDLES", "ROPE", "SHORT_BAR", "STRAPS").forEach { equipment ->
            val exercise = Exercise(
                name = "Dual $equipment",
                muscleGroup = "Test",
                equipment = equipment,
                cableIntent = ExerciseCableIntent.DUAL,
            )

            assertEquals(1, exercise.liveUnifiedAccessoryDisplayMultiplier(), equipment)
        }
    }

    @Test
    fun `live unified accessory display multiplier fails closed for non-explicit dual unified metadata`() {
        val unilateralBar = Exercise(
            name = "Single Cable Bar",
            muscleGroup = "Back",
            equipment = "BAR",
            cableIntent = ExerciseCableIntent.SINGLE,
        )
        val alternatingBar = Exercise(
            name = "Alternating Lunge",
            muscleGroup = "Legs",
            equipment = "BAR",
            cableIntent = ExerciseCableIntent.EITHER,
        )
        val unknownBar = Exercise(
            name = "Custom Bar",
            muscleGroup = "Back",
            equipment = "BAR",
            cableIntent = null,
            isCustom = true,
        )
        val nullExercise: Exercise? = null

        assertEquals(1, unilateralBar.liveUnifiedAccessoryDisplayMultiplier())
        assertEquals(1, alternatingBar.liveUnifiedAccessoryDisplayMultiplier())
        assertEquals(1, unknownBar.liveUnifiedAccessoryDisplayMultiplier())
        assertEquals(1, nullExercise.liveUnifiedAccessoryDisplayMultiplier())
    }
}
