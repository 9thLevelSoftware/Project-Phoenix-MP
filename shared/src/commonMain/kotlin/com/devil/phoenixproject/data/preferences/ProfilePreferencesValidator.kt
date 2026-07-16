package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences

object ProfilePreferencesValidator {
    fun core(value: CoreProfilePreferences): List<String> = buildList {
        if (!value.bodyWeightKg.isFinite() || (value.bodyWeightKg != 0f && value.bodyWeightKg !in 20f..300f)) add("bodyWeightKg")
        if (!value.weightIncrement.isFinite() || (value.weightIncrement != -1f && value.weightIncrement <= 0f)) add("weightIncrement")
    }

    fun rack(value: RackPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.items.map { it.id }.distinct().size != value.items.size) add("duplicateRackItemId")
        value.items.forEach { item ->
            if (item.id.isBlank()) add("rackItem.id")
            if (item.name.isBlank()) add("rackItem.name")
            if (!item.weightKg.isFinite() || item.weightKg < 0f) add("rackItem.weightKg")
        }
    }

    fun workout(value: WorkoutPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.summaryCountdownSeconds !in setOf(-1, 0, 5, 10, 15, 20, 25, 30)) add("summaryCountdownSeconds")
        if (value.autoStartCountdownSeconds !in 2..10) add("autoStartCountdownSeconds")
        if (value.defaultRoutineExerciseWeightPercentOfPR !in 50..120) add("defaultRoutineExerciseWeightPercentOfPR")
        if (value.justLiftDefaults.restSeconds != 0 && value.justLiftDefaults.restSeconds !in 5..300) add("justLiftDefaults.restSeconds")
        if (!value.justLiftDefaults.weightPerCableKg.isFinite() || value.justLiftDefaults.weightPerCableKg < 0f) add("justLiftDefaults.weightPerCableKg")
        if (!value.justLiftDefaults.weightChangePerRep.isFinite()) add("justLiftDefaults.weightChangePerRep")
        if (value.justLiftDefaults.workoutModeId !in setOf(0, 2, 3, 4, 6, 10)) add("justLiftDefaults.workoutModeId")
        if (value.justLiftDefaults.eccentricLoadPercentage !in 0..150) add("justLiftDefaults.eccentricLoadPercentage")
        if (value.justLiftDefaults.echoLevelValue !in 0..3) add("justLiftDefaults.echoLevelValue")
        if (value.justLiftDefaults.repCountTimingName !in RepCountTiming.entries.map { it.name }) add("justLiftDefaults.repCountTimingName")
        value.singleExerciseDefaults.forEach { (key, defaults) ->
            if (key != defaults.exerciseId || key.isBlank()) add("singleExerciseDefaults.exerciseId")
            if (!defaults.weightPerCableKg.isFinite() || defaults.weightPerCableKg < 0f) add("singleExerciseDefaults.weightPerCableKg")
            if (!defaults.progressionKg.isFinite()) add("singleExerciseDefaults.progressionKg")
            if (defaults.setWeightsPerCableKg.any { !it.isFinite() || it < 0f }) add("singleExerciseDefaults.setWeightsPerCableKg")
            if (defaults.setRestSeconds.any { it != 0 && it !in 5..300 }) add("singleExerciseDefaults.setRestSeconds")
            if (defaults.setReps.any { it != null && it < 0 }) add("singleExerciseDefaults.setReps")
            if (defaults.workoutModeId !in setOf(0, 2, 3, 4, 6, 10)) add("singleExerciseDefaults.workoutModeId")
            if (defaults.eccentricLoadPercentage !in 0..150) add("singleExerciseDefaults.eccentricLoadPercentage")
            if (defaults.echoLevelValue !in 0..3) add("singleExerciseDefaults.echoLevelValue")
            if (defaults.duration < 0) add("singleExerciseDefaults.duration")
            if (defaults.defaultRackItemIds.any { it.isBlank() } || defaults.defaultRackItemIds.distinct().size != defaults.defaultRackItemIds.size) add("singleExerciseDefaults.defaultRackItemIds")
        }
    }

    fun led(value: LedPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.colorScheme < 0) add("colorScheme")
    }

    fun vbt(value: VbtPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.velocityLossThresholdPercent !in 10..50) add("velocityLossThresholdPercent")
    }
}
