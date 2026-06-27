package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ScalingBasisTest {
    @Test fun `fromPrType maps PR types`() {
        assertEquals(ScalingBasis.MAX_WEIGHT_PR, ScalingBasis.fromPrType(PRType.MAX_WEIGHT))
        assertEquals(ScalingBasis.MAX_VOLUME_PR, ScalingBasis.fromPrType(PRType.MAX_VOLUME))
    }

    @Test fun `effectiveScalingBasis uses explicit value when set`() {
        val ex = sampleRoutineExercise().copy(scalingBasis = ScalingBasis.ESTIMATED_1RM)
        assertEquals(ScalingBasis.ESTIMATED_1RM, ex.effectiveScalingBasis)
    }

    @Test fun `effectiveScalingBasis derives from prTypeForScaling when null`() {
        val ex = sampleRoutineExercise().copy(scalingBasis = null, prTypeForScaling = PRType.MAX_VOLUME)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, ex.effectiveScalingBasis)
    }
}

fun sampleRoutineExercise(): RoutineExercise = RoutineExercise(
    id = "test-exercise",
    exercise = Exercise(
        name = "Bench Press",
        muscleGroup = "Chest",
    ),
    orderIndex = 0,
    weightPerCableKg = 50f,
)
