package com.devil.phoenixproject.data.maintenance

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.presentation.manager.CableTelemetryArchivalRetention
import com.devil.phoenixproject.presentation.manager.RetentionDecision

/**
 * Pure retroactive compaction planner (issue #1425). No DB, DI or UI access.
 *
 * Gate order is fixed and matches the integration model: G1 → G2 → G3 → G4 → NULL_POSITION
 * (I1) → `planRetention(romEnabled = true)` (write-path parity with #1386) → G5 → G7.
 * Every gate failure preserves the set with a stable [PreserveReason]; under-trimming is the
 * safe direction everywhere.
 */
object RetroactiveTelemetryCompactionPlanner {

    /**
     * @param samples archival samples in id order (I2), or null when the mapper found a NULL
     *   position (I1: NULL_POSITION preserves the whole set — never coalesced to 0).
     * @param minUnitVersion MIN(MetricSample.metric_unit_version) for the set.
     * @param firstRepStartMs MIN(RepMetric.startTimestamp) over ALL rows — no zero filtering
     *   (signoff #4: a 0/missing start keeps the set).
     * @param epochRepairApplied whether the epoch-zero-session-starts repair key is recorded.
     */
    fun plan(
        candidate: CompactionCandidate,
        samples: List<WorkoutMetric>?,
        minUnitVersion: Long,
        firstRepStartMs: Long?,
        epochRepairApplied: Boolean,
    ): SetCompactionPlan {
        // G1: unrepairable/corrupt session start cannot anchor a safe historical boundary.
        if (candidate.timestamp <= 0L || !epochRepairApplied) {
            return SetCompactionPlan.Preserve(PreserveReason.CORRUPT_OR_UNREPAIRED_START)
        }
        // G2: timed/AMRAP sets have no rep target, so ROM onset cannot be confirmed.
        if (candidate.targetReps <= 0L) {
            return SetCompactionPlan.Preserve(PreserveReason.NOT_REP_TARGETED)
        }
        // G3: bodyweight sets have no cable excursion contract; unknown exercises stay preserved.
        if (candidate.exerciseId == null ||
            candidate.exerciseIsBodyweight == null ||
            candidate.exerciseIsBodyweight != 0L
        ) {
            return SetCompactionPlan.Preserve(PreserveReason.EXERCISE_UNKNOWN_OR_BODYWEIGHT)
        }
        // G4: legacy cm-scale rows predate the mm contract (#1400).
        if (minUnitVersion < 2L) {
            return SetCompactionPlan.Preserve(PreserveReason.LEGACY_UNIT_SCALE)
        }
        // I1: a NULL position anywhere makes the stationary baseline untrustworthy.
        if (samples == null) {
            return SetCompactionPlan.Preserve(PreserveReason.NULL_POSITION)
        }

        val retention = CableTelemetryArchivalRetention.planRetention(samples, romEnabled = true)
        if (retention is RetentionDecision.Keep) {
            return SetCompactionPlan.Preserve(PreserveReason.policyKeep(retention.reason))
        }
        val trim = retention as RetentionDecision.Trim

        // G5: the trimmed boundary must sit at or before the first rep start (no zero filtering).
        if (firstRepStartMs == null) {
            return SetCompactionPlan.Preserve(PreserveReason.NO_REP_EVIDENCE)
        }
        if (trim.boundaryTimestamp > firstRepStartMs) {
            return SetCompactionPlan.Preserve(PreserveReason.BOUNDARY_INSIDE_REP)
        }

        val retainedCount = samples.size - trim.discardCount
        // G7: every touched session keeps at least one sample (childless-row checks stay invariant).
        if (retainedCount < 1) {
            return SetCompactionPlan.Preserve(PreserveReason.INVARIANT_VIOLATION)
        }
        if (trim.discardCount == 0) {
            return SetCompactionPlan.Preserve(PreserveReason.NOTHING_TO_TRIM)
        }
        return SetCompactionPlan.Trim(
            boundaryTs = trim.boundaryTimestamp,
            discardCount = trim.discardCount,
            retainedCount = retainedCount,
            retainedSafetyCount = trim.retainedSafetyCount,
        )
    }
}
