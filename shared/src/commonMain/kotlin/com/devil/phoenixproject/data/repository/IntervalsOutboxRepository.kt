package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.integration.intervals.IntervalsExportStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobState
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.data.integration.intervals.IntervalsTransportPolicy
import kotlinx.coroutines.flow.Flow

/**
 * Outbox persistence seam for the Intervals.icu export (issue #1384 packet P4).
 *
 * Binding contracts (spec §4.4/§4.5, integration-model §6):
 * - The outbox key is `(profileId, resolvedAthleteId, externalId)`; the payload is frozen
 *   at insert and never rewritten (payload immutable after first send).
 * - Claiming is a lease CAS: state must be `PENDING`/`RETRY_WAIT`, the lease must be free
 *   or expired, and the row's `dispatch_auth_generation` must equal the caller's expected
 *   value. The claim durably increments `attempts` (max
 *   [IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS] create attempts per job lifetime,
 *   including the first).
 * - Every result write is acknowledged through [completeJob] under the lease-token +
 *   generation CAS so a stale callback can never mutate a replacement connection/job.
 *   Jobs keep their captured `originalGeneration` until an explicit same-destination
 *   re-enable re-stamps it (a credential rotation would otherwise permanently strand
 *   queued rows); a different athlete is never rebound.
 * - Expired `IN_FLIGHT` leases become `UNCERTAIN` ([IntervalsReasonCode.LEASE_EXPIRED]),
 *   never unrestricted `PENDING`.
 * - Pause retains already eligible jobs as `PAUSED`; it never converts ambiguity into
 *   safe-to-send (`UNCERTAIN` / `PERMANENT_FAILURE` rows are untouched).
 * - [observeStatus] is a nonsecret projection (job counters + injected connection status).
 *   No secret, key or payload body is ever returned by this interface.
 *
 * Implementations must not suspend while a DB transaction is open.
 */
interface IntervalsOutboxRepository {

    /** Idempotent job insert. True when this call inserted the row. Existing rows are never rewritten. */
    suspend fun insertJobIfAbsent(draft: IntervalsJobDraft, now: Long): Boolean

    suspend fun getJob(
        profileId: String,
        resolvedAthleteId: String,
        externalId: String,
    ): IntervalsJobRecord?

    /** Due work of ONE profile in creation order: `PENDING`/`RETRY_WAIT`, `attempts < maxAttempts`, next attempt due. Profile-scoped in SQL so other profiles can never starve the active one. */
    suspend fun dueJobs(profileId: String, now: Long, maxAttempts: Int, limit: Int): List<IntervalsJobRecord>

    /**
     * `UNCERTAIN` jobs for one profile in creation order. Used to bound reconciliation
     * passes per foreground drain. (The frozen P1 schema ships no named UNCERTAIN-listing
     * query; the SQLDelight implementation reads the same table with a read-only scan.)
     */
    suspend fun uncertainJobs(profileId: String, limit: Int): List<IntervalsJobRecord>

    /**
     * Lease-CAS claim. Returns the claimed row (freshly read) or null when the CAS lost
     * (already claimed, wrong generation, or not due). The attempt is counted durably here.
     */
    suspend fun claimJob(
        profileId: String,
        resolvedAthleteId: String,
        externalId: String,
        expectedDispatchAuthGeneration: Long,
        leaseToken: String,
        leaseExpiresAt: Long,
        now: Long,
    ): IntervalsJobRecord?

    /**
     * CAS-guarded transition write. True only when exactly one row transitioned under the
     * given lease token + generation. A false return is a stale write and must not be
     * retried against the replacement row. [leaseToken] is null for reconciliation writes
     * to UNCERTAIN rows (their lease is gone; the SQL uses `IS NULL`).
     */
    suspend fun completeJob(
        profileId: String,
        resolvedAthleteId: String,
        externalId: String,
        leaseToken: String?,
        expectedDispatchAuthGeneration: Long,
        state: IntervalsJobState,
        reasonCode: IntervalsReasonCode?,
        remoteActivityId: String?,
        nextAttemptAt: Long?,
        firstSentAt: Long?,
        now: Long,
    ): Boolean

    /** Expired `IN_FLIGHT` leases -> `UNCERTAIN`/`LEASE_EXPIRED`. Returns rows affected. */
    suspend fun expireLeases(now: Long): Long

    /** `PENDING`/`RETRY_WAIT`/`AUTH_REQUIRED` jobs of a profile -> `PAUSED`. Returns rows affected. */
    suspend fun pauseProfileJobs(profileId: String, now: Long): Long

    /** `PAUSED`/`AUTH_REQUIRED` jobs of one destination -> `PENDING`/`RETRY_WAIT` (attempts preserved). Returns rows affected. */
    suspend fun resumeProfileJobs(
        profileId: String,
        resolvedAthleteId: String,
        now: Long,
        maxAttempts: Int = IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS,
    ): Long

    /** `PENDING`/`RETRY_WAIT`/`IN_FLIGHT` jobs of a profile -> `AUTH_REQUIRED` (401/403). Returns rows affected. */
    suspend fun markProfileAuthRequired(profileId: String, now: Long): Long

    /**
     * Literal wire-identity reservation `(resolvedAthleteId, externalId)`. Reuse by another
     * profile/kind/completion is a visible collision, never a silent replacement; the same
     * owner reserving again is idempotent success.
     */
    suspend fun reserveExternalId(
        resolvedAthleteId: String,
        externalId: String,
        ownerProfileId: String,
        ownerLocalKind: String,
        ownerCompletionId: String,
        now: Long,
    ): IntervalsReservationOutcome

    suspend fun findReservation(
        resolvedAthleteId: String,
        externalId: String,
    ): IntervalsReservationRecord?

    /** Nonsecret status projection for one profile (never global). */
    fun observeStatus(profileId: String): Flow<IntervalsExportStatus>
}

/** Frozen fields of an outbox job at insert time. Payload bytes never change afterwards. */
data class IntervalsJobDraft(
    val profileId: String,
    val resolvedAthleteId: String,
    val externalId: String,
    val receiptProfileId: String,
    val receiptLocalKind: String,
    val receiptCompletionId: String,
    val payloadJson: String,
    val payloadVersion: Long,
    val payloadDigest: String,
    val originalGeneration: Long,
    val dispatchAuthGeneration: Long,
)

/** One outbox row. No secret material: payload bytes are the wire DTO already frozen locally. */
data class IntervalsJobRecord(
    val profileId: String,
    val resolvedAthleteId: String,
    val externalId: String,
    val receiptProfileId: String,
    val receiptLocalKind: String,
    val receiptCompletionId: String,
    val payloadJson: String,
    val payloadVersion: Long,
    val payloadDigest: String,
    val originalGeneration: Long,
    val dispatchAuthGeneration: Long,
    val remoteActivityId: String?,
    val state: IntervalsJobState,
    val reasonCode: IntervalsReasonCode?,
    val attempts: Long,
    val nextAttemptAt: Long?,
    val leaseToken: String?,
    val leaseExpiresAt: Long?,
    val firstSentAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Existing literal wire-identity reservation row. */
data class IntervalsReservationRecord(
    val resolvedAthleteId: String,
    val externalId: String,
    val ownerProfileId: String,
    val ownerLocalKind: String,
    val ownerCompletionId: String,
    val createdAt: Long,
)

sealed interface IntervalsReservationOutcome {
    /** This owner holds the reservation (inserted now, or an identical idempotent repeat). */
    data object Reserved : IntervalsReservationOutcome

    /** The same literal id is owned by a different profile/kind/completion. Fail visibly. */
    data class Collision(
        val ownerProfileId: String,
        val ownerLocalKind: String,
        val ownerCompletionId: String,
    ) : IntervalsReservationOutcome
}
