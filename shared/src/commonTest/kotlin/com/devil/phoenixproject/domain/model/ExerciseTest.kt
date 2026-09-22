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

    @Test
    fun `cycle one rep max conversion splits unified total and round trips`() {
        val squat = Exercise(
            name = "Barbell Squat",
            muscleGroup = "Legs",
            equipment = "BARBELL",
            cableIntent = ExerciseCableIntent.DUAL,
        )

        assertEquals(50f, squat.oneRepMaxInputToPerCableKg(100f)!!, 0.0001f)
        assertEquals(100f, squat.perCableKgToOneRepMaxInput(50f)!!, 0.0001f)
    }

    @Test
    fun `cycle one rep max input rejects unresolved positive values before persistence`() {
        val known = Exercise(
            name = "Known Lift",
            muscleGroup = "Back",
            id = "known-lift",
            cableIntent = ExerciseCableIntent.SINGLE,
        )
        val unknownIntent = Exercise(
            name = "Unknown Lift",
            muscleGroup = "Back",
            id = "unknown-lift",
            cableIntent = ExerciseCableIntent.EITHER,
        )

        val result = normalizeCycleOneRepMaxInputs(
            inputValues = mapOf(
                "Known Lift" to 80f,
                "Unknown Lift" to 100f,
                "Skipped Lift" to 0f,
            ),
            exercisesByName = mapOf(
                "Known Lift" to known,
                "Unknown Lift" to unknownIntent,
            ),
        )

        assertEquals(
            CycleOneRepMaxNormalization.Invalid("Unknown Lift"),
            result,
        )
        assertEquals(false, result is CycleOneRepMaxNormalization.Valid)
    }

    @Test
    fun `cycle one rep max input rejects signed negative zero`() {
        assertEquals(
            CycleOneRepMaxNormalization.Invalid("Negative Zero Lift"),
            normalizeCycleOneRepMaxInputs(
                inputValues = mapOf("Negative Zero Lift" to -0.0f),
                exercisesByName = emptyMap(),
            ),
        )
    }

    @Test
    fun `cycle one rep max input normalizes positive values and omits zero`() {
        val known = Exercise(
            name = "Known Lift",
            muscleGroup = "Back",
            id = "known-lift",
            cableIntent = ExerciseCableIntent.SINGLE,
        )

        val result = normalizeCycleOneRepMaxInputs(
            inputValues = mapOf(
                "Known Lift" to 80f,
                "Skipped Lift" to 0f,
            ),
            exercisesByName = mapOf("Known Lift" to known),
        )

        assertEquals(
            CycleOneRepMaxNormalization.Valid(
                mapOf(
                    "Known Lift" to NormalizedCycleOneRepMaxValue(
                        exerciseId = "known-lift",
                        perCableKg = 80f,
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `cycle one rep max input rejects negative and non-finite values`() {
        val known = Exercise(
            name = "Known Lift",
            muscleGroup = "Back",
            id = "known-lift",
            cableIntent = ExerciseCableIntent.SINGLE,
        )
        val exercisesByName = mapOf("Known Lift" to known)

        assertEquals(
            CycleOneRepMaxNormalization.Invalid("Negative Infinity Lift"),
            normalizeCycleOneRepMaxInputs(
                inputValues = mapOf(
                    "Negative Infinity Lift" to Float.NEGATIVE_INFINITY,
                    "Skipped Lift" to 0f,
                ),
                exercisesByName = exercisesByName,
            ),
        )
        assertEquals(
            CycleOneRepMaxNormalization.Invalid("Negative Lift"),
            normalizeCycleOneRepMaxInputs(
                inputValues = mapOf(
                    "Negative Lift" to -1f,
                    "Skipped Lift" to 0f,
                ),
                exercisesByName = exercisesByName,
            ),
        )
    }

    @Test
    fun `cycle one rep max conversion fails closed for unknown cable intent`() {
        val unknown = Exercise(
            name = "Unknown Bar",
            muscleGroup = "Back",
            equipment = "BAR",
            cableIntent = ExerciseCableIntent.EITHER,
        )

        assertEquals(null, unknown.oneRepMaxInputToPerCableKg(100f))
        assertEquals(null, unknown.perCableKgToOneRepMaxInput(50f))
    }
}
