package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadAdjustment
import com.devil.phoenixproject.util.Constants

class ApplyEquipmentRackLoadUseCase {
    fun calculate(
        programmedWeightPerCableKg: Float,
        displayMultiplier: Int,
        selectedItems: List<RackItem>,
        isEchoMode: Boolean,
        validatorMinimumPerCableKg: Float = Constants.MIN_WEIGHT_KG,
    ): RackLoadAdjustment {
        val multiplier = displayMultiplier.coerceAtLeast(1)
        val uniqueItems = selectedItems.distinctBy { it.id }
        val externalAddedLoadKg = uniqueItems.loadFor(RackItemBehavior.ADDED_RESISTANCE)
        val counterweightKg = uniqueItems.loadFor(RackItemBehavior.COUNTERWEIGHT)
        val displayLoadKg = (
            programmedWeightPerCableKg.coerceAtLeast(0f) * multiplier +
                externalAddedLoadKg -
                counterweightKg
            ).coerceAtLeast(0f)
        val adjustedMachineWeightPerCableKg = if (isEchoMode) {
            programmedWeightPerCableKg
        } else {
            (programmedWeightPerCableKg - (counterweightKg / multiplier))
                .coerceIn(
                    validatorMinimumPerCableKg.coerceAtLeast(Constants.MIN_WEIGHT_KG),
                    Constants.MAX_WEIGHT_PER_CABLE_KG,
                )
        }

        return RackLoadAdjustment(
            selectedItems = uniqueItems,
            externalAddedLoadKg = externalAddedLoadKg,
            counterweightKg = counterweightKg,
            displayLoadKg = displayLoadKg,
            adjustedMachineWeightPerCableKg = adjustedMachineWeightPerCableKg,
        )
    }

    fun calculateBodyweightEffectiveLoadKg(
        bodyWeightKg: Float,
        percentage: Float,
        selectedItems: List<RackItem>,
    ): Float {
        if (bodyWeightKg <= 0f || percentage <= 0f) return 0f
        val uniqueItems = selectedItems.distinctBy { it.id }
        return (
            bodyWeightKg * percentage +
                uniqueItems.loadFor(RackItemBehavior.ADDED_RESISTANCE) -
                uniqueItems.loadFor(RackItemBehavior.COUNTERWEIGHT)
            ).coerceAtLeast(0f)
    }

    private fun List<RackItem>.loadFor(behavior: RackItemBehavior): Float = filter {
        it.enabled && it.behavior == behavior
    }.sumOf { it.weightKg.toDouble() }.toFloat()
}
