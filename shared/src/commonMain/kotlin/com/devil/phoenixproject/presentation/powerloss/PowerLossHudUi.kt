package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossHudState

/**
 * Issue #1392 — pure HUD projection (P5, ui-spec §4 with SD6 corrections).
 *
 * Renders the live HUD strictly from [PowerLossHudState] facts:
 *  - authoritative completed count (`actualReps`) is never promoted to the
 *    headline working-attempt identity (SD6);
 *  - the readback is `Power-loss target · 20%` / `Max 20 working reps` — NEVER
 *    `9 / 12` from nominal reps;
 *  - missing data shows `—` with a reason, never a fabricated zero;
 *  - the committed `Ending set…` state is distinct from the completed transition.
 */
data class PowerLossHudUi(
    /** False for REP sets — the panel renders nothing (REP regression unchanged). */
    val visible: Boolean,
    val targetReadback: String,
    val capReadback: String,
    /** `8 completed reps · attempt 9 not confirmed complete` or the completed-attempt line. */
    val completedLine: String,
    val statusLine: String,
    val lossDisplay: String,
    val latestPowerLine: String,
    val bestLine: String,
    val thresholdLine: String,
    val nearLimit: Boolean,
    /** Once-only cue identity: cue already emitted for this set. */
    val nearLimitCueEmitted: Boolean,
    val invalidPowerLine: String?,
    val lastValidLine: String?,
    val shortRomLine: String?,
    val endingLine: String?,
    val referenceResetLine: String?,
    /** E04 caveat while the reference's ROM/mask is untrusted (ui-spec §4). */
    val referenceLimitedNote: String? = null,
    val lockedNote: String = PowerLossUiCopy.TARGET_LOCKED,
    val warmupNote: String? = null,
    val capAndStopActive: String = PowerLossUiCopy.HUD_CAP_AND_STOP_ACTIVE,
) {
    /** SD6 readback identity: never a nominal-rep `9 / 12` fraction. */
    val nominalRepFractionHidden: Boolean get() = true
}

/**
 * Project HUD facts into display state.
 *
 * @param warmupActive Warm-up phase (counters kept separate; no fatigue/ROM cue/stop).
 * @param referenceRomUncertain Initial ROM/mask uncertainty caveat.
 * @param interrupted Restart/reconnect/restored interruption state.
 * @param latestPowerWatts Last sealed concentric MCP in watts (display only), or null.
 * @param bestWatts Best working MCP, or null.
 * @param qualifyingCount Two-consecutive streak count (1..2), or null when the
 *   requirement is off.
 */
fun powerLossHudUi(
    state: PowerLossHudState,
    warmupActive: Boolean = false,
    referenceRomUncertain: Boolean = false,
    interrupted: Boolean = false,
    latestPowerWatts: Float? = null,
    bestWatts: Float? = null,
    qualifyingCount: Int? = null,
): PowerLossHudUi {
    if (!state.powerActive) return inactiveHudUi()

    val attempt = state.currentAttemptOrdinal
    val completed = state.completedRepCount
    val completedLine = if (attempt > completed) {
        PowerLossUiCopy.hudPendingLine(completed, attempt)
    } else if (attempt > 0) {
        PowerLossUiCopy.hudCompletedAttemptLine(attempt)
    } else {
        PowerLossUiCopy.hudPendingLine(completed, attempt)
    }

    val shortRomLine = state.cableRomPercentOfReference.entries
        .filter { it.value < SHORT_ROM_REFERENCE_PERCENT }
        .sortedBy { it.key.name }
        .joinToString(" · ") { (cable, percent) ->
            PowerLossUiCopy.cableRomFragment(cableLabel(cable), percent.toInt())
        }
        .takeIf { it.isNotEmpty() }
        ?.let { PowerLossUiCopy.HUD_SHORT_ROM_PREFIX + it }

    val invalidPowerLine = state.invalidPowerReason?.let {
        PowerLossUiCopy.HUD_INVALID_POWER_PREFIX + it
    }
    val lastValidLine = state.lastValidAttemptOrdinal?.let {
        PowerLossUiCopy.HUD_LAST_VALID_PREFIX + it
    }

    val lossDisplay = state.lastLossPercent?.let { "${displayPercent(it)}%" } ?: PowerLossUiCopy.MISSING
    val latestPowerLine = if (latestPowerWatts != null) {
        PowerLossUiCopy.HUD_LATEST_POWER_PREFIX + "${latestPowerWatts.toInt()} W"
    } else {
        PowerLossUiCopy.HUD_LATEST_POWER_PREFIX + PowerLossUiCopy.MISSING
    }
    val bestLine = if (state.referenceEstablished && bestWatts != null) {
        PowerLossUiCopy.HUD_BEST_PREFIX + "${state.referenceWorkingOrdinal ?: "—"} · ${bestWatts.toInt()} W"
    } else {
        PowerLossUiCopy.HUD_BEST_PREFIX + PowerLossUiCopy.MISSING
    }

    val nearLimit = state.lastLossPercent != null &&
        state.lastLossPercent > 0f &&
        state.lastLossPercent >= state.nearLimitLossPercent

    val statusLine = when {
        state.endingSet -> PowerLossUiCopy.HUD_ENDING_SET
        warmupActive -> PowerLossUiCopy.HUD_WARMUP
        interrupted -> PowerLossUiCopy.HUD_INTERRUPTED
        invalidPowerLine != null -> invalidPowerLine
        !state.referenceEstablished -> PowerLossUiCopy.HUD_NO_BASELINE
        qualifyingCount != null && qualifyingCount > 0 ->
            PowerLossUiCopy.HUD_CONSECUTIVE_QUALIFYING_PREFIX + qualifyingCount + PowerLossUiCopy.HUD_CONSECUTIVE_OF
        nearLimit -> PowerLossUiCopy.HUD_NEAR_LIMIT
        // E04 (ui-spec §4 `Initial ROM/mask uncertain`): while the reference's
        // ROM/mask is untrusted the generic first-attempt/normal states do not
        // apply — range checks are limited until a trustworthy reference exists.
        referenceRomUncertain -> PowerLossUiCopy.HUD_ROM_REFERENCE_LIMITED
        attempt == 1 -> PowerLossUiCopy.HUD_FIRST_ATTEMPT_NOTE
        else -> PowerLossUiCopy.HUD_BELOW_TARGET
    }

    return PowerLossHudUi(
        visible = true,
        targetReadback = PowerLossUiCopy.hudTargetReadback(state.thresholdPercent),
        capReadback = PowerLossUiCopy.hudCapReadback(state.maxWorkingReps),
        completedLine = completedLine,
        statusLine = statusLine,
        lossDisplay = lossDisplay,
        latestPowerLine = latestPowerLine,
        bestLine = bestLine,
        thresholdLine = PowerLossUiCopy.hudThresholdLine(state.thresholdPercent, state.nearLimitLossPercent),
        nearLimit = nearLimit,
        nearLimitCueEmitted = state.nearLimitCueEmitted,
        invalidPowerLine = invalidPowerLine,
        lastValidLine = lastValidLine,
        shortRomLine = shortRomLine,
        endingLine = if (state.endingSet) {
            PowerLossUiCopy.HUD_ENDING_SET
        } else {
            null
        },
        referenceResetLine = if (interrupted) {
            PowerLossUiCopy.HUD_REFERENCE_RESET
        } else {
            null
        },
        referenceLimitedNote = if (referenceRomUncertain) {
            PowerLossUiCopy.HUD_ROM_REFERENCE_LIMITED_NOTE
        } else {
            null
        },
        warmupNote = if (warmupActive) PowerLossUiCopy.HUD_WARMUP_NOTE else null,
    )
}

private fun inactiveHudUi(): PowerLossHudUi = PowerLossHudUi(
    visible = false,
    targetReadback = "",
    capReadback = "",
    completedLine = "",
    statusLine = "",
    lossDisplay = PowerLossUiCopy.MISSING,
    latestPowerLine = "",
    bestLine = "",
    thresholdLine = "",
    nearLimit = false,
    nearLimitCueEmitted = false,
    invalidPowerLine = null,
    lastValidLine = null,
    shortRomLine = null,
    endingLine = null,
    referenceResetLine = null,
)

/** Display-only rounding to whole percent, clamped 0–100 (never used for decisions). */
internal fun displayPercent(value: Float): Int = value.coerceIn(0f, 100f).toInt()

/** Strictly below 85% of the reference ROM is short on an active cable; equality is not (SD2). */
internal const val SHORT_ROM_REFERENCE_PERCENT: Float = 85f

private fun cableLabel(cable: PowerCable): String = when (cable) {
    PowerCable.A -> "Cable A"
    PowerCable.B -> "Cable B"
}
