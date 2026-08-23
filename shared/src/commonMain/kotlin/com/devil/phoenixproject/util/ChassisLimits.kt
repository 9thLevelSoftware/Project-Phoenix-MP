package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.floor
import kotlin.math.min

/**
 * Per-cable chassis limits. Unknown hardware fail-closes to V-Form (100 kg/cable).
 *
 * CONFIG forceMax is [forceMaxKg] = min(selected + 10, chassisMax). Echo 0x4E does
 * not carry kilograms — only the 0x04 CONFIG floats are clamped.
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

    fun maxDisplay(model: PhoenixModel, unit: WeightUnit): Float {
        val kg = maxKgPerCable(model)
        return if (unit == WeightUnit.LB) {
            floor(UnitConverter.kgToLb(kg))
        } else {
            kg
        }
    }
}
