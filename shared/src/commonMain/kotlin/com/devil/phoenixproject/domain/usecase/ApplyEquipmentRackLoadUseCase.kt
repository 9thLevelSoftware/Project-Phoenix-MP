package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadAdjustment
import com.devil.phoenixproject.util.Constants

class ApplyEquipmentRackLoadUseCase {
    fun calculate(
        programmedWeightPerCableKg: Float,
        physicalCableCount: Int,
        selectedItems: List<RackItem>,
        isEchoMode: Boolean,
        validatorMinimumPerCableKg: Float = Constants.MIN_WEIGHT_KG,
        behaviorOverrides: Map<String, RackItemBehavior> = emptyMap(),
    ): RackLoadAdjustment {
        val cableCount = physicalCableCount.coerceIn(1, 2)
        val uniqueItems = selectedItems.distinctBy { it.id }
        val externalAddedLoadKg = uniqueItems.loadFor(RackItemBehavior.ADDED_RESISTANCE, behaviorOverrides)
        val counterweightKg = uniqueItems.loadFor(RackItemBehavior.COUNTERWEIGHT, behaviorOverrides)
        val displayLoadKg = (
            programmedWeightPerCableKg.coerceAtLeast(0f) * cableCount +
                externalAddedLoadKg -
                counterweightKg
            ).coerceAtLeast(0f)
        val adjustedMachineWeightPerCableKg = if (isEchoMode) {
            programmedWeightPerCableKg
        } else {
            (programmedWeightPerCableKg - (counterweightKg / cableCount))
                .coerceIn(
                    // F373: clamp the lower bound below the ceiling. A caller-supplied
                    // validatorMinimumPerCableKg above MAX_WEIGHT_PER_CABLE_KG would make
                    // coerceIn(min, max) have min > max and throw.
                    validatorMinimumPerCableKg.coerceIn(Constants.MIN_WEIGHT_KG, Constants.MAX_WEIGHT_PER_CABLE_KG),
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
        behaviorOverrides: Map<String, RackItemBehavior> = emptyMap(),
    ): Float {
        if (bodyWeightKg <= 0f || percentage <= 0f) return 0f
        val uniqueItems = selectedItems.distinctBy { it.id }
        return (
            bodyWeightKg * percentage +
                uniqueItems.loadFor(RackItemBehavior.ADDED_RESISTANCE, behaviorOverrides) -
                uniqueItems.loadFor(RackItemBehavior.COUNTERWEIGHT, behaviorOverrides)
            ).coerceAtLeast(0f)
    }

    /**
     * Resolve effective behavior: override if present, otherwise global.
     */
    private fun List<RackItem>.loadFor(
        behavior: RackItemBehavior,
        overrides: Map<String, RackItemBehavior> = emptyMap(),
    ): Float = filter { item ->
        val effectiveBehavior = overrides[item.id] ?: item.behavior
        item.enabled && effectiveBehavior == behavior
    }.sumOf { it.weightKg.toDouble() }.toFloat()
}
