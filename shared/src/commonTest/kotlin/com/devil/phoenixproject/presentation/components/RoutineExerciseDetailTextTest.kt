package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.util.UnitConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoutineExerciseDetailTextTest {

    private val kgToDisplay: (Float, WeightUnit) -> Float = { kg, unit ->
        WeightDisplayFormatter.toDisplayWeight(kg, unit)
    }

    @Test
    fun repRow_fractionalKilograms_keepsTheDecimal() {
        val text = routineExerciseDetailText(row(weightPerCableKg = 20.5f), WeightUnit.KG, kgToDisplay)

        assertEquals("3 sets x 10 reps @ 20.5 kg", text)
    }

    @Test
    fun repRow_wholeKilograms_omitsTrailingZero() {
        val text = routineExerciseDetailText(row(weightPerCableKg = 20f), WeightUnit.KG, kgToDisplay)

        assertEquals("3 sets x 10 reps @ 20 kg", text)
        assertFalse(text.contains(".0"))
    }

    @Test
    fun repRow_fractionalPounds_keepsTheDecimalAndLbsLabel() {
        val weightKg = 20.5f
        val text = routineExerciseDetailText(row(weightPerCableKg = weightKg), WeightUnit.LB, kgToDisplay)
        val formatted = WeightDisplayFormatter.formatDisplayWeight(weightKg, WeightUnit.LB)
        val truncated = "${WeightDisplayFormatter.toDisplayWeight(weightKg, WeightUnit.LB).toInt()}"

        assertEquals("3 sets x 10 reps @ $formatted lbs", text)
        assertFalse(formatted == truncated, "20.5 kg in lb must not be truncated to an integer")
    }

    @Test
    fun timedRow_fractionalAndWholeWeights_followTheSameFormatter() {
        assertEquals(
            "3 sets x 30s @ 20.5 kg",
            routineExerciseDetailText(row(weightPerCableKg = 20.5f, duration = 30), WeightUnit.KG, kgToDisplay),
        )
        assertEquals(
            "3 sets x 45s @ 20 kg",
            routineExerciseDetailText(row(weightPerCableKg = 20f, duration = 45), WeightUnit.KG, kgToDisplay),
        )
    }

    @Test
    fun timedRow_fractionalPounds_keepsLbsLabel() {
        val weightKg = 9.3f
        val formatted = WeightDisplayFormatter.formatDisplayWeight(weightKg, WeightUnit.LB)
        val text = routineExerciseDetailText(
            row(weightPerCableKg = weightKg, duration = 30),
            WeightUnit.LB,
            kgToDisplay,
        )

        assertEquals("3 sets x 30s @ $formatted lbs", text)
        assertEquals("20.5", formatted)
    }

    @Test
    fun progressionSuffix_keepsSingularPoundLabel() {
        val text = routineExerciseDetailText(
            row(weightPerCableKg = 20.5f, progressionKg = 0.5f),
            WeightUnit.LB,
            kgToDisplay,
        )
        val formatted = WeightDisplayFormatter.formatDisplayWeight(20.5f, WeightUnit.LB)
        val progression = 0.5f * UnitConverter.KG_TO_LB

        assertEquals("3 sets x 10 reps @ $formatted lbs (+${progression}lb/rep)", text)
    }

    @Test
    fun kilogramProgression_staysAttachedToTheFractionalSetWeight() {
        val text = routineExerciseDetailText(
            row(weightPerCableKg = 20.5f, progressionKg = 0.5f),
            WeightUnit.KG,
            kgToDisplay,
        )

        assertEquals("3 sets x 10 reps @ 20.5 kg (+0.5kg/rep)", text)
    }

    @Test
    fun echoRows_stayAdaptive() {
        assertEquals(
            "3 sets x 10 reps @ Adaptive",
            routineExerciseDetailText(
                row(weightPerCableKg = 20.5f, programMode = ProgramMode.Echo),
                WeightUnit.KG,
                kgToDisplay,
            ),
        )
        assertEquals(
            "2 sets x 30s @ Adaptive",
            routineExerciseDetailText(
                row(weightPerCableKg = 20.5f, duration = 30, programMode = ProgramMode.Echo, setReps = listOf(10, 10)),
                WeightUnit.LB,
                kgToDisplay,
            ),
        )
    }

    @Test
    fun amrapRow_showsFractionalWeight() {
        val text = routineExerciseDetailText(
            row(weightPerCableKg = 20.5f, isAMRAP = true),
            WeightUnit.KG,
            kgToDisplay,
        )

        assertEquals("3 sets x AMRAP @ 20.5 kg", text)
    }

    @Test
    fun bodyweightRow_ignoresStoredWeight() {
        val text = routineExerciseDetailText(
            row(weightPerCableKg = 20.5f, exercise = bodyweightExercise),
            WeightUnit.KG,
            kgToDisplay,
        )

        assertEquals("3 sets x 30s", text)
    }

    private fun row(
        weightPerCableKg: Float,
        duration: Int? = null,
        progressionKg: Float = 0f,
        programMode: ProgramMode = ProgramMode.OldSchool,
        isAMRAP: Boolean = false,
        setReps: List<Int?> = listOf(10, 10, 10),
        exercise: Exercise = cableExercise,
    ) = RoutineExercise(
        id = "row-1",
        exercise = exercise,
        orderIndex = 0,
        setReps = setReps,
        weightPerCableKg = weightPerCableKg,
        programMode = programMode,
        progressionKg = progressionKg,
        duration = duration,
        isAMRAP = isAMRAP,
    )

    private val cableExercise = Exercise(
        name = "Bench Press",
        muscleGroup = "Chest",
        equipment = "BAR",
        id = "bench",
    )

    private val bodyweightExercise = Exercise(
        name = "Plank",
        muscleGroup = "Core",
        equipment = "",
        id = "plank",
    )
}
