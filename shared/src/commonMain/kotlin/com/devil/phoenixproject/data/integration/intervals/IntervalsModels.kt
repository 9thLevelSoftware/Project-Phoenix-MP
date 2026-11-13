package com.devil.phoenixproject.data.integration.intervals

/**
 * Frozen shared contracts for the Intervals.icu outbound session sync (issue #1384).
 *
 * Authority chain: signoff.md -> integration-model.md -> architecture.md. The shapes in
 * this file are the implementation's local vocabulary: completion identity, capture-time
 * context, the job state machine, and nonsecret UI status. No secrets, credentials or
 * payload bodies are represented here. Wire DTOs live in [IntervalsWireDtos].
 */

/** Local completion kind. Never changes wire identity: `external_id` is the literal session key. */
enum class IntervalsLocalKind { ROUTINE, STANDALONE }

/** Explicit completion reason. UI disappearance is never a finish signal. */
enum class IntervalsFinishReason {
    /** Routine finished normally (summary with no next step, or final-exercise completion). */
    NORMAL_FINISH,

    /** Explicitly finished routine with persisted valid work (a valid partial finish). */
    VALID_PARTIAL_FINISH,

    /** Standalone set/session commit outside a routine. */
    STANDALONE_COMMIT,
}

/**
 * Frozen job state machine (architecture vocabulary). Reason codes are stored separately
 * so PAUSED never converts ambiguity into safe-to-send.
 */
enum class IntervalsJobState {
    PENDING,
    IN_FLIGHT,
    RETRY_WAIT,
    SENT,
    AUTH_REQUIRED,
    PERMANENT_FAILURE,
    UNCERTAIN,
    PAUSED,
}

/** Machine-readable failure/uncertainty reasons stored beside the state. */
enum class IntervalsReasonCode {
    RETRY_EXHAUSTED,
    LEASE_EXPIRED,
    AUTH_REQUIRED,
    VALIDATION_REJECTED,
    PAYLOAD_TOO_LARGE,
    SAFE_RETRYABLE,
    AMBIGUOUS_OUTCOME,
    INCOMPLETE_RECONCILIATION,
    NO_REMOTE_MATCH,
    MULTIPLE_REMOTE_MATCHES,
    SUPPRESSED_BY_PAUSE,
}

/** Stable identity of one local completion. Unique per profile. */
data class IntervalsCompletionKey(
    val profileId: String,
    val localKind: IntervalsLocalKind,
    val completionId: String,
)

/** One expected member of a completion's exact frozen membership. */
data class IntervalsChildRef(
    val childKey: String,
    val childKind: IntervalsChildKind,
    val sessionId: String,
    /** True when the child's durable write is still pending at admission time. */
    val pendingAtAdmission: Boolean,
)

enum class IntervalsChildKind { SESSION, COMPLETED_SET }

/**
 * Immutable capture-time context. Captured synchronously at completion admission and
 * passed into async work; async code must never re-read the active profile or the
 * device time zone to rebuild it.
 */
data class IntervalsCompletionCapture(
    val key: IntervalsCompletionKey,
    val installationId: String,
    val finishReason: IntervalsFinishReason,
    val routineTitle: String?,
    /** Wire `external_id`: exactly routineSessionId, else standalone session id. Never empty. */
    val externalId: String,
    val startEpochMs: Long,
    /** Offset-free local ISO8601 start (no zone suffix), captured at admission. */
    val startLocalIso: String,
    /** IANA zone id, e.g. "America/New_York". */
    val timeZoneId: String,
    /** Zone offset at start, in minutes. */
    val utcOffsetMinutes: Int,
    val completedEpochMs: Long,
    /** True when the completion was admitted while the integration was enabled and unpaused. */
    val eligible: Boolean,
    val resolvedAthleteId: String?,
    val connectionGeneration: Long,
)

/**
 * Dispatch authorization binding captured before any async work. The job keeps its
 * original binding forever; [dispatchAuthGeneration] may advance on reconnect but never
 * rewrites completion provenance.
 */
data class IntervalsDispatchBinding(
    val installationId: String,
    val profileId: String,
    val resolvedAthleteId: String,
    val completionId: String,
    val originalGeneration: Long,
)

/** Nonsecret connection metadata (mirrors the IntervalsConnection row, never the secret). */
data class IntervalsConnectionStatus(
    val profileId: String,
    val resolvedAthleteId: String,
    val connectionGeneration: Long,
    val dispatchAuthGeneration: Long,
    val installationId: String,
    val enabled: Boolean,
    val paused: Boolean,
    val authRequired: Boolean,
    val validatedAt: Long?,
    val enabledAt: Long?,
    val pausedAt: Long?,
    val consentVersion: Int,
    val disclosureVersion: Int,
    val eligibilityEpoch: Long,
    val updatedAt: Long,
)

/** Per-state job counters, scoped to the captured profile/destination. Never global. */
data class IntervalsJobCounts(
    val state: IntervalsJobState,
    val count: Long,
    val lastSentAt: Long?,
)

/** Projected, nonsecret status for the Settings screen. */
data class IntervalsExportStatus(
    val connection: IntervalsConnectionStatus?,
    val jobCounts: List<IntervalsJobCounts>,
    val lastSentAt: Long?,
) {
    val queuedCount: Long
        get() = jobCounts
            .filter {
                it.state == IntervalsJobState.PENDING ||
                    it.state == IntervalsJobState.IN_FLIGHT ||
                    it.state == IntervalsJobState.RETRY_WAIT ||
                    it.state == IntervalsJobState.PAUSED
            }
            .sumOf { it.count }

    val sentCount: Long
        get() = jobCounts.firstOrNull { it.state == IntervalsJobState.SENT }?.count ?: 0L

    val uncertainCount: Long
        get() = jobCounts.firstOrNull { it.state == IntervalsJobState.UNCERTAIN }?.count ?: 0L

    val authRequiredCount: Long
        get() = jobCounts.firstOrNull { it.state == IntervalsJobState.AUTH_REQUIRED }?.count ?: 0L

    val permanentFailureCount: Long
        get() = jobCounts.firstOrNull { it.state == IntervalsJobState.PERMANENT_FAILURE }?.count ?: 0L
}

/** Result of a nonmutating connection validation (GET athlete). Never enables the export. */
sealed class IntervalsValidationResult {
    data class Valid(val canonicalAthleteId: String, val displayName: String?) : IntervalsValidationResult()
    data class InvalidCredentials(val detail: String?) : IntervalsValidationResult()
    data class Rejected(val statusCode: Int, val detail: String?) : IntervalsValidationResult()
    data class NetworkFailure(val detail: String?) : IntervalsValidationResult()
}

/**
 * Outcome of one create attempt. The dispatcher maps these onto the frozen state machine;
 * ambiguous outcomes are never automatically retried with a second POST.
 */
sealed class IntervalsCreateOutcome {
    data class Accepted(val remoteActivityId: String?) : IntervalsCreateOutcome()
    data class ValidationRejected(val statusCode: Int, val detail: String?) : IntervalsCreateOutcome()
    data class AuthRejected(val statusCode: Int) : IntervalsCreateOutcome()
    data class RateLimitedOrServerFailure(
        val statusCode: Int,
        /** True only when the write is provably not accepted or replay is provider-proven. */
        val provablyNotAccepted: Boolean,
        val retryAfterMillis: Long?,
    ) : IntervalsCreateOutcome()

    /** Timeout, connection loss or cancellation after possible delivery. */
    data object Ambiguous : IntervalsCreateOutcome()
}

/** Reconciliation evidence for one external id. One unique readback match -> SENT. */
sealed class IntervalsReconciliationResult {
    data class UniqueMatch(val remoteActivityId: String) : IntervalsReconciliationResult()
    data object NoMatch : IntervalsReconciliationResult()
    data object MultipleMatches : IntervalsReconciliationResult()
    data object IncompleteSearch : IntervalsReconciliationResult()
}

/** Transport bounds (frozen policy from integration-model §6, not current repo constants). */
object IntervalsTransportPolicy {
    const val CONNECT_TIMEOUT_MILLIS = 10_000L
    const val SOCKET_TIMEOUT_MILLIS = 20_000L
    const val REQUEST_TIMEOUT_MILLIS = 30_000L
    const val SQL_LEASE_MILLIS = 120_000L
    const val MAX_CREATE_ATTEMPTS = 5
    const val RETRY_BASE_DELAY_MILLIS = 30_000L
    const val RETRY_MAX_DELAY_MILLIS = 6L * 60L * 60L * 1000L
    const val RECONCILIATION_PAGES_PER_DRAIN = 3
    const val RECONCILIATION_PAGE_SIZE = 100
    const val RECONCILIATION_MAX_PAGES_PER_PASS = 10
    const val JOBS_PER_DRAIN = 10

    /** Full jitter up to min(30s * 2^(attempt-1), 6h). Valid Retry-After always wins. */
    fun safeRetryDelayMillis(attempt: Int, jitterUnit: Double): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val cap = (RETRY_BASE_DELAY_MILLIS.toDouble() * (1L shl exponent).toDouble())
            .coerceAtMost(RETRY_MAX_DELAY_MILLIS.toDouble())
        val clampedUnit = jitterUnit.coerceIn(0.0, 1.0)
        return (cap * clampedUnit).toLong().coerceAtLeast(0L)
    }
}

/** Local preflight bounds. Defensive prototype limits, NOT vendor-verified limits. */
object IntervalsPayloadLimits {
    const val MAX_DESCRIPTION_BYTES = 32_768
    const val MAX_NAME_BYTES = 256
}

/**
 * Read side of the connection/consent lifecycle (implemented in P3's
 * IntervalsConnectionStore). The dispatcher and Settings VM depend only on this seam.
 */
interface IntervalsConnectionReader {
    /** Nonsecret status for one profile, or null when no connection exists. */
    suspend fun status(profileId: String): IntervalsConnectionStatus?

    /**
     * True when this exact captured binding may still dispatch right now (enabled, not
     * paused, generation current, installation authorized). Checked again immediately
     * before every request.
     */
    suspend fun isDispatchAuthorized(binding: IntervalsDispatchBinding): Boolean
}
