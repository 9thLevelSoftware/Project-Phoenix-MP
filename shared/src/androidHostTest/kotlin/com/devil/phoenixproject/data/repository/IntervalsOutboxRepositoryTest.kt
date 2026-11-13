package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionReader
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsDispatchBinding
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobState
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.data.integration.intervals.IntervalsTransportPolicy
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Real-SQLite (JdbcSqliteDriver) tests for [SqlDelightIntervalsOutboxRepository]
 * (issue #1384 packet P4): unique keys/reservations and lease CAS across two repository
 * instances sharing one database, exactly as two coordinator workers would.
 */
class IntervalsOutboxRepositoryTest {

    private class FakeConnectionReader : IntervalsConnectionReader {
        var statusValue: IntervalsConnectionStatus? = null

        override suspend fun status(profileId: String): IntervalsConnectionStatus? = statusValue

        override suspend fun isDispatchAuthorized(binding: IntervalsDispatchBinding): Boolean = true
    }

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PhoenixDatabase
    private val reader = FakeConnectionReader()
    private var now = 10_000L

    private fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createTestSchema(driver)
        db = PhoenixDatabase(driver)
    }

    /** Two repository instances over one database, like two dispatchers racing. */
    private fun repositories(): Pair<SqlDelightIntervalsOutboxRepository, SqlDelightIntervalsOutboxRepository> {
        setUp()
        return (
            SqlDelightIntervalsOutboxRepository(db, reader, rawScanDriver = driver) to
                SqlDelightIntervalsOutboxRepository(db, reader, rawScanDriver = driver)
            )
    }

    private fun draft(
        externalId: String = "ext-1",
        profileId: String = "profile-1",
        dispatchAuthGeneration: Long = 1,
    ) = IntervalsJobDraft(
        profileId = profileId,
        resolvedAthleteId = "i42",
        externalId = externalId,
        receiptProfileId = profileId,
        receiptLocalKind = "ROUTINE",
        receiptCompletionId = "completion-$externalId",
        payloadJson = """{"type":"WeightTraining","external_id":"$externalId"}""",
        payloadVersion = 1,
        payloadDigest = "digest-$externalId",
        originalGeneration = 1,
        dispatchAuthGeneration = dispatchAuthGeneration,
    )

    // ------------------------------------------------------------------ uniqueness

    @Test
    fun jobInsertIsIdempotentAndPayloadIsImmutable() = runTest {
        setUp()
        val repo = SqlDelightIntervalsOutboxRepository(db, reader, rawScanDriver = driver)

        assertTrue(repo.insertJobIfAbsent(draft(), now))
        assertFalse(repo.insertJobIfAbsent(draft().copy(payloadJson = """{"tampered":true}"""), now))

        val row = assertNotNull(repo.getJob("profile-1", "i42", "ext-1"))
        assertEquals("""{"type":"WeightTraining","external_id":"ext-1"}""", row.payloadJson)
        assertEquals(IntervalsJobState.PENDING, row.state)
        assertEquals(0L, row.attempts)
    }

    @Test
    fun reservationIsIdempotentForSameOwnerAndCollidesVisiblyForOthers() = runTest {
        setUp()
        val repo = SqlDelightIntervalsOutboxRepository(db, reader, rawScanDriver = driver)

        val first = repo.reserveExternalId("i42", "ext-1", "profile-1", "ROUTINE", "completion-1", now)
        assertEquals(IntervalsReservationOutcome.Reserved, first)

        // Same owner again: idempotent success, identical content confirmed by readback.
        val repeat = repo.reserveExternalId("i42", "ext-1", "profile-1", "ROUTINE", "completion-1", now)
        assertEquals(IntervalsReservationOutcome.Reserved, repeat)

        // Different profile/kind/completion on the same literal id: visible collision.
        val collision = repo.reserveExternalId("i42", "ext-1", "profile-2", "STANDALONE", "completion-2", now)
        val outcome = assertIs<IntervalsReservationOutcome.Collision>(collision)
        assertEquals("profile-1", outcome.ownerProfileId)
        assertEquals("ROUTINE", outcome.ownerLocalKind)
        assertEquals("completion-1", outcome.ownerCompletionId)

        // Cross-repository check: the reservation is durable and visible.
        val other = SqlDelightIntervalsOutboxRepository(db, reader, rawScanDriver = driver)
        val found = assertNotNull(other.findReservation("i42", "ext-1"))
        assertEquals("profile-1", found.ownerProfileId)
    }

    // ------------------------------------------------------------------ lease CAS

    @Test
    fun leaseClaimIsCompareAndSetAcrossTwoRepositoryInstances() = runTest {
        val (repoA, repoB) = repositories()
        assertTrue(repoA.insertJobIfAbsent(draft(), now))

        val claimedByA = repoA.claimJob(
            profileId = "profile-1",
            resolvedAthleteId = "i42",
            externalId = "ext-1",
            expectedDispatchAuthGeneration = 1,
            leaseToken = "lease-a",
            leaseExpiresAt = now + IntervalsTransportPolicy.SQL_LEASE_MILLIS,
            now = now,
        )
        assertNotNull(claimedByA)
        assertEquals(IntervalsJobState.IN_FLIGHT, claimedByA.state)
        assertEquals(1L, claimedByA.attempts)

        // Second instance loses the CAS while the lease is live.
        val claimedByB = repoB.claimJob(
            profileId = "profile-1",
            resolvedAthleteId = "i42",
            externalId = "ext-1",
            expectedDispatchAuthGeneration = 1,
            leaseToken = "lease-b",
            leaseExpiresAt = now + IntervalsTransportPolicy.SQL_LEASE_MILLIS,
            now = now,
        )
        assertNull(claimedByB)

        // Wrong generation also loses.
        val wrongGeneration = repoB.claimJob(
            profileId = "profile-1",
            resolvedAthleteId = "i42",
            externalId = "ext-1",
            expectedDispatchAuthGeneration = 2,
            leaseToken = "lease-b",
            leaseExpiresAt = now + IntervalsTransportPolicy.SQL_LEASE_MILLIS,
            now = now,
        )
        assertNull(wrongGeneration)

        // An expired lease is never reclaimed as unrestricted work: foreground recovery
        // (expireIntervalsLeases) turns it into UNCERTAIN and the attempt counter stands.
        val later = now + IntervalsTransportPolicy.SQL_LEASE_MILLIS + 1
        assertEquals(0L, repoB.expireLeases(now)) // live lease is untouched
        assertEquals(1L, repoB.expireLeases(later))
        val recovered = assertNotNull(repoB.getJob("profile-1", "i42", "ext-1"))
        assertEquals(IntervalsJobState.UNCERTAIN, recovered.state)
        assertEquals(IntervalsReasonCode.LEASE_EXPIRED, recovered.reasonCode)
        assertEquals(1L, recovered.attempts, "the claim counted one durable attempt")
        assertNull(
            repoB.claimJob("profile-1", "i42", "ext-1", 1, "lease-b", later + 120_000L, later),
            "UNCERTAIN rows are not claimable work",
        )
    }

    @Test
    fun completeIsGuardedByLeaseTokenAndGeneration() = runTest {
        val (repoA, repoB) = repositories()
        assertTrue(repoA.insertJobIfAbsent(draft(), now))
        val claimed = assertNotNull(
            repoA.claimJob("profile-1", "i42", "ext-1", 1, "lease-a", now + 120_000L, now),
        )

        // Stale token: rejected.
        assertFalse(
            repoB.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-1",
                leaseToken = "lease-b",
                expectedDispatchAuthGeneration = 1,
                state = IntervalsJobState.SENT,
                reasonCode = null,
                remoteActivityId = "99",
                nextAttemptAt = null,
                firstSentAt = now,
                now = now,
            ),
        )

        // Stale generation: rejected even with the right token.
        assertFalse(
            repoB.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-1",
                leaseToken = "lease-a",
                expectedDispatchAuthGeneration = 2,
                state = IntervalsJobState.SENT,
                reasonCode = null,
                remoteActivityId = "99",
                nextAttemptAt = null,
                firstSentAt = now,
                now = now,
            ),
        )
        assertEquals(IntervalsJobState.IN_FLIGHT, assertNotNull(repoB.getJob("profile-1", "i42", "ext-1")).state)

        // Matching token + generation: exactly one transition.
        assertTrue(
            repoA.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-1",
                leaseToken = claimed.leaseToken,
                expectedDispatchAuthGeneration = claimed.dispatchAuthGeneration,
                state = IntervalsJobState.SENT,
                reasonCode = null,
                remoteActivityId = "99",
                nextAttemptAt = null,
                firstSentAt = now,
                now = now,
            ),
        )
        val sent = assertNotNull(repoB.getJob("profile-1", "i42", "ext-1"))
        assertEquals(IntervalsJobState.SENT, sent.state)
        assertEquals("99", sent.remoteActivityId)
        assertEquals(now, sent.firstSentAt)
        assertNull(sent.leaseToken)

        // The write cannot be replayed after the lease is gone.
        assertFalse(
            repoA.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-1",
                leaseToken = claimed.leaseToken,
                expectedDispatchAuthGeneration = 1,
                state = IntervalsJobState.UNCERTAIN,
                reasonCode = IntervalsReasonCode.AMBIGUOUS_OUTCOME,
                remoteActivityId = null,
                nextAttemptAt = null,
                firstSentAt = null,
                now = now,
            ),
        )
    }

    @Test
    fun expiredLeasesBecomeUncertainNeverUnrestrictedPending() = runTest {
        val (repo, _) = repositories()
        assertTrue(repo.insertJobIfAbsent(draft(), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-1", 1, "lease-a", now + 1_000L, now))

        val expired = repo.expireLeases(now + 1_001L)
        assertEquals(1L, expired)

        val row = assertNotNull(repo.getJob("profile-1", "i42", "ext-1"))
        assertEquals(IntervalsJobState.UNCERTAIN, row.state)
        assertEquals(IntervalsReasonCode.LEASE_EXPIRED, row.reasonCode)
        assertNull(row.leaseToken)

        // Not due for dispatch: UNCERTAIN is not a sendable state.
        assertTrue(repo.dueJobs("profile-1", now + 1_001L, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS, 10).isEmpty())
    }

    // ------------------------------------------------------------------ transitions

    @Test
    fun pauseResumeAndAuthRequiredTransitionsMatchFrozenSemantics() = runTest {
        val (repo, _) = repositories()
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-due"), now))
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-uncertain"), now))
        // Force one row to UNCERTAIN through the full claim + ambiguous-complete path.
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-uncertain", 1, "lease-u", now + 120_000L, now))
        assertTrue(
            repo.completeJob(
                "profile-1", "i42", "ext-uncertain", "lease-u", 1,
                IntervalsJobState.UNCERTAIN, IntervalsReasonCode.AMBIGUOUS_OUTCOME,
                remoteActivityId = null, nextAttemptAt = null, firstSentAt = now, now = now,
            ),
        )

        // Pause keeps eligible rows and never converts ambiguity into safe-to-send.
        assertEquals(1L, repo.pauseProfileJobs("profile-1", now))
        assertEquals(IntervalsJobState.PAUSED, assertNotNull(repo.getJob("profile-1", "i42", "ext-due")).state)
        assertEquals(
            IntervalsJobState.UNCERTAIN,
            assertNotNull(repo.getJob("profile-1", "i42", "ext-uncertain")).state,
        )

        // Resume restores the same-destination job only; counters are preserved.
        assertEquals(1L, repo.resumeProfileJobs("profile-1", "i42", now, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS))
        assertEquals(IntervalsJobState.PENDING, assertNotNull(repo.getJob("profile-1", "i42", "ext-due")).state)

        // 401/403 path.
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-due", 1, "lease-d", now + 120_000L, now))
        assertEquals(1L, repo.markProfileAuthRequired("profile-1", now))
        val authRequired = assertNotNull(repo.getJob("profile-1", "i42", "ext-due"))
        assertEquals(IntervalsJobState.AUTH_REQUIRED, authRequired.state)
        assertEquals(IntervalsReasonCode.AUTH_REQUIRED, authRequired.reasonCode)
        assertNull(authRequired.leaseToken)
    }

    // ------------------------------------------------------------------ status + scans

    @Test
    fun statusFlowProjectsNonsecretScopedCounts() = runTest {
        val (repo, _) = repositories()
        reader.statusValue = IntervalsConnectionStatus(
            profileId = "profile-1",
            resolvedAthleteId = "i42",
            connectionGeneration = 1,
            dispatchAuthGeneration = 1,
            installationId = "install-1",
            enabled = true,
            paused = false,
            authRequired = false,
            validatedAt = 1L,
            enabledAt = 2L,
            pausedAt = null,
            consentVersion = 1,
            disclosureVersion = 1,
            eligibilityEpoch = 0L,
            updatedAt = 3L,
        )
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-due"), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-due", 1, "lease-a", now + 120_000L, now))
        assertTrue(
            repo.completeJob(
                "profile-1", "i42", "ext-due", "lease-a", 1,
                IntervalsJobState.SENT, null, remoteActivityId = "99",
                nextAttemptAt = null, firstSentAt = now, now = now,
            ),
        )
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-pending"), now + 1))
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-uncertain"), now + 2))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-uncertain", 1, "lease-u", now + 120_000L, now + 50_000L))
        assertTrue(
            repo.completeJob(
                "profile-1", "i42", "ext-uncertain", "lease-u", 1,
                IntervalsJobState.UNCERTAIN, IntervalsReasonCode.AMBIGUOUS_OUTCOME,
                remoteActivityId = null, nextAttemptAt = null, firstSentAt = now + 50_000L, now = now + 50_000L,
            ),
        )

        val status = repo.observeStatus("profile-1").first()
        assertEquals("i42", status.connection?.resolvedAthleteId)
        assertEquals(1L, status.sentCount)
        assertEquals(1L, status.queuedCount)
        assertEquals(1L, status.uncertainCount)
        assertEquals(now, status.lastSentAt, "UNCERTAIN attempts must never advance lastSentAt")
    }

    @Test
    fun uncertainScanFindsPersistedRowsAcrossInstances() = runTest {
        val (repoA, repoB) = repositories()
        assertTrue(repoA.insertJobIfAbsent(draft(externalId = "ext-1"), now))
        assertNotNull(repoA.claimJob("profile-1", "i42", "ext-1", 1, "lease-a", now + 120_000L, now))
        assertTrue(
            repoA.completeJob(
                "profile-1", "i42", "ext-1", "lease-a", 1,
                IntervalsJobState.UNCERTAIN, IntervalsReasonCode.AMBIGUOUS_OUTCOME,
                remoteActivityId = null, nextAttemptAt = null, firstSentAt = now, now = now,
            ),
        )

        // A second instance (fresh in-memory registry) still sees the persisted row.
        val uncertain = repoB.uncertainJobs("profile-1", 3)
        assertEquals(1, uncertain.size)
        assertEquals("ext-1", uncertain.single().externalId)
        assertEquals(IntervalsReasonCode.AMBIGUOUS_OUTCOME, uncertain.single().reasonCode)
    }

    @Test
    fun uncertainJobsWithoutRawScanDriverIsEmptyNeverGuessed() = runTest {
        setUp()
        val repo = SqlDelightIntervalsOutboxRepository(db, reader) // no raw scan driver
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-1"), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-1", 1, "lease-a", now + 120_000L, now))
        assertTrue(
            repo.completeJob(
                "profile-1", "i42", "ext-1", "lease-a", 1,
                IntervalsJobState.UNCERTAIN, IntervalsReasonCode.AMBIGUOUS_OUTCOME,
                remoteActivityId = null, nextAttemptAt = null, firstSentAt = now, now = now,
            ),
        )
        // No scan driver: the persisted UNCERTAIN scan is unavailable and there is
        // deliberately NO in-process fallback registry (an unsynchronized unbounded
        // cache must never be reconciliation's source of truth). Never guesses: an
        // empty list, and the row stays reachable through getJob with its known key.
        assertTrue(repo.uncertainJobs("profile-1", 3).isEmpty())
        assertEquals("ext-1", repo.getJob("profile-1", "i42", "ext-1")?.externalId)
    }

    @Test
    fun dueJobsRespectAttemptsFloorAndSchedule() = runTest {
        val (repo, _) = repositories()
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-due"), now))
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-later"), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-later", 1, "lease-l", now + 120_000L, now))
        assertTrue(
            repo.completeJob(
                "profile-1", "i42", "ext-later", "lease-l", 1,
                IntervalsJobState.RETRY_WAIT, IntervalsReasonCode.SAFE_RETRYABLE,
                remoteActivityId = null, nextAttemptAt = now + 5_000L, firstSentAt = null, now = now,
            ),
        )

        // Only the unscheduled job is due now.
        val due = repo.dueJobs("profile-1", now, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS, 10)
        assertEquals(listOf("ext-due"), due.map { it.externalId })

        // After the retry floor both are due, in creation order.
        val later = repo.dueJobs("profile-1", now + 5_000L, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS, 10)
        assertEquals(listOf("ext-due", "ext-later"), later.map { it.externalId })

        // A row at the attempt ceiling is never selected again.
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-due", 1, "lease-d1", now + 120_000L, now))
        repo.completeJob(
            "profile-1", "i42", "ext-due", "lease-d1", 1,
            IntervalsJobState.PENDING, null, null, null, null, now,
        )
        for (attempt in 2..IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS) {
            assertNotNull(repo.claimJob("profile-1", "i42", "ext-due", 1, "lease-d$attempt", now + 120_000L, now))
            repo.completeJob(
                "profile-1", "i42", "ext-due", "lease-d$attempt", 1,
                IntervalsJobState.PENDING, null, null, null, null, now,
            )
        }
        val exhausted = repo.dueJobs("profile-1", now + 5_000L, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS, 10)
        assertEquals(listOf("ext-later"), exhausted.map { it.externalId })
    }

    // ------------------------------------------------- review-fix regressions (PR #1388)

    @Test
    fun completeJobWithNullLeaseMatchesUncertainRows() = runTest {
        val (repo, _) = repositories()
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-recon"), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-recon", 1, "lease-1", now + 1_000L, now))
        assertEquals(1L, repo.expireLeases(now + 1_001L))
        val uncertain = assertNotNull(repo.getJob("profile-1", "i42", "ext-recon"))
        assertEquals(IntervalsJobState.UNCERTAIN, uncertain.state)
        assertNull(uncertain.leaseToken)

        // The reconciliation verdict passes a NULL lease token and must match the
        // UNCERTAIN row: the contract is `lease_token IS NULL` for these writes. With
        // `lease_token = :lease_token` this write was a silent no-op and every
        // reconciliation verdict was lost (review CRITICAL finding).
        assertTrue(
            repo.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-recon",
                leaseToken = null,
                expectedDispatchAuthGeneration = 1,
                state = IntervalsJobState.SENT,
                reasonCode = null,
                remoteActivityId = "remote-77",
                nextAttemptAt = null,
                firstSentAt = now + 1_002L,
                now = now + 1_002L,
            ),
        )
        val sent = assertNotNull(repo.getJob("profile-1", "i42", "ext-recon"))
        assertEquals(IntervalsJobState.SENT, sent.state)
        assertEquals("remote-77", sent.remoteActivityId)

        // The NULL-lease clause must not over-match: a row that currently holds a real
        // lease is untouchable by a NULL-lease write.
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-leased"), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-leased", 1, "lease-2", now + 1_000L, now))
        assertFalse(
            repo.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-leased",
                leaseToken = null,
                expectedDispatchAuthGeneration = 1,
                state = IntervalsJobState.PERMANENT_FAILURE,
                reasonCode = IntervalsReasonCode.RETRY_EXHAUSTED,
                remoteActivityId = null,
                nextAttemptAt = null,
                firstSentAt = null,
                now = now,
            ),
        )
    }

    @Test
    fun dueJobsAreProfileScopedSoOtherProfilesCannotStarveTheActiveOne() = runTest {
        val (repo, _) = repositories()
        // Ten older rows for a second profile would exhaust a global LIMIT before the
        // coordinator's post-filter ever sees the active profile's row.
        repeat(10) { i ->
            assertTrue(
                repo.insertJobIfAbsent(
                    draft(externalId = "other-$i", profileId = "profile-2"),
                    now - 10_000L,
                ),
            )
        }
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "mine", profileId = "profile-1"), now))
        val due = repo.dueJobs("profile-1", now, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS, 10)
        assertEquals(listOf("mine"), due.map { it.externalId })
    }

    @Test
    fun resumeProfileJobsAlsoResumesAuthRequiredRows() = runTest {
        val (repo, _) = repositories()
        assertTrue(repo.insertJobIfAbsent(draft(externalId = "ext-auth"), now))
        assertNotNull(repo.claimJob("profile-1", "i42", "ext-auth", 1, "lease-a", now + 1_000L, now))
        // 401/403 parks the row in AUTH_REQUIRED with the lease released.
        assertTrue(
            repo.completeJob(
                profileId = "profile-1",
                resolvedAthleteId = "i42",
                externalId = "ext-auth",
                leaseToken = "lease-a",
                expectedDispatchAuthGeneration = 1,
                state = IntervalsJobState.AUTH_REQUIRED,
                reasonCode = IntervalsReasonCode.AUTH_REQUIRED,
                remoteActivityId = null,
                nextAttemptAt = null,
                firstSentAt = null,
                now = now,
            ),
        )
        // A reconnect (fresh enable -> resume) must make it dispatchable again.
        assertEquals(
            1L,
            repo.resumeProfileJobs("profile-1", "i42", now + 1_000L, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS),
        )
        val due = repo.dueJobs("profile-1", now + 1_000L, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS, 10)
        assertEquals(listOf("ext-auth"), due.map { it.externalId })
    }
}
