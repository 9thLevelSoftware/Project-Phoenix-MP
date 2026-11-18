package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetSummaryFacts
import com.devil.phoenixproject.domain.powerloss.PowerStopReason

/**
 * Issue #1392 — pure set-summary projection (P5, ui-spec §6 with SD6 corrections).
 *
 * SD6 (binding): the headline `Stopped at rep 9 — 21% power loss (best: rep 2)`
 * renders ONLY with the adjacent `8 completed reps · lifting attempt 9`
 * clarification, and the completed count comes from `actualReps` — NEVER promoted
 * to match the headline. UI renders the engine's committed winner only; it never
 * chooses a new winner when both ROM and loss qualified (SD2).
 */
data class PowerLossSummaryUi(
    /** Null when no power block should render (plain REP sets — regression unchanged). */
    val headline: String?,
    /** SD6 clarification; must render adjacent to [headline] whenever [headline] != null. */
    val clarification: String?,
    val configLine: String,
    val helpLine: String = PowerLossUiCopy.SUMMARY_HELP,
    val secondaryLine: String? = null,
    val unavailableLine: String? = null,
    val disclosure: String = PowerLossUiCopy.SUMMARY_DISCLOSURE,
    val table: PowerLossRepTableUi,
    val savedRepsHeader: String,
    val powerActiveBadge: String?,
    val savedRepReadback: String?,
) {
    /** SD6 invariant: headline never renders without its clarification. */
    val headlineWithClarification: String?
        get() = if (headline == null) null else "$headline\n${clarification.orEmpty()}"
}

/** Enumerated summary variants (mutually exclusive headline forms). */
enum class PowerLossSummaryVariant {
    POWER_LOSS,
    SHORT_ROM,
    MAX_REPS_BACKSTOP,
    MANUAL,
    LEGACY_UNAVAILABLE,
}

fun powerLossSummaryVariant(facts: PowerLossSetSummaryFacts): PowerLossSummaryVariant = when {
    facts.winnerReason == PowerStopReason.POWER_LOSS -> PowerLossSummaryVariant.POWER_LOSS
    facts.winnerReason == PowerStopReason.SHORT_ROM -> PowerLossSummaryVariant.SHORT_ROM
    facts.winnerReason == PowerStopReason.MAX_REPS_BACKSTOP -> PowerLossSummaryVariant.MAX_REPS_BACKSTOP
    facts.attempts.isNotEmpty() -> PowerLossSummaryVariant.MANUAL
    else -> PowerLossSummaryVariant.LEGACY_UNAVAILABLE
}

/**
 * Render summary facts. [endedManually] distinguishes a manual stop from
 * safety/stall/release/disconnect flows (which keep their existing reason heading —
 * the power block adds observations only and never overwrites it).
 */
fun powerLossSummaryUi(
    facts: PowerLossSetSummaryFacts,
    savedNominalReps: Int? = facts.savedNominalReps,
    endedManually: Boolean = false,
    activeCables: Set<com.devil.phoenixproject.domain.powerloss.PowerCable> =
        setOf(
            com.devil.phoenixproject.domain.powerloss.PowerCable.A,
            com.devil.phoenixproject.domain.powerloss.PowerCable.B,
        ),
): PowerLossSummaryUi {
    val variant = powerLossSummaryVariant(facts)
    val stoppedAt = facts.stoppedAtWorkingAttempt
    val table = powerLossRepTable(
        attempts = facts.attempts,
        triggerOrdinal = stoppedAt,
        bestWorkingOrdinal = facts.bestWorkingOrdinal,
        thresholdPercent = facts.thresholdPercent,
        nearLimitLossPercent = maxOf(0, facts.thresholdPercent - 5),
        activeCables = activeCables,
    )
    val configLine = PowerLossUiCopy.summaryConfigLine(
        thresholdPercent = facts.thresholdPercent,
        maxWorkingReps = facts.maxWorkingReps,
        requireTwoConsecutive = facts.requireTwoConsecutive,
    )
    val savedRepsHeader = PowerLossUiCopy.savedRepsHeader(savedNominalReps)
    val powerActiveBadge =
        if (variant == PowerLossSummaryVariant.LEGACY_UNAVAILABLE) null else PowerLossUiCopy.POWER_ACTIVE_BADGE
    val savedRepReadback =
        if (variant == PowerLossSummaryVariant.LEGACY_UNAVAILABLE) {
            null
        } else {
            PowerLossUiCopy.savedRepReadback(savedNominalReps)
        }

    return when (variant) {
        PowerLossSummaryVariant.POWER_LOSS -> {
            val lossDisplay = facts.lossPercentAtStop?.let { displayPercent(it) } ?: 0
            PowerLossSummaryUi(
                headline = PowerLossUiCopy.summaryPowerLossHeadline(
                    stoppedAtAttempt = stoppedAt ?: 0,
                    lossPercentDisplay = lossDisplay,
                    bestWorkingOrdinal = facts.bestWorkingOrdinal,
                ),
                clarification = PowerLossUiCopy.summaryClarification(facts.completedRepCount, stoppedAt),
                configLine = configLine,
                secondaryLine = null,
                table = table,
                savedRepsHeader = savedRepsHeader,
                powerActiveBadge = powerActiveBadge,
                savedRepReadback = savedRepReadback,
            )
        }
        PowerLossSummaryVariant.SHORT_ROM -> {
            val trigger = facts.attempts.firstOrNull { it.workingConcentricOrdinal == stoppedAt }
            val romFacts = secondaryRomFacts(trigger)
            PowerLossSummaryUi(
                headline = PowerLossUiCopy.summaryShortRomHeadline(stoppedAt ?: 0),
                clarification = PowerLossUiCopy.summaryClarification(facts.completedRepCount, stoppedAt),
                configLine = configLine,
                secondaryLine = romFacts,
                table = table,
                savedRepsHeader = savedRepsHeader,
                powerActiveBadge = powerActiveBadge,
                savedRepReadback = savedRepReadback,
            )
        }
        PowerLossSummaryVariant.MAX_REPS_BACKSTOP -> {
            val secondary = facts.lossPercentAtStop?.let {
                "Loss at stop: ${displayPercent(it)}% — measured, not the stop cause"
            }
            PowerLossSummaryUi(
                headline = PowerLossUiCopy.summaryMaxRepsHeadline(facts.maxWorkingReps ?: facts.completedRepCount),
                clarification = PowerLossUiCopy.summaryClarification(facts.completedRepCount, stoppedAt),
                configLine = configLine,
                secondaryLine = secondary,
                table = table,
                savedRepsHeader = savedRepsHeader,
                powerActiveBadge = powerActiveBadge,
                savedRepReadback = savedRepReadback,
            )
        }
        PowerLossSummaryVariant.MANUAL -> PowerLossSummaryUi(
            headline = if (endedManually) PowerLossUiCopy.SUMMARY_MANUAL else null,
            clarification = null,
            configLine = configLine,
            secondaryLine = null,
            table = table,
            savedRepsHeader = savedRepsHeader,
            powerActiveBadge = powerActiveBadge,
            savedRepReadback = savedRepReadback,
        )
        PowerLossSummaryVariant.LEGACY_UNAVAILABLE -> PowerLossSummaryUi(
            headline = null,
            clarification = null,
            configLine = configLine,
            unavailableLine = PowerLossUiCopy.SUMMARY_UNAVAILABLE,
            table = table,
            savedRepsHeader = savedRepsHeader,
            powerActiveBadge = null,
            savedRepReadback = null,
        )
    }
}

/**
 * SHORT_ROM secondary facts: `Cable A: 82% of reference · best: rep 2`. Missing
 * MCP/loss stays unavailable — a ROM deficit is never converted into a loss.
 */
private fun secondaryRomFacts(trigger: PowerLossAttemptRecord?): String {
    val cableText = listOfNotNull(
        trigger?.romAPercentOfReference?.let { "Cable A: ${it.toInt()}% of reference" },
        trigger?.romBPercentOfReference?.let { "Cable B: ${it.toInt()}% of reference" },
    ).joinToString(" · ")
    return cableText
}
