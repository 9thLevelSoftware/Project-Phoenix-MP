package com.devil.phoenixproject.data.maintenance

import com.devil.phoenixproject.presentation.manager.RetentionKeepReason

/**
 * Issue #1425 retroactive cable-telemetry compaction models. Pure data; no DB, DI or UI.
 * All reason codes are stable strings — the UI groups on them and tests assert on them.
 */
const val TELEMETRY_COMPACTION_POLICY_VERSION = 1

/** One persisted set considered for retroactive pre-ROM trimming. */
data class CompactionCandidate(
    val sessionId: String,
    val timestamp: Long,
    val targetReps: Long,
    val exerciseId: String?,
    /** `Exercise.isBodyweight` via LEFT JOIN; null = exercise unknown. Never coalesced. */
    val exerciseIsBodyweight: Long?,
)

/** Stable preserve reason codes (string codes so `POLICY_KEEP_*` can carry the policy reason). */
data class PreserveReason(val code: String) {
    companion object {
        val CORRUPT_OR_UNREPAIRED_START = PreserveReason("CORRUPT_OR_UNREPAIRED_START")
        val NOT_REP_TARGETED = PreserveReason("NOT_REP_TARGETED")
        val EXERCISE_UNKNOWN_OR_BODYWEIGHT = PreserveReason("EXERCISE_UNKNOWN_OR_BODYWEIGHT")
        val LEGACY_UNIT_SCALE = PreserveReason("LEGACY_UNIT_SCALE")
        val NULL_POSITION = PreserveReason("NULL_POSITION")
        val NO_REP_EVIDENCE = PreserveReason("NO_REP_EVIDENCE")
        val BOUNDARY_INSIDE_REP = PreserveReason("BOUNDARY_INSIDE_REP")
        val NOTHING_TO_TRIM = PreserveReason("NOTHING_TO_TRIM")
        val INVARIANT_VIOLATION = PreserveReason("INVARIANT_VIOLATION")

        fun policyKeep(reason: RetentionKeepReason): PreserveReason =
            PreserveReason("POLICY_KEEP_${reason.name}")
    }
}

/** Per-set retroactive compaction decision. */
sealed class SetCompactionPlan {
    data class Preserve(val reason: PreserveReason) : SetCompactionPlan()

    data class Trim(
        val boundaryTs: Long,
        val discardCount: Int,
        val retainedCount: Int,
        val retainedSafetyCount: Int,
    ) : SetCompactionPlan()
}

/** Read-only dry-run result. Byte figures are estimates only; the Done state uses measurements. */
data class CompactionReport(
    val policyVersion: Int = TELEMETRY_COMPACTION_POLICY_VERSION,
    val setsScanned: Int,
    val setsCleanable: Int,
    val setsKept: Int,
    val rowsReclaimable: Int,
    /** Estimated on-device bytes reclaimable (approximate; UI says "about"). */
    val estimatedDatabaseBytesReclaimable: Long?,
    /** Estimated JSON backup bytes reclaimable; null when raw telemetry is excluded. */
    val estimatedBackupBytesReclaimable: Long?,
    val keepReasonCounts: Map<PreserveReason, Int>,
)

/** Measured DB size snapshot (`page_size × page_count` is the logical size). */
data class DatabaseSizeSnapshot(
    val pageSize: Long,
    val pageCount: Long,
    val freelistCount: Long,
) {
    val logicalBytes: Long get() = pageSize * pageCount
}

/** Guarded VACUUM precheck (issue #1425 P3). */
sealed class VacuumPrecheck {
    data object Ready : VacuumPrecheck()
    data class Blocked(val reason: VacuumSkipReason) : VacuumPrecheck()
}

/** Guarded VACUUM outcome. Never thrown out of the service. */
sealed class VacuumOutcome {
    data class Ran(
        val bytesBefore: Long,
        val bytesAfter: Long,
        val checkpointBusy: Boolean,
    ) : VacuumOutcome()

    data class Skipped(val reason: VacuumSkipReason) : VacuumOutcome()
}

enum class VacuumSkipReason {
    NOT_REQUESTED,
    ACTIVE_WORKOUT,
    INSUFFICIENT_FREELIST,
    INSUFFICIENT_FREE_VOLUME,
    UNSUPPORTED_PLATFORM,
    VACUUM_FAILED,
}

/** Result of one `compact()` pass. Sizes are measured; both null unless VACUUM ran. */
data class CompactionResult(
    val setsCleaned: Int,
    val rowsDeleted: Int,
    val stoppedAfterSet: Boolean,
    val abortedActiveWorkout: Boolean,
    val databaseBytesBefore: Long?,
    val databaseBytesAfter: Long?,
    val vacuum: VacuumOutcome,
)

/** Device-local scan watermark so later passes can skip already-scanned prefix sets. */
data class TelemetryCompactionWatermark(
    val policyVersion: Int,
    val timestamp: Long,
    val sessionId: String,
)
