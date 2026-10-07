package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState

/**
 * Resolves the **achieved** per-cable load for an Echo set.
 *
 * Echo sends a level + eccentric profile, not a prescribed weight, so the load a user
 * actually lifted is measured telemetry, never the configured/command metadata. This is
 * the single source of truth for that measured load so completion, reports and outbound
 * sync can never disagree (issue #1182).
 *
 * The achieved Echo load is the **measured peak per cable**: the cable-aware working-window
 * peak already computed as `heaviestLiftKgPerCable` / `WorkoutSession.heaviestLiftKg`. It is
 * deliberately NOT the working/phase average (`workingAvgWeightKg`) and NOT the configured
 * seed (`weightPerCableKg`). Those keep their own labels and meanings.
 *
 * Availability contract (binding): a set with no accepted finite working telemetry has NO
 * measured load. Callers must render `null` as "Load unavailable" and must NOT fall back to
 * the configured weight (never present the placeholder seed as achieved) and must NOT render
 * the non-null `0` sentinel as "0 kg lifted". Derived estimates (rep-based 1RM, volume
 * scaling from achieved load) are suppressed when this returns null.
 */
object EchoAchievedLoadResolver {

    /** True when the recorded session ran in an Echo mode. */
    fun isEcho(session: WorkoutSession): Boolean =
        session.mode.contains("Echo", ignoreCase = true)

    /**
     * Live resolution from a just-computed set summary.
     *
     * Returns the measured peak per cable, or null when there is no real measurement. A summary
     * built from an empty telemetry window stores the configured fallback weight in
     * `heaviestLiftKgPerCable`; that is command metadata, not a measurement, so it is rejected
     * here (peak == configured is exactly the placeholder signature) rather than masquerading
     * as achieved load. The same rule keeps a historical `toSetSummary()` row honest: its
     * `heaviestLiftKgPerCable` collapses to the configured weight when no measurement exists.
     */
    fun fromSummary(summary: WorkoutState.SetSummary): Float? {
        if (!summary.isEchoMode) return null
        val peak = summary.heaviestLiftKgPerCable
        if (!peak.isFinite() || peak <= 0f) return null
        val configured = summary.configuredWeightKgPerCable
        if (configured.isFinite() && peak == configured) return null
        return peak
    }

    /**
     * Historical resolution from a persisted session row.
     *
     * `WorkoutSession.heaviestLiftKg` is the measured column. It is trusted as achieved load
     * only when it is a positive finite value that is not merely the configured placeholder
     * (`weightPerCableKg`). When there was no accepted telemetry the recorded heaviest equals
     * the configured seed; that equality is exactly the placeholder signature, so it resolves
     * to unavailable rather than re-rendering the configured weight as achievement.
     *
     * Legacy rows are resolved at read time. Nothing is rewritten or backfilled.
     */
    fun fromSession(session: WorkoutSession): Float? {
        if (!isEcho(session)) return null
        val measured = session.heaviestLiftKg ?: return null
        if (!measured.isFinite() || measured <= 0f) return null
        val configured = session.weightPerCableKg
        if (configured.isFinite() && measured == configured) {
            // No distinct measurement: the recorded heaviest is the configured seed.
            return null
        }
        return measured
    }

    /**
     * Achieved per-cable load for a completed-set row.
     *
     * Echo sets resolve through the associated session's measured peak (a legacy
     * `CompletedSet.actualWeightKg` placeholder is not achievement). Non-Echo sets keep their
     * recorded set weight unchanged. Returns null only for an Echo set with no measurement,
     * which callers must show as "Load unavailable".
     */
    fun completedSetLoadKg(set: CompletedSet, session: WorkoutSession): Float? {
        return if (isEcho(session)) fromSession(session) else set.actualWeightKg
    }

    /**
     * Primary per-cable load a saved-session row should display.
     *
     * Non-Echo keeps its configured/command weight (for a fixed-load set that IS the achieved
     * load and its display must not change). Echo resolves to the measured peak, or null when
     * there is no measurement, which callers render as "Load unavailable".
     */
    fun primaryLoadKg(session: WorkoutSession): Float? {
        return if (isEcho(session)) fromSession(session) else session.weightPerCableKg
    }
}
