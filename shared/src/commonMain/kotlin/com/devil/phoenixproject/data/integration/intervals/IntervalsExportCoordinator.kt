package com.devil.phoenixproject.data.integration.intervals

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.IntervalsJobRecord
import com.devil.phoenixproject.data.repository.IntervalsOutboxRepository
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.max
import kotlin.random.Random

/**
 * Request-scoped credential seam (implemented by P3's IntervalsConnectionStore). The key
 * is fetched immediately before use and never held on the coordinator or written to the
 * outbox, logs or status projection.
 */
fun interface IntervalsCredentialSource {
    /** User API key for [profileId], or null when unavailable (nothing may be sent). */
    suspend fun apiKey(profileId: String): String?
}

/**
 * Active-profile seam. Null means the profile context is switching or not `Ready`, which
 * suppresses dispatch and cancels in-flight coordination work (integration-model §6).
 */
fun interface IntervalsActiveProfileSource {
    suspend fun activeProfileId(): String?
}

/**
 * Finalization hook run at the top of each bounded drain so admitted-but-unfinished
 * routine completion journals materialize into outbox jobs before due-job selection.
 */
fun interface PendingCompletionFinalizer {
    suspend fun finalizePending()
}

/**
 * Coalesced trigger shared between completion producers (`WorkoutCompletionFinalizer` and
 * standalone `SqlDelightWorkoutRepository` commits) and [IntervalsExportCoordinator]
 * without introducing a Koin constructor cycle.
 */
class IntervalsDrainTrigger {
    internal val signals = Channel<Unit>(Channel.CONFLATED)

    fun signal() {
        signals.trySend(Unit)
    }
}

/**
 * Best-effort resolution of [IntervalsDrainTrigger] outside the Intervals DI module.
 * Returns null outside a Koin app or when the Intervals module is not loaded.
 */
fun resolveIntervalsDrainTriggerOrNull(): IntervalsDrainTrigger? = runCatching {
    org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<IntervalsDrainTrigger>()
}.getOrNull()

/**
 * Bounded, generation-guarded export dispatcher (issue #1384 packet P4).
 *
 * Binding contracts (spec §4.4/§4.5, integration-model §6):
 * - Dedicated supervised application scope; no BLE dependency and never blocks workout
 *   completion (signals only enqueue; the drain is bounded and asynchronous).
 * - Singleflight, coalesced drains: one bounded drain per foreground/completion signal,
 *   at most [IntervalsTransportPolicy.JOBS_PER_DRAIN] jobs and one lease of
 *   [IntervalsTransportPolicy.SQL_LEASE_MILLIS] per claim; never spins.
 * - Max [IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS] create attempts per job lifetime
 *   (counted durably at claim). Safe retry uses full-jitter
 *   [IntervalsTransportPolicy.safeRetryDelayMillis]; a valid `Retry-After` is honored and
 *   may defer beyond the backoff cap but never retries earlier. Exhaustion becomes
 *   `PERMANENT_FAILURE`/`RETRY_EXHAUSTED`.
 * - Authorization is rechecked immediately before EVERY request via
 *   [IntervalsConnectionReader.isDispatchAuthorized]; result writes are CAS-guarded
 *   (state + lease token + generation) so stale callbacks cannot mutate a replacement
 *   connection or job. Jobs keep their captured original generation forever.
 * - 401/403 -> job `AUTH_REQUIRED` + connection dispatch suppression until the connection
 *   generation changes (fresh reconnect authorization). Validation 4xx ->
 *   `PERMANENT_FAILURE`. Provably-not-accepted retryables -> `RETRY_WAIT`; anything
 *   ambiguous after possible delivery -> `UNCERTAIN`.
 * - Ambiguous outcomes are never automatically POSTed again. Recovery is reconciliation
 *   only: documented activities listing with exact `external_id` match plus GET readback;
 *   1 unique match -> `SENT` with `remoteActivityId`; multiple or incomplete ->
 *   `UNCERTAIN`; a complete listing with no match also stays `UNCERTAIN` (`NO_REMOTE_MATCH`,
 *   absence is not proof). Manual retry goes through the same reconciliation and never
 *   recreates or resets lifetime counters.
 * - Pause retains jobs `PAUSED` (new completions are ineligible at the finalizer); profile
 *   switch suppresses dispatch and cancels the worker.
 */
class IntervalsExportCoordinator(
    applicationScope: CoroutineScope,
    private val api: IntervalsApi,
    private val outbox: IntervalsOutboxRepository,
    private val connectionReader: IntervalsConnectionReader,
    private val credentials: IntervalsCredentialSource,
    private val activeProfile: IntervalsActiveProfileSource,
    private val pendingFinalizer: PendingCompletionFinalizer = PendingCompletionFinalizer {},
    private val drainTrigger: IntervalsDrainTrigger = IntervalsDrainTrigger(),
    private val nowEpochMs: () -> Long = { currentTimeMillis() },
    private val jitterUnit: () -> Double = { Random.nextDouble() },
    private val leaseTokenFactory: () -> String = { generateUUID() },
) {

    /** Dedicated supervised scope: children fail independently, dies with the application scope. */
    private val scope = CoroutineScope(
        SupervisorJob(applicationScope.coroutineContext[Job]) +
            applicationScope.coroutineContext.minusKey(Job) +
            CoroutineName("IntervalsExportCoordinator"),
    )

    private val drainMutex = Mutex()

    /** Profile -> connection generation at the moment a 401/403 suppressed dispatch. */
    private val authSuppressedGeneration = MutableStateFlow<Map<String, Long>>(emptyMap())

    init {
        scope.launch {
            for (signal in drainTrigger.signals) {
                // One bad drain pass must never kill the only drain worker (a SupervisorJob
                // child that throws would leave `signals` undrained for the process
                // lifetime). Cancellation still propagates so shutdown() works.
                try {
                    runDrain()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    Logger.w(t) { "Intervals export drain failed; waiting for the next signal" }
                }
            }
        }
    }

    /** Foreground/resume signal. Coalesced with any pending signal; never spins. */
    fun signalForeground() {
        drainTrigger.signal()
    }

    /** New-completion signal from the finalizer. Coalesced like [signalForeground]. */
    fun signalCompletion() {
        drainTrigger.signal()
    }

    /** Cancels the dedicated worker. Never touches remote activities. */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * One bounded drain: pending journal finalization, foreground lease recovery, a bounded
     * dispatch pass and bounded reconciliation passes. Singleflight — concurrent callers
     * serialize on one drain.
     */
    suspend fun runDrain() {
        drainMutex.withLock {
            try {
                pendingFinalizer.finalizePending()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Logger.w(t) { "Intervals pending completion finalization failed before drain" }
            }
            val now = nowEpochMs()
            // Snapshot before lease recovery/dispatch so jobs that go UNCERTAIN in this
            // drain reconcile on the next drain, not immediately against a possibly
            // lagging listing (and never overwrite fresh reason codes).
            val profileForSnapshot = activeProfile.activeProfileId() ?: return
            val uncertainSnapshot = outbox.uncertainJobs(
                profileId = profileForSnapshot,
                limit = IntervalsTransportPolicy.RECONCILIATION_PAGES_PER_DRAIN,
            )

            // Foreground journal recovery: expired IN_FLIGHT leases become UNCERTAIN,
            // never unrestricted PENDING (spec 4.4).
            outbox.expireLeases(now)

            val profileId = activeProfile.activeProfileId() ?: return
            val status = connectionReader.status(profileId)

            dispatchDueJobs(profileId, status)

            for (job in uncertainSnapshot) {
                if (activeProfile.activeProfileId() != profileId) return
                reconcileOne(job)
            }
        }
    }

    /**
     * Manual "Check upload / status" for one uncertain job. This is the ONLY retry path:
     * it reconciles evidence and never re-POSTs, recreates or resets lifetime counters.
     */
    suspend fun reconcileJob(
        profileId: String,
        resolvedAthleteId: String,
        externalId: String,
    ): IntervalsReconciliationResult {
        drainMutex.withLock {
            val job = outbox.getJob(profileId, resolvedAthleteId, externalId)
                ?: return IntervalsReconciliationResult.NoMatch
            if (job.state != IntervalsJobState.UNCERTAIN) {
                // Never reinterprets SENT/PERMANENT_FAILURE/PAUSED rows as sendable work.
                return IntervalsReconciliationResult.NoMatch
            }
            return reconcileOne(job)
        }
    }

    // ---------------------------------------------------------------- dispatch

    private suspend fun dispatchDueJobs(profileId: String, status: IntervalsConnectionStatus?) {
        if (status == null) return
        if (!status.enabled || status.paused || status.authRequired) return
        if (authSuppressedGeneration.value[profileId] == status.dispatchAuthGeneration) return

        val due = outbox.dueJobs(
            profileId = profileId,
            now = nowEpochMs(),
            maxAttempts = IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS,
            limit = IntervalsTransportPolicy.JOBS_PER_DRAIN,
        )
        for (job in due) {
            if (job.profileId != profileId) continue
            // Profile switch suppresses dispatch and cancels the worker.
            if (activeProfile.activeProfileId() != profileId) return

            val binding = dispatchBinding(status, job)
            if (!connectionReader.isDispatchAuthorized(binding)) continue

            val apiKey = credentials.apiKey(job.profileId) ?: continue

            val leaseToken = leaseTokenFactory()
            val claimed = outbox.claimJob(
                profileId = job.profileId,
                resolvedAthleteId = job.resolvedAthleteId,
                externalId = job.externalId,
                expectedDispatchAuthGeneration = job.dispatchAuthGeneration,
                leaseToken = leaseToken,
                leaseExpiresAt = nowEpochMs() + IntervalsTransportPolicy.SQL_LEASE_MILLIS,
                now = nowEpochMs(),
            ) ?: continue

            // Mandated pre-request recheck. Nothing has been sent yet, so the claim can be
            // released without ambiguity; the claim still counted as one durable attempt.
            if (!connectionReader.isDispatchAuthorized(binding)) {
                releaseClaim(claimed, leaseToken)
                continue
            }

            val suppressed = sendClaimed(claimed, leaseToken, apiKey, status.dispatchAuthGeneration)
            if (suppressed) return
        }
    }

    private fun dispatchBinding(
        status: IntervalsConnectionStatus,
        job: IntervalsJobRecord,
    ): IntervalsDispatchBinding = IntervalsDispatchBinding(
        installationId = status.installationId,
        profileId = job.profileId,
        resolvedAthleteId = job.resolvedAthleteId,
        completionId = job.receiptCompletionId,
        originalGeneration = job.originalGeneration,
    )

    /** Returns true when dispatch is now suppressed (401/403) and the drain must stop. */
    private suspend fun sendClaimed(
        job: IntervalsJobRecord,
        leaseToken: String,
        apiKey: String,
        connectionAuthGeneration: Long,
    ): Boolean {
        val activity = decodePayload(job)
        if (activity == null) {
            completeSafely(
                job = job,
                leaseToken = leaseToken,
                state = IntervalsJobState.PERMANENT_FAILURE,
                reasonCode = IntervalsReasonCode.VALIDATION_REJECTED,
                remoteActivityId = null,
                nextAttemptAt = null,
                firstSentAt = null,
            )
            return false
        }
        // Local defensive preflight (spec 4.3): visible failure, never truncation or
        // multiple activities. These are local prototype bounds, not vendor limits.
        if (activity.description.encodeToByteArray().size > IntervalsPayloadLimits.MAX_DESCRIPTION_BYTES ||
            activity.name.encodeToByteArray().size > IntervalsPayloadLimits.MAX_NAME_BYTES
        ) {
            completeSafely(
                job = job,
                leaseToken = leaseToken,
                state = IntervalsJobState.PERMANENT_FAILURE,
                reasonCode = IntervalsReasonCode.PAYLOAD_TOO_LARGE,
                remoteActivityId = null,
                nextAttemptAt = null,
                firstSentAt = null,
            )
            return false
        }

        val outcome = api.createManualActivity(job.resolvedAthleteId, apiKey, activity)
        return applyCreateOutcome(job, leaseToken, outcome, connectionAuthGeneration)
    }

    /** Applies the frozen §4.4 classification under the lease-token + generation CAS. */
    private suspend fun applyCreateOutcome(
        job: IntervalsJobRecord,
        leaseToken: String,
        outcome: IntervalsCreateOutcome,
        connectionAuthGeneration: Long,
    ): Boolean {
        val now = nowEpochMs()
        return when (outcome) {
            is IntervalsCreateOutcome.Accepted -> {
                completeSafely(
                    job = job,
                    leaseToken = leaseToken,
                    state = IntervalsJobState.SENT,
                    reasonCode = null,
                    remoteActivityId = outcome.remoteActivityId,
                    nextAttemptAt = null,
                    firstSentAt = now,
                )
                false
            }

            is IntervalsCreateOutcome.AuthRejected -> {
                completeSafely(
                    job = job,
                    leaseToken = leaseToken,
                    state = IntervalsJobState.AUTH_REQUIRED,
                    reasonCode = IntervalsReasonCode.AUTH_REQUIRED,
                    remoteActivityId = null,
                    nextAttemptAt = null,
                    firstSentAt = null,
                )
                // Suppress connection dispatch; other rows go AUTH_REQUIRED after our own
                // CAS write so the bulk transition cannot steal this lease. The map is
                // keyed by the CONNECTION's authorization generation — the value
                // dispatchDueJobs compares against — never the job row's (always-0)
                // dispatch_auth_generation.
                outbox.markProfileAuthRequired(job.profileId, now)
                authSuppressedGeneration.value =
                    authSuppressedGeneration.value + (job.profileId to connectionAuthGeneration)
                true
            }

            is IntervalsCreateOutcome.ValidationRejected -> {
                completeSafely(
                    job = job,
                    leaseToken = leaseToken,
                    state = IntervalsJobState.PERMANENT_FAILURE,
                    reasonCode = if (outcome.statusCode == 413) {
                        IntervalsReasonCode.PAYLOAD_TOO_LARGE
                    } else {
                        IntervalsReasonCode.VALIDATION_REJECTED
                    },
                    remoteActivityId = null,
                    nextAttemptAt = null,
                    firstSentAt = null,
                )
                false
            }

            is IntervalsCreateOutcome.RateLimitedOrServerFailure -> {
                if (outcome.provablyNotAccepted) {
                    // The write provably never happened; safe retry is allowed within the
                    // lifetime bound. Valid Retry-After defers beyond the cap, never earlier.
                    if (job.attempts >= IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS) {
                        completeSafely(
                            job = job,
                            leaseToken = leaseToken,
                            state = IntervalsJobState.PERMANENT_FAILURE,
                            reasonCode = IntervalsReasonCode.RETRY_EXHAUSTED,
                            remoteActivityId = null,
                            nextAttemptAt = null,
                            firstSentAt = null,
                        )
                    } else {
                        completeSafely(
                            job = job,
                            leaseToken = leaseToken,
                            state = IntervalsJobState.RETRY_WAIT,
                            reasonCode = IntervalsReasonCode.SAFE_RETRYABLE,
                            remoteActivityId = null,
                            nextAttemptAt = now + nextAttemptDelayMillis(job.attempts, outcome.retryAfterMillis),
                            firstSentAt = null,
                        )
                    }
                } else {
                    // 429/5xx after possible delivery is not provably safe: UNCERTAIN.
                    recordUncertain(job, leaseToken, IntervalsReasonCode.AMBIGUOUS_OUTCOME, now)
                }
                false
            }

            IntervalsCreateOutcome.Ambiguous -> {
                // Timeout / connection loss after possible delivery. (Cancellation is
                // never classified here — it propagates from the client and the claimed
                // lease expires to UNCERTAIN/LEASE_EXPIRED.)
                recordUncertain(job, leaseToken, IntervalsReasonCode.AMBIGUOUS_OUTCOME, now)
                false
            }
        }
    }

    private suspend fun recordUncertain(
        job: IntervalsJobRecord,
        leaseToken: String,
        reason: IntervalsReasonCode,
        now: Long,
    ) {
        withContext(NonCancellable) {
            completeSafely(
                job = job,
                leaseToken = leaseToken,
                state = IntervalsJobState.UNCERTAIN,
                reasonCode = reason,
                remoteActivityId = null,
                nextAttemptAt = null,
                firstSentAt = now,
            )
        }
    }

    /** Releases an unsent claim back to PENDING (provably nothing was delivered). */
    private suspend fun releaseClaim(job: IntervalsJobRecord, leaseToken: String) {
        completeSafely(
            job = job,
            leaseToken = leaseToken,
            state = IntervalsJobState.PENDING,
            reasonCode = null,
            remoteActivityId = null,
            nextAttemptAt = null,
            firstSentAt = null,
        )
    }

    /**
     * CAS-guarded write using the generation captured at claim time. A false return is a
     * stale write (the row was replaced/re-authorized meanwhile) and is never retried.
     */
    private suspend fun completeSafely(
        job: IntervalsJobRecord,
        leaseToken: String?,
        state: IntervalsJobState,
        reasonCode: IntervalsReasonCode?,
        remoteActivityId: String?,
        nextAttemptAt: Long?,
        firstSentAt: Long?,
    ): Boolean = outbox.completeJob(
        profileId = job.profileId,
        resolvedAthleteId = job.resolvedAthleteId,
        externalId = job.externalId,
        leaseToken = leaseToken,
        expectedDispatchAuthGeneration = job.dispatchAuthGeneration,
        state = state,
        reasonCode = reasonCode,
        remoteActivityId = remoteActivityId,
        nextAttemptAt = nextAttemptAt,
        firstSentAt = firstSentAt,
        now = nowEpochMs(),
    )

    private fun nextAttemptDelayMillis(attempts: Long, retryAfterMillis: Long?): Long {
        val jitterDelay = IntervalsTransportPolicy.safeRetryDelayMillis(
            attempt = attempts.toInt().coerceIn(1, IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS),
            jitterUnit = jitterUnit(),
        )
        return max(jitterDelay, retryAfterMillis ?: 0L)
    }

    private fun decodePayload(job: IntervalsJobRecord): IntervalsManualActivity? =
        runCatching {
            wirePayloadJson.decodeFromString(IntervalsManualActivity.serializer(), job.payloadJson)
        }.getOrNull()

    // ---------------------------------------------------------------- reconciliation

    /**
     * Reconciliation-only recovery for one UNCERTAIN job: documented listing with exact
     * `external_id` match + GET readback. Never POSTs. Bounds: one pass = up to
     * [IntervalsTransportPolicy.RECONCILIATION_MAX_PAGES_PER_PASS] pages of
     * [IntervalsTransportPolicy.RECONCILIATION_PAGE_SIZE] items; reaching the cap is an
     * incomplete search, not absence.
     */
    private suspend fun reconcileOne(job: IntervalsJobRecord): IntervalsReconciliationResult {
        val status = connectionReader.status(job.profileId) ?: return IntervalsReconciliationResult.IncompleteSearch
        val apiKey = credentials.apiKey(job.profileId) ?: return IntervalsReconciliationResult.IncompleteSearch

        val window = reconciliationWindow(job)
        val matchedIds = mutableListOf<String>()
        var enumerationComplete = false
        var page = 1
        while (page <= IntervalsTransportPolicy.RECONCILIATION_MAX_PAGES_PER_PASS) {
            val binding = dispatchBinding(status, job)
            if (!connectionReader.isDispatchAuthorized(binding)) {
                return IntervalsReconciliationResult.IncompleteSearch
            }
            when (val listing = api.listActivities(job.resolvedAthleteId, apiKey, window.first, window.second, page)) {
                is IntervalsListResult.AuthRejected -> {
                    outbox.markProfileAuthRequired(job.profileId, nowEpochMs())
                    authSuppressedGeneration.value =
                        authSuppressedGeneration.value + (job.profileId to status.dispatchAuthGeneration)
                    return IntervalsReconciliationResult.IncompleteSearch
                }

                IntervalsListResult.Failed -> return finishReconciliation(
                    job = job,
                    result = IntervalsReconciliationResult.IncompleteSearch,
                    reason = IntervalsReasonCode.INCOMPLETE_RECONCILIATION,
                )

                is IntervalsListResult.Page -> {
                    listing.items
                        .filter { it.resolvedExternalId == job.externalId }
                        .mapNotNullTo(matchedIds) { it.id }
                    if (listing.complete) {
                        enumerationComplete = true
                        break
                    }
                    page++
                }
            }
        }
        if (!enumerationComplete) {
            // Page cap reached with full pages: incomplete search, never evidence of absence.
            return finishReconciliation(
                job = job,
                result = IntervalsReconciliationResult.IncompleteSearch,
                reason = IntervalsReasonCode.INCOMPLETE_RECONCILIATION,
            )
        }

        return when {
            matchedIds.size > 1 -> finishReconciliation(
                job = job,
                result = IntervalsReconciliationResult.MultipleMatches,
                reason = IntervalsReasonCode.MULTIPLE_REMOTE_MATCHES,
            )

            matchedIds.isEmpty() -> finishReconciliation(
                job = job,
                result = IntervalsReconciliationResult.NoMatch,
                reason = IntervalsReasonCode.NO_REMOTE_MATCH,
            )

            else -> {
                val remoteId = matchedIds.first()
                val binding = dispatchBinding(status, job)
                if (!connectionReader.isDispatchAuthorized(binding)) {
                    return IntervalsReconciliationResult.IncompleteSearch
                }
                when (val readback = api.readbackActivity(job.resolvedAthleteId, apiKey, remoteId)) {
                    is IntervalsReadbackResult.AuthRejected -> {
                        outbox.markProfileAuthRequired(job.profileId, nowEpochMs())
                        IntervalsReconciliationResult.IncompleteSearch
                    }

                    is IntervalsReadbackResult.Found -> {
                        if (readback.summary.resolvedExternalId == job.externalId) {
                            val confirmedId = readback.summary.id ?: remoteId
                            completeSafely(
                                job = job,
                                leaseToken = job.leaseToken,
                                state = IntervalsJobState.SENT,
                                reasonCode = null,
                                remoteActivityId = confirmedId,
                                nextAttemptAt = null,
                                firstSentAt = nowEpochMs(),
                            )
                            IntervalsReconciliationResult.UniqueMatch(confirmedId)
                        } else {
                            finishReconciliation(
                                job = job,
                                result = IntervalsReconciliationResult.IncompleteSearch,
                                reason = IntervalsReasonCode.INCOMPLETE_RECONCILIATION,
                            )
                        }
                    }

                    IntervalsReadbackResult.NotFound,
                    IntervalsReadbackResult.Failed,
                    -> finishReconciliation(
                        job = job,
                        result = IntervalsReconciliationResult.IncompleteSearch,
                        reason = IntervalsReasonCode.INCOMPLETE_RECONCILIATION,
                    )
                }
            }
        }
    }

    /**
     * Persisted reconciliation verdicts short of a readback-confirmed match: everything
     * preserves uncertainty. The job keeps its original generation and lifetime counters.
     */
    private suspend fun finishReconciliation(
        job: IntervalsJobRecord,
        result: IntervalsReconciliationResult,
        reason: IntervalsReasonCode,
    ): IntervalsReconciliationResult {
        completeSafely(
            job = job,
            leaseToken = job.leaseToken,
            state = IntervalsJobState.UNCERTAIN,
            reasonCode = reason,
            remoteActivityId = null,
            nextAttemptAt = null,
            firstSentAt = null,
        )
        return result
    }

    /**
     * Listing window for reconciliation: Intervals.icu indexes activities by their
     * `start_date_local` calendar date, which may precede `createdAt`/`firstSentAt` when a
     * workout ran before midnight or replayed offline days later. Anchor ±1 day around the
     * payload's `start_date_local` (`YYYY-MM-DD`), falling back to `firstSentAt ?: createdAt`
     * only when the payload date cannot be parsed. Never reads the device zone (spec 4.3).
     */
    private fun reconciliationWindow(job: IntervalsJobRecord): Pair<String, String> {
        val day = 24L * 60L * 60L * 1_000L
        val payloadAnchorMs = decodePayload(job)?.startDateLocal
            ?.takeIf { it.length >= 10 }
            ?.let { raw ->
                runCatching {
                    LocalDate.parse(raw.substring(0, 10)).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                }.getOrNull()
            }
        val anchor = payloadAnchorMs ?: job.firstSentAt ?: job.createdAt
        return intervalsUtcDateString(anchor - day) to intervalsUtcDateString(anchor + day)
    }

    private companion object {
        val wirePayloadJson = kotlinx.serialization.json.Json {
            explicitNulls = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
