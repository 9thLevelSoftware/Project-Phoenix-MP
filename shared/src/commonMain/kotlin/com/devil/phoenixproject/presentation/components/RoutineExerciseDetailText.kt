package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit

/**
 * Builds the set/rep/weight detail string for a routine exercise row.
 *
 * Three branches:
 *  - Bodyweight (no cable accessory): "N sets x Xs"
 *  - Timed cable exercise: "N sets x Xs @ Wkg/lbs (+prog)"
 *  - Rep-based exercise: "N sets x R reps @ Wkg/lbs (+prog/rep)"
 *
 * Pure function — no Compose APIs used.
 */
fun routineExerciseDetailText(
    exercise: RoutineExercise,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
): String {
    // Bodyweight = no cable accessories (handles, bar, rope, etc.) in equipment list
    val isBodyweight = !exercise.exercise.hasCableAccessory

    return if (isBodyweight) {
        // Bodyweight exercise - always duration, never reps (no cables engaged)
        val duration = exercise.duration ?: 30
        "${exercise.sets} sets x ${duration}s"
    } else if (exercise.duration != null) {
        // Timed cable exercise - show duration AND weight/progression
        val isEchoMode = exercise.programMode == ProgramMode.Echo
        val weightText = if (isEchoMode) {
            "Adaptive"
        } else {
            val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
            val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
            "${weight.toInt()} $unitLabel"
        }
        val progressionText = when {
            exercise.progressionKg > 0 -> {
                val progWeight = kgToDisplay(exercise.progressionKg, weightUnit)
                val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                " (+${progWeight}$unitLabel)"
            }

            exercise.progressionKg < 0 -> {
                val regWeight = kgToDisplay(-exercise.progressionKg, weightUnit)
                val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                " (-${regWeight}$unitLabel)"
            }

            else -> ""
        }
        "${exercise.sets} sets x ${exercise.duration}s @ $weightText$progressionText"
    } else {
        // Rep-based exercise with weight
        val isEchoMode = exercise.programMode == ProgramMode.Echo
        val weightText = if (isEchoMode) {
            "Adaptive"
        } else {
            val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
            val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
            "${weight.toInt()} $unitLabel"
        }
        // Handle AMRAP vs fixed reps display
        val repsText = if (exercise.isAMRAP) "AMRAP" else "${exercise.reps} reps"
        // Build progression/regression suffix if configured
        val progressionText = when {
            exercise.progressionKg > 0 -> {
                val progWeight = kgToDisplay(exercise.progressionKg, weightUnit)
                val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                " (+${progWeight}$unitLabel/rep)"
            }

            exercise.progressionKg < 0 -> {
                val regWeight = kgToDisplay(-exercise.progressionKg, weightUnit)
                val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                " (-${regWeight}$unitLabel/rep)"
            }

            else -> ""
        }
        "${exercise.sets} sets x $repsText @ $weightText$progressionText"
    }
}
