package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.RoutineExercise
import kotlin.math.roundToInt

/**
 * Resolves the programmed per-cable weight for one routine set.
 *
 * Routine loading resolves PR-backed exercises before execution. [currentPrKg] is kept
 * here for pure callers that need to resolve a still-programmed PR percentage; routine
 * execution normally consumes that already-resolved occurrence with a null PR.
 */
data class RoutineSetWeightRequest(
    val exercise: RoutineExercise,
    val setIndex: Int,
    val currentPrKg: Float?,
    val occurrenceMultiplier: Float = 1f,
    val manualAdjustmentPerCableKg: Float? = null,
)

/**
 * Applies the routine weight precedence without validating or clamping the result.
 */
object RoutineSetWeightResolver {
    operator fun invoke(request: RoutineSetWeightRequest): Float {
        val exercise = request.exercise
        val percent = exercise.setWeightsPercentOfPR.getOrNull(request.setIndex)
            ?: exercise.weightPercentOfPR
        val usesPr = exercise.usePercentOfPR &&
            request.currentPrKg != null &&
            request.currentPrKg > 0f &&
            percent > 0

        val programmedWeight = if (usesPr) {
            request.currentPrKg!! * percent / 100f
        } else {
            exercise.setWeightsPerCableKg.getOrNull(request.setIndex) ?: exercise.weightPerCableKg
        }
        val occurrenceWeight = programmedWeight * request.occurrenceMultiplier
        val roundedWeight = if (usesPr) occurrenceWeight.roundToHalfKg() else occurrenceWeight

        return roundedWeight + (request.manualAdjustmentPerCableKg ?: 0f)
    }
}

private fun Float.roundToHalfKg(): Float = (this * 2).roundToInt() / 2f
