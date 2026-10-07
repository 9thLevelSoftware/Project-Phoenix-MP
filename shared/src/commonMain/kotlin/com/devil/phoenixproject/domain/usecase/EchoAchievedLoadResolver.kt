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
 * The achieved Echo load is the **measured peak per cable**: the cable-aware peak over the
 * accepted finite WORKING-window samples (`SetSummary.measuredWorkingPeakKgPerCable` /
 * `WorkoutSession.heaviestLiftKg`). It is deliberately NOT the working/phase average
 * (`workingAvgWeightKg`), NOT a warmup transient, and NOT the configured seed
 * (`weightPerCableKg`). Those keep their own labels and meanings.
 *
 * Availability contract (binding): a set with no accepted finite working telemetry has NO
 * measured load. Callers must render `null` as "Load unavailable" and must NOT fall back to
 * the configured weight (never present the placeholder seed as achieved) and must NOT render
 * the non-null `0` sentinel as "0 kg lifted". Derived estimates (rep-based 1RM, volume
 * scaling from achieved load) are suppressed when this returns null.
 *
 * Availability is decided by PROVENANCE - was working telemetry actually captured? - never
 * by comparing the measurement to the configured metadata. A legitimate measured load can
 * equal the configured weight, and it is still a measurement (merge-gate R1). Conversely a
 * warmup-only or empty window has no measurement even when the summary's compatibility
 * fallback reports a positive peak (merge-gate R2).
 */
object EchoAchievedLoadResolver {

    /** True when the recorded session ran in an Echo mode. */
    fun isEcho(session: WorkoutSession): Boolean =
        session.mode.contains("Echo", ignoreCase = true)

    /**
     * Live resolution from a just-computed set summary.
     *
     * Returns the measured working-window peak per cable, or null when no accepted finite
     * working sample was captured. The summary carries that provenance explicitly in
     * `measuredWorkingPeakKgPerCable`; the compatibility fields (`heaviestLiftKgPerCable`,
     * phase peaks/averages) still carry their fallback values for their own consumers and
     * are intentionally NOT consulted here.
     */
    fun fromSummary(summary: WorkoutState.SetSummary): Float? {
        if (!summary.isEchoMode) return null
        val peak = summary.measuredWorkingPeakKgPerCable ?: return null
        return peak.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Historical resolution from a persisted session row (read time; no rewrite, no
     * backfill).
     *
     * Since issue #1182 the measured column `WorkoutSession.heaviestLiftKg` records the
     * achieved Echo peak and uses the same non-null `0` sentinel as the set rows when there
     * was no accepted working telemetry, so `<= 0` resolves to unavailable and a positive
     * value is a real measurement (kept even when it equals the configured metadata).
     *
     * Rows written before that provenance scheme store the summary's compatibility
     * fallback in that column instead: for an empty telemetry window that fallback is
     * exactly the configured seed. A recorded measured value that equals the configured
     * metadata is therefore only trusted when the row carries independent telemetry
     * evidence (recorded peak forces); otherwise it is the conservative legacy placeholder
     * and resolves to unavailable rather than re-rendering the configured weight as
     * achievement (never the configured 11.02 lb). Nothing is rewritten or backfilled.
     */
    fun fromSession(session: WorkoutSession): Float? {
        if (!isEcho(session)) return null
        val measured = session.heaviestLiftKg ?: return null
        if (!measured.isFinite() || measured <= 0f) return null
        return if (hasMeasuredLoad(measured, session.weightPerCableKg, hasForceTelemetry(session))) {
            measured
        } else {
            null
        }
    }

    /**
     * Row-level measured-load provenance, shared with analytics consumers.
     *
     * True only for a finite positive recorded measured value that is either distinct from
     * the configured placeholder or backed by independent telemetry evidence. See
     * [fromSession] for the conservative handling of ambiguous legacy rows.
     */
    fun hasMeasuredLoad(measuredKg: Float?, configuredKg: Float, forceTelemetry: Boolean = false): Boolean {
        val measured = measuredKg ?: return false
        if (!measured.isFinite() || measured <= 0f) return false
        if (configuredKg.isFinite() && measured == configuredKg) return forceTelemetry
        return true
    }

    /**
     * Independent telemetry evidence on a persisted row: the set summary's force peaks are
     * computed from accepted samples, so a non-zero recorded force proves telemetry was
     * captured even when the recorded measured load happens to equal the configured
     * metadata. An empty-window row (the legacy placeholder signature) has zero forces.
     */
    fun hasForceTelemetry(session: WorkoutSession): Boolean =
        (session.peakForceConcentricA ?: 0f) != 0f ||
            (session.peakForceConcentricB ?: 0f) != 0f ||
            (session.peakForceEccentricA ?: 0f) != 0f ||
            (session.peakForceEccentricB ?: 0f) != 0f

    /**
     * Achieved per-cable load for a completed-set row.
     *
     * Echo sets resolve through the associated session's measured peak (a legacy
     * `CompletedSet.actualWeightKg` placeholder is not achievement); the set row's own `0`
     * sentinel is honored first. Non-Echo sets keep their recorded set weight unchanged.
     * Returns null only for an Echo set with no measurement, which callers must show as
     * "Load unavailable".
     */
    fun completedSetLoadKg(set: CompletedSet, session: WorkoutSession): Float? {
        if (!isEcho(session)) return set.actualWeightKg
        if (!set.actualWeightKg.isFinite() || set.actualWeightKg <= 0f) return null
        return fromSession(session)
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
