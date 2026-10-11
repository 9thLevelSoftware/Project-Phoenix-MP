package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import com.devil.phoenixproject.domain.model.WeightUnit
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.label_kg
import projectphoenix.shared.generated.resources.label_lbs
import kotlin.math.roundToInt

/**
 * Display text for Smart Insights weights and volumes.
 *
 * Insight calculations stay in kilograms. These helpers only convert what the
 * screen prints. Volume totals are already aggregated (cable count is included
 * in the insight math); [WeightDisplayFormatter] changes the unit and does not
 * take a cable count. That is the same kilogram-to-pound factor History and
 * Home volume text uses through `UnitConverter.kgToLb`.
 */
object SmartInsightsWeightText {

    /** The localized unit word from the shared `label_kg` / `label_lbs` resources. */
    @Composable
    fun unitLabel(unit: WeightUnit): String =
        stringResource(if (unit == WeightUnit.LB) Res.string.label_lbs else Res.string.label_kg)

    /**
     * Weekly-volume cell, in whole display units like the other volume totals.
     * The column header carries [unitLabel]. A non-finite volume prints 0.
     */
    fun volumeAmount(volumeKg: Float, unit: WeightUnit): String {
        val display = WeightDisplayFormatter.toDisplayWeight(volumeKg, unit)
        return if (display.isFinite()) display.roundToInt().toString() else "0"
    }

    /** Readiness acute/chronic volume, followed by [unitLabel]. */
    fun volumeWithUnit(volumeKg: Float, unit: WeightUnit, unitLabel: String): String =
        "${volumeAmount(volumeKg, unit)} $unitLabel"

    /** Plateau load is a per-cable weight, so it keeps the formatter's one decimal. */
    fun plateauLoad(weightPerCableKg: Float, unit: WeightUnit, unitLabel: String): String =
        "${WeightDisplayFormatter.formatDisplayWeight(weightPerCableKg, unit)} $unitLabel"
}
