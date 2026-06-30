package com.devil.phoenixproject.presentation.routine

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineExerciseDefaultsTest {

    private val cableExercise = Exercise(
        id = "squat",
        name = "Squat",
        muscleGroup = "Legs",
        equipment = "BAR",
    )

    private val bodyweightExercise = Exercise(
        id = "plank",
        name = "Plank",
        muscleGroup = "Core",
        equipment = "",
    )

    @Test
    fun `preference off preserves manual fallback for cable exercise`() {
        val exercise = buildDefaultRoutineExerciseForEditor(
            id = "routine-ex-1",
            selectedExercise = cableExercise,
            orderIndex = 0,
            userPreferences = UserPreferences(
                defaultRoutineExerciseUsePercentOfPR = false,
                defaultRoutineExerciseWeightPercentOfPR = 90,
                defaultScalingBasis = ScalingBasis.MAX_VOLUME_PR,
            ),
        )

        assertFalse(exercise.usePercentOfPR)
        assertEquals(5f, exercise.weightPerCableKg)
        assertEquals(emptyList(), exercise.setWeightsPercentOfPR)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, exercise.scalingBasis)
        assertEquals(PRType.MAX_VOLUME, exercise.prTypeForScaling)
    }

    @Test
    fun `preference on seeds eligible cable exercise with percent fields`() {
        val exercise = buildDefaultRoutineExerciseForEditor(
            id = "routine-ex-2",
            selectedExercise = cableExercise,
            orderIndex = 1,
            userPreferences = UserPreferences(
                defaultRoutineExerciseUsePercentOfPR = true,
                defaultRoutineExerciseWeightPercentOfPR = 80,
                defaultScalingBasis = ScalingBasis.ESTIMATED_1RM,
            ),
            supersetId = "superset-1",
            orderInSuperset = 2,
        )

        assertTrue(exercise.usePercentOfPR)
        assertEquals(80, exercise.weightPercentOfPR)
        assertEquals(listOf(80, 80, 80), exercise.setWeightsPercentOfPR)
        assertEquals(5f, exercise.weightPerCableKg)
        assertEquals(ScalingBasis.ESTIMATED_1RM, exercise.scalingBasis)
        assertEquals(PRType.MAX_WEIGHT, exercise.prTypeForScaling)
        assertEquals("superset-1", exercise.supersetId)
        assertEquals(2, exercise.orderInSuperset)
    }

    @Test
    fun `preference on does not seed bodyweight or non cable exercise`() {
        val exercise = buildDefaultRoutineExerciseForEditor(
            id = "routine-ex-3",
            selectedExercise = bodyweightExercise,
            orderIndex = 0,
            userPreferences = UserPreferences(
                defaultRoutineExerciseUsePercentOfPR = true,
                defaultRoutineExerciseWeightPercentOfPR = 80,
                defaultScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
            ),
        )

        assertFalse(exercise.usePercentOfPR)
        assertEquals(80, exercise.weightPercentOfPR)
        assertEquals(emptyList(), exercise.setWeightsPercentOfPR)
        assertEquals(5f, exercise.weightPerCableKg)
    }

    @Test
    fun `routine exercise default percent is coerced into supported range`() {
        val low = buildDefaultRoutineExerciseForEditor(
            id = "routine-ex-low",
            selectedExercise = cableExercise,
            orderIndex = 0,
            userPreferences = UserPreferences(
                defaultRoutineExerciseUsePercentOfPR = true,
                defaultRoutineExerciseWeightPercentOfPR = 10,
            ),
        )
        val high = buildDefaultRoutineExerciseForEditor(
            id = "routine-ex-high",
            selectedExercise = cableExercise,
            orderIndex = 0,
            userPreferences = UserPreferences(
                defaultRoutineExerciseUsePercentOfPR = true,
                defaultRoutineExerciseWeightPercentOfPR = 200,
            ),
        )

        assertEquals(50, low.weightPercentOfPR)
        assertEquals(listOf(50, 50, 50), low.setWeightsPercentOfPR)
        assertEquals(120, high.weightPercentOfPR)
        assertEquals(listOf(120, 120, 120), high.setWeightsPercentOfPR)
    }
}
