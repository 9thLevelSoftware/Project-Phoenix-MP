package com.devil.phoenixproject.presentation.viewmodel

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.data.integration.intervals.IntervalsExportCoordinator
import com.devil.phoenixproject.data.integration.intervals.IntervalsExportStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.data.integration.intervals.IntervalsReconciliationResult
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionStore
import com.devil.phoenixproject.data.integration.intervals.IntervalsTransportPolicy
import com.devil.phoenixproject.data.integration.intervals.IntervalsValidationResult
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.IntervalsOutboxRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.util.ConnectivityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Real [IntervalsStatusSource] binding for the Settings screen (issue #1384, packet P3):
 * backed by [IntervalsConnectionStore] (connection/consent/secure store),
 * [IntervalsExportCoordinator] (bounded reconciliation) and [IntervalsOutboxRepository]
 * (nonsecret status projection). Constructed in `NavGraph` from Koin-injected singletons
 * and remembered for the route's lifetime; [close] releases the derived-state scope.
 *
 * Semantics kept exactly per the seam contract:
 * - [saveCredentials] / [testConnection] never enable the export.
 * - [enable] / [pause] / [resume] / [disconnect] delegate to the store (which enforces
 *   the generation/consent rules) and then signal the coordinator.
 * - [checkUpload] triggers reconciliation ONLY — it can never create a second activity
 *   ("upload again" is forbidden).
 * - Status is profile/destination scoped to the ACTIVE profile; a profile switch
 *   (`activeProfileContext` not `Ready`) yields no active profile and suppresses
 *   dispatch.
 */
class RealIntervalsStatusSource(
    private val connectionStore: IntervalsConnectionStore,
    private val coordinator: IntervalsExportCoordinator,
    private val outbox: IntervalsOutboxRepository,
    private val userProfileRepository: UserProfileRepository,
    private val connectivity: ConnectivityChecker? = null,
    /**
     * Read-only outbox scan for the newest PERMANENT_FAILURE reason code. The frozen P1
     * query set has no named operation for it (same precedent as the outbox repository's
     * `rawScanDriver`); without a driver the reason degrades to null, never a guess.
     */
    private val outboxScanDriver: SqlDriver? = null,
) : IntervalsStatusSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val emptyStatus = IntervalsExportStatus(
        connection = null,
        jobCounts = emptyList(),
        lastSentAt = null,
    )

    /** Bumped after every local mutation so connection-only changes re-project promptly. */
    private val refreshTick = MutableStateFlow(0L)

    private val _networkAvailable = MutableStateFlow(connectivity?.isOnline() ?: true)

    private val activeProfile: StateFlow<String?> = userProfileRepository.activeProfileContext
        .map { context -> (context as? ActiveProfileContext.Ready)?.profile?.id }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id,
        )

    /** Releases the derived-state scope. Called when the Settings route is disposed. */
    fun close() {
        scope.cancel()
    }

    override fun status(): Flow<IntervalsExportStatus> =
        activeProfile.flatMapLatest { profileId ->
            if (profileId == null) {
                flowOf(emptyStatus)
            } else {
                combine(outbox.observeStatus(profileId), refreshTick) { export, _ -> export }
                    .mapLatest { export ->
                        _networkAvailable.value = connectivity?.isOnline() ?: true
                        // Re-resolve the connection projection at every emission so
                        // consent/generation changes are reflected immediately.
                        export.copy(connection = connectionStore.status(profileId))
                    }
            }
            // The projection re-resolves the connection (DB + secure-store read) on every
            // emission: never on the collector's (main) thread.
        }.flowOn(Dispatchers.IO)

    override fun secureStoreAvailable(): StateFlow<Boolean> = connectionStore.secureStoreAvailable

    /**
     * Coarse connectivity view (documented caveat: there is no reactive connectivity
     * source in the repo, so this samples [ConnectivityChecker] on construction and on
     * every status projection/mutation; queued jobs are held, never lost, while offline).
     */
    override fun networkAvailable(): StateFlow<Boolean> = _networkAvailable

    override fun activeProfileId(): StateFlow<String?> = activeProfile

    override fun permanentFailureReason(): StateFlow<IntervalsReasonCode?> =
        activeProfile.flatMapLatest { profileId ->
            if (profileId == null) {
                flowOf(null)
            } else {
                status().mapLatest { export ->
                    if (export.permanentFailureCount > 0L) {
                        withContext(Dispatchers.IO) { latestPermanentFailureReason(profileId) }
                    } else {
                        null
                    }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun saveCredentials(athleteId: String, apiKey: String): IntervalsValidationResult {
        val profileId = activeProfile.value
            ?: return IntervalsValidationResult.Rejected(0, "no active profile")
        // Store mutations are SQLDelight + secure-store work: never on the main thread.
        val result = withContext(Dispatchers.IO) {
            connectionStore.saveCredentials(profileId, athleteId, apiKey)
        }
        bump()
        return result
    }

    override suspend fun testConnection(athleteId: String, apiKey: String): IntervalsValidationResult {
        // Nonmutating: testing never enables and never stores anything.
        val result = withContext(Dispatchers.IO) { connectionStore.testConnection(athleteId, apiKey) }
        bump()
        return result
    }

    override suspend fun enable(): Boolean {
        val profileId = activeProfile.value ?: return false
        val enabled = withContext(Dispatchers.IO) { connectionStore.enable(profileId) }
        if (enabled) coordinator.signalForeground()
        bump()
        return enabled
    }

    override suspend fun pause() {
        val profileId = activeProfile.value ?: return
        withContext(Dispatchers.IO) { connectionStore.pause(profileId) }
        bump()
    }

    override suspend fun resume() {
        val profileId = activeProfile.value ?: return
        withContext(Dispatchers.IO) { connectionStore.resume(profileId) }
        coordinator.signalForeground()
        bump()
    }

    override suspend fun disconnect() {
        val profileId = activeProfile.value ?: return
        withContext(Dispatchers.IO) { connectionStore.disconnect(profileId) }
        bump()
    }

    /**
     * "Check upload / status": bounded reconciliation of UNCERTAIN jobs ONLY. This never
     * re-POSTs, recreates or resets lifetime counters — a manual check is readback, not
     * "upload again".
     */
    override suspend fun checkUpload(): IntervalsCheckUploadResult {
        val profileId = activeProfile.value
            ?: return IntervalsCheckUploadResult.Failed(IntervalsReasonCode.INCOMPLETE_RECONCILIATION, retryable = true)
        val incomplete = withContext(Dispatchers.IO) {
            val uncertain = outbox.uncertainJobs(
                profileId = profileId,
                limit = IntervalsTransportPolicy.RECONCILIATION_PAGES_PER_DRAIN,
            )
            if (uncertain.isEmpty()) {
                false
            } else {
                var incompleteSearch = false
                for (job in uncertain) {
                    when (coordinator.reconcileJob(profileId, job.resolvedAthleteId, job.externalId)) {
                        is IntervalsReconciliationResult.UniqueMatch,
                        IntervalsReconciliationResult.NoMatch,
                        IntervalsReconciliationResult.MultipleMatches,
                        -> Unit

                        IntervalsReconciliationResult.IncompleteSearch -> incompleteSearch = true
                    }
                }
                incompleteSearch
            }
        }
        bump()
        return if (incomplete) {
            IntervalsCheckUploadResult.Failed(IntervalsReasonCode.INCOMPLETE_RECONCILIATION, retryable = true)
        } else {
            IntervalsCheckUploadResult.Checked
        }
    }

    private fun bump() {
        refreshTick.value = refreshTick.value + 1L
    }

    /**
     * Newest durable PERMANENT_FAILURE reason of the profile, read with a read-only
     * scan (mirrors the outbox repository's raw-scan precedent). Never guesses: no
     * scan driver or no parsable reason yields null.
     */
    private fun latestPermanentFailureReason(profileId: String): IntervalsReasonCode? {
        val driver = outboxScanDriver ?: return null
        return try {
            val raw = driver.executeQuery(
                null,
                "SELECT reason_code FROM IntervalsOutbox WHERE profile_id = ? " +
                    "AND state = 'PERMANENT_FAILURE' ORDER BY updated_at DESC LIMIT 1",
                { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                },
                1,
            ) {
                bindString(0, profileId)
            }.value
            raw?.let { code -> runCatching { IntervalsReasonCode.valueOf(code) }.getOrNull() }
        } catch (t: Throwable) {
            null
        }
    }
}
