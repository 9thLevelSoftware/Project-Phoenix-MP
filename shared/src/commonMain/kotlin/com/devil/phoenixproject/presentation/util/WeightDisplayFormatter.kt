package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.format

/**
 * Centralized weight display formatter that applies cable multiplication BEFORE unit conversion.
 *
 * Architecture Decision:
 * - Database stores `weightPerCableKg` (Float) — always per-cable, always kg.
 * - This formatter multiplies by cableCount to get total weight, then converts to display unit.
 * - Storage, sync DTOs, and BLE commands remain per-cable — this is display-only.
 * - HealthIntegration already multiplies by cableCount — do NOT use this formatter there.
 * - Portal already multiplies by WEIGHT_MULTIPLIER (transforms.ts) — sync must stay per-cable.
 *
 * Null cableCount defaults to 1, matching:
 * - HealthIntegration convention
 * - SessionSummary.cableMultiplier: `if (cableCount == 2) 2 else 1`
 */
object WeightDisplayFormatter {

    private const val KG_TO_LB = 2.20462f

    /**
     * Convert a per-cable kg weight to display weight (total, in user's preferred unit).
     *
     * @param weightPerCableKg Weight per cable in kilograms (from DB/model)
     * @param cableCount Number of cables (null = legacy data, defaults to 1)
     * @param unit User's preferred weight unit (KG or LB)
     * @return Display weight as Float (total weight in chosen unit)
     */
    fun toDisplayWeight(weightPerCableKg: Float, cableCount: Int?, unit: WeightUnit): Float {
        val multiplier = cableCount ?: 1
        val totalKg = weightPerCableKg * multiplier
        return when (unit) {
            WeightUnit.KG -> totalKg
            WeightUnit.LB -> totalKg * KG_TO_LB
        }
    }

    /**
     * Format a per-cable kg weight as a display string (total, in user's preferred unit).
     * Integers display without decimals; fractional values show 1 decimal place.
     *
     * @param weightPerCableKg Weight per cable in kilograms (from DB/model)
     * @param cableCount Number of cables (null = legacy data, defaults to 1)
     * @param unit User's preferred weight unit (KG or LB)
     * @return Formatted display string (e.g., "100" or "110.2")
     */
    fun formatDisplayWeight(weightPerCableKg: Float, cableCount: Int?, unit: WeightUnit): String {
        val display = toDisplayWeight(weightPerCableKg, cableCount, unit)
        return if (display % 1f == 0f) {
            display.toInt().toString()
        } else {
            display.format(1)
        }
    }
}
