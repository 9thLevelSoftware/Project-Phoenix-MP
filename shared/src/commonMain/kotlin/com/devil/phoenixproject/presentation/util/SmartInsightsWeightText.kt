package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit

/**
 * Display text for Smart Insights weights and volumes.
 *
 * Insight calculations stay in kilograms. These helpers only convert what the
 * screen prints. Volume totals are already aggregated (cable count is included
 * in the insight math); [WeightDisplayFormatter] changes the unit and does not
 * take a cable count. That is the same kilogram-to-pound factor History and
 * Home volume text uses through `UnitConverter.kgToLb`.
 *
 * The unit word is the existing "kg" / "lbs" pair from Home recent activity,
 * History measured peak, and the volume chart (`label_kg` / `label_lbs`).
 */
object SmartInsightsWeightText {

    fun unitLabel(unit: WeightUnit): String = if (unit == WeightUnit.LB) "lbs" else "kg"

    /** Weekly-volume cell. The column header carries [unitLabel]. */
    fun volumeAmount(volumeKg: Float, unit: WeightUnit): String =
        WeightDisplayFormatter.formatDisplayWeight(volumeKg, unit)

    /** Readiness acute/chronic volume, including the unit word. */
    fun volumeWithUnit(volumeKg: Float, unit: WeightUnit): String =
        "${volumeAmount(volumeKg, unit)} ${unitLabel(unit)}"

    /** Plateau load is a per-cable weight. */
    fun plateauLoad(weightPerCableKg: Float, unit: WeightUnit): String =
        "${WeightDisplayFormatter.formatDisplayWeight(weightPerCableKg, unit)} ${unitLabel(unit)}"
}
