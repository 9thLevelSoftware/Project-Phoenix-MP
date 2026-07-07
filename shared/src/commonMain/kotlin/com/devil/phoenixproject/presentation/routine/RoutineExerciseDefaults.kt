package com.devil.phoenixproject.presentation.routine

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences

private val DefaultRoutineSetReps: List<Int?> = listOf(10, 10, 10)

fun buildDefaultRoutineExerciseForEditor(
    id: String,
    selectedExercise: Exercise,
    orderIndex: Int,
    userPreferences: UserPreferences,
    supersetId: String? = null,
    orderInSuperset: Int = 0,
): RoutineExercise {
    val shouldSeedPercent = userPreferences.defaultRoutineExerciseUsePercentOfPR && !selectedExercise.isBodyweight
    val defaultPercent = userPreferences.defaultRoutineExerciseWeightPercentOfPR.coerceIn(50, 120)
    val defaultScalingBasis = userPreferences.defaultScalingBasis

    return RoutineExercise(
        id = id,
        exercise = selectedExercise,
        orderIndex = orderIndex,
        setReps = DefaultRoutineSetReps,
        weightPerCableKg = 5f,
        usePercentOfPR = shouldSeedPercent,
        weightPercentOfPR = defaultPercent,
        setWeightsPercentOfPR = if (shouldSeedPercent) {
            List(DefaultRoutineSetReps.size) { defaultPercent }
        } else {
            emptyList()
        },
        scalingBasis = defaultScalingBasis,
        prTypeForScaling = defaultScalingBasis.toRoutineExercisePRType(),
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )
}

fun ScalingBasis.toRoutineExercisePRType(): PRType = when (this) {
    ScalingBasis.MAX_VOLUME_PR -> PRType.MAX_VOLUME
    ScalingBasis.MAX_WEIGHT_PR,
    ScalingBasis.ESTIMATED_1RM,
    -> PRType.MAX_WEIGHT
}
