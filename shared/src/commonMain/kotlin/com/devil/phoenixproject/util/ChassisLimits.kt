package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Per-cable chassis limits. Unknown hardware fail-closes to V-Form (100 kg/cable).
 *
 * CONFIG forceMax is [forceMaxKg] = min(selected + 10, chassisMax). Finite-rep
 * progression is [finiteRepProgressionKg] so implied peak cannot exceed chassis.
 * Echo 0x4E does not carry kilograms — only the 0x04 CONFIG floats are clamped.
 */
object ChassisLimits {
    const val V_FORM_KG_PER_CABLE = 100f
    const val TRAINER_PLUS_KG_PER_CABLE = 110f
    const val UNKNOWN_FAIL_CLOSED_KG_PER_CABLE = V_FORM_KG_PER_CABLE
    const val FORCE_MAX_HEADROOM_KG = 10f

    fun modelOf(state: ConnectionState): PhoenixModel =
        (state as? ConnectionState.Connected)?.hardwareModel ?: PhoenixModel.Unknown

    fun maxKgPerCable(model: PhoenixModel): Float = when (model) {
        PhoenixModel.TrainerPlus -> TRAINER_PLUS_KG_PER_CABLE
        PhoenixModel.VFormTrainer,
        PhoenixModel.Unknown,
        -> UNKNOWN_FAIL_CLOSED_KG_PER_CABLE
    }

    fun forceMaxKg(weightPerCableKg: Float, model: PhoenixModel): Float =
        min(weightPerCableKg + FORCE_MAX_HEADROOM_KG, maxKgPerCable(model))

    /**
     * Finite-rep CONFIG progression so implied peak
     * `weight + progression * (reps-1)` cannot exceed chassis max.
     * AMRAP / Just Lift leave the requested increment unchanged ([forceMaxKg] is the cap).
     * Negative regression is unchanged.
     */
    fun finiteRepProgressionKg(
        requestedKg: Float,
        weightPerCableKg: Float,
        reps: Int,
        model: PhoenixModel,
        unlimitedReps: Boolean,
    ): Float {
        if (!requestedKg.isFinite()) return 0f
        if (unlimitedReps || requestedKg <= 0f) return requestedKg
        val steps = max(reps - 1, 1)
        val headroom = ((maxKgPerCable(model) - weightPerCableKg) / steps).coerceAtLeast(0f)
        return min(requestedKg, headroom)
    }

    fun maxDisplay(model: PhoenixModel, unit: WeightUnit): Float {
        val kg = maxKgPerCable(model)
        return if (unit == WeightUnit.LB) {
            floor(UnitConverter.kgToLb(kg))
        } else {
            kg
        }
    }
}
