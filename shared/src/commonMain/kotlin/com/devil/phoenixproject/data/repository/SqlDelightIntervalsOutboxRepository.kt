package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionReader
import com.devil.phoenixproject.data.integration.intervals.IntervalsExportStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobCounts
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobState
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.database.IntervalsOutbox as IntervalsOutboxRow
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

/**
 * SQLDelight implementation of [IntervalsOutboxRepository] over the shared PhoenixDatabase
 * (issue #1384 packet P4). Uses the frozen named operations from `PhoenixDatabase.sq`:
 * `insertIntervalsJobIfAbsent`, `selectIntervalsJob`, `selectDueIntervalsJobs`,
 * `claimIntervalsJob`, `completeIntervalsJob`, `expireIntervalsLeases`,
 * `pauseIntervalsProfileJobs`, `resumeIntervalsProfileJobs`,
 * `authRequiredIntervalsProfileJobs`, `reserveIntervalsExternalId`,
 * `selectIntervalsExternalIdReservation`, `selectIntervalsJobCounts`.
 *
 * No suspend call ever happens inside a DB transaction here: every transaction body is
 * synchronous SQLDelight work only. Result writes rely on the generated CAS predicates
 * (state + lease token + generation) exactly as frozen in P1.
 *
 * [rawScanDriver] is the `SqlDriver` backing [db]. The frozen P1 query set has no named
 * UNCERTAIN-listing operation and `TransacterImpl.driver` is protected, so the persisted
 * `UNCERTAIN` scan in [uncertainJobs] needs the driver passed in. DI always injects the
 * same driver SQLDelight uses; without it the scan is unavailable and [uncertainJobs]
 * returns an empty list (rows from earlier process runs are then only reachable through
 * [IntervalsOutboxRepository.getJob] with a known key). There is deliberately NO
 * in-process fallback registry: an unsynchronized unbounded cache would be the only
 * "source of truth" for reconciliation in exactly the deployments that lack the driver.
 */
class SqlDelightIntervalsOutboxRepository(
    private val db: PhoenixDatabase,
    private val connectionReader: IntervalsConnectionReader,
    private val rawScanDriver: SqlDriver? = null,
) : IntervalsOutboxRepository {

    private val queries get() = db.phoenixDatabaseQueries

    override suspend fun insertJobIfAbsent(draft: IntervalsJobDraft, now: Long): Boolean {
        val inserted = queries.insertIntervalsJobIfAbsent(
            profile_id = draft.profileId,
            resolved_athlete_id = draft.resolvedAthleteId,
            external_id = draft.externalId,
            receipt_profile_id = draft.receiptProfileId,
            receipt_local_kind = draft.receiptLocalKind,
            receipt_completion_id = draft.receiptCompletionId,
            payload_json = draft.payloadJson,
            payload_version = draft.payloadVersion,
            payload_digest = draft.payloadDigest,
            original_generation = draft.originalGeneration,
            dispatch_auth_generation = draft.dispatchAuthGeneration,
            created_at = now,
        ).value
        return inserted > 0
    }

    override suspend fun getJob(
        profileId: String,
        resolvedAthleteId: String,
        externalId: String,
    ): IntervalsJobRecord? =
        queries.selectIntervalsJob(profileId, resolvedAthleteId, externalId)
            .executeAsOneOrNull()
            ?.toRecord()

    override suspend fun dueJobs(profileId: String, now: Long, maxAttempts: Int, limit: Int): List<IntervalsJobRecord> =
        queries.selectDueIntervalsJobs(
            profile_id = profileId,
            max_attempts = maxAttempts.toLong(),
            now = now,
            limit = limit.coerceAtLeast(1).toLong(),
        ).executeAsList().map { it.toRecord() }

    override suspend fun uncertainJobs(profileId: String, limit: Int): List<IntervalsJobRecord> {
        val boundedLimit = limit.coerceIn(1, 1_000)
        val driver = rawScanDriver ?: return emptyList()
        // Read-only scan of the frozen column order; mutates nothing and issues no
        // query notifications.
        return driver.executeQuery(
            null,
            "SELECT * FROM IntervalsOutbox WHERE state = 'UNCERTAIN' AND profile_id = ? " +
                "ORDER BY created_at ASC LIMIT $boundedLimit",
            { cursor ->
                val rows = mutableListOf<IntervalsJobRecord>()
                while (cursor.next().value) {
                    rows.add(cursor.toJobRecord())
                }
                QueryResult.Value(rows)
            },
            1,
        ) {
            bindString(0, profileId)
        }.value
    }

    override suspend fun claimJob(
        profileId: String,
        resolvedAthleteId: String,
        externalId: String,
        expectedDispatchAuthGeneration: Long,
        leaseToken: String,
        leaseExpiresAt: Long,
        now: Long,
    ): IntervalsJobRecord? = db.transactionWithResult {
        val claimed = queries.claimIntervalsJob(
            lease_token = leaseToken,
            lease_expires_at = leaseExpiresAt,
            now = now,
            profile_id = profileId,
            resolved_athlete_id = resolvedAthleteId,
            external_id = externalId,
            dispatch_auth_generation = expectedDispatchAuthGeneration,
        ).value
        if (claimed <= 0) return@transactionWithResult null
        val row = queries.selectIntervalsJob(profileId, resolvedAthleteId, externalId).executeAsOneOrNull()
        if (row == null || row.lease_token != leaseToken) return@transactionWithResult null
        row.toRecord()
    }

    override suspend fun completeJob(
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
    ): Boolean {
        val updated = queries.completeIntervalsJob(
            state = state.name,
            reason_code = reasonCode?.name,
            remote_activity_id = remoteActivityId,
            next_attempt_at = nextAttemptAt,
            first_sent_at = firstSentAt,
            now = now,
            profile_id = profileId,
            resolved_athlete_id = resolvedAthleteId,
            external_id = externalId,
            lease_token = leaseToken,
            dispatch_auth_generation = expectedDispatchAuthGeneration,
        ).value
        return updated > 0
    }

    override suspend fun expireLeases(now: Long): Long =
        queries.expireIntervalsLeases(now).value

    override suspend fun pauseProfileJobs(profileId: String, now: Long): Long =
        queries.pauseIntervalsProfileJobs(now = now, profile_id = profileId).value

    override suspend fun resumeProfileJobs(
        profileId: String,
        resolvedAthleteId: String,
        now: Long,
        maxAttempts: Int,
    ): Long = queries.resumeIntervalsProfileJobs(
        now = now,
        profile_id = profileId,
        resolved_athlete_id = resolvedAthleteId,
        max_attempts = maxAttempts.toLong(),
    ).value

    override suspend fun markProfileAuthRequired(profileId: String, now: Long): Long =
        queries.authRequiredIntervalsProfileJobs(now = now, profile_id = profileId).value

    override suspend fun reserveExternalId(
        resolvedAthleteId: String,
        externalId: String,
        ownerProfileId: String,
        ownerLocalKind: String,
        ownerCompletionId: String,
        now: Long,
    ): IntervalsReservationOutcome = db.transactionWithResult {
        val inserted = queries.reserveIntervalsExternalId(
            resolved_athlete_id = resolvedAthleteId,
            external_id = externalId,
            owner_profile_id = ownerProfileId,
            owner_local_kind = ownerLocalKind,
            owner_completion_id = ownerCompletionId,
            created_at = now,
        ).value
        if (inserted > 0) return@transactionWithResult IntervalsReservationOutcome.Reserved
        // Uniqueness-conflict comparison, never a blanket ignore: read the existing owner
        // back and decide on identical content vs a visible collision.
        val existing = queries.selectIntervalsExternalIdReservation(resolvedAthleteId, externalId)
            .executeAsOneOrNull()
        if (existing == null) {
            // Concurrent delete between insert and readback; one bounded retry.
            val retried = queries.reserveIntervalsExternalId(
                resolved_athlete_id = resolvedAthleteId,
                external_id = externalId,
                owner_profile_id = ownerProfileId,
                owner_local_kind = ownerLocalKind,
                owner_completion_id = ownerCompletionId,
                created_at = now,
            ).value
            return@transactionWithResult if (retried > 0) {
                IntervalsReservationOutcome.Reserved
            } else {
                IntervalsReservationOutcome.Collision(ownerProfileId, ownerLocalKind, ownerCompletionId)
            }
        }
        val identicalOwner = existing.owner_profile_id == ownerProfileId &&
            existing.owner_local_kind == ownerLocalKind &&
            existing.owner_completion_id == ownerCompletionId
        if (identicalOwner) {
            IntervalsReservationOutcome.Reserved
        } else {
            IntervalsReservationOutcome.Collision(
                ownerProfileId = existing.owner_profile_id,
                ownerLocalKind = existing.owner_local_kind,
                ownerCompletionId = existing.owner_completion_id,
            )
        }
    }

    override suspend fun findReservation(
        resolvedAthleteId: String,
        externalId: String,
    ): IntervalsReservationRecord? =
        queries.selectIntervalsExternalIdReservation(resolvedAthleteId, externalId)
            .executeAsOneOrNull()
            ?.let {
                IntervalsReservationRecord(
                    resolvedAthleteId = it.resolved_athlete_id,
                    externalId = it.external_id,
                    ownerProfileId = it.owner_profile_id,
                    ownerLocalKind = it.owner_local_kind,
                    ownerCompletionId = it.owner_completion_id,
                    createdAt = it.created_at,
                )
            }

    override fun observeStatus(profileId: String): Flow<IntervalsExportStatus> =
        queries.selectIntervalsJobCounts(profileId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .mapLatest { rows ->
                IntervalsExportStatus(
                    connection = connectionReader.status(profileId),
                    jobCounts = rows.map {
                        IntervalsJobCounts(
                            state = IntervalsJobState.valueOf(it.state),
                            count = it.job_count,
                            lastSentAt = it.last_sent_at,
                        )
                    },
                    lastSentAt = rows
                        .filter { it.state == IntervalsJobState.SENT.name }
                        .mapNotNull { it.last_sent_at }
                        .maxOrNull(),
                )
            }
}

private fun IntervalsOutboxRow.toRecord(): IntervalsJobRecord = IntervalsJobRecord(
    profileId = profile_id,
    resolvedAthleteId = resolved_athlete_id,
    externalId = external_id,
    receiptProfileId = receipt_profile_id,
    receiptLocalKind = receipt_local_kind,
    receiptCompletionId = receipt_completion_id,
    payloadJson = payload_json,
    payloadVersion = payload_version,
    payloadDigest = payload_digest,
    originalGeneration = original_generation,
    dispatchAuthGeneration = dispatch_auth_generation,
    remoteActivityId = remote_activity_id,
    state = IntervalsJobState.valueOf(state),
    reasonCode = reason_code?.let { IntervalsReasonCode.valueOf(it) },
    attempts = attempts,
    nextAttemptAt = next_attempt_at,
    leaseToken = lease_token,
    leaseExpiresAt = lease_expires_at,
    firstSentAt = first_sent_at,
    createdAt = created_at,
    updatedAt = updated_at,
)

/**
 * Column order matches `CREATE TABLE IntervalsOutbox` (frozen in P1) so `SELECT *` maps
 * positionally exactly like the generated row class.
 */
private fun app.cash.sqldelight.db.SqlCursor.toJobRecord(): IntervalsJobRecord = IntervalsJobRecord(
    profileId = getString(0)!!,
    resolvedAthleteId = getString(1)!!,
    externalId = getString(2)!!,
    receiptProfileId = getString(3)!!,
    receiptLocalKind = getString(4)!!,
    receiptCompletionId = getString(5)!!,
    payloadJson = getString(6)!!,
    payloadVersion = getLong(7)!!,
    payloadDigest = getString(8)!!,
    originalGeneration = getLong(9)!!,
    dispatchAuthGeneration = getLong(10)!!,
    remoteActivityId = getString(11),
    state = IntervalsJobState.valueOf(getString(12)!!),
    reasonCode = getString(13)?.let { IntervalsReasonCode.valueOf(it) },
    attempts = getLong(14)!!,
    nextAttemptAt = getLong(15),
    leaseToken = getString(16),
    leaseExpiresAt = getLong(17),
    firstSentAt = getLong(18),
    createdAt = getLong(19)!!,
    updatedAt = getLong(20)!!,
)
