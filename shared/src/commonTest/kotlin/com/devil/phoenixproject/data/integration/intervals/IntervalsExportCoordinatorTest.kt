package com.devil.phoenixproject.data.integration.intervals

import com.devil.phoenixproject.data.repository.IntervalsJobDraft
import com.devil.phoenixproject.data.repository.IntervalsJobRecord
import com.devil.phoenixproject.data.repository.IntervalsOutboxRepository
import com.devil.phoenixproject.data.repository.IntervalsReservationOutcome
import com.devil.phoenixproject.data.repository.IntervalsReservationRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Deterministic clock/jitter tests for [IntervalsExportCoordinator] (issue #1384 packet
 * P4): state CAS, 401/403, safe vs ambiguous 429/5xx, Retry-After, timeout -> UNCERTAIN,
 * attempt exhaustion, singleflight, foreground reentry, lease expiry, immutable payloads,
 * stale generation callbacks, pause/disconnect/reconnect, profile switch, and two-device
 * race reconciliation. No network, no real time, no real secrets.
 */
class IntervalsExportCoordinatorTest {

    // ------------------------------------------------------------------ fakes

    private class FakeOutbox : IntervalsOutboxRepository {
        val rows = linkedMapOf<String, IntervalsJobRecord>()
        var claimCount = 0
        val completedExpectations = mutableListOf<Pair<String, Long?>>()
        var pauseCalls = 0
        var resumeCalls = 0
        var authRequiredCalls = 0

        fun keyOf(profileId: String, resolvedAthleteId: String, externalId: String) =
            "$profileId|$resolvedAthleteId|$externalId"

        fun put(record: IntervalsJobRecord) {
            rows[keyOf(record.profileId, record.resolvedAthleteId, record.externalId)] = record
        }

        fun row(externalId: String, profileId: String = "profile-1"): IntervalsJobRecord =
            rows.getValue(keyOf(profileId, "i42", externalId))

        fun bumpDispatchGeneration(externalId: String, newGeneration: Long) {
            val key = keyOf("profile-1", "i42", externalId)
            rows.getValue(key)
            rows[key] = rows.getValue(key).copy(dispatchAuthGeneration = newGeneration)
        }

        override suspend fun insertJobIfAbsent(draft: IntervalsJobDraft, now: Long): Boolean {
            val key = keyOf(draft.profileId, draft.resolvedAthleteId, draft.externalId)
            if (key in rows) return false
            rows[key] = IntervalsJobRecord(
                profileId = draft.profileId,
                resolvedAthleteId = draft.resolvedAthleteId,
                externalId = draft.externalId,
                receiptProfileId = draft.receiptProfileId,
                receiptLocalKind = draft.receiptLocalKind,
                receiptCompletionId = draft.receiptCompletionId,
                payloadJson = draft.payloadJson,
                payloadVersion = draft.payloadVersion,
                payloadDigest = draft.payloadDigest,
                originalGeneration = draft.originalGeneration,
                dispatchAuthGeneration = draft.dispatchAuthGeneration,
                remoteActivityId = null,
                state = IntervalsJobState.PENDING,
                reasonCode = null,
                attempts = 0,
                nextAttemptAt = null,
                leaseToken = null,
                leaseExpiresAt = null,
                firstSentAt = null,
                createdAt = now,
                updatedAt = now,
            )
            return true
        }

        override suspend fun getJob(
            profileId: String,
            resolvedAthleteId: String,
            externalId: String,
        ): IntervalsJobRecord? = rows[keyOf(profileId, resolvedAthleteId, externalId)]

        override suspend fun dueJobs(
            profileId: String,
            now: Long,
            maxAttempts: Int,
            limit: Int,
        ): List<IntervalsJobRecord> =
            rows.values
                .filter {
                    it.profileId == profileId &&
                        (it.state == IntervalsJobState.PENDING || it.state == IntervalsJobState.RETRY_WAIT) &&
                        it.attempts < maxAttempts &&
                        (it.nextAttemptAt == null || it.nextAttemptAt <= now)
                }
                .sortedBy { it.createdAt }
                .take(limit)

        override suspend fun uncertainJobs(profileId: String, limit: Int): List<IntervalsJobRecord> =
            rows.values
                .filter { it.profileId == profileId && it.state == IntervalsJobState.UNCERTAIN }
                .sortedBy { it.createdAt }
                .take(limit)

        override suspend fun claimJob(
            profileId: String,
            resolvedAthleteId: String,
            externalId: String,
            expectedDispatchAuthGeneration: Long,
            leaseToken: String,
            leaseExpiresAt: Long,
            now: Long,
        ): IntervalsJobRecord? {
            val key = keyOf(profileId, resolvedAthleteId, externalId)
            val row = rows[key] ?: return null
            val claimable = (row.state == IntervalsJobState.PENDING || row.state == IntervalsJobState.RETRY_WAIT) &&
                (row.leaseToken == null || (row.leaseExpiresAt ?: 0) <= now) &&
                row.dispatchAuthGeneration == expectedDispatchAuthGeneration
            if (!claimable) return null
            claimCount++
            val claimed = row.copy(
                state = IntervalsJobState.IN_FLIGHT,
                leaseToken = leaseToken,
                leaseExpiresAt = leaseExpiresAt,
                attempts = row.attempts + 1,
                updatedAt = now,
            )
            rows[key] = claimed
            return claimed
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
            val key = keyOf(profileId, resolvedAthleteId, externalId)
            val row = rows[key] ?: return false
            completedExpectations += key to expectedDispatchAuthGeneration
            // CAS exactly like the frozen SQL: lease token + generation.
            if (row.leaseToken != leaseToken) return false
            if (row.dispatchAuthGeneration != expectedDispatchAuthGeneration) return false
            rows[key] = row.copy(
                state = state,
                reasonCode = reasonCode,
                remoteActivityId = remoteActivityId ?: row.remoteActivityId,
                leaseToken = null,
                leaseExpiresAt = null,
                nextAttemptAt = nextAttemptAt,
                firstSentAt = row.firstSentAt ?: firstSentAt,
                updatedAt = now,
            )
            return true
        }

        override suspend fun expireLeases(now: Long): Long {
            var expired = 0
            for ((key, row) in rows) {
                if (row.state == IntervalsJobState.IN_FLIGHT && row.leaseToken != null &&
                    (row.leaseExpiresAt ?: 0) <= now
                ) {
                    rows[key] = row.copy(
                        state = IntervalsJobState.UNCERTAIN,
                        reasonCode = IntervalsReasonCode.LEASE_EXPIRED,
                        leaseToken = null,
                        leaseExpiresAt = null,
                        updatedAt = now,
                    )
                    expired++
                }
            }
            return expired.toLong()
        }

        override suspend fun pauseProfileJobs(profileId: String, now: Long): Long {
            pauseCalls++
            var count = 0
            for ((key, row) in rows) {
                if (row.profileId == profileId &&
                    (row.state == IntervalsJobState.PENDING || row.state == IntervalsJobState.RETRY_WAIT ||
                        row.state == IntervalsJobState.AUTH_REQUIRED)
                ) {
                    rows[key] = row.copy(state = IntervalsJobState.PAUSED, updatedAt = now)
                    count++
                }
            }
            return count.toLong()
        }

        override suspend fun resumeProfileJobs(
            profileId: String,
            resolvedAthleteId: String,
            now: Long,
            maxAttempts: Int,
        ): Long {
            resumeCalls++
            var count = 0
            for ((key, row) in rows) {
                if (row.profileId == profileId && row.resolvedAthleteId == resolvedAthleteId &&
                    (row.state == IntervalsJobState.PAUSED || row.state == IntervalsJobState.AUTH_REQUIRED) &&
                    row.attempts < maxAttempts
                ) {
                    rows[key] = row.copy(
                        state = if (row.nextAttemptAt != null && row.nextAttemptAt > now) {
                            IntervalsJobState.RETRY_WAIT
                        } else {
                            IntervalsJobState.PENDING
                        },
                        updatedAt = now,
                    )
                    count++
                }
            }
            return count.toLong()
        }

        override suspend fun markProfileAuthRequired(profileId: String, now: Long): Long {
            authRequiredCalls++
            var count = 0
            for ((key, row) in rows) {
                if (row.profileId == profileId &&
                    (row.state == IntervalsJobState.PENDING || row.state == IntervalsJobState.RETRY_WAIT ||
                        row.state == IntervalsJobState.IN_FLIGHT)
                ) {
                    rows[key] = row.copy(
                        state = IntervalsJobState.AUTH_REQUIRED,
                        reasonCode = IntervalsReasonCode.AUTH_REQUIRED,
                        leaseToken = null,
                        leaseExpiresAt = null,
                        updatedAt = now,
                    )
                    count++
                }
            }
            return count.toLong()
        }

        override suspend fun reserveExternalId(
            resolvedAthleteId: String,
            externalId: String,
            ownerProfileId: String,
            ownerLocalKind: String,
            ownerCompletionId: String,
            now: Long,
        ): IntervalsReservationOutcome = IntervalsReservationOutcome.Reserved

        override suspend fun findReservation(
            resolvedAthleteId: String,
            externalId: String,
        ): IntervalsReservationRecord? = null

        override fun observeStatus(profileId: String): Flow<IntervalsExportStatus> = emptyFlow()
    }

    private class FakeApi : IntervalsApi {
        val createCalls = mutableListOf<IntervalsManualActivity>()
        val listPages = mutableListOf<Int>()
        val listWindows = mutableListOf<Pair<String, String>>()
        val readbackCalls = mutableListOf<String>()
        var createHandler: suspend (IntervalsManualActivity) -> IntervalsCreateOutcome =
            { IntervalsCreateOutcome.Accepted("remote-1") }
        var listHandler: suspend (Int) -> IntervalsListResult = { IntervalsListResult.Page(emptyList(), complete = true) }
        var readbackHandler: suspend (String) -> IntervalsReadbackResult =
            { IntervalsReadbackResult.NotFound }

        override suspend fun validateAthlete(athleteId: String, apiKey: String): IntervalsValidationResult =
            IntervalsValidationResult.Valid(athleteId, null)

        override suspend fun createManualActivity(
            athleteId: String,
            apiKey: String,
            activity: IntervalsManualActivity,
        ): IntervalsCreateOutcome {
            createCalls += activity
            return createHandler(activity)
        }

        override suspend fun readbackActivity(
            athleteId: String,
            apiKey: String,
            remoteActivityId: String,
        ): IntervalsReadbackResult {
            readbackCalls += remoteActivityId
            return readbackHandler(remoteActivityId)
        }

        override suspend fun listActivities(
            athleteId: String,
            apiKey: String,
            oldest: String,
            newest: String,
            page: Int,
        ): IntervalsListResult {
            listWindows += oldest to newest
            listPages += page
            return listHandler(page)
        }
    }

    private class FakeConnectionReader : IntervalsConnectionReader {
        var statusValue: IntervalsConnectionStatus? = defaultStatus()
        var authorizationChecks = 0
        var authorizationHandler: suspend () -> Boolean = { true }

        override suspend fun status(profileId: String): IntervalsConnectionStatus? = statusValue

        override suspend fun isDispatchAuthorized(binding: IntervalsDispatchBinding): Boolean {
            authorizationChecks++
            return authorizationHandler()
        }

        companion object {
            fun defaultStatus(
                enabled: Boolean = true,
                paused: Boolean = false,
                authRequired: Boolean = false,
                dispatchAuthGeneration: Long = 1,
            ) = IntervalsConnectionStatus(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                connectionGeneration = 1,
                dispatchAuthGeneration = dispatchAuthGeneration,
                installationId = "install-1",
                enabled = enabled,
                paused = paused,
                authRequired = authRequired,
                validatedAt = 1L,
                enabledAt = 2L,
                pausedAt = null,
                consentVersion = 1,
                disclosureVersion = 1,
                eligibilityEpoch = 0L,
                updatedAt = 3L,
            )
        }
    }

    private class FakeActiveProfile {
        var value: String? = "profile-1"
    }

    private class FakeCredentials {
        var value: String? = "test-key"
    }

    private fun payloadJson(
        externalId: String = "ext-1",
        startDateLocal: String = "2026-11-08T09:03:21",
    ): String =
        """{"type":"WeightTraining","external_id":"$externalId","name":"Push","description":"d",""" +
            """"start_date":"2026-11-08T14:03:21Z","start_date_local":"$startDateLocal","elapsed_time":3600}"""

    private fun job(
        externalId: String = "ext-1",
        profileId: String = "profile-1",
        state: IntervalsJobState = IntervalsJobState.PENDING,
        reasonCode: IntervalsReasonCode? = null,
        attempts: Long = 0,
        nextAttemptAt: Long? = null,
        leaseToken: String? = null,
        leaseExpiresAt: Long? = null,
        dispatchAuthGeneration: Long = 1,
        createdAt: Long = 1_000L,
        firstSentAt: Long? = null,
        payloadJson: String = payloadJson(externalId),
    ) = IntervalsJobRecord(
        profileId = profileId,
        resolvedAthleteId = "i42",
        externalId = externalId,
        receiptProfileId = profileId,
        receiptLocalKind = "ROUTINE",
        receiptCompletionId = "completion-$externalId",
        payloadJson = payloadJson,
        payloadVersion = 1,
        payloadDigest = "digest-$externalId",
        originalGeneration = 1,
        dispatchAuthGeneration = dispatchAuthGeneration,
        remoteActivityId = null,
        state = state,
        reasonCode = reasonCode,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        leaseToken = leaseToken,
        leaseExpiresAt = leaseExpiresAt,
        firstSentAt = firstSentAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun listItem(externalId: String, id: String) = IntervalsActivityListItemDto(
        id = id,
        externalId = externalId,
        externalIdAlt = null,
    )

    // ------------------------------------------------------------------ harness

    private class Harness(
        val outbox: FakeOutbox,
        val api: FakeApi,
        val connection: FakeConnectionReader,
        val activeProfile: FakeActiveProfile,
        val credentials: FakeCredentials,
        val drainTrigger: IntervalsDrainTrigger,
        val clock: () -> Long,
        val coordinator: IntervalsExportCoordinator,
    ) {
        var advanceTo: (Long) -> Unit = {}

        fun advanceTime(newNow: Long) = advanceTo(newNow)
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        now: Long = 10_000L,
        jitter: Double = 1.0,
        pendingFinalizer: (FakeOutbox) -> PendingCompletionFinalizer = { PendingCompletionFinalizer {} },
    ): Harness {
        val outbox = FakeOutbox()
        val api = FakeApi()
        val connection = FakeConnectionReader()
        val activeProfile = FakeActiveProfile()
        val credentials = FakeCredentials()
        val drainTrigger = IntervalsDrainTrigger()
        var current = now
        val clock = { current }
        // Bind the application scope to the test scheduler with eager execution (repo
        // pattern in SafeWordDetectionManagerTest): plain test dispatchers are not pumped
        // by advanceUntilIdle in this harness, while UnconfinedTestDispatcher(testScheduler)
        // runs signal handling deterministically inline.
        val appScope = kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        val coordinator = IntervalsExportCoordinator(
            applicationScope = appScope,
            api = api,
            outbox = outbox,
            connectionReader = connection,
            credentials = { credentials.value },
            activeProfile = { activeProfile.value },
            pendingFinalizer = pendingFinalizer(outbox),
            drainTrigger = drainTrigger,
            nowEpochMs = clock,
            jitterUnit = { jitter },
            leaseTokenFactory = { "lease-${++leaseCounter}" },
        )
        return Harness(outbox, api, connection, activeProfile, credentials, drainTrigger, clock, coordinator).also {
            it.advanceTo = { newNow -> current = newNow }
        }
    }

    private var leaseCounter = 0

    // ------------------------------------------------------------------ dispatch

    @Test
    fun dispatchesDueJobAndMarksSentWithRemoteId() = runTest {
        val h = harness()
        h.outbox.put(job())
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.SENT, row.state)
        assertEquals("remote-1", row.remoteActivityId)
        assertEquals(10_000L, row.firstSentAt)
        assertEquals(1, h.api.createCalls.size)
        assertNull(row.leaseToken)
    }

    @Test
    fun safeRetryableFailureSchedulesRetryWaitAtFullJitterCap() = runTest {
        val h = harness(jitter = 1.0)
        h.outbox.put(job())
        h.api.createHandler = {
            IntervalsCreateOutcome.RateLimitedOrServerFailure(503, provablyNotAccepted = true, retryAfterMillis = null)
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.RETRY_WAIT, row.state)
        assertEquals(IntervalsReasonCode.SAFE_RETRYABLE, row.reasonCode)
        assertNull(row.firstSentAt)
        // Attempt 1 full jitter: min(30s * 2^0, 6h) = 30s.
        assertEquals(10_000L + 30_000L, row.nextAttemptAt)
        assertEquals(1, row.attempts)
    }

    @Test
    fun validRetryAfterIsHonoredDeferringBeyondBackoffCapButNeverEarlier() = runTest {
        val h = harness(jitter = 1.0)
        // Attempt 2 after claim: backoff cap = min(30s * 2^1, 6h) = 60s.
        h.outbox.put(job(attempts = 1))
        h.api.createHandler = {
            IntervalsCreateOutcome.RateLimitedOrServerFailure(
                429,
                provablyNotAccepted = true,
                retryAfterMillis = 2L * 60L * 60L * 1_000L,
            )
        }
        h.coordinator.runDrain()
        val deferred = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.RETRY_WAIT, deferred.state)
        // Retry-After (2h) defers beyond the 60s cap.
        assertEquals(10_000L + 2L * 60L * 60L * 1_000L, deferred.nextAttemptAt)

        // Zero jitter must still never schedule earlier than the server's Retry-After.
        val h2 = harness(jitter = 0.0)
        h2.outbox.put(job(externalId = "ext-2"))
        h2.api.createHandler = {
            IntervalsCreateOutcome.RateLimitedOrServerFailure(429, provablyNotAccepted = true, retryAfterMillis = 45_000L)
        }
        h2.coordinator.runDrain()
        val floor = h2.outbox.row("ext-2")
        assertEquals(10_000L + 45_000L, floor.nextAttemptAt)
    }

    @Test
    fun attemptExhaustionBecomesPermanentFailureWithRetryExhausted() = runTest {
        val h = harness()
        h.outbox.put(job(attempts = 4))
        h.api.createHandler = {
            IntervalsCreateOutcome.RateLimitedOrServerFailure(503, provablyNotAccepted = true, retryAfterMillis = null)
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.PERMANENT_FAILURE, row.state)
        assertEquals(IntervalsReasonCode.RETRY_EXHAUSTED, row.reasonCode)
        assertNull(row.firstSentAt)
        assertEquals(5, row.attempts, "max 5 create attempts per job lifetime, including the first")
    }

    @Test
    fun authRejectionMarksAuthRequiredSuppressesDispatchUntilGenerationChanges() = runTest {
        val h = harness()
        h.outbox.put(job(externalId = "ext-1", createdAt = 1_000L))
        h.outbox.put(job(externalId = "ext-2", createdAt = 2_000L))
        h.api.createHandler = { IntervalsCreateOutcome.AuthRejected(401) }
        h.coordinator.runDrain()

        val first = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.AUTH_REQUIRED, first.state)
        assertEquals(IntervalsReasonCode.AUTH_REQUIRED, first.reasonCode)
        assertNull(first.firstSentAt)
        assertEquals(1, h.outbox.authRequiredCalls)
        assertEquals(1, h.api.createCalls.size, "connection dispatch suppressed mid-drain")

        // Suppressed: a fresh drain with the same generation sends nothing.
        h.api.createHandler = { IntervalsCreateOutcome.Accepted("remote-x") }
        h.coordinator.runDrain()
        assertEquals(1, h.api.createCalls.size)

        // Fresh authorization generation (reconnect) resumes AUTH_REQUIRED same-destination work directly.
        h.connection.statusValue = FakeConnectionReader.defaultStatus(dispatchAuthGeneration = 2)
        h.outbox.resumeProfileJobs("profile-1", "i42", now = 10_000L, maxAttempts = 5)
        h.coordinator.runDrain()
        assertEquals(3, h.api.createCalls.size, "both re-authorized same-destination jobs resume")
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-1").state)
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-2").state)
    }

    @Test
    fun validationRejectionIsPermanentFailureWithVisibleReason() = runTest {
        val h = harness()
        h.outbox.put(job(externalId = "ext-1", createdAt = 1_000L))
        h.outbox.put(job(externalId = "ext-2", createdAt = 2_000L))
        h.api.createHandler = { activity ->
            if (activity.externalId == "ext-1") {
                IntervalsCreateOutcome.ValidationRejected(400, "HTTP 400")
            } else {
                IntervalsCreateOutcome.ValidationRejected(413, "HTTP 413")
            }
        }
        h.coordinator.runDrain()

        assertEquals(IntervalsJobState.PERMANENT_FAILURE, h.outbox.row("ext-1").state)
        assertEquals(IntervalsReasonCode.VALIDATION_REJECTED, h.outbox.row("ext-1").reasonCode)
        assertNull(h.outbox.row("ext-1").firstSentAt)
        assertEquals(IntervalsJobState.PERMANENT_FAILURE, h.outbox.row("ext-2").state)
        assertEquals(IntervalsReasonCode.PAYLOAD_TOO_LARGE, h.outbox.row("ext-2").reasonCode)
        assertNull(h.outbox.row("ext-2").firstSentAt)
    }

    @Test
    fun ambiguousOutcomeIsUncertainAndNeverAutomaticallyPostedAgain() = runTest {
        val h = harness()
        h.outbox.put(job())
        h.api.createHandler = { IntervalsCreateOutcome.Ambiguous }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.AMBIGUOUS_OUTCOME, row.reasonCode)
        assertEquals(10_000L, row.firstSentAt)
        assertEquals(1, h.api.createCalls.size)

        // Next drain: reconciliation only (empty complete listing), no blind POST replay.
        h.api.createHandler = { IntervalsCreateOutcome.Accepted("should-not-happen") }
        h.coordinator.runDrain()
        assertEquals(1, h.api.createCalls.size, "ambiguous outcomes are never auto-POSTed")
        assertEquals(IntervalsJobState.UNCERTAIN, h.outbox.row("ext-1").state)
        assertEquals(IntervalsReasonCode.NO_REMOTE_MATCH, h.outbox.row("ext-1").reasonCode)
    }

    @Test
    fun unprovenServerFailureIsUncertainNotRetryWait() = runTest {
        val h = harness()
        h.outbox.put(job())
        h.api.createHandler = {
            IntervalsCreateOutcome.RateLimitedOrServerFailure(500, provablyNotAccepted = false, retryAfterMillis = null)
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.AMBIGUOUS_OUTCOME, row.reasonCode)
    }

    // ------------------------------------------------------------------ leases, CAS

    @Test
    fun expiredLeaseBecomesUncertainWithLeaseExpiredReason() = runTest {
        val h = harness()
        h.outbox.put(
            job(
                state = IntervalsJobState.IN_FLIGHT,
                attempts = 1,
                leaseToken = "stale-lease",
                leaseExpiresAt = 9_000L,
            ),
        )
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.LEASE_EXPIRED, row.reasonCode)
        assertEquals(0, h.api.createCalls.size, "expired leases never become unrestricted PENDING sends")

        // The next drain reconciles it (no match) without ever POSTing.
        h.coordinator.runDrain()
        assertEquals(0, h.api.createCalls.size)
    }

    @Test
    fun preRequestRecheckReleasesClaimWithoutSending() = runTest {
        val h = harness()
        h.outbox.put(job())
        var checks = 0
        h.connection.authorizationHandler = {
            checks++
            checks <= 1 // authorized pre-claim, revoked before the request
        }
        h.coordinator.runDrain()

        assertEquals(0, h.api.createCalls.size, "no request after the pre-request recheck failed")
        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.PENDING, row.state)
        assertNull(row.leaseToken)
        assertEquals(1, row.attempts, "the claim itself counted as one durable attempt")
    }

    @Test
    fun staleGenerationCallbackCannotMutateReplacementJobState() = runTest {
        val h = harness()
        h.outbox.put(job())
        h.api.createHandler = {
            // The connection is re-authorized (new generation) while the POST is in flight;
            // the stale callback must not mutate the replacement state.
            h.outbox.bumpDispatchGeneration("ext-1", newGeneration = 2)
            IntervalsCreateOutcome.Accepted("remote-stale")
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(2, row.dispatchAuthGeneration, "replacement generation untouched")
        assertEquals(IntervalsJobState.IN_FLIGHT, row.state, "stale CAS write was rejected")
        assertNull(row.remoteActivityId)
        val (key, expectedGeneration) = h.outbox.completedExpectations.single()
        assertEquals("profile-1|i42|ext-1", key)
        assertEquals(1L, expectedGeneration, "the job's captured generation is what guards the write")
    }

    // ------------------------------------------------------------------ singleflight

    @Test
    fun concurrentDrainsAreSingleflightAndSendExactlyOnce() = runTest {
        val h = harness()
        h.outbox.put(job())
        val gate = CompletableDeferred<Unit>()
        h.api.createHandler = {
            gate.await()
            IntervalsCreateOutcome.Accepted("remote-1")
        }
        val first = launch { h.coordinator.runDrain() }
        val second = launch { h.coordinator.runDrain() }
        val third = launch { h.coordinator.runDrain() }
        gate.complete(Unit)
        first.join()
        second.join()
        third.join()

        assertEquals(1, h.api.createCalls.size, "one bounded drain does the work; others observe it done")
        assertEquals(1, h.outbox.claimCount)
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-1").state)
    }

    @Test
    fun coalescedSignalsNeverSpinAndForegroundReentryDrainsNewWork() = runTest {
        val h = harness()
        h.outbox.put(job(externalId = "ext-1", createdAt = 1_000L))
        h.coordinator.signalCompletion()
        h.coordinator.signalForeground()
        h.coordinator.signalCompletion()
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.api.createCalls.size, "coalesced signals run at most one drain each and never resend")

        h.outbox.put(job(externalId = "ext-2", createdAt = 2_000L))
        h.coordinator.signalForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(2, h.api.createCalls.size, "foreground reentry picks up new work")
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-2").state)

        // No spin: another idle pass with no signals sends nothing further.
        testScheduler.advanceUntilIdle()
        assertEquals(2, h.api.createCalls.size)
    }

    @Test
    fun drainIsBoundedToTenJobs() = runTest {
        val h = harness()
        for (index in 1..11) {
            h.outbox.put(job(externalId = "ext-$index", createdAt = index.toLong()))
        }
        h.coordinator.runDrain()
        assertEquals(10, h.api.createCalls.size)
        assertEquals(IntervalsJobState.PENDING, h.outbox.row("ext-11").state)
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun pauseRetainsJobsAndResumeRedispatchesSameDestinationOnly() = runTest {
        val h = harness()
        h.outbox.put(job())

        // Pause: dispatch suppressed, jobs retained as PAUSED.
        h.connection.statusValue = FakeConnectionReader.defaultStatus(paused = true)
        h.connection.authorizationHandler = { false }
        h.outbox.pauseProfileJobs("profile-1", now = 10_000L)
        h.coordinator.runDrain()
        assertEquals(0, h.api.createCalls.size)
        assertEquals(IntervalsJobState.PAUSED, h.outbox.row("ext-1").state)

        // Resume with unchanged destination: same job dispatches; no rebinding happened.
        h.connection.statusValue = FakeConnectionReader.defaultStatus()
        h.connection.authorizationHandler = { true }
        h.outbox.resumeProfileJobs("profile-1", "i42", now = 10_000L, maxAttempts = 5)
        h.coordinator.runDrain()
        assertEquals(1, h.api.createCalls.size)
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-1").state)
    }

    @Test
    fun profileSwitchSuppressesDispatchAndCancelsWorker() = runTest {
        val h = harness()
        h.outbox.put(job(externalId = "ext-1", createdAt = 1_000L))
        h.outbox.put(job(externalId = "ext-2", createdAt = 2_000L))

        // Not Ready at drain entry: nothing runs at all.
        h.activeProfile.value = null
        h.coordinator.runDrain()
        assertEquals(0, h.outbox.claimCount)

        // Switch mid-drain: the in-flight job finishes but the worker stops.
        h.activeProfile.value = "profile-1"
        h.api.createHandler = {
            h.activeProfile.value = null
            IntervalsCreateOutcome.Accepted("remote-1")
        }
        h.coordinator.runDrain()
        assertEquals(1, h.api.createCalls.size)
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-1").state)
        assertEquals(IntervalsJobState.PENDING, h.outbox.row("ext-2").state)
    }

    @Test
    fun payloadIsImmutableAcrossAttempts() = runTest {
        val h = harness(jitter = 0.0)
        h.outbox.put(job())
        var attempt = 0
        h.api.createHandler = {
            attempt++
            if (attempt == 1) {
                IntervalsCreateOutcome.RateLimitedOrServerFailure(503, provablyNotAccepted = true, retryAfterMillis = 1_000L)
            } else {
                IntervalsCreateOutcome.Accepted("remote-1")
            }
        }
        h.coordinator.runDrain()
        assertEquals(IntervalsJobState.RETRY_WAIT, h.outbox.row("ext-1").state)

        // Due again after the Retry-After floor.
        h.advanceTime(20_000L)
        h.coordinator.runDrain()
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-1").state)

        assertEquals(2, h.api.createCalls.size)
        assertEquals(h.api.createCalls[0], h.api.createCalls[1], "payload bytes never change between attempts")
        assertEquals("ext-1", h.api.createCalls[0].externalId)
        assertEquals("Push", h.api.createCalls[0].name)
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    fun reconciliationUniqueMatchResolvesUncertainToSentWithoutRepost() = runTest {
        val h = harness()
        h.outbox.put(job(state = IntervalsJobState.UNCERTAIN, reasonCode = IntervalsReasonCode.AMBIGUOUS_OUTCOME))
        h.api.listHandler = {
            IntervalsListResult.Page(listOf(listItem("ext-1", "77")), complete = true)
        }
        h.api.readbackHandler = {
            IntervalsReadbackResult.Found(IntervalsActivitySummaryDto(id = "77", externalId = "ext-1"))
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.SENT, row.state)
        assertEquals("77", row.remoteActivityId)
        assertEquals(0, h.api.createCalls.size, "reconciliation never re-creates")
        assertEquals(listOf("77"), h.api.readbackCalls)
    }

    @Test
    fun reconciliationMultipleMatchesStayUncertainTwoDeviceRace() = runTest {
        val h = harness()
        h.outbox.put(job(state = IntervalsJobState.UNCERTAIN, reasonCode = IntervalsReasonCode.AMBIGUOUS_OUTCOME))
        h.api.listHandler = {
            IntervalsListResult.Page(
                listOf(listItem("ext-1", "77"), listItem("ext-1", "78")),
                complete = true,
            )
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.MULTIPLE_REMOTE_MATCHES, row.reasonCode)
        assertEquals(0, h.api.createCalls.size, "two-device races are not solved by local replay")
    }

    @Test
    fun reconciliationIncompleteSearchStaysUncertainAtPageCap() = runTest {
        val h = harness()
        h.outbox.put(job(state = IntervalsJobState.UNCERTAIN, reasonCode = IntervalsReasonCode.AMBIGUOUS_OUTCOME))
        h.api.listHandler = {
            // Full pages forever: the pass reaches its page cap = incomplete search.
            IntervalsListResult.Page((1..100).map { listItem("other-$it", "$it") }, complete = false)
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.INCOMPLETE_RECONCILIATION, row.reasonCode)
        assertEquals(
            IntervalsTransportPolicy.RECONCILIATION_MAX_PAGES_PER_PASS,
            h.api.listPages.size,
            "10 pages per pass, then stop",
        )
    }

    @Test
    fun manualReconcileNeverRecreatesOrResetsLifetimeCounters() = runTest {
        val h = harness()
        h.outbox.put(job(state = IntervalsJobState.UNCERTAIN, attempts = 3))
        h.api.listHandler = { IntervalsListResult.Page(emptyList(), complete = true) }

        val result = h.coordinator.reconcileJob("profile-1", "i42", "ext-1")
        assertEquals(IntervalsReconciliationResult.NoMatch, result)

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(3, row.attempts, "manual retry never resets lifetime counters")
        assertEquals(0, h.api.createCalls.size)

        // Non-uncertain rows are never reinterpreted as sendable work.
        h.outbox.put(job(externalId = "ext-sent", state = IntervalsJobState.SENT, createdAt = 2_000L))
        val noop = h.coordinator.reconcileJob("profile-1", "i42", "ext-sent")
        assertEquals(IntervalsReconciliationResult.NoMatch, noop)
        assertEquals(0, h.api.createCalls.size)
    }

    @Test
    fun reconciliationReadbackMismatchStaysUncertain() = runTest {
        val h = harness()
        h.outbox.put(job(state = IntervalsJobState.UNCERTAIN, reasonCode = IntervalsReasonCode.AMBIGUOUS_OUTCOME))
        h.api.listHandler = { IntervalsListResult.Page(listOf(listItem("ext-1", "77")), complete = true) }
        h.api.readbackHandler = {
            IntervalsReadbackResult.Found(IntervalsActivitySummaryDto(id = "77", externalId = "someone-elses"))
        }
        h.coordinator.runDrain()

        val row = h.outbox.row("ext-1")
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.INCOMPLETE_RECONCILIATION, row.reasonCode)
        assertNull(row.remoteActivityId)
    }

    @Test
    fun reconciliationAuthRejectionSuppressesDispatch() = runTest {
        val h = harness()
        h.outbox.put(job(externalId = "ext-uncertain", state = IntervalsJobState.UNCERTAIN, createdAt = 1_000L))
        h.api.listHandler = { IntervalsListResult.AuthRejected(401) }
        h.coordinator.runDrain()

        // Reconciliation's 401 marks the connection auth-required.
        assertTrue(h.outbox.authRequiredCalls >= 1)
        assertEquals(0, h.api.createCalls.size)

        // Connection dispatch stays suppressed until the generation changes. The frozen
        // bulk auth-required transition covers every dispatchable row of the profile, so a
        // job created after the 401 also lands AUTH_REQUIRED instead of being sent.
        h.outbox.put(job(externalId = "ext-due", createdAt = 2_000L))
        h.coordinator.runDrain()
        assertEquals(0, h.api.createCalls.size)
        assertEquals(IntervalsJobState.AUTH_REQUIRED, h.outbox.row("ext-due").state)
    }

    @Test
    fun credentialsUnavailableSuppressesDispatch() = runTest {
        val h = harness()
        h.credentials.value = null
        h.outbox.put(job())
        h.coordinator.runDrain()
        assertEquals(0, h.outbox.claimCount)
        assertEquals(0, h.api.createCalls.size)
        assertEquals(IntervalsJobState.PENDING, h.outbox.row("ext-1").state)
    }

    @Test
    fun disabledConnectionSuppressesDispatch() = runTest {
        val h = harness()
        h.connection.statusValue = FakeConnectionReader.defaultStatus(enabled = false)
        h.outbox.put(job())
        h.coordinator.runDrain()
        assertEquals(0, h.outbox.claimCount)
        assertNotNull(h.connection.statusValue)
        assertFalse(h.connection.statusValue!!.enabled)
    }

    @Test
    fun signalTriggerRunsPendingFinalizerBeforeDueJobDispatch() = runTest {
        var finalized = false
        val h = harness(
            pendingFinalizer = { outbox ->
                PendingCompletionFinalizer {
                    if (!finalized) {
                        finalized = true
                        outbox.put(job(externalId = "ext-finalized", createdAt = 10_000L))
                    }
                }
            },
        )
        h.drainTrigger.signal()
        testScheduler.advanceUntilIdle()

        assertTrue(finalized)
        assertEquals(1, h.api.createCalls.size)
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-finalized").state)
    }

    @Test
    fun reconciliationWindowUsesActivityStartDateLocalWhenDelayedOffline() = runTest {
        val h = harness(now = 1_776_700_000_000L) // 2026-04-20 UTC
        h.outbox.put(
            job(
                externalId = "ext-delayed",
                state = IntervalsJobState.UNCERTAIN,
                reasonCode = IntervalsReasonCode.AMBIGUOUS_OUTCOME,
                createdAt = 1_776_700_000_000L,
                firstSentAt = 1_776_700_000_000L,
                payloadJson = payloadJson(externalId = "ext-delayed", startDateLocal = "2026-04-10T23:30:00"),
            ),
        )
        h.api.listHandler = {
            IntervalsListResult.Page(listOf(listItem("ext-delayed", "88")), complete = true)
        }
        h.api.readbackHandler = {
            IntervalsReadbackResult.Found(IntervalsActivitySummaryDto(id = "88", externalId = "ext-delayed"))
        }

        h.coordinator.runDrain()

        assertEquals(listOf("2026-04-09" to "2026-04-11"), h.api.listWindows)
        assertEquals(IntervalsJobState.SENT, h.outbox.row("ext-delayed").state)
        assertEquals("88", h.outbox.row("ext-delayed").remoteActivityId)
    }
}
