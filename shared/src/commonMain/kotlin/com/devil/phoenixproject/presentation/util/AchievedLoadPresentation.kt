package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import kotlin.math.roundToInt

/**
 * The single source of truth for how a load is rendered on the reporting surfaces
 * (Recent Sessions, Home, Set Summary, History) - issue #1182.
 *
 * Every surface used to format its own load inline, so "what does the user actually see?"
 * was only testable by rendering the composables. Extracting the decision here means the
 * exact rendered strings are unit-testable across kg/lb and measured/unavailable, and the
 * composables become dumb `Text(...)` call sites over these values.
 *
 * Binding display contract:
 *  - an Echo set shows the ACHIEVED measured peak per cable, never the configured seed;
 *  - an Echo set with no accepted measurement shows [LOAD_UNAVAILABLE] - never "0 kg lifted"
 *    and never a fallback to the configured weight;
 *  - a non-Echo set keeps its configured/set weight, which for a fixed-load set IS the
 *    achieved load and must not change.
 */
object AchievedLoadPresentation {

    /** The one wording used for "we have no measured load" everywhere. */
    const val LOAD_UNAVAILABLE = "Load unavailable"

    private fun unitLabel(weightUnit: WeightUnit): String = if (weightUnit == WeightUnit.LB) "lbs" else "kg"

    /**
     * Recent Sessions / quick-history row text - the primary load cell.
     *
     * [formatWeight] is the host's own weight formatter (unit conversion lives there), so the
     * value here stays per-cable and this layer never multiplies by a display multiplier.
     */
    fun sessionLoadText(
        session: WorkoutSession,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): String = EchoAchievedLoadResolver.primaryLoadKg(session)
        ?.let { formatWeight(it, weightUnit) }
        ?: LOAD_UNAVAILABLE

    /** Home "recent activity" secondary line: `9 reps • 176.4 lbs` or `9 reps • Load unavailable`. */
    fun homeRecentActivityLine(session: WorkoutSession, weightUnit: WeightUnit): String {
        val reps = session.workingReps
        val achievedLoadKg = EchoAchievedLoadResolver.primaryLoadKg(session) ?: return "$reps reps • $LOAD_UNAVAILABLE"
        val displayWeight = WeightDisplayFormatter.formatDisplayWeight(achievedLoadKg, weightUnit)
        return "$reps reps • $displayWeight ${unitLabel(weightUnit)}"
    }

    /** The Set Summary's primary weight stat: label, value and unit suffix. */
    data class SetSummaryPrimary(
        val label: String,
        val valueText: String,
        val unitText: String,
    )

    /**
     * Set Summary primary stat. Echo is explicitly labelled "Peak load" because it is a
     * measured peak; a fixed-load set keeps "Set Weight". The unit suffix disappears with the
     * value when there is no measurement, so an unavailable row never shows a stray "(lbs/cable)".
     */
    fun setSummaryPrimary(summary: WorkoutState.SetSummary, weightUnit: WeightUnit): SetSummaryPrimary {
        val unitLabel = unitLabel(weightUnit)
        if (!summary.isEchoMode) {
            return SetSummaryPrimary(
                label = "Set Weight",
                valueText = WeightDisplayFormatter.toDisplayWeight(summary.configuredWeightKgPerCable, weightUnit)
                    .roundToInt()
                    .toString(),
                unitText = "($unitLabel/cable)",
            )
        }
        val achievedEchoLoadKg = EchoAchievedLoadResolver.fromSummary(summary)
            ?: return SetSummaryPrimary(label = "Peak load", valueText = LOAD_UNAVAILABLE, unitText = "")
        return SetSummaryPrimary(
            label = "Peak load",
            valueText = WeightDisplayFormatter.toDisplayWeight(achievedEchoLoadKg, weightUnit).roundToInt().toString(),
            unitText = "($unitLabel/cable)",
        )
    }

    /**
     * History completed-set row text: `9 x 176.4 lb`, or `9 x Load unavailable` for an Echo
     * set with no measurement. Echo rows resolve through the associated session at read time.
     *
     * Note the unit suffix here is `weightUnit.name.lowercase()` ("lb"), which is what this
     * surface has always rendered; the other surfaces use "lbs". That wording difference is
     * pre-existing and deliberately untouched - issue #1182 is about WHICH load is shown.
     */
    fun historySetText(
        set: CompletedSet,
        session: WorkoutSession,
        weightUnit: WeightUnit,
    ): String {
        val resolvedLoadKg = EchoAchievedLoadResolver.completedSetLoadKg(set, session)
            ?: return "${set.actualReps} x $LOAD_UNAVAILABLE"
        val unitLabel = weightUnit.name.lowercase()
        val displayWeight = WeightDisplayFormatter.formatDisplayWeight(resolvedLoadKg, weightUnit)
        return "${set.actualReps} x $displayWeight $unitLabel"
    }
}
