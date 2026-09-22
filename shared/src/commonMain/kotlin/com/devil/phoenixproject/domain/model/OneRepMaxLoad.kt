package com.devil.phoenixproject.domain.model

/**
 * The cycle wizard accepts a human-facing load, while persisted baselines are
 * always kilograms per cable.  Keep the conversion at that boundary.
 */
fun Exercise.oneRepMaxInputToPerCableKg(inputKg: Float): Float? =
    displayMultiplier?.let { inputKg / it }

/** Convert a canonical per-cable baseline back to the wizard's total-load input. */
fun Exercise.perCableKgToOneRepMaxInput(perCableKg: Float): Float? =
    displayMultiplier?.let { perCableKg * it }

/**
 * Convert a canonical per-cable baseline back to the wizard's editable kilogram value.
 * Only resolved display metadata can safely determine the wizard input value.
 */
fun Exercise?.oneRepMaxInputPrefillKg(perCableKg: Float): Float? =
    this?.displayMultiplier?.let { multiplier -> perCableKg * multiplier }

/**
 * Label the cycle wizard's 1RM input in the user's current unit.
 * Only the confirmed dual/unified case is a combined total; all other cases stay per-cable.
 */
fun Exercise?.oneRepMaxInputUnitLabel(unitLabel: String): String =
    if (this?.displayMultiplier == 2) "Total load ($unitLabel)" else unitLabel

data class NormalizedCycleOneRepMaxValue(
    val exerciseId: String,
    val perCableKg: Float,
)

sealed interface CycleOneRepMaxNormalization {
    data class Valid(val values: Map<String, NormalizedCycleOneRepMaxValue>) : CycleOneRepMaxNormalization
    data class Invalid(val exerciseName: String) : CycleOneRepMaxNormalization
}

/**
 * Validate and normalize submitted positive cycle 1RM values before persistence.
 * Exactly zero represents a blank/skipped field and remains omitted.
 */
fun normalizeCycleOneRepMaxInputs(
    inputValues: Map<String, Float>,
    exercisesByName: Map<String, Exercise?>,
): CycleOneRepMaxNormalization {
    val normalizedValues = mutableMapOf<String, NormalizedCycleOneRepMaxValue>()

    inputValues.forEach { (exerciseName, inputKg) ->
        if (inputKg.toBits() == 0) return@forEach
        if (inputKg <= 0f || !inputKg.isFinite()) {
            return CycleOneRepMaxNormalization.Invalid(exerciseName)
        }

        val exercise = exercisesByName[exerciseName]
            ?: return CycleOneRepMaxNormalization.Invalid(exerciseName)
        val exerciseId = exercise.id
            ?: return CycleOneRepMaxNormalization.Invalid(exerciseName)
        val perCableKg = exercise.oneRepMaxInputToPerCableKg(inputKg)
            ?: return CycleOneRepMaxNormalization.Invalid(exerciseName)
        if (!perCableKg.isFinite() || perCableKg <= 0f) {
            return CycleOneRepMaxNormalization.Invalid(exerciseName)
        }

        normalizedValues[exerciseName] = NormalizedCycleOneRepMaxValue(
            exerciseId = exerciseId,
            perCableKg = perCableKg,
        )
    }

    return CycleOneRepMaxNormalization.Valid(normalizedValues.toMap())
}