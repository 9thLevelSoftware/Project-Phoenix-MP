package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument

internal fun SingleExerciseDefaults.toDocument() = SingleExerciseDefaultsDocument(
    exerciseId = exerciseId,
    setReps = setReps,
    weightPerCableKg = weightPerCableKg,
    setWeightsPerCableKg = setWeightsPerCableKg,
    progressionKg = progressionKg,
    setRestSeconds = setRestSeconds,
    workoutModeId = workoutModeId,
    eccentricLoadPercentage = eccentricLoadPercentage,
    echoLevelValue = echoLevelValue,
    duration = duration,
    isAMRAP = isAMRAP,
    perSetRestTime = perSetRestTime,
    defaultRackItemIds = defaultRackItemIds,
)

internal fun SingleExerciseDefaultsDocument.toLegacySingleExerciseDefaults() = SingleExerciseDefaults(
    exerciseId = exerciseId,
    setReps = setReps,
    weightPerCableKg = weightPerCableKg,
    setWeightsPerCableKg = setWeightsPerCableKg,
    progressionKg = progressionKg,
    setRestSeconds = setRestSeconds,
    workoutModeId = workoutModeId,
    eccentricLoadPercentage = eccentricLoadPercentage,
    echoLevelValue = echoLevelValue,
    duration = duration,
    isAMRAP = isAMRAP,
    perSetRestTime = perSetRestTime,
    defaultRackItemIds = defaultRackItemIds,
)

internal fun com.devil.phoenixproject.data.preferences.JustLiftDefaults.toDocument() =
    JustLiftDefaultsDocument(
        workoutModeId = workoutModeId,
        weightPerCableKg = weightPerCableKg,
        weightChangePerRep = weightChangePerRep,
        eccentricLoadPercentage = eccentricLoadPercentage,
        echoLevelValue = echoLevelValue,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTimingName = repCountTimingName,
        restSeconds = restSeconds,
    )
