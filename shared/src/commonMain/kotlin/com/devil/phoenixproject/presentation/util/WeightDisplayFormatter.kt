package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.UnitConverter
import com.devil.phoenixproject.util.format

/**
 * Centralized weight display formatter.
 *
 * Phoenix stores, syncs, recommends, and commands machine load as per-cable kg.
 * Cable count metadata must not change ordinary
 * selected-load display. Total two-cable text is available only through the
 * explicitly named total helper methods below.
 */
object WeightDisplayFormatter {

    /**
     * Convert per-cable kg to the user's selected display unit.
     *
     * Ordinary load display is per-cable. Cable count is not an input;
     * two-cable totals go through [toTwoCableTotalDisplayWeight].
     */
    fun toDisplayWeight(weightPerCableKg: Float, unit: WeightUnit): Float =
        toPerCableDisplayWeight(weightPerCableKg, unit)

    /**
     * Format per-cable kg in the user's selected display unit.
     *
     * Ordinary load display is per-cable. Cable count is not an input;
     * two-cable totals go through [formatTwoCableTotalWeight].
     */
    fun formatDisplayWeight(weightPerCableKg: Float, unit: WeightUnit): String =
        formatPerCableWeight(weightPerCableKg, unit)

    fun toPerCableDisplayWeight(weightPerCableKg: Float, unit: WeightUnit): Float = when (unit) {
        WeightUnit.KG -> weightPerCableKg
        WeightUnit.LB -> weightPerCableKg * UnitConverter.KG_TO_LB
    }

    fun formatPerCableWeight(weightPerCableKg: Float, unit: WeightUnit): String =
        formatNumeric(toPerCableDisplayWeight(weightPerCableKg, unit))

    fun toTwoCableTotalDisplayWeight(weightPerCableKg: Float, unit: WeightUnit): Float =
        toPerCableDisplayWeight(weightPerCableKg * 2f, unit)

    fun formatTwoCableTotalWeight(weightPerCableKg: Float, unit: WeightUnit): String =
        formatNumeric(toTwoCableTotalDisplayWeight(weightPerCableKg, unit))

    private fun formatNumeric(display: Float): String = if (display % 1f == 0f) {
        display.toInt().toString()
    } else {
        display.format(1)
    }
}
