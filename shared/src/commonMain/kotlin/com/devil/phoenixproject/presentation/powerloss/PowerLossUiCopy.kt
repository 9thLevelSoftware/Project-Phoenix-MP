package com.devil.phoenixproject.presentation.powerloss

/**
 * Issue #1392 — canonical power-loss UI copy (P5).
 *
 * Every string here is fixed by `prototype/design/ui-spec.md` §3–§7 and corrected
 * by signoff SD6 (which wins on conflict). Tests assert these strings verbatim.
 *
 * Copy contains NO physiological/RPE/efficacy claims and NO verified-audible-playback
 * claims (spec §1). Never localize by string-splitting: preset labels carry invisible
 * word joiners so `10%` and `Custom` cannot break across lines (SD6).
 */
object PowerLossUiCopy {

    /** Invisible word joiner (U+2060): blocks line breaks without changing glyphs. */
    const val WJ: String = "\u2060"

    // ── Config section (ui-spec §3) ────────────────────────────────────────────
    const val SECTION_HEADING = "Set target"
    const val REP_TARGET_LABEL = "Rep target"
    const val POWER_TARGET_LABEL = "Power-loss target"

    const val REP_HELPER = "End at your rep target."

    const val POWER_LEAD = "End when lifting power drops from your best working rep."
    const val POWER_HELP =
        "Mean Concentric Power (MCP) averages the combined lifting power of the active cables. " +
            "Warm-ups do not set the best rep. Power is checked after each lifting phase, not continuously."

    const val THRESHOLD_LABEL = "Power-loss threshold"
    const val CUSTOM_THRESHOLD_LABEL = "Custom threshold (%)"
    const val CUSTOM_THRESHOLD_HELPER = "Whole number from 5 to 40."
    const val CUSTOM_THRESHOLD_ERROR = "Enter a whole number from 5 to 40."

    const val HIGH_THRESHOLD_TITLE = "High power-loss threshold"
    const val HIGH_THRESHOLD_BODY =
        "Above 35% allows a larger drop before the set ends. Consider a lower threshold."

    const val MAX_WORKING_REPS_LABEL = "Max working reps"
    const val MAX_WORKING_REPS_HELPER =
        "The set ends at this limit even if power data is unavailable. Warm-ups are not included."

    /** Dynamic bound helper: `Enter 2–{maxAllowed} working reps.` (en dash). */
    fun maxWorkingRepsError(maxAllowed: Int): String = "Enter 2–$maxAllowed working reps."

    const val TWO_CONSECUTIVE_LABEL = "Require 2 consecutive reps"
    const val TWO_CONSECUTIVE_OFF_HELPER =
        "One qualifying working rep can end the set, starting with the second working attempt."

    /**
     * SD1-corrected helper: an unavailable attempt resets the streak only when the
     * observation has NEITHER trustworthy qualifying reason — not any null-MCP
     * observation (a trusted short-ROM observation may still qualify with missing power).
     */
    const val TWO_CONSECUTIVE_ON_HELPER =
        "Two adjacent qualifying attempts are required. Either power loss or short range of motion " +
            "can qualify. An attempt with neither qualifying reason — including an unavailable " +
            "attempt — resets the count. The max-reps limit still applies."

    const val SHORT_ROM_SAFEGUARD =
        "Short range of motion also counts: below 85% of the best rep\u2019s movement on either active cable."
    const val SHORT_ROM_SAFEGUARD_HELP =
        "A valid power baseline and at least two observed working lifting attempts are required. " +
            "Missing power alone does not count as fatigue. Initial range checks may be limited " +
            "until a trustworthy reference is available."

    const val ECHO_NOTE = "Stopping uses lifting power only. Lowering power is logged separately when available."

    const val NEAR_LIMIT_CUE_PREFIX = "Near-limit cue: "
    const val NEAR_LIMIT_CUE_SUFFIX = "% loss"
    const val NEAR_LIMIT_CUE_FIRST_POSITIVE = "Near-limit cue: first positive power loss"
    const val NEAR_LIMIT_CUE_HELP =
        "Plays once on a valid positive loss, if sound is enabled. A set-ending cue takes priority."

    const val LOCAL_DATA_DISCLOSURE =
        "Power-loss settings and detailed results stay on this device. Use backup or power-data " +
            "export to move them; they are not synced to the portal."

    const val SAVED_REPS_HEADER_PREFIX = "Saved reps: "
    const val POWER_ACTIVE_BADGE = "Power-loss target active"
    const val SAVED_REP_READBACK_SUFFIX = " (not used for this set)"
    const val SAVED_REP_READBACK_PREFIX = "Saved rep target: "

    /** `Near-limit cue: {max(0, T−5)}% loss`; at 5% the baseline is not promised a beep. */
    fun nearLimitCueReadback(thresholdPercent: Int): String {
        val cue = maxOf(0, thresholdPercent - 5)
        return if (cue == 0) NEAR_LIMIT_CUE_FIRST_POSITIVE else "$NEAR_LIMIT_CUE_PREFIX$cue$NEAR_LIMIT_CUE_SUFFIX"
    }

    /** `Saved reps: 12` header fact (SD6). */
    fun savedRepsHeader(savedNominalReps: Int?): String =
        SAVED_REPS_HEADER_PREFIX + (savedNominalReps?.toString() ?: "—")

    /** `Saved rep target: 12 (not used for this set)` POWER readback. */
    fun savedRepReadback(savedNominalReps: Int?): String =
        SAVED_REP_READBACK_PREFIX + (savedNominalReps?.toString() ?: "—") + SAVED_REP_READBACK_SUFFIX

    // ── Draft/apply semantics (ui-spec §3) ─────────────────────────────────────
    const val MODE_CONFIRMATION_TITLE = "Use Rep target for this mode?"
    const val MODE_CONFIRMATION_BODY_PREFIX = "Power-loss target is not available for "
    const val MODE_CONFIRMATION_BODY_SUFFIX = ". Your power-loss settings will be kept, but will not apply."
    const val MODE_CONFIRMATION_CANCEL = "Keep current mode"
    const val MODE_CONFIRMATION_CONFIRM = "Use Rep target"

    fun modeConfirmationBody(modeLabel: String): String =
        MODE_CONFIRMATION_BODY_PREFIX + modeLabel + MODE_CONFIRMATION_BODY_SUFFIX

    const val INCOMPATIBLE_SAVED_BANNER = "Power-loss target is not active in this mode."
    const val CAPABILITY_UNAVAILABLE = "Power-loss target unavailable for this trainer."
    const val CAPABILITY_UNAVAILABLE_SECONDARY = "Use Rep target to continue."

    const val TARGET_LOCKED = "Target locked for this set. Edit before the next set."
    const val TARGET_LOCKED_SHORT = "Target locked for this set."

    // ── Active HUD (ui-spec §4) ────────────────────────────────────────────────
    const val HUD_POWER_PANEL_LABEL = "Power loss"
    const val HUD_BELOW_TARGET = "Below target"
    const val HUD_NEAR_LIMIT = "Near power-loss target"
    const val HUD_WARMUP = "Warm-up — building movement reference"
    const val HUD_WARMUP_NOTE = "Warm-ups do not set best power."
    const val HUD_NO_BASELINE = "Waiting for valid lifting power"
    const val HUD_FIRST_ATTEMPT_NOTE = "Monitoring starts with the second working attempt."
    const val HUD_INVALID_POWER_PREFIX = "Power unavailable — "
    const val HUD_LAST_VALID_PREFIX = "Last valid · attempt "
    const val HUD_CAP_AND_STOP_ACTIVE = "Max-reps limit and manual stop remain active."
    const val HUD_ROM_REFERENCE_LIMITED = "Initial movement reference limited"
    const val HUD_ROM_REFERENCE_LIMITED_NOTE =
        "Range checks may be limited until a trustworthy reference is available."
    const val HUD_ENDING_SET = "Ending set…"
    const val HUD_REFERENCE_RESET = "Power reference reset"
    const val HUD_INTERRUPTED = "This set was interrupted. Restart the set to build a new reference."
    const val HUD_CONSECUTIVE_QUALIFYING_PREFIX = "Qualifying attempts: "
    const val HUD_CONSECUTIVE_OF = " of 2"
    const val HUD_QUALIFYING_POWER_LOSS = "Power loss reached"
    const val HUD_QUALIFYING_SHORT_ROM = "Short range of motion"
    const val HUD_QUALIFYING_RESET = "Qualifying count reset"
    const val HUD_LATEST_POWER_PREFIX = "Latest lifting power "
    const val HUD_BEST_PREFIX = "Best: rep "
    const val HUD_NEW_BEST_PREFIX = "New best · rep "
    const val HUD_SHORT_ROM_PREFIX = "Short range of motion · "

    /** `Power-loss target · 20%` readback (never `9 / 12`). */
    fun hudTargetReadback(thresholdPercent: Int): String = "Power-loss target · $thresholdPercent%"

    /** `Max 20 working reps` readback; empty when no finite cap applies. */
    fun hudCapReadback(maxWorkingReps: Int?): String =
        maxWorkingReps?.let { "Max $it working reps" } ?: ""

    /** `Target 20% · cue from 15%` (cue line is the near-limit threshold). */
    fun hudThresholdLine(thresholdPercent: Int, nearLimitLossPercent: Int): String =
        "Target $thresholdPercent% · cue from $nearLimitLossPercent%"

    /** `8 completed reps · attempt 9 not confirmed complete` (SD6 — completed from actualReps). */
    fun hudPendingLine(completedRepCount: Int, attemptOrdinal: Int): String =
        "$completedRepCount completed reps · attempt $attemptOrdinal not confirmed complete"

    /** `Working attempt 9 · lifting phase complete`. */
    fun hudCompletedAttemptLine(attemptOrdinal: Int): String =
        "Working attempt $attemptOrdinal · lifting phase complete"

    /** `Cable A 82% of reference` fragment. */
    fun cableRomFragment(cableLabel: String, percentOfReference: Int): String =
        "$cableLabel $percentOfReference% of reference"

    // ── Summary (ui-spec §6) ───────────────────────────────────────────────────
    /** SD6: the headline renders ONLY with this adjacent clarification. */
    fun summaryClarification(completedRepCount: Int, stoppedAtAttempt: Int?): String =
        "$completedRepCount completed reps · lifting attempt ${stoppedAtAttempt ?: "—"}"

    fun summaryConfigLine(thresholdPercent: Int, maxWorkingReps: Int?, requireTwoConsecutive: Boolean): String {
        val cap = maxWorkingReps?.let { "Max $it working reps" } ?: "Max reps —"
        val consecutive = if (requireTwoConsecutive) "Consecutive requirement on" else "Consecutive requirement off"
        return "Target $thresholdPercent% · $cap · $consecutive"
    }

    const val SUMMARY_HELP =
        "Rep numbers here refer to working lifting attempts. The final attempt may not have a confirmed lowering phase."
    const val SUMMARY_UNAVAILABLE = "Power-loss details unavailable for this set."
    const val SUMMARY_MANUAL = "Set stopped manually"
    const val SUMMARY_SHORT_ROM_PREFIX = "Stopped at attempt "
    const val SUMMARY_SHORT_ROM_SUFFIX = " — short range of motion"
    const val SUMMARY_MAX_REPS_SUFFIX = " — max-reps limit"
    const val SUMMARY_DISCLOSURE =
        "Detailed power data is stored on this device, not synced to the portal."

    /** `Stopped at rep 9 — 21% power loss (best: rep 2)` (display-rounded loss). */
    fun summaryPowerLossHeadline(stoppedAtAttempt: Int, lossPercentDisplay: Int, bestWorkingOrdinal: Int?): String {
        val best = bestWorkingOrdinal?.let { " (best: rep $it)" } ?: ""
        return "Stopped at rep $stoppedAtAttempt — $lossPercentDisplay% power loss$best"
    }

    /** `Stopped at attempt 9 — short range of motion`. */
    fun summaryShortRomHeadline(stoppedAtAttempt: Int): String =
        SUMMARY_SHORT_ROM_PREFIX + stoppedAtAttempt + SUMMARY_SHORT_ROM_SUFFIX

    /** `Stopped at 20 working reps — max-reps limit`. */
    fun summaryMaxRepsHeadline(maxWorkingReps: Int): String =
        "Stopped at $maxWorkingReps working reps" + SUMMARY_MAX_REPS_SUFFIX

    // ── History / per-rep details (ui-spec §6) ─────────────────────────────────
    const val DETAILS_EXPAND_LABEL = "Power details"
    const val DETAILS_CAPTION = "Mean Concentric Power (MCP) · W"
    const val COL_ATTEMPT = "Working attempt"
    const val COL_MCP = "MCP (W)"
    const val COL_LOSS = "Loss at decision"
    const val COL_REFERENCE = "Reference at decision"
    const val COL_ROM_A = "ROM A"
    const val COL_ROM_B = "ROM B"
    const val COL_CONCENTRIC = "Concentric"
    const val COL_ECCENTRIC = "Eccentric"
    const val COL_STATUS = "Status"
    const val BADGE_BEST = "Best"
    const val BADGE_TRIGGER = "Trigger"
    const val BADGE_NEAR_LIMIT = "Near-limit"
    const val BADGE_INVALID = "Invalid"
    const val STATUS_COMPLETED = "Completed"
    const val STATUS_PENDING = "Pending — not confirmed complete"
    const val MISSING = "—"
    const val ECCENTRIC_INTERRUPTED = "Not recorded — set ended before lowering completed"
    const val CABLE_INACTIVE = "Not active"
    const val ROM_UNTRUSTED = "Unavailable — reference not established"
    const val TRIGGER_ONLY_ROW_NOTE = "Trigger observation — no completed rep recorded"

    // ── Export (ui-spec §6) ────────────────────────────────────────────────────
    const val EXPORT_ENTRY = "Export power data"
    const val LEGACY_EXPORT_DISCLOSURE =
        "This format does not include power-loss settings or per-attempt details. Use power-data export to keep them."
    const val LEGACY_EXPORT_CHOOSE_POWER = "Choose power-data export"
    const val LEGACY_EXPORT_CONTINUE = "Export without power details"
}
