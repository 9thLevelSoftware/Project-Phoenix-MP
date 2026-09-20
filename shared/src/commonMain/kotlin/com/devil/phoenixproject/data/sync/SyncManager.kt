package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.ExternalActivitySyncKey
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.OwnershipEvent
import com.devil.phoenixproject.data.repository.OwnershipEventApplier
import com.devil.phoenixproject.data.repository.OwnershipTransferRepository
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutDeletionRepository
import com.devil.phoenixproject.data.repository.WorkoutSyncSnapshot
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import com.devil.phoenixproject.getPlatform
import com.devil.phoenixproject.isIosPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()

    /**
     * Syncing with pagination progress reporting.
     * @param pagesProcessed Number of pages fetched so far
     * @param entitiesFetched Total entities fetched across all pages
     */
    data class SyncingWithProgress(
        val pagesProcessed: Int,
        val entitiesFetched: Int,
    ) : SyncState()

    data class Success(val syncTime: Long) : SyncState()

    /**
     * Partial sync success: push succeeded but pull failed.
     * Indicates that local changes were uploaded, but remote changes weren't retrieved.
     * UI should display this as a warning and offer pull retry.
     */
    data class PartialSuccess(
        val pushSucceeded: Boolean,
        val pullSucceeded: Boolean,
        val lastSyncTime: Long,
        val pullError: String? = null,
    ) : SyncState()

    data class Error(val message: String, val errorCategory: SyncErrorCategory? = null) : SyncState()
    object NotAuthenticated : SyncState()
    object NotPremium : SyncState()
}

/**
 * Sync configuration constants for pagination and limits.
 */
object SyncConfig {
    /** Default number of entities per pull page. */
    const val DEFAULT_PAGE_SIZE = 100

    /**
     * Emergency circuit breaker: absolute maximum pages per pull.
     *
     * This is a telemetry-backed safety net, NOT a product limit. Normal pagination
     * terminates via `hasMore=false` or cursor-validity checks. A legitimate account
     * should never reach this ceiling; if it fires, it signals a server-side
     * pagination bug (e.g. cursor never exhausted).
     *
     * Previous value was 100, which blocked accounts with >10,000 entities.
     * See: https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/679
     */
    const val MAX_PAGES = 10_000

    /**
     * Maximum number of IDs per parity list sent in a single pull request.
     *
     * UPDATE 2026-04-20: Server now uses RPC functions (get_sessions_excluding_ids,
     * etc.) which accept IDs in POST body instead of URL params. No URL length limit.
     * Raised cap to 10,000 for power users with years of workout history.
     *
     * The RPC functions use PostgreSQL array parameters, which handle large arrays
     * efficiently via `id != ALL(p_known_ids)`.
     */
    const val MAX_PARITY_IDS = 10_000

    // ─── Phase 4.2: self-cap + self-throttle (audit item #9) ──────────────
    //
    // These constants mirror the server-side limits in
    // phoenix-portal/supabase/functions/mobile-sync-push/index.ts so that a
    // misbehaving client fails fast locally instead of wasting an Edge
    // Function invocation only to receive HTTP 413/429. The client caps stay
    // slightly below the server limits where possible so spurious retries do
    // not teeter on the threshold.

    /** Maximum sessions per push batch. Must stay <= server MAX_ARRAY_SIZE (10000). */
    const val MAX_SESSIONS_PER_BATCH = 10_000

    /** Maximum routines per push batch (mirrors server cap). */
    const val MAX_ROUTINES_PER_BATCH = 10_000

    /** Maximum training cycles per push batch. Aligned with server cap in audit #6. */
    const val MAX_CYCLES_PER_BATCH = 10_000

    /** Maximum rep_telemetry points per push batch. Server accepts up to MAX_ARRAY_SIZE. */
    const val MAX_TELEMETRY_PER_BATCH = 10_000

    /**
     * Maximum serialized payload size in bytes for a single push request.
     * Set 500 KiB below the server's 10 MiB limit to leave headroom for
     * compression envelope + HTTP framing overhead.
     */
    const val MAX_PAYLOAD_BYTES = 9_500_000L

    /** Maximum push requests per minute (matches server-enforced rate limit). */
    const val PUSH_RATE_LIMIT_PER_MIN = 10

    /** Maximum pull requests per minute (matches server-enforced rate limit). */
    const val PULL_RATE_LIMIT_PER_MIN = 20

    /** Maximum Retry-After-driven retries allowed for a single pull page. */
    const val MAX_RETRY_AFTER_ATTEMPTS_PER_PAGE = 3

    /** Rate-limit window in milliseconds (60 seconds). */
    const val RATE_LIMIT_WINDOW_MS = 60_000L
}

private val PROFILE_PREFERENCE_REASON_NAMES =
    ProfilePreferenceSyncIssueReason.entries.mapTo(mutableSetOf<String>()) { it.name }

private fun safeProfilePreferenceReason(reason: String): String =
    reason.takeIf { it in PROFILE_PREFERENCE_REASON_NAMES }
        ?: "INVALID_PROFILE_PREFERENCE_DIAGNOSTIC"

internal enum class ProfilePreferenceLocalFailureStage {
    SNAPSHOT,
    MUTATION_MAPPING,
    CHUNK_PLANNING,
    RESPONSE_MAPPING,
    OUTCOME_APPLY,
    PULL_RESPONSE_MAPPING,
    PULL_APPLY,
}

internal fun profilePreferenceIssueLogLine(issue: ProfilePreferenceSyncIssue): String =
    "PROFILE_PREFERENCE_NOT_SENT section=${issue.key.section.name} " +
        "reason=${safeProfilePreferenceReason(issue.reason)}"

internal fun profilePreferenceMetadataDeferredLogLine(
    key: ProfilePreferenceSectionKey,
): String = "PROFILE_PREFERENCE_NOT_SENT section=${key.section.name} " +
    "reason=PROFILE_METADATA_NOT_SENT"

internal fun profilePreferenceDuplicateResultLogLine(
    key: ProfilePreferenceSectionKey,
): String = "PROFILE_PREFERENCE_DUPLICATE_RESULT section=${key.section.name}"

internal fun profilePreferenceInvalidCanonicalLogLine(
    invalid: ProfilePreferenceCanonicalDecodeResult.Invalid,
): String = "PROFILE_PREFERENCE_INVALID_CANONICAL " +
    "reason=${safeProfilePreferenceReason(invalid.reason)}"

internal enum class ProfilePreferencePullDiagnosticCategory {
    INVALID_CANONICAL,
    DUPLICATE_KEY,
    LATER_PAGE_IGNORED,
    UNKNOWN_PROFILE,
    REPOSITORY_INVALID,
}

internal data class ProfilePreferencePullPlan(
    val valid: List<CanonicalProfilePreferenceSection>,
    val invalidCanonicalCount: Int,
    val duplicateKeyCount: Int,
)

private fun profilePreferencePullEnvelopeKey(
    dto: PortalProfilePreferenceSectionCanonicalDto,
): ProfilePreferenceSectionKey? {
    val section = ProfilePreferenceSectionName.entries.firstOrNull { it.name == dto.section }
        ?: return null
    return ProfilePreferenceSectionKey(dto.localProfileId, section)
}

internal fun planProfilePreferencePullSections(
    dtos: List<PortalProfilePreferenceSectionCanonicalDto>,
): ProfilePreferencePullPlan {
    val keyCounts = dtos.mapNotNull(::profilePreferencePullEnvelopeKey)
        .groupingBy { it }
        .eachCount()
    val duplicateKeys = keyCounts.filterValues { it > 1 }.keys
    val decoded = dtos.map(PortalPullAdapter::toCanonicalProfilePreferenceSection)
    return ProfilePreferencePullPlan(
        valid = decoded
            .filterIsInstance<ProfilePreferenceCanonicalDecodeResult.Valid>()
            .map { it.section }
            .filterNot { it.key in duplicateKeys },
        invalidCanonicalCount = decoded.count {
            it is ProfilePreferenceCanonicalDecodeResult.Invalid
        },
        duplicateKeyCount = duplicateKeys.size,
    )
}

internal fun profilePreferencePullCountLogLine(
    category: ProfilePreferencePullDiagnosticCategory,
    count: Int,
): String = "PROFILE_PREFERENCE_PULL category=${category.name} count=$count"

internal fun profilePreferenceChunkFailureLogLine(error: Throwable?): String {
    val status = (error as? PortalApiException)?.statusCode?.toString() ?: "UNKNOWN"
    return "PROFILE_PREFERENCE_CHUNK_FAILED status=$status"
}

internal fun profilePreferenceLocalFailureLogLine(
    stage: ProfilePreferenceLocalFailureStage,
): String = "PROFILE_PREFERENCE_LOCAL_FAILURE stage=${stage.name}"

internal suspend fun <T> isolateProfilePreferenceFailure(
    stage: ProfilePreferenceLocalFailureStage,
    onFailure: (String) -> Unit,
    block: suspend () -> T,
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    onFailure(profilePreferenceLocalFailureLogLine(stage))
    null
}

private data class PreferenceOutcomeCandidate(
    val key: ProfilePreferenceSectionKey,
    val serverRevision: Long,
    val canonical: CanonicalProfilePreferenceSection,
    val rejectionReason: String?,
)

private val PROFILE_PREFERENCE_REJECTION_REASONS = setOf(
    "REVISION_CONFLICT",
    "VALIDATION_FAILED",
    "UNSUPPORTED_SECTION",
    "UNSUPPORTED_DOCUMENT_VERSION",
    "UNKNOWN_PROFILE",
    "SECTION_TOO_LARGE",
    "DUPLICATE_SECTION",
)

private fun responseKey(
    localProfileId: String,
    section: String,
): ProfilePreferenceSectionKey? {
    val parsed = ProfilePreferenceSectionName.entries.firstOrNull { it.name == section }
        ?: return null
    return ProfilePreferenceSectionKey(localProfileId, parsed)
}

internal fun buildProfilePreferencePushOutcomes(
    response: PortalSyncPushResponse,
    ledger: Map<ProfilePreferenceSectionKey, Long>,
): List<ProfilePreferencePushOutcome> {
    val candidates = mutableListOf<PreferenceOutcomeCandidate>()
    val responseCounts = mutableMapOf<ProfilePreferenceSectionKey, Int>()
    response.canonicalProfilePreferenceSections.forEach { dto ->
        val key = responseKey(dto.localProfileId, dto.section) ?: return@forEach
        if (key !in ledger) return@forEach
        responseCounts[key] = responseCounts.getOrElse(key) { 0 } + 1
        val decoded = PortalPullAdapter.toCanonicalProfilePreferenceSection(dto)
        if (decoded is ProfilePreferenceCanonicalDecodeResult.Valid && decoded.section.key == key) {
            candidates += PreferenceOutcomeCandidate(
                key = key,
                serverRevision = decoded.section.serverRevision,
                canonical = decoded.section,
                rejectionReason = null,
            )
        }
    }
    response.profilePreferenceRejections.forEach { rejection ->
        val key = responseKey(rejection.localProfileId, rejection.section) ?: return@forEach
        if (key !in ledger) return@forEach
        responseCounts[key] = responseCounts.getOrElse(key) { 0 } + 1
        if (rejection.reason !in PROFILE_PREFERENCE_REJECTION_REASONS) return@forEach
        if (rejection.reason != "REVISION_CONFLICT") return@forEach
        val canonicalDto = rejection.canonicalSection ?: return@forEach
        val canonical = PortalPullAdapter.toCanonicalProfilePreferenceSection(canonicalDto)
        val decodedCanonical = (canonical as? ProfilePreferenceCanonicalDecodeResult.Valid)
            ?.section
            ?: return@forEach
        if (decodedCanonical.key != key ||
            decodedCanonical.serverRevision != rejection.serverRevision
        ) {
            return@forEach
        }
        candidates += PreferenceOutcomeCandidate(
            key = key,
            serverRevision = rejection.serverRevision,
            canonical = decodedCanonical,
            rejectionReason = rejection.reason,
        )
    }
    return candidates.groupBy(PreferenceOutcomeCandidate::key).mapNotNull { (key, entries) ->
        if (entries.size != 1 || responseCounts[key] != 1) {
            Logger.w("SyncManager") { profilePreferenceDuplicateResultLogLine(key) }
            return@mapNotNull null
        }
        val candidate = entries.single()
        ProfilePreferencePushOutcome(
            key = key,
            sentLocalGeneration = ledger.getValue(key),
            serverRevision = candidate.serverRevision,
            canonical = candidate.canonical,
            rejectionReason = candidate.rejectionReason,
        )
    }
}

internal fun missingAcknowledgedMutationIds(
    sent: Set<String>,
    acknowledged: Set<String>,
): Set<String> = sent - acknowledged

class SyncManager(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage,
    private val syncRepository: SyncRepository,
    private val gamificationRepository: GamificationRepository,
    private val repMetricRepository: RepMetricRepository,
    private val userProfileRepository: UserProfileRepository,
    private val profilePreferenceSyncRepository: ProfilePreferenceSyncRepository,
    private val externalActivityRepository: ExternalActivityRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    private val rateLimiter: ClientRateLimiter = ClientRateLimiter(),
    private val isProfilePreferenceMigrationReady: () -> Boolean,
    private val completedSetRepository: CompletedSetRepository? = null,
    private val workoutDeletionRepository: WorkoutDeletionRepository? = null,
    private val ownershipTransferRepository: OwnershipTransferRepository? = null,
    private val ownershipEventApplier: OwnershipEventApplier? = null,
    private val profileMutationBarrier: ProfileMutationBarrier? = null,
    private val trainingCycleRepository: TrainingCycleRepository? = null,
) {
    companion object {
        /**
         * Maximum sessions per sync batch. Keeps HTTP payload well under the Edge Function
         * body limit (~1 MB). Each session includes nested exercises, sets, rep summaries,
         * and linked telemetry + phase stats, so 50 sessions is a safe upper bound.
         */
        const val SYNC_BATCH_SIZE = 50

        /**
         * Maximum consecutive full-batch retry attempts before requiring manual retry.
         * Prevents infinite retry storms when the same batch keeps failing.
         */
        const val MAX_FULL_BATCH_RETRIES = 3

        private const val COMPLETED_SET_IDENTITY_CHUNK = 500

        /**
         * Subscription tier that entitles a user to sync 50 Hz rep telemetry
         * (raw force curves) to the portal. Matches the portal's "Session replay
         * with 50 Hz telemetry" and "Force curves & VBT zones" Inferno features.
         * All other tiers sync rep summaries but not per-sample telemetry.
         */
        const val TELEMETRY_SYNC_TIER = "INFERNO"

        private val CANONICAL_UUID_REGEX = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )

        private fun personalRecordSessionKey(exerciseId: String, timestamp: Long): String = "$exerciseId:$timestamp"
    }

    /**
     * Tracks consecutive full-batch retry failures. Reset on successful full sync.
     * When this reaches MAX_FULL_BATCH_RETRIES, sync will fail with a clear error
     * requiring user intervention (manual retry trigger).
     */
    private var consecutiveFullRetries = 0

    /**
     * Hash of the last failed batch payload for retry detection.
     * If the same payload fails repeatedly, we increment consecutiveFullRetries.
     */
    private var lastFailedBatchHash: Int? = null

    /**
     * Routine ids whose push the server rejected under LWW (server copy is newer).
     * The next completed pull applies the server version for these even though the
     * local row was edited after lastSync; otherwise the "local wins" routine merge
     * would keep the stale local copy and delta pulls would never re-send the server
     * row. Entries are account/profile scoped and cleared only when that scope's
     * pull completes. Guarded by [syncMutex].
     */
    private val pendingServerWinsRoutineIdsByScope = mutableMapOf<String, MutableSet<String>>()

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** Account-scoped cursor published atomically with token identity transitions. */
    val lastSyncTime: StateFlow<Long> = tokenStorage.lastSyncTimestamp

    val isAuthenticated: StateFlow<Boolean> = tokenStorage.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = tokenStorage.currentUser

    /** Auth events for UI notification (session expiry, refresh failure, logout). */
    val authEvents = tokenStorage.authEvents

    // === Authentication ===

    suspend fun login(email: String, password: String): Result<PortalUser> {
        val signInResult = apiClient.signIn(email, password)
        if (signInResult.isFailure) return signInResult.map { it.toPortalAuthResponse().user }

        val goTrueResponse = signInResult.getOrThrow()
        return syncMutex.withLock {
            try {
                withProfileMutationBarrier {
                    // Capture prior identity while the same lock that guards sync is held.
                    val previousUserId = tokenStorage.currentUser.value?.id
                    val previousPremium = tokenStorage.currentUser.value?.isPremium ?: false
                    val previousTier = tokenStorage.getSubscriptionTier()
                    val sameAccount = previousUserId != null && previousUserId == goTrueResponse.user.id

                    commitPortalIdentityUnderProfileMutationBarrier(
                        response = goTrueResponse,
                        tokenStorage = tokenStorage,
                        userProfileRepository = userProfileRepository,
                    )
                    _syncState.value = SyncState.Idle

                    val fallbackPremium = if (sameAccount) previousPremium else false
                    val fallbackTier = if (sameAccount) previousTier else null
                    val premiumResult = apiClient.checkPremiumStatus()
                    val isPremium = if (premiumResult.isSuccess) premiumResult.getOrNull() ?: false else fallbackPremium
                    tokenStorage.updatePremiumStatus(isPremium)
                    val tierResult = apiClient.getActiveSubscriptionTier()
                    val resolvedTier = if (tierResult.isSuccess) tierResult.getOrNull() else fallbackTier
                    tokenStorage.updateSubscriptionTier(resolvedTier)

                    Logger.i("SyncManager") {
                        "Login successful, premium=$isPremium, " +
                            "tier=${resolvedTier ?: "none"} (sameAccount=$sameAccount, " +
                            "server checks: premium=${premiumResult.isSuccess}, tier=${tierResult.isSuccess})"
                    }
                    Result.success(tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalUser> {
        val signUpResult = apiClient.signUp(email, password, displayName)
        if (signUpResult.isFailure) return signUpResult.map { it.toPortalAuthResponse().user }

        val goTrueResponse = signUpResult.getOrThrow()
        return syncMutex.withLock {
            try {
                withProfileMutationBarrier {
                    commitPortalIdentityUnderProfileMutationBarrier(
                        response = goTrueResponse,
                        tokenStorage = tokenStorage,
                        userProfileRepository = userProfileRepository,
                    )
                    tokenStorage.updatePremiumStatus(false)
                    tokenStorage.updateSubscriptionTier(null)
                    _syncState.value = SyncState.Idle
                    Logger.i("SyncManager") { "Signup successful" }
                    Result.success(tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }

    /**
     * Logs out the user by:
     * 1. Invalidating the server-side session via GoTrue signOut (best-effort)
     * 2. Clearing local auth tokens
     * 3. Emitting logout event for UI
     *
     * Issue 1.5: Server-side logout ensures refresh token is revoked server-side,
     * not just cleared locally. signOut() is fire-and-forget (swallows errors).
     */
    suspend fun logout() {
        // Best-effort server-side session invalidation
        // signOut() is designed to swallow exceptions (see PortalApiClient line 267-280)
        apiClient.signOut()

        syncMutex.withLock {
            withProfileMutationBarrier {
                tokenStorage.updatePremiumStatus(false)
                tokenStorage.updateSubscriptionTier(null)
                tokenStorage.clearAuth()
                tokenStorage.emitLogoutEvent()
                _syncState.value = SyncState.NotAuthenticated
            }
        }
    }

    /**
     * Resets [_syncState] to [SyncState.Idle] without performing a sync.
     *
     * Use this after an out-of-band sign-in (OAuth, deep-link, etc.) that
     * bypasses [login] but still needs to clear a stale
     * [SyncState.NotAuthenticated] left over from a prior [logout]. Otherwise
     * the UI continues to show "Authentication failed — please sign out and
     * sign back in" even though the new session is valid.
     */
    suspend fun resetSyncStateToIdle() {
        syncMutex.withLock {
            _syncState.value = SyncState.Idle
        }
    }

    /**
     * Refreshes [PortalUser.isPremium] from the server subscription endpoint.
     * Prefer this on app foreground; do not infer entitlement from sync HTTP status alone.
     */
    suspend fun refreshPremiumStatusFromServer() {
        syncMutex.withLock {
            withProfileMutationBarrier {
                val expectedUserId = tokenStorage.currentUser.value?.id ?: return@withProfileMutationBarrier
                val expectedGeneration = tokenStorage.authGeneration()
                val existingPremium = tokenStorage.currentUser.value?.isPremium ?: false
                val existingTier = tokenStorage.getSubscriptionTier()

                // Serialize the account-scoped response with every identity transition.
                // The generation fence also fails closed if a future identity writer does
                // not share syncMutex but does advance token state.
                val premiumResult = apiClient.checkPremiumStatus()
                val tierResult = apiClient.getActiveSubscriptionTier()
                val identityUnchanged = tokenStorage.authGeneration() == expectedGeneration &&
                    tokenStorage.currentUser.value?.id == expectedUserId
                if (!identityUnchanged) {
                    Logger.w("SyncManager") { "Discarded stale entitlement response after identity changed" }
                    return@withProfileMutationBarrier
                }

                // A successful `null` from the server means the user has no active
                // subscription (a real downgrade) and MUST clear the cached tier.
                // Only a failed call (network, 5xx) preserves the existing value.
                val isPremium = if (premiumResult.isSuccess) {
                    premiumResult.getOrNull() ?: false
                } else {
                    existingPremium
                }
                val resolvedTier = if (tierResult.isSuccess) tierResult.getOrNull() else existingTier
                tokenStorage.updatePremiumStatus(isPremium)
                tokenStorage.updateSubscriptionTier(resolvedTier)

                Logger.d("SyncManager") {
                    "refreshPremiumStatusFromServer: premium=$isPremium, tier=${resolvedTier ?: "none"} " +
                        "(network ok: premium=${premiumResult.isSuccess}, tier=${tierResult.isSuccess})"
                }
            }
        }
    }

    // === Sync Operations ===

    /**
     * Forces a complete re-sync by resetting the lastSync timestamp to 0.
     *
     * Use this when:
     * - Previous syncs failed but advanced the timestamp (data was missed)
     * - User wants to re-pull all data from the server
     * - Debugging sync issues where delta sync returns empty results
     *
     * This will cause the next sync to pull ALL data from the server, not just
     * changes since the last sync: the pull sends lastSync=0, so the server returns
     * the whole profile. It still sends the device's known entity ids so the server
     * also returns tombstones (e.g. deleted personal records) for rows this device
     * holds. A [retryPull] after a failed forced resync behaves the same, because the
     * stored lastSync stays 0 until a pull completes. Note: push will still only send
     * unsynced local data.
     *
     * @return Result from the subsequent sync operation
     */
    suspend fun forceFullResync(): Result<Long> = syncMutex.withLock {
        withProfileMutationBarrier {
            // Keep the reset and the following sync in one critical section. Otherwise an
            // already-running sync can complete after this reset, restore a non-zero checkpoint,
            // and make the queued "full" pull a delta pull.
            Logger.i("SyncManager") { "Forcing full resync - resetting lastSyncTimestamp to 0" }
            tokenStorage.setLastSyncTimestamp(0L)
            syncLocked()
        }
    }

    /**
     * Performs a full sync operation (push + pull).
     *
     * @return Result.success with sync timestamp if push succeeded.
     *         Note: Even on PartialSuccess (push OK, pull failed), this returns Result.success
     *         because the push timestamp is valid for retry purposes. Callers should check
     *         [syncState] for the actual sync status (Success vs PartialSuccess vs Error).
     *
     * @see SyncState.PartialSuccess for incomplete sync handling
     */
    suspend fun sync(): Result<Long> = syncMutex.withLock {
        withProfileMutationBarrier { syncLocked() }
    }

    private suspend fun syncLocked(): Result<Long> {
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing

        // Push local changes (no status check -- Railway backend abandoned)
        Logger.d("SyncManager") { "Sync starting: hasToken=${tokenStorage.hasToken()}" }
        val pushResult = pushLocalChanges()
        if (pushResult.isFailure) {
            val error = pushResult.exceptionOrNull()
            Logger.e("SyncManager") {
                "Push FAILED: status=${(error as? PortalApiException)?.statusCode}, msg=${error?.message}"
            }
            if (error is PortalApiException && error.statusCode == 401) {
                _syncState.value = SyncState.NotAuthenticated
            } else if (error is PortalApiException &&
                (error.statusCode == 402 || error.statusCode == 403)
            ) {
                _syncState.value = SyncState.NotPremium
            } else {
                _syncState.value = SyncState.Error(error?.message ?: "Push failed")
            }
            return Result.failure(error ?: Exception("Push failed"))
        }
        Logger.i("SyncManager") { "Push succeeded" }

        // Inspect per-entity LWW rejections (Phase 3.2 contract: the server
        // rejects an incoming row when it already holds a newer updated_at,
        // and mobile is expected to log the conflict and let the next pull
        // repair convergence). Without this, rejections were decoded but never
        // surfaced (audit F025).
        val pushResponse = pushResult.getOrThrow()
        val rejections = pushResponse.rejections
        val rejectedRoutineIds = rejections.routines.mapTo(mutableSetOf()) { it.id }
        if (rejectedRoutineIds.isNotEmpty()) {
            tokenStorage.currentUser.value?.id?.let { userId ->
                val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"
                val rejectionScopeKey = "$userId:$activeProfileId"
                pendingServerWinsRoutineIdsByScope
                    .getOrPut(rejectionScopeKey) { mutableSetOf() }
                    .addAll(rejectedRoutineIds)
            }
        }
        val totalRejections = rejections.sessions.size + rejections.routines.size +
            rejections.cycles.size + rejections.externalActivities.size +
            rejections.rpgAttributes.size + rejections.gamificationStats.size
        if (totalRejections > 0) {
            Logger.w("SyncManager") {
                "Push LWW rejections ($totalRejections): " +
                    "sessions=${rejections.sessions.size}, routines=${rejections.routines.size}, " +
                    "cycles=${rejections.cycles.size}, externalActivities=${rejections.externalActivities.size}, " +
                    "rpgAttributes=${rejections.rpgAttributes.size}, gamificationStats=${rejections.gamificationStats.size}. " +
                    "Next pull will repair convergence."
            }
        }

        // Parse syncTime from ISO 8601 to epoch millis
        val syncTimeEpoch = try {
            kotlin.time.Instant.parse(pushResponse.syncTime).toEpochMilliseconds()
        } catch (e: Exception) {
            Logger.w(e) {
                "Failed to parse syncTime '${pushResponse.syncTime}', using current time"
            }
            currentTimeMillis()
        }

        // Pull remote changes using parity-based sync (entity IDs plus the stored lastSync).
        // Entity IDs are collected inside pullRemoteChangesWithResult to ensure we send
        // the current state of local storage after the push has completed.
        val pullResult = pullRemoteChangesWithResult()

        return if (pullResult.isSuccess) {
            // Full success: both push and pull succeeded
            val completedPull = pullResult.getOrThrow()
            val finalSyncTime = completedPull.syncTime
            recordCompletedPull(completedPull)
            _syncState.value = SyncState.Success(finalSyncTime)
            Result.success(finalSyncTime)
        } else {
            // Partial success: push succeeded but pull failed
            // CRITICAL: Do NOT advance lastSyncTimestamp on pull failure.
            // This ensures:
            // 1. The same sessions won't be pushed again (they're already stamped)
            // 2. The next pull will still retrieve remote changes from the correct checkpoint
            // 3. The user is notified that sync is incomplete
            val pullError = pullResult.exceptionOrNull()
            val pullErrorMsg = pullError?.message ?: "Pull failed"
            Logger.w("SyncManager") {
                "Partial sync: push succeeded but pull failed. Not advancing lastSyncTimestamp. Error: $pullErrorMsg"
            }

            // Use push syncTime for state reporting but don't persist it
            _syncState.value = SyncState.PartialSuccess(
                pushSucceeded = true,
                pullSucceeded = false,
                lastSyncTime = syncTimeEpoch,
                pullError = pullErrorMsg,
            )
            // Return success with push timestamp (data was pushed successfully)
            // But state is PartialSuccess to indicate pull needs retry
            Result.success(syncTimeEpoch)
        }
    }

    /**
     * Retry just the pull operation after a partial sync.
     * Use when push succeeded but pull failed.
     */
    suspend fun retryPull(): Result<Long> = syncMutex.withLock {
        withProfileMutationBarrier { retryPullLocked() }
    }

    private suspend fun retryPullLocked(): Result<Long> {
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing
        val lastSync = tokenStorage.getLastSyncTimestamp()

        val pullResult = pullRemoteChangesWithResult()

        return if (pullResult.isSuccess) {
            val completedPull = pullResult.getOrThrow()
            val finalSyncTime = completedPull.syncTime
            recordCompletedPull(completedPull)
            _syncState.value = SyncState.Success(finalSyncTime)
            Logger.i("SyncManager") { "Pull retry succeeded, updated timestamp to $finalSyncTime" }
            Result.success(finalSyncTime)
        } else {
            val pullError = pullResult.exceptionOrNull()
            val pullErrorMsg = pullError?.message ?: "Pull retry failed"
            Logger.w("SyncManager") { "Pull retry failed: $pullErrorMsg" }

            _syncState.value = SyncState.PartialSuccess(
                pushSucceeded = true,
                pullSucceeded = false,
                lastSyncTime = lastSync,
                pullError = pullErrorMsg,
            )
            Result.failure(pullError ?: PortalApiException("Pull retry failed"))
        }
    }

    // === Private Helpers ===

    /** Result of a pull whose every page merged (loop ended with `hasMore=false`). */
    private data class CompletedPull(
        /** Earliest server `syncTime` across the pull's pages; the next pull's lastSync. */
        val syncTime: Long,
        /** Delta-pull marker to store with [syncTime], or null to force a full pull next time. */
        val deltaPullKey: String?,
        /** Account/profile scope whose pending LWW rejections this pull consumed. */
        val serverWinsRoutineScopeKey: String?,
    )

    /**
     * Persists lastSync and the delta-pull marker together (lastSync first), and clears
     * only the account/profile-scoped LWW server-wins routine set this pull applied.
     */
    private fun recordCompletedPull(completedPull: CompletedPull) {
        tokenStorage.recordCompletedPull(completedPull.syncTime, completedPull.deltaPullKey)
        completedPull.serverWinsRoutineScopeKey?.let { scopeKey ->
            pendingServerWinsRoutineIdsByScope.remove(scopeKey)
        }
    }

    private suspend fun <T> withProfileMutationBarrier(block: suspend () -> T): T =
        profileMutationBarrier?.withExclusive(block) ?: block()

    private suspend fun pushLocalChanges(): Result<PortalSyncPushResponse> {
        val userId = tokenStorage.currentUser.value?.id
            ?: return Result.failure(PortalApiException("Not authenticated", null, 401))

        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val platform = getPlatformName()
        userProfileRepository.ensureDefaultProfile()
        val allProfiles = userProfileRepository.allProfiles.value
        val activeProfile = userProfileRepository.activeProfile.value
            ?: allProfiles.firstOrNull { it.isActive }
            ?: allProfiles.firstOrNull { it.id == "default" }
        val activeProfileId = activeProfile?.id ?: "default"

        val phaseBackfillCheckpoint = tokenStorage.getPhasePRBackfillCheckpoint(activeProfileId)
        val phaseBackfillResult = syncRepository.backfillPhaseSpecificPRs(
            profileId = activeProfileId,
            fromSessionTimestamp = phaseBackfillCheckpoint,
        )
        phaseBackfillResult.maxScannedSessionTimestamp?.let { maxScannedTimestamp ->
            tokenStorage.setPhasePRBackfillCheckpoint(activeProfileId, maxScannedTimestamp)
        }
        if (phaseBackfillResult.changedRows > 0) {
            Logger.i("SyncManager") {
                "Backfilled ${phaseBackfillResult.changedRows} phase-specific PR row(s) before portal push"
            }
        }

        // 1. Freeze the dirty workout generation snapshot, expanding each dirty
        // portal parent to all live component rows before any payload is built.
        val workoutSnapshot = syncRepository.getDirtyWorkoutSnapshot(activeProfileId)
        val sessions = dedupeWorkoutSessionsById(
            workoutSnapshot.sessions,
            context = "Push payload",
        )
        val pendingWorkoutDeletions = workoutDeletionRepository
            ?.pendingForOwner(userId)
            .orEmpty()
        val pendingOwnershipTransfers = ownershipTransferRepository
            ?.pendingForOwner(userId)
            .orEmpty()
        val queueObservedAt = currentTimeMillis()
        val oldestWorkoutDeletionAgeMs = pendingWorkoutDeletions.minOfOrNull { it.deletedAt }
            ?.let { (queueObservedAt - it).coerceAtLeast(0L) }
        val oldestOwnershipTransferAgeMs = pendingOwnershipTransfers.minOfOrNull { it.createdAt }
            ?.let { (queueObservedAt - it).coerceAtLeast(0L) }
        Logger.i("SyncManager") {
            "Durable sync queues: workoutDeletionCount=${pendingWorkoutDeletions.size}, " +
                "workoutDeletionOldestAgeMs=${oldestWorkoutDeletionAgeMs ?: 0L}, " +
                "ownershipTransferCount=${pendingOwnershipTransfers.size}, " +
                "ownershipTransferOldestAgeMs=${oldestOwnershipTransferAgeMs ?: 0L}"
        }
        val pendingCycleDeletions = trainingCycleRepository
            ?.getPendingCycleDeletions(userId, activeProfileId)
            .orEmpty()
            .filter { CANONICAL_UUID_REGEX.matches(it.id) }

        // Dedicated PR rows need the full snapshot so stable UUID, updatedAt, and
        // deletedAt all reach the portal. Reuse that same projection for legacy
        // set-level PR hints rather than reading a lossy second delta.
        val recentPRs = syncRepository.getFullPRsModifiedSince(lastSync, activeProfileId)
        val prBySessionKey = recentPRs.groupBy { pr ->
            personalRecordSessionKey(pr.exerciseId, pr.timestamp)
        }
        val sessionIdByDeltaPrKey = sessions.mapNotNull { session ->
            val exerciseId = session.exerciseId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            personalRecordSessionKey(exerciseId, session.timestamp) to session.id
        }.toMap()
        val missingSessionRecords = recentPRs.filter { pr ->
            personalRecordSessionKey(pr.exerciseId, pr.timestamp) !in sessionIdByDeltaPrKey
        }
        val historicalSessionIdByPrKey = if (missingSessionRecords.isEmpty()) {
            emptyMap()
        } else {
            syncRepository.findSessionIdsForPersonalRecords(missingSessionRecords, activeProfileId)
        }
        val sessionIdByPrKey = sessionIdByDeltaPrKey + historicalSessionIdByPrKey

        // 3. Build SessionWithReps (fetch rep metrics per session, detect PRs, attach PR metadata)
        val completedSetsBySessionId = workoutSnapshot.completedSetsByComponentId
        val sessionsWithReps = sessions.map { session ->
            val repMetrics = workoutSnapshot.repMetricsByComponentId[session.id].orEmpty()
            val sessionKey = session.exerciseId
                ?.takeIf { it.isNotBlank() }
                ?.let { exerciseId -> personalRecordSessionKey(exerciseId, session.timestamp) }
            val prRecords = sessionKey?.let { prBySessionKey[it] } ?: emptyList()

            // Resolve the real muscle group from the exercise catalog instead of
            // hardcoding "General". Sessions don't carry a muscle group, so look it
            // up by exerciseId/name; fall back to "General" only for ad-hoc /
            // unknown movements that aren't in the catalog.
            val muscleGroup =
                syncRepository.getExerciseMuscleGroup(session.exerciseId, session.exerciseName)
                    ?: "General"

            PortalSyncAdapter.SessionWithReps(
                session = session,
                repMetrics = repMetrics,
                muscleGroup = muscleGroup,
                isPr = prRecords.isNotEmpty(),
                prRecords = prRecords,
                logicalSetIdentity = logicalSetIdentityFor(
                    session,
                    completedSetsBySessionId[session.id].orEmpty(),
                ),
            )
        }
        val personalRecordDtos = recentPRs.map { pr ->
            val sessionKey = personalRecordSessionKey(pr.exerciseId, pr.timestamp)
            val muscleGroup =
                syncRepository.getExerciseMuscleGroup(pr.exerciseId, pr.exerciseName)
                    ?: "General"
            PortalSyncAdapter.toPortalPersonalRecord(
                record = pr,
                sessionId = sessionIdByPrKey[sessionKey],
                muscleGroup = muscleGroup,
            )
        }

        // 4. Gather routines as full domain objects, but only ship canonical UUID IDs.
        // Local template-derived cycle routines use "cycle_routine_<uuid>" and must never
        // reach the server's UUID ownership checks.
        val rawRoutines = syncRepository.getFullRoutinesModifiedSince(lastSync, activeProfileId)
        val routines = rawRoutines.filter { routine -> CANONICAL_UUID_REGEX.matches(routine.id) }
        val droppedRoutineCount = rawRoutines.size - routines.size
        if (droppedRoutineCount > 0) {
            Logger.w("SyncManager") {
                "Push payload: dropped $droppedRoutineCount non-UUID routines before send"
            }
        }

        // 4a. Gather soft-deleted routine IDs for server-side deletion propagation.
        val deletedRoutineIds = syncRepository.getDeletedRoutineIdsSince(lastSync, activeProfileId)
            .filter { CANONICAL_UUID_REGEX.matches(it) }
        if (deletedRoutineIds.isNotEmpty()) {
            Logger.d("SyncManager") {
                "Push payload: ${deletedRoutineIds.size} deleted routine(s) to propagate"
            }
        }

        if (pendingCycleDeletions.isNotEmpty()) {
            Logger.d("SyncManager") {
                "Push payload: ${pendingCycleDeletions.size} account-bound cycle deletion(s) to propagate"
            }
        }

        // 4b. Freeze dirty complete-cycle generations before payload construction.
        // Cycle days may still point at local-only template routines that are hidden from
        // the main routines list via the "cycle_routine_<uuid>" prefix. Null those
        // references for server push so one bad local ID cannot fail the entire sync.
        val cycleSnapshot = syncRepository.getDirtyCycleSnapshot(activeProfileId)
        val rawCyclesWithContext = cycleSnapshot.cycles
        var droppedCycleRoutineRefs = 0
        val cyclesWithContext = rawCyclesWithContext.map { ctx ->
            val sanitizedDays = ctx.cycle.days.map { day ->
                val routineId = day.routineId
                if (routineId != null && !CANONICAL_UUID_REGEX.matches(routineId)) {
                    droppedCycleRoutineRefs++
                    day.copy(routineId = null)
                } else {
                    day
                }
            }
            if (sanitizedDays == ctx.cycle.days) {
                ctx
            } else {
                ctx.copy(cycle = ctx.cycle.copy(days = sanitizedDays))
            }
        }
        if (droppedCycleRoutineRefs > 0) {
            Logger.w("SyncManager") {
                "Push payload: dropped $droppedCycleRoutineRefs non-UUID cycle-day routine references before send"
            }
        }

        // 5. Gather gamification data (profile-scoped)
        val rpgInput = gamificationRepository.getRpgInput(activeProfileId)
        val rpgProfile = RpgAttributeEngine.computeProfile(rpgInput)
        val rpgDto = PortalRpgAttributesSyncDto(
            userId = userId,
            strength = rpgProfile.strength,
            power = rpgProfile.power,
            stamina = rpgProfile.stamina,
            consistency = rpgProfile.consistency,
            mastery = rpgProfile.mastery,
            characterClass = rpgProfile.characterClass.name,
            level = 1,
            experiencePoints = 0,
        )

        val earnedBadges = gamificationRepository.getEarnedBadges(activeProfileId).first()
        val badgeDtos = earnedBadges.map { earned ->
            val badgeDef = BadgeDefinitions.getBadgeById(earned.badgeId)
            PortalEarnedBadgeSyncDto(
                userId = userId,
                badgeId = earned.badgeId,
                badgeName = badgeDef?.name ?: earned.badgeId,
                badgeDescription = badgeDef?.description,
                badgeTier = badgeDef?.tier?.name?.lowercase() ?: "bronze",
                earnedAt = kotlin.time.Instant.fromEpochMilliseconds(earned.earnedAt).toString(),
            )
        }

        val legacyStats = syncRepository.getGamificationStatsForSync(activeProfileId)
        val gamStatsDto = legacyStats?.let { stats ->
            PortalGamificationStatsSyncDto(
                userId = userId,
                totalWorkouts = stats.totalWorkouts,
                totalReps = stats.totalReps,
                totalVolumeKg = stats.totalVolumeKg,
                longestStreak = stats.longestStreak,
                currentStreak = stats.currentStreak,
                totalTimeSeconds = 0,
            )
        }

        // 5b. External activities (paid users only)
        val localPaid = activeProfile?.subscriptionStatus == SubscriptionStatus.ACTIVE
        val portalPaid = tokenStorage.currentUser.value?.isPremium == true
        val isPremium = localPaid || portalPaid
        val externalActivityDtos = if (isPremium) {
            // F018 (deferred): getUnsyncedActivities intentionally excludes deletion
            // tombstones (deletedAt set). Neither ExternalActivitySyncDto nor the
            // portal mobile-sync-push handler carries a deletion field today, so
            // including tombstones here would make the server re-create the deleted
            // activity. Syncing external-activity deletions requires a coordinated
            // wire + Edge Function change before the query can return tombstones.
            val unsyncedActivities = externalActivityRepository.getUnsyncedActivities(
                activeProfileId,
            )
            unsyncedActivities.map { activity ->
                ExternalActivitySyncDto(
                    id = activity.id,
                    externalId = activity.externalId,
                    provider = activity.provider.key,
                    name = activity.name,
                    activityType = activity.activityType,
                    startedAt = kotlin.time.Instant.fromEpochMilliseconds(
                        activity.startedAt,
                    ).toString(),
                    durationSeconds = activity.durationSeconds,
                    distanceMeters = activity.distanceMeters,
                    calories = activity.calories,
                    avgHeartRate = activity.avgHeartRate,
                    maxHeartRate = activity.maxHeartRate,
                    elevationGainMeters = activity.elevationGainMeters,
                    rawData = activity.rawData,
                    syncedAt = kotlin.time.Instant.fromEpochMilliseconds(
                        activity.syncedAt,
                    ).toString(),
                )
            }
        } else {
            emptyList()
        }

        // 6. Phase 3 extended metrics (GAPs 7-9)
        val phaseStatsBySessionId = workoutSnapshot.phaseStatisticsByComponentId.values.flatten()
            .map { PortalSyncAdapter.toPortalPhaseStatistics(it) }
            .groupBy { it.sessionId }
        val assessmentDtos = syncRepository.getAllAssessments(activeProfileId)
            .map { PortalSyncAdapter.toPortalAssessmentResult(it) }
        // Send all custom catalog rows, not just rows modified since lastSync.
        // Older portal-sync builds never sent this field, so existing custom
        // exercise IDs may be missing remotely even after a successful sync.
        val customExerciseDtos = syncRepository.getCustomExercisesModifiedSince(0L)

        // 7. Build the velocity-based 1RM map (catalog exerciseId → latest passing
        // per-cable estimate). Looked up here (suspend context with repo + profile)
        // so the pure adapter just attaches the value. Distinct from the rep-based
        // estimate the adapter computes inline.
        //
        // Single query for the whole profile (getAllPassing) instead of one
        // getLatestPassing per distinct exercise — avoids an N+1 during full sync.
        // getAllPassing returns the full passing history; pick the latest per
        // exercise with the SAME ordering as getLatestPassing (computedAt DESC,
        // then id DESC as the tie-break) so the two paths agree exactly. The
        // adapter only reads keys for exercises actually in this push, so a
        // superset map is harmless.
        val velocityEstimatesByExerciseId = velocityOneRepMaxRepository
            .getAllPassing(activeProfileId)
            .groupBy { it.exerciseId }
            .mapNotNull { (exId, estimates) ->
                estimates
                    .maxWithOrNull(compareBy({ it.computedAt }, { it.id }))
                    ?.let { exId to it.estimatedPerCableKg }
            }
            .toMap()

        // 7b. Build portal session + telemetry DTOs (telemetry setIds match generated exercise set IDs)
        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(
            sessionsWithReps,
            userId,
            velocityEstimatesByExerciseId,
        )
        val sessionNotesByPortalId = workoutSnapshot.sessionNotesByPortalId
        val portalSessions = buildResult.sessions.map { session ->
            val notes = sessionNotesByPortalId[session.id] ?: return@map session
            val sessionUpdatedAt = session.updatedAt
                ?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                ?: 0L
            session.copy(
                notes = notes.notes,
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(
                    maxOf(sessionUpdatedAt, notes.updatedAtMillis),
                ).toString(),
            )
        }

        // Gate telemetry push behind the Inferno tier. Force-curve / 50 Hz session
        // replay is an Inferno-only feature per the subscription matrix. Other
        // tiers (Ember, Flame) and users whose tier is unresolved (offline login,
        // network error during subscription check) fail closed — no telemetry on
        // the wire. Rep summaries still ship in `sessions` regardless of tier so
        // Ember/Flame users get full history, PRs, and analytics without the raw
        // per-sample payload that blows past the server cap. When Inferno
        // launches, this gate automatically opens for those subscribers with no
        // further code changes.
        val tier = tokenStorage.getSubscriptionTier()
        val telemetryAllowed = tier == TELEMETRY_SYNC_TIER
        val effectiveTelemetry = if (telemetryAllowed) buildResult.telemetry else emptyList()
        if (!telemetryAllowed && buildResult.telemetry.isNotEmpty()) {
            Logger.i("SyncManager") {
                "Telemetry push gated off: tier=${tier ?: "unknown"} " +
                    "($TELEMETRY_SYNC_TIER required). Skipping ${buildResult.telemetry.size} points; " +
                    "rep summaries still sync."
            }
        }

        // Build a telemetry index keyed by set ID for batch slicing.
        // Each session's exercises contain sets whose IDs are referenced by telemetry rows.
        val sessionSetIds = portalSessions.associate { session ->
            val setIds = session.exercises.flatMap { ex -> ex.sets.map { s -> s.id } }.toSet()
            session.id to setIds
        }
        val telemetryBySetId = effectiveTelemetry.groupBy { it.setId }

        // 7b. Profile data for portal tagging and profile-scoped filtering
        val routineDtos = routines.map { PortalSyncAdapter.toPortalRoutine(it, userId) }
        val cycleDtos = cyclesWithContext.map {
            PortalSyncAdapter.toPortalTrainingCycle(it, userId)
        }
        val payloadProfileId = activeProfile?.id ?: "default"
        val payloadProfileName = activeProfile?.name ?: "Default"
        val profileDtos = allProfiles
            .map { LocalProfileDto(it.id, it.name, it.colorIndex) }
            .let { dtos ->
                if (activeProfile != null && dtos.none { it.id == activeProfile.id }) {
                    dtos + LocalProfileDto(activeProfile.id, activeProfile.name, activeProfile.colorIndex)
                } else {
                    dtos
                }
            }
            .ifEmpty {
                listOf(
                    LocalProfileDto(
                        id = payloadProfileId,
                        name = payloadProfileName,
                        colorIndex = activeProfile?.colorIndex ?: 0,
                    ),
                )
            }

        var lastResponse: PortalSyncPushResponse? = null

        pendingOwnershipTransfers.chunked(SYNC_BATCH_SIZE).forEach { transferBatch ->
            val payload = PortalSyncPayload(
                deviceId = deviceId,
                platform = platform,
                lastSync = lastSync,
                profileId = payloadProfileId,
                profileName = payloadProfileName,
                allProfiles = profileDtos,
                ownershipTransfers = transferBatch.map { transfer ->
                    PortalOwnershipTransferDto(
                        mutationId = transfer.mutationId,
                        sourceProfileId = transfer.sourceProfileId,
                        targetProfileId = transfer.targetProfileId,
                        workoutSessionIds = transfer.workoutSessionIds,
                        routineIds = transfer.routineIds,
                        cycleIds = transfer.cycleIds,
                        personalRecordIds = transfer.personalRecordIds,
                    )
                },
            )
            rejectDuplicatePushPayloadKeys(payload)?.let { return it }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return result
            val response = result.getOrThrow()
            val sentTransferIds = transferBatch.mapTo(linkedSetOf()) { it.mutationId }
            val acknowledgedTransferIds = response.acknowledgedOwnershipTransferIds
                .filterTo(linkedSetOf()) { it in sentTransferIds }
            ownershipTransferRepository?.acknowledge(
                ownerUserId = userId,
                mutationIds = acknowledgedTransferIds,
                acknowledgedAt = currentTimeMillis(),
            )
            val missingTransferAcks = missingAcknowledgedMutationIds(
                sent = sentTransferIds,
                acknowledged = acknowledgedTransferIds,
            )
            Logger.i("SyncManager") {
                "Ownership transfer response missingExactAckCount=${missingTransferAcks.size}"
            }
            if (missingTransferAcks.isNotEmpty()) {
                return Result.failure(
                    PortalApiException(
                        "Portal did not acknowledge ownership transfer mutation(s): " +
                            missingTransferAcks.joinToString(),
                        statusCode = 409,
                    ),
                )
            }
            lastResponse = response
        }

        // Durable operations commit before ordinary uploads. Ack only mutation IDs
        // returned by the portal; a successful HTTP response without an exact ack
        // deliberately leaves the local queue pending for retry.
        pendingWorkoutDeletions.groupBy { it.profileId }.forEach { (routingProfileId, routedDeletions) ->
            routedDeletions.chunked(SYNC_BATCH_SIZE).forEach { deletionBatch ->
            val routingProfileName = allProfiles.firstOrNull { it.id == routingProfileId }?.name
                ?: "Recovered profile"
            val payload = PortalSyncPayload(
                deviceId = deviceId,
                platform = platform,
                lastSync = lastSync,
                profileId = routingProfileId,
                profileName = routingProfileName,
                allProfiles = profileDtos,
                workoutDeletions = deletionBatch.map { deletion ->
                    PortalWorkoutDeletionDto(
                        mutationId = deletion.mutationId,
                        scope = deletion.scope,
                        portalSessionId = deletion.portalSessionId,
                        componentSessionId = deletion.componentSessionId,
                        deletedAt = kotlin.time.Instant
                            .fromEpochMilliseconds(deletion.deletedAt)
                            .toString(),
                    )
                },
            )
            rejectDuplicatePushPayloadKeys(payload)?.let { return it }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return result
            val response = result.getOrThrow()
            val sentDeletionIds = deletionBatch.mapTo(linkedSetOf()) { it.mutationId }
            val acknowledgedDeletionIds = response.acknowledgedWorkoutDeletionIds
                .filterTo(linkedSetOf()) { it in sentDeletionIds }
            workoutDeletionRepository?.acknowledge(
                ownerUserId = userId,
                mutationIds = acknowledgedDeletionIds,
                acknowledgedAt = currentTimeMillis(),
            )
            val missingDeletionAckCount = sentDeletionIds.count { it !in acknowledgedDeletionIds }
            Logger.i("SyncManager") {
                "Workout deletion response missingExactAckCount=$missingDeletionAckCount"
            }
            if (missingDeletionAckCount > 0) {
                return Result.failure(
                    PortalApiException(
                        "Portal did not acknowledge $missingDeletionAckCount workout deletion mutation(s)",
                        statusCode = 409,
                    ),
                )
            }
            lastResponse = response
            }
        }

        pendingCycleDeletions.chunked(SYNC_BATCH_SIZE).forEach { deletionBatch ->
            val payload = PortalSyncPayload(
                deviceId = deviceId,
                platform = platform,
                lastSync = lastSync,
                profileId = payloadProfileId,
                profileName = payloadProfileName,
                allProfiles = profileDtos,
                deletedCycles = deletionBatch.map { deletion ->
                    PortalDeletedCycleDto(
                        id = deletion.id,
                        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(deletion.updatedAt).toString(),
                    )
                },
            )
            rejectDuplicatePushPayloadKeys(payload)?.let { return it }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return result
            val response = result.getOrThrow()
            val acknowledged = response.acknowledgedDeletedCycleIds.toSet()
            deletionBatch.filter { it.id in acknowledged }.forEach { deletion ->
                trainingCycleRepository?.acknowledgeCycleDeletions(
                    ownerUserId = userId,
                    sentGenerationsById = mapOf(deletion.id to deletion.generation),
                    acknowledgedIds = setOf(deletion.id),
                    at = deletion.updatedAt,
                )
            }
            lastResponse = response
        }

        // 8. Chunked push -- batch sessions to stay under Edge Function body limit (~1 MB)
        //    AND under the server-side rep_telemetry array cap (MAX_TELEMETRY_PER_BATCH).
        //    Non-session data (routines, cycles, custom exercises, badges, RPG, gamification, assessments)
        //    is included only in the final batch to avoid duplicate upserts.
        //    IMPORTANT: We do NOT update lastSync until ALL batches succeed. This prevents
        //    data consistency gaps where a partial batch sequence leaves the timestamp
        //    advanced but later batches uncommitted (audit 4.1 fix).
        val allSessions = portalSessions
        val telemetryCountBySessionId = allSessions.associate { session ->
            val count = (sessionSetIds[session.id] ?: emptySet()).sumOf { setId ->
                telemetryBySetId[setId]?.size ?: 0
            }
            session.id to count
        }
        val batchPlan = planSessionBatches(allSessions, telemetryCountBySessionId)
        val totalBatches = batchPlan.size.coerceAtLeast(1)

        Logger.d("SyncManager") {
            "Pushing portal payload: ${allSessions.size} sessions ($totalBatches batch(es)), " +
                "${effectiveTelemetry.size} telemetry points, " +
                "${routineDtos.size} routines, ${cycleDtos.size} cycles, " +
                "${customExerciseDtos.size} custom exercises, " +
                "${personalRecordDtos.size} personal records, " +
                "${phaseStatsBySessionId.size} sessions with phase stats, " +
                "${assessmentDtos.size} assessments"
        }

        if (batchPlan.size <= 1) {
            // --- Single-push fast path (most common case) ---
            val payload = PortalSyncPayload(
                deviceId = deviceId,
                platform = platform,
                lastSync = lastSync,
                sessions = allSessions,
                telemetry = effectiveTelemetry,
                routines = routineDtos,
                deletedRoutineIds = deletedRoutineIds,
                cycles = cycleDtos,
                rpgAttributes = rpgDto,
                badges = badgeDtos,
                gamificationStats = gamStatsDto,
                phaseStatistics = phaseStatsBySessionId.values.flatten(),
                assessments = assessmentDtos,
                customExercises = customExerciseDtos,
                profileId = payloadProfileId,
                profileName = payloadProfileName,
                allProfiles = profileDtos,
                externalActivities = externalActivityDtos,
                personalRecords = personalRecordDtos,
            )
            rejectDuplicatePushPayloadKeys(payload)?.let { return it }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return result
            val singleResponse = result.getOrThrow()
            lastResponse = singleResponse
            acknowledgeAcceptedWorkoutParents(
                workoutSnapshot = workoutSnapshot,
                sentPortalSessionIds = allSessions.mapTo(linkedSetOf()) { it.id },
                response = singleResponse,
            )
            acknowledgeAcceptedCycles(
                cycleSnapshot = cycleSnapshot,
                sentCycleIds = cycleDtos.mapTo(linkedSetOf()) { it.id },
                response = singleResponse,
            )
            // Single-batch success - reset retry tracking
            consecutiveFullRetries = 0
            lastFailedBatchHash = null
        } else {
            // --- Batched push for large history syncs ---
            val batches = batchPlan
            batches.forEachIndexed { index, batchSessions ->
                val isLastBatch = index == batches.lastIndex
                Logger.i("SyncManager") {
                    "Sync batch ${index + 1}/$totalBatches: ${batchSessions.size} sessions" +
                        if (isLastBatch) " (+ non-session data)" else ""
                }

                // Slice telemetry to only rows belonging to this batch's sessions
                val batchTelemetry = batchSessions.flatMap { session ->
                    val setIds = sessionSetIds[session.id] ?: emptySet()
                    setIds.flatMap { setId -> telemetryBySetId[setId] ?: emptyList() }
                }

                // Slice phase stats to this batch's sessions
                val batchPhaseStats = batchSessions.flatMap { session ->
                    phaseStatsBySessionId[session.id] ?: emptyList()
                }

                val payload = PortalSyncPayload(
                    deviceId = deviceId,
                    platform = platform,
                    lastSync = lastSync,
                    sessions = batchSessions,
                    telemetry = batchTelemetry,
                    // Non-session data only on last batch to avoid duplicate upserts
                    routines = if (isLastBatch) routineDtos else emptyList(),
                    deletedRoutineIds = if (isLastBatch) deletedRoutineIds else emptyList(),
                    cycles = if (isLastBatch) cycleDtos else emptyList(),
                    rpgAttributes = if (isLastBatch) rpgDto else null,
                    badges = if (isLastBatch) badgeDtos else emptyList(),
                    gamificationStats = if (isLastBatch) gamStatsDto else null,
                    phaseStatistics = batchPhaseStats,
                    assessments = if (isLastBatch) assessmentDtos else emptyList(),
                    customExercises = if (isLastBatch) customExerciseDtos else emptyList(),
                    profileId = payloadProfileId,
                    profileName = payloadProfileName,
                    allProfiles = if (isLastBatch) profileDtos else null,
                    externalActivities = if (isLastBatch) externalActivityDtos else emptyList(),
                    personalRecords = if (isLastBatch) personalRecordDtos else emptyList(),
                )

                rejectDuplicatePushPayloadKeys(payload)?.let { return it }
                val result = pushPayloadWithRateLimit(payload)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    val batchSessionIds = batchSessions.map { it.id }.take(3)
                    val batchSummary = "sessions=${batchSessions.size}, " +
                        "ids=[${batchSessionIds.joinToString()}${if (batchSessions.size > 3) "..." else ""}]"

                    Logger.e("SyncManager") {
                        "Batch ${index + 1}/$totalBatches failed: ${error?.message} | $batchSummary"
                    }

                    // Track retry attempts for this specific batch payload to prevent retry storms.
                    // Use a hash of session IDs to detect if the same batch is failing repeatedly.
                    val batchHash = batchSessions.map { it.id }.hashCode()
                    if (lastFailedBatchHash == batchHash) {
                        consecutiveFullRetries++
                        Logger.w("SyncManager") {
                            "Same batch failed again, retry count: $consecutiveFullRetries/$MAX_FULL_BATCH_RETRIES"
                        }
                        if (consecutiveFullRetries >= MAX_FULL_BATCH_RETRIES) {
                            val exhaustedError = PortalApiException(
                                "Batch ${index + 1}/$totalBatches failed $MAX_FULL_BATCH_RETRIES consecutive times. " +
                                    "Manual retry required after investigating the issue. " +
                                    "Last error: ${error?.message}",
                                null,
                                (error as? PortalApiException)?.statusCode,
                            )
                            return Result.failure(exhaustedError)
                        }
                    } else {
                        // Different batch or first failure - reset counter and record hash
                        consecutiveFullRetries = 1
                        lastFailedBatchHash = batchHash
                    }

                    // CRITICAL: Do NOT update lastSync timestamp on failure.
                    // All batches must succeed before we advance the timestamp.
                    // On next retry, the full batch sequence will be re-sent.
                    return result
                }

                val batchResponse = result.getOrThrow()
                lastResponse = batchResponse
                acknowledgeAcceptedWorkoutParents(
                    workoutSnapshot = workoutSnapshot,
                    sentPortalSessionIds = batchSessions.mapTo(linkedSetOf()) { it.id },
                    response = batchResponse,
                )
                if (isLastBatch) {
                    acknowledgeAcceptedCycles(
                        cycleSnapshot = cycleSnapshot,
                        sentCycleIds = cycleDtos.mapTo(linkedSetOf()) { it.id },
                        response = batchResponse,
                    )
                }

                // Log batch success but do NOT update timestamp yet.
                // Timestamp is deferred until ALL batches complete successfully.
                Logger.d("SyncManager") {
                    "Batch ${index + 1}/$totalBatches pushed successfully (timestamp deferred)"
                }
            }

            // All batches succeeded - reset retry tracking
            consecutiveFullRetries = 0
            lastFailedBatchHash = null
        }

        val sentMetadataProfileIds = profileDtos.mapTo(linkedSetOf()) { it.id }
        pushDirtyProfilePreferences(
            deviceId = deviceId,
            platform = platform,
            lastSync = lastSync,
            sentMetadataProfileIds = sentMetadataProfileIds,
        )

        // Mark external activities as synced based on server acknowledgement.
        // Only mark activities the server confirmed it persisted — prevents silently
        // dropping activities that the server soft-failed on.
        val finalResponse = lastResponse
        if (externalActivityDtos.isNotEmpty() && finalResponse != null) {
            val acknowledgedSyncKeys = finalResponse.externalActivityKeys.mapNotNull { ack ->
                IntegrationProvider.fromKey(ack.provider)?.let { provider ->
                    ExternalActivitySyncKey(externalId = ack.externalId, provider = provider)
                }
            }
            if (acknowledgedSyncKeys.isNotEmpty()) {
                // Server confirmed exact provider-scoped keys — mark only those.
                externalActivityRepository.markSyncedBySyncKeys(
                    syncKeys = acknowledgedSyncKeys,
                    profileId = activeProfileId,
                )
                Logger.d("SyncManager") {
                    "Marked ${acknowledgedSyncKeys.size} external activities as synced (by server-confirmed provider/externalId keys)"
                }
            } else if (finalResponse.externalActivityIds.isNotEmpty()) {
                Logger.w("SyncManager") {
                    "Server returned legacy externalActivityIds without provider scoping; skipping optimistic sync stamping"
                }
            } else if (finalResponse.externalActivitiesUpserted > 0) {
                // Backward compat: server confirmed a count but no IDs list
                val syncedIds = externalActivityDtos.map { it.id }
                externalActivityRepository.markSynced(syncedIds)
                Logger.d("SyncManager") {
                    "Marked ${syncedIds.size} external activities as synced (backward compat, server confirmed ${finalResponse.externalActivitiesUpserted})"
                }
            } else {
                // Server did not confirm any activities were persisted — do NOT mark as synced
                Logger.w("SyncManager") {
                    "Pushed ${externalActivityDtos.size} external activities but server confirmed 0 — will retry on next sync"
                }
            }
        }

        // Stamp pushed PRs (Issue #528) so getFullPRsModifiedSince doesn't keep
        // re-shipping the same rows on every push. Re-use the exact recentPRs
        // collected for this payload, deduped by id, and stamp only after the
        // server confirmed the push. This gives PersonalRecord rows the same
        // post-confirmation resend protection that WorkoutSession rows get from
        // the caller's post-push stamping block.
        val pushedPrIds = recentPRs
            .filter { it.deletedAt == null && it.id >= 0L }
            .map { it.id }
            .distinct()
        if (pushedPrIds.isNotEmpty()) {
            val prStampTime = currentTimeMillis()
            syncRepository.updatePersonalRecordTimestamp(pushedPrIds, prStampTime)
            Logger.d("SyncManager") {
                "Stamped ${pushedPrIds.size} pushed personal records with updatedAt=$prStampTime"
            }
        }

        return Result.success(lastResponse!!)
        // No updateServerIds() -- portal uses client-provided UUIDs
    }

    private suspend fun acknowledgeAcceptedWorkoutParents(
        workoutSnapshot: WorkoutSyncSnapshot,
        sentPortalSessionIds: Set<String>,
        response: PortalSyncPushResponse,
    ) {
        if (sentPortalSessionIds.isEmpty()) return
        val acceptedPortalSessionIds = response.acknowledgedWorkoutSessionIds
            .filterTo(linkedSetOf()) { it in sentPortalSessionIds }
        syncRepository.acknowledgeWorkoutSnapshot(workoutSnapshot, acceptedPortalSessionIds)
    }

    private suspend fun acknowledgeAcceptedCycles(
        cycleSnapshot: com.devil.phoenixproject.data.repository.CycleSyncSnapshot,
        sentCycleIds: Set<String>,
        response: PortalSyncPushResponse,
    ) {
        if (sentCycleIds.isEmpty()) return
        val rejections = response.rejections.cycles.associateBy { it.id }
        val acceptedCycleIds = response.acknowledgedCycleIds
            .filterTo(linkedSetOf()) { it in sentCycleIds }
        syncRepository.acknowledgeCycleSnapshot(cycleSnapshot, acceptedCycleIds)
        val sentComponentsById = cycleSnapshot.components.associateBy { it.context.cycle.id }
        rejections.forEach { (cycleId, rejection) ->
            if (cycleId !in sentCycleIds) return@forEach
            val rejectedComponent = sentComponentsById[cycleId] ?: return@forEach
            val rejectedAt = rejection.serverUpdatedAt
                ?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                ?: currentTimeMillis()
            trainingCycleRepository?.saveRejectedCycleDraft(rejectedComponent, rejectedAt)
        }
    }

    private suspend fun logicalSetCompletedSetsBySessionId(
        sessionIds: List<String>,
    ): Map<String, List<CompletedSet>> {
        val repository = completedSetRepository ?: return emptyMap()
        if (sessionIds.isEmpty()) return emptyMap()
        return sessionIds
            .chunked(COMPLETED_SET_IDENTITY_CHUNK)
            .flatMap { chunk -> repository.getCompletedSetsForSessions(chunk) }
            .groupBy { it.sessionId }
    }

    private fun logicalSetIdentityFor(
        session: WorkoutSession,
        completedSets: List<CompletedSet>,
    ): PortalSyncAdapter.LogicalSetSyncIdentity? {
        val routineSessionId = session.routineSessionId ?: return null
        val completedSet = completedSets.firstOrNull { !it.routineExerciseId.isNullOrBlank() } ?: return null
        val routineExerciseId = completedSet.routineExerciseId ?: return null
        return PortalSyncAdapter.LogicalSetSyncIdentity(
            routineSessionId = routineSessionId,
            routineExerciseId = routineExerciseId,
            setNumber = completedSet.setNumber,
        )
    }

    private suspend fun pushPayloadWithRateLimit(
        payload: PortalSyncPayload,
    ): Result<PortalSyncPushResponse> {
        if (!rateLimiter.tryAcquire("push", SyncConfig.PUSH_RATE_LIMIT_PER_MIN)) {
            return Result.failure(
                PortalApiException(
                    "Client rate limit exceeded for push " +
                        "(${SyncConfig.PUSH_RATE_LIMIT_PER_MIN}/min). Try again shortly.",
                    statusCode = 429,
                ),
            )
        }
        return apiClient.pushPortalPayload(payload)
    }

    private suspend fun pushDirtyProfilePreferences(
        deviceId: String,
        platform: String,
        lastSync: Long,
        sentMetadataProfileIds: Set<String>,
    ) {
        if (!isProfilePreferenceMigrationReady()) return
        val safeFailureLogger: (String) -> Unit = { line ->
            Logger.w("SyncManager") { line }
        }
        val snapshot = isolateProfilePreferenceFailure(
            ProfilePreferenceLocalFailureStage.SNAPSHOT,
            safeFailureLogger,
        ) {
            profilePreferenceSyncRepository.snapshotDirtySections()
        } ?: return
        snapshot.unsyncable.forEach { issue ->
            Logger.w("SyncManager") { profilePreferenceIssueLogLine(issue) }
        }
        val (eligible, deferred) = snapshot.valid.partition {
            it.key.localProfileId in sentMetadataProfileIds
        }
        deferred.forEach { section ->
            Logger.i("SyncManager") { profilePreferenceMetadataDeferredLogLine(section.key) }
        }
        val prepared = isolateProfilePreferenceFailure(
            ProfilePreferenceLocalFailureStage.MUTATION_MAPPING,
            safeFailureLogger,
        ) {
            eligible.map(PortalSyncAdapter::toPortalProfilePreferenceMutation)
        } ?: return
        if (prepared.isEmpty()) return

        val base = PortalSyncPayload(
            deviceId = deviceId,
            platform = platform,
            lastSync = lastSync,
        )
        val plan = isolateProfilePreferenceFailure(
            ProfilePreferenceLocalFailureStage.CHUNK_PLANNING,
            safeFailureLogger,
        ) {
            planProfilePreferencePushChunks(base, prepared)
        } ?: return
        plan.unsyncable.forEach { issue ->
            Logger.w("SyncManager") { profilePreferenceIssueLogLine(issue) }
        }

        for (chunk in plan.chunks) {
            val result = pushPayloadWithRateLimit(chunk.payload)
            if (result.isFailure) {
                Logger.w("SyncManager") {
                    profilePreferenceChunkFailureLogLine(result.exceptionOrNull())
                }
                return
            }
            val response = result.getOrThrow()
            if (response.profilePreferencesAccepted != true) {
                Logger.i("SyncManager") {
                    "Backend did not acknowledge profile preference support"
                }
                return
            }
            val outcomes = isolateProfilePreferenceFailure(
                ProfilePreferenceLocalFailureStage.RESPONSE_MAPPING,
                safeFailureLogger,
            ) {
                buildProfilePreferencePushOutcomes(response, chunk.ledger)
            } ?: return
            if (outcomes.isNotEmpty()) {
                isolateProfilePreferenceFailure(
                    ProfilePreferenceLocalFailureStage.OUTCOME_APPLY,
                    safeFailureLogger,
                ) {
                    val report = profilePreferenceSyncRepository.applyPushOutcomes(outcomes)
                    if (report.applied > 0) {
                        userProfileRepository.refreshProfiles()
                    }
                    report
                } ?: return
            }
        }
    }

    /**
     * Pull portal data and merge into local database with parity-based sync.
     *
     * Instead of using timestamps to determine what's new, we send the server
     * a list of entity IDs we already have. The server returns entities
     * that exist server-side but not in our list.
     *
     * Delta pulls: the request carries the stored server `syncTime` of the last completed
     * pull as `lastSync`, captured once before the page loop so every page of this pull
     * sends the same value. The server then skips known entities unchanged since then
     * (minus a small overlap). The caller persists the new value only after the final
     * page (`hasMore=false`). The new value is the EARLIEST page `syncTime` of this pull:
     * a known row edited while later pages were being fetched is then re-sent by the
     * next pull instead of being skipped for good.
     *
     * `lastSync=0` (full pull) is sent instead when the stored value is not known to
     * belong to this user + profile (delta-pull marker absent or different):
     *  - no pull has completed since upgrading from builds that always sent 0 (one-time
     *    full pull, so server-side fixes made before the upgrade reach this device);
     *  - the stored lastSync was produced by a pull of a different profile or account
     *    (the stored timestamp is global, but known ids and server filtering are per
     *    user and profile);
     *  - the previous pull's external activities were truncated by the server cap.
     * [forceFullResync] resets the stored value to 0 and so also sends 0.
     *
     * @return Result with the completed pull on success, or failure with classified error
     */
    private suspend fun pullRemoteChangesWithResult(): Result<CompletedPull> {
        val deviceId = tokenStorage.getDeviceId()
        val activeProfileId = userProfileRepository.activeProfile.value?.id
        val mergeProfileId = activeProfileId ?: "default"
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val deltaPullKey = tokenStorage.currentUser.value?.id?.let { userId -> "$userId:$mergeProfileId" }
        val storedDeltaPullKey = tokenStorage.getDeltaPullKey()
        val deltaMarkerMatches = deltaPullKey != null && storedDeltaPullKey == deltaPullKey
        val requestLastSync = if (deltaMarkerMatches) lastSync else 0L
        // An explicit key mismatch means [lastSync] belongs to another profile and cannot
        // safely participate in this profile's routine LWW comparison. An absent marker is
        // different: it represents upgrade/truncation recovery, where the stored boundary still
        // protects local edits while the wire request deliberately performs a full pull.
        val mergeLastSync = if (storedDeltaPullKey != null && storedDeltaPullKey != deltaPullKey) {
            0L
        } else {
            lastSync
        }
        val serverWinsRoutineIds = deltaPullKey
            ?.let { pendingServerWinsRoutineIdsByScope[it]?.toSet() }
            .orEmpty()
        Logger.i("SyncManager") {
            "Pull mode: requestLastSync=$requestLastSync (stored=$lastSync, deltaMarkerMatches=$deltaMarkerMatches, " +
                "mergeLastSync=$mergeLastSync, profile=$mergeProfileId, " +
                "serverWinsRoutines=${serverWinsRoutineIds.size})"
        }

        // Collect local entity IDs for parity comparison.
        //
        // fix(audit #7): cap each list at MAX_PARITY_IDS to stay within the
        // server's enforced HTTP 413 threshold. If a user has more than
        // MAX_PARITY_IDS entities, we send the most recent window and rely on
        // the mobile-side dedupe against local DB to handle the tail. This is
        // strictly better than the prior server behavior which silently
        // returned empty for over-cap lists.
        val rawSessionIds = syncRepository.getAllSessionIds(mergeProfileId)
        val rawRoutineIds = syncRepository.getAllRoutineIds(mergeProfileId)
        val rawCycleIds = syncRepository.getAllCycleIds(mergeProfileId)
        val rawBadgeIds = syncRepository.getAllBadgeIds(mergeProfileId)
        val rawPersonalRecordIds = syncRepository.getAllPersonalRecordIds(mergeProfileId)

        // fix(pull 400): TemplateConverter mints cycle-derived routine IDs as
        // "cycle_routine_<uuid>" which aren't valid UUIDs. The server's
        // mobile-sync-pull validator rejects the whole request if any entry
        // in knownEntityIds fails UUID validation. Filter non-canonical UUIDs
        // client-side before sending.
        fun filterUuids(ids: List<String>, label: String): List<String> {
            val filtered = ids.filter { CANONICAL_UUID_REGEX.matches(it) }
            val dropped = ids.size - filtered.size
            if (dropped > 0) {
                Logger.w("SyncManager") {
                    "Parity list '$label': dropped $dropped non-UUID entries before send"
                }
            }
            return filtered
        }

        val filteredRoutineIds = filterUuids(rawRoutineIds, "routineIds")
        val filteredSessionIds = filterUuids(rawSessionIds, "sessionIds")
        val filteredCycleIds = filterUuids(rawCycleIds, "cycleIds")
        val filteredBadgeIds = filterUuids(rawBadgeIds, "badgeIds")

        fun <T> capParity(list: List<T>, label: String): List<T> = if (list.size <= SyncConfig.MAX_PARITY_IDS) {
            list
        } else {
            Logger.w("SyncManager") {
                "Parity list '$label' has ${list.size} entries; truncating to last " +
                    "${SyncConfig.MAX_PARITY_IDS} to stay within server cap. " +
                    "Local dedupe will handle the older tail."
            }
            list.takeLast(SyncConfig.MAX_PARITY_IDS)
        }

        val knownPersonalRecordIds = capParity(
            filterUuids(rawPersonalRecordIds, "personalRecordIds"),
            "personalRecordIds",
        ).toMutableList()

        fun currentKnownEntityIds(): KnownEntityIds = KnownEntityIds(
            sessionIds = capParity(filteredSessionIds, "sessionIds"),
            routineIds = capParity(filteredRoutineIds, "routineIds"),
            cycleIds = capParity(filteredCycleIds, "cycleIds"),
            badgeIds = capParity(filteredBadgeIds, "badgeIds"),
            // Send known PR UUIDs so the portal can page with
            // get_personal_records_excluding_ids and still return tombstones
            // via get_personal_record_tombstones. Empty lists force the server
            // to rely only on the cursor, which loops when many PRs share one
            // microsecond timestamp.
            personalRecordIds = capParity(knownPersonalRecordIds.toList(), "personalRecordIds"),
        )

        val entityIds = currentKnownEntityIds()
        Logger.i("SyncManager") {
            "Parity sync: sending ${entityIds.sessionIds.size} session IDs, " +
                "${entityIds.routineIds.size} routine IDs, ${entityIds.cycleIds.size} cycle IDs, " +
                "${entityIds.personalRecordIds.size} personal record IDs"
        }

        var pagesProcessed = 0
        var totalEntitiesFetched = 0
        var currentCursor: String? = null
        // Earliest server syncTime across pages: the snapshot boundary the whole pull is safe from.
        var pullSyncTime: Long? = null
        var externalActivitiesTruncated = false
        val seenCursors = mutableSetOf<String>() // cursor-repetition detection (issue #679)

        // Pagination loop: fetch pages until hasMore is false
        while (true) {
            // Early exit on coroutine cancellation
            currentCoroutineContext().ensureActive()

            // Emergency circuit breaker — should never fire during normal operation.
            // If it does, the server's pagination protocol is broken (cursor never exhausted).
            if (pagesProcessed >= SyncConfig.MAX_PAGES) {
                val error = PortalApiException(
                    "Pull exceeded emergency page limit (${SyncConfig.MAX_PAGES}). " +
                        "Processed $totalEntitiesFetched entities across $pagesProcessed pages. " +
                        "Server pagination may be broken - please contact support.",
                )
                Logger.e("SyncManager") { error.message!! }
                return Result.failure(error)
            }

            // Cursor-validity guard: reject blank or repeated cursors (issue #679).
            // A repeated cursor means the server is sending the same page forever;
            // a blank cursor with hasMore=true is a protocol violation.
            if (currentCursor != null) {
                if (currentCursor.isBlank()) {
                    val error = PortalApiException(
                        "Pull received blank continuation cursor after $pagesProcessed pages. " +
                            "Processed $totalEntitiesFetched entities. Server pagination protocol error.",
                    )
                    Logger.e("SyncManager") { error.message!! }
                    return Result.failure(error)
                }
                if (!seenCursors.add(currentCursor)) {
                    val error = PortalApiException(
                        "Pull detected repeated cursor after $pagesProcessed pages " +
                            "(cursor=${currentCursor.take(16)}...). " +
                            "Processed $totalEntitiesFetched entities. Server may be stuck in a loop.",
                    )
                    Logger.e("SyncManager") { error.message!! }
                    return Result.failure(error)
                }
            }

            // Emit progress state for UI feedback
            if (pagesProcessed > 0) {
                _syncState.value = SyncState.SyncingWithProgress(
                    pagesProcessed = pagesProcessed,
                    entitiesFetched = totalEntitiesFetched,
                )
            }

            val knownEntityIds = currentKnownEntityIds()

            // Fetch next page
            // DIAGNOSTIC: Log pull request parameters to trace sync issues
            Logger.d("SyncManager") {
                "PULL REQUEST: deviceId=$deviceId, profileId=$activeProfileId, " +
                    "knownSessions=${knownEntityIds.sessionIds.size}, knownRoutines=${knownEntityIds.routineIds.size}, " +
                    "knownPersonalRecords=${knownEntityIds.personalRecordIds.size}, " +
                    "cursor=$currentCursor"
            }

            val pullResponse = run {
                var retryAttempts = 0
                var firstRateLimitError: Throwable? = null
                var successfulResponse: PortalSyncPullResponse? = null

                while (true) {
                    rateLimiter.acquireWithWait("pull", SyncConfig.PULL_RATE_LIMIT_PER_MIN)

                    val pullResult = apiClient.pullPortalPayload(
                        knownEntityIds = knownEntityIds,
                        deviceId = deviceId,
                        profileId = mergeProfileId,
                        cursor = currentCursor,
                        pageSize = SyncConfig.DEFAULT_PAGE_SIZE,
                        lastSync = requestLastSync,
                    )

                    if (pullResult.isSuccess) {
                        successfulResponse = pullResult.getOrThrow()
                        break
                    }

                    val error = pullResult.exceptionOrNull() ?: PortalApiException("Pull failed")
                    Logger.w("SyncManager") {
                        "Pull page ${pagesProcessed + 1} failed (cursor=$currentCursor): ${error.message}"
                    }

                    val portalError = error as? PortalApiException
                    val isRetryAfterStatus = portalError?.statusCode == 429 || portalError?.statusCode == 503
                    if (!isRetryAfterStatus) {
                        return Result.failure(error)
                    }

                    retryAttempts++
                    if (firstRateLimitError == null) {
                        firstRateLimitError = error
                    }
                    if (retryAttempts > SyncConfig.MAX_RETRY_AFTER_ATTEMPTS_PER_PAGE) {
                        return Result.failure(firstRateLimitError ?: error)
                    }

                    val retryDelayMs = portalError?.retryAfterSeconds?.toLong()?.coerceAtLeast(0L)?.times(1000L)
                        ?: SyncConfig.RATE_LIMIT_WINDOW_MS
                    Logger.w("SyncManager") {
                        "Retrying pull page ${pagesProcessed + 1} after ${retryDelayMs}ms " +
                            "(attempt $retryAttempts/${SyncConfig.MAX_RETRY_AFTER_ATTEMPTS_PER_PAGE}, cursor=$currentCursor)"
                    }
                    delay(retryDelayMs)
                }
                successfulResponse ?: return Result.failure(PortalApiException("Pull failed"))
            }
            pagesProcessed++

            // Count entities in this page
            val pageEntityCount = pullResponse.sessions.size +
                pullResponse.routines.size +
                pullResponse.cycles.size +
                pullResponse.badges.size +
                pullResponse.personalRecords.size +
                (pullResponse.profilePreferenceSections?.size ?: 0) +
                (if (pullResponse.rpgAttributes != null) 1 else 0) +
                (if (pullResponse.gamificationStats != null) 1 else 0) +
                pullResponse.externalActivities.size
            totalEntitiesFetched += pageEntityCount

            // A page with no entities mobile decodes but hasMore=true is legitimate: e.g. a
            // page holding only customExercises (paged last by the server, not decoded
            // here). Keep following its cursor. The missing/blank/repeated-cursor guards
            // and MAX_PAGES bound the loop; each fails the pull, so neither lastSync nor the
            // delta-pull marker advances over pages that were never fetched.
            if (pageEntityCount == 0 && pullResponse.hasMore) {
                Logger.d("SyncManager") {
                    "Pull page $pagesProcessed has no decoded entities but hasMore=true; following cursor"
                }
            }

            Logger.d("SyncManager") {
                "Pull page $pagesProcessed: sessions=${pullResponse.sessions.size}, routines=${pullResponse.routines.size}, " +
                    "cycles=${pullResponse.cycles.size}, badges=${pullResponse.badges.size}, hasMore=${pullResponse.hasMore}"
            }
            if (pullResponse.routines.isNotEmpty()) {
                Logger.d("SyncManager") {
                    "Pull routines: ${pullResponse.routines.size} received"
                }
            }
            if (pullResponse.cycles.isNotEmpty()) {
                Logger.d("SyncManager") {
                    "Pull cycles: ${pullResponse.cycles.size} received"
                }
            }

            // Merge this page in preference-first repository order
            val mergeResult = mergePullPage(
                pullResponse = pullResponse,
                lastSync = mergeLastSync,
                mergeProfileId = mergeProfileId,
                isFirstPage = pagesProcessed == 1,
                serverWinsRoutineIds = serverWinsRoutineIds,
            )
            if (mergeResult.isFailure) {
                // Map Result<Unit> to Result<Long> for consistent return type
                return Result.failure(mergeResult.exceptionOrNull() ?: PortalApiException("Merge failed"))
            }

            for (record in pullResponse.personalRecords) {
                val id = record.id
                if (CANONICAL_UUID_REGEX.matches(id) && id !in knownPersonalRecordIds) {
                    knownPersonalRecordIds += id
                }
            }

            // Update pagination state
            pullSyncTime = pullSyncTime?.let { minOf(it, pullResponse.syncTime) } ?: pullResponse.syncTime
            if (pullResponse.externalActivitiesHasMore) {
                externalActivitiesTruncated = true
            }

            if (!pullResponse.hasMore) {
                // All pages complete
                Logger.i("SyncManager") {
                    "Pull complete: $pagesProcessed page(s), $totalEntitiesFetched total entities"
                }
                break
            }

            // Prepare for next page
            currentCursor = pullResponse.nextCursor
            if (currentCursor == null) {
                // hasMore=true but no cursor is a protocol violation — treat as pull failure
                // so sync() reports PartialSuccess and preserves the prior lastSync timestamp.
                val error = PortalApiException(
                    "Pull page $pagesProcessed has hasMore=true but no nextCursor. " +
                        "Processed $totalEntitiesFetched entities across $pagesProcessed pages. " +
                        "Server pagination protocol error — missing continuation cursor.",
                )
                Logger.e("SyncManager") { error.message!! }
                return Result.failure(error)
            }
        }

        // The portal pull response is a delta: it returns entities that are new
        // to the client or updated since lastSync. Missing known IDs therefore
        // do not prove deletion. Server-side routine/cycle deletes need an
        // explicit tombstone channel before local hard-delete is safe.

        // The server caps external activities at 500 per pull (oldest synced_at first)
        // and has no cursor for the rest. A delta lastSync would skip the truncated tail
        // for good, so drop the delta-pull marker: the next pull is a full lastSync=0
        // pull (the pre-delta behaviour). Delivering the tail needs a server cursor.
        val completedDeltaPullKey = if (externalActivitiesTruncated) {
            Logger.w("SyncManager") {
                "Pull: server truncated external activities (externalActivitiesHasMore=true); " +
                    "next pull will be a full pull. Activities beyond the server cap are not delivered."
            }
            null
        } else {
            deltaPullKey
        }

        return Result.success(
            CompletedPull(
                syncTime = pullSyncTime ?: lastSync,
                deltaPullKey = completedDeltaPullKey,
                serverWinsRoutineScopeKey = deltaPullKey,
            ),
        )
    }

    private suspend fun applyPulledProfilePreferences(
        dtos: List<PortalProfilePreferenceSectionCanonicalDto>?,
        isFirstPage: Boolean,
    ) {
        val sections = dtos.orEmpty()
        if (sections.isEmpty()) return
        if (!isFirstPage) {
            Logger.w("SyncManager") {
                profilePreferencePullCountLogLine(
                    ProfilePreferencePullDiagnosticCategory.LATER_PAGE_IGNORED,
                    sections.size,
                )
            }
            return
        }
        if (!isProfilePreferenceMigrationReady()) return

        val safeFailureLogger: (String) -> Unit = { line ->
            Logger.w("SyncManager") { line }
        }
        val plan = isolateProfilePreferenceFailure(
            ProfilePreferenceLocalFailureStage.PULL_RESPONSE_MAPPING,
            safeFailureLogger,
        ) {
            planProfilePreferencePullSections(sections)
        } ?: return
        if (plan.invalidCanonicalCount > 0) {
            Logger.w("SyncManager") {
                profilePreferencePullCountLogLine(
                    ProfilePreferencePullDiagnosticCategory.INVALID_CANONICAL,
                    plan.invalidCanonicalCount,
                )
            }
        }
        if (plan.duplicateKeyCount > 0) {
            Logger.w("SyncManager") {
                profilePreferencePullCountLogLine(
                    ProfilePreferencePullDiagnosticCategory.DUPLICATE_KEY,
                    plan.duplicateKeyCount,
                )
            }
        }
        if (plan.valid.isEmpty()) return

        val report = isolateProfilePreferenceFailure(
            ProfilePreferenceLocalFailureStage.PULL_APPLY,
            safeFailureLogger,
        ) {
            val applyReport = profilePreferenceSyncRepository.applyPulledSections(plan.valid)
            if (applyReport.applied > 0) {
                userProfileRepository.refreshProfiles()
            }
            applyReport
        } ?: return
        if (report.ignoredUnknownProfile > 0) {
            Logger.i("SyncManager") {
                profilePreferencePullCountLogLine(
                    ProfilePreferencePullDiagnosticCategory.UNKNOWN_PROFILE,
                    report.ignoredUnknownProfile,
                )
            }
        }
        if (report.invalid > 0) {
            Logger.w("SyncManager") {
                profilePreferencePullCountLogLine(
                    ProfilePreferencePullDiagnosticCategory.REPOSITORY_INVALID,
                    report.invalid,
                )
            }
        }
    }

    /**
     * Merge one pull page in preference-first order.
     *
     * Preference sections commit through ProfilePreferenceSyncRepository before ordinary
     * entities. SyncRepository's ordinary merge owns its own transaction, while session LWW,
     * preferences, notes, RPG, and external activities may use separate repository transactions.
     * A later ordinary failure therefore does not roll back an earlier preference commit.
     * The caller leaves lastSync unchanged and retries the page; revision guards make replay
     * idempotent and dirty-section predicates preserve concurrent local edits.
     */
    private suspend fun mergePullPage(
        pullResponse: PortalSyncPullResponse,
        lastSync: Long,
        mergeProfileId: String,
        isFirstPage: Boolean,
        serverWinsRoutineIds: Set<String>,
    ): Result<Unit> {
        val ownerUserId = tokenStorage.currentUser.value?.id
            ?: return Result.failure(PortalApiException("Not authenticated", null, 401))
        if (pullResponse.ownershipEvents.isNotEmpty()) {
            val events = pullResponse.ownershipEvents.map { event ->
                OwnershipEvent(
                    mutationId = event.mutationId,
                    sourceProfileId = event.sourceProfileId,
                    targetProfileId = event.targetProfileId,
                    targetProfileName = event.targetProfileName,
                    targetProfileColorIndex = event.targetProfileColorIndex,
                    workoutSessionIds = event.workoutSessionIds,
                    routineIds = event.routineIds,
                    cycleIds = event.cycleIds,
                    personalRecordIds = event.personalRecordIds,
                    transferredAt = kotlin.time.Instant.parse(event.transferredAt).toEpochMilliseconds(),
                )
            }
            requireNotNull(ownershipEventApplier) {
                "OwnershipEventApplier is required when ownership events are present"
            }.applyRemoteEvents(ownerUserId, events)
            userProfileRepository.refreshProfiles()
        }
        applyPulledProfilePreferences(
            dtos = pullResponse.profilePreferenceSections,
            isFirstPage = isFirstPage,
        )

        // 1. Prepare sessions with exercise lookup (pre-transaction to avoid DB calls in transaction)
        var unmatchedExerciseCount = 0
        val unmatchedExerciseNames = mutableSetOf<String>()
        val mobileSessions = pullResponse.sessions.flatMap { portalSession ->
            PortalPullAdapter.toWorkoutSessionsWithLookup(
                portalSession,
                mergeProfileId,
            ) { name, muscleGroup, existingExerciseId ->
                val exerciseId = syncRepository.findExerciseId(name, muscleGroup, existingExerciseId)
                if (exerciseId == null) {
                    unmatchedExerciseCount++
                    unmatchedExerciseNames.add(name)
                }
                exerciseId
            }
        }

        // Telemetry: log unmatched exercises for catalog gap analysis
        if (unmatchedExerciseCount > 0) {
            Logger.w("SyncManager") {
                "Pull: $unmatchedExerciseCount exercises not found in local catalog: ${unmatchedExerciseNames.take(10).joinToString()}" +
                    if (unmatchedExerciseNames.size > 10) " (and ${unmatchedExerciseNames.size - 10} more)" else ""
            }
        }

        // 2. Prepare badge and PR DTOs
        val badgeDtos = pullResponse.badges.map { PortalPullAdapter.toBadgeSyncDto(it) }
        // Resolve the catalog exercise id by name/muscle group; the portal PR
        // projection carries no exercise_id (audit F021). Cache lookups by
        // (name, muscleGroup) so a pull with many PRs for the same exercise does
        // not issue a DB query per row (N+1).
        val exerciseIdCache = mutableMapOf<Pair<String, String>, String?>()
        val prDtos = pullResponse.personalRecords.map { pr ->
            val resolvedExerciseId = exerciseIdCache.getOrPut(pr.exerciseName to pr.muscleGroup) {
                syncRepository.findExerciseId(pr.exerciseName, pr.muscleGroup)
            }
            PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId)
        }
        val gamificationStatsDto = pullResponse.gamificationStats?.let {
            PortalPullAdapter.toGamificationStatsSyncDto(it)
        }

        // 2b. Phase 3.5: extract session-level notes for the SessionNotes
        // side-table. Keyed on the portal `routineSessionId` (== portal
        // session id). Sessions without notes are skipped. Prefer
        // session.updatedAt for LWW; fall back to startedAt, then now, when
        // older Edge Function versions omit updatedAt.
        val sessionNotesMap: Map<String, com.devil.phoenixproject.data.repository.SessionNotesEntry> =
            pullResponse.sessions
                .filter { !it.notes.isNullOrBlank() }
                .associate { ps ->
                    ps.id to com.devil.phoenixproject.data.repository.SessionNotesEntry(
                        notes = ps.notes,
                        updatedAtMillis = sessionNotesLwwEpochMillis(ps.updatedAt, ps.startedAt),
                    )
                }

        val syncInvariantViolations = SyncInvariantChecker.checkPullPage(
            pullResponse = pullResponse,
            mobileSessions = mobileSessions,
            sessionNoteKeys = sessionNotesMap.keys,
        )
        if (syncInvariantViolations.isNotEmpty()) {
            Logger.w("SyncManager") {
                val sample = syncInvariantViolations.take(8).joinToString("; ") { violation ->
                    "${violation.code}:${violation.entityId ?: "n/a"}"
                }
                "Pull invariant warnings (${syncInvariantViolations.size}): $sample" +
                    if (syncInvariantViolations.size > 8) " ..." else ""
            }
        }

        // Phase 3.3 (audit item #1): build per-session updatedAt map keyed
        // on the per-exercise WorkoutSession.id (== portal exercise id).
        // Each portal session's updatedAt applies to all child mobile rows.
        val sessionUpdatedAtById: Map<String, Long> = pullResponse.sessions
            .flatMap { ps ->
                val ts = ps.updatedAt?.let { iso ->
                    runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }
                        .getOrNull()
                } ?: 0L
                ps.exercises.map { ex -> ex.id to ts }
            }
            .toMap()

        // 3. Apply durable deletions before live session projections in one repository
        // transaction. The repository selects LWW or legacy insertion from the timestamp map.
        try {
            syncRepository.mergeAllPullData(
                ownerUserId = ownerUserId,
                workoutDeletions = pullResponse.workoutDeletions,
                sessions = mobileSessions,
                routines = pullResponse.routines,
                cycles = pullResponse.cycles,
                badges = badgeDtos,
                gamificationStats = gamificationStatsDto,
                personalRecords = prDtos,
                lastSync = lastSync,
                profileId = mergeProfileId,
                serverWinsRoutineIds = serverWinsRoutineIds,
                sessionNotes = sessionNotesMap,
                sessionUpdatedAtById = sessionUpdatedAtById,
            )

            Logger.d("SyncManager") {
                "Ordinary pull merge complete: ${mobileSessions.size} sessions (${mobileSessions.count { it.exerciseId != null }} with exerciseId), " +
                    "${pullResponse.routines.size} routines, ${pullResponse.cycles.size} cycles, " +
                    "${pullResponse.badges.size} badges, ${prDtos.size} PRs, " +
                    "${sessionNotesMap.size} session notes"
            }
        } catch (e: Exception) {
            Logger.e(e) {
                "Ordinary pull merge failed; lastSync will not advance and earlier per-repository " +
                    "merges may remain for idempotent retry."
            }
            return Result.failure(PortalApiException("Pull merge failed: ${e.message}"))
        }

        // RPG attributes are checkpoint-critical because the server filters them by lastSync.
        // A failure does not roll back earlier repository commits, but it must fail the page so
        // the checkpoint stays unchanged and the idempotent retry receives the RPG row again.
        try {
            // RPG attributes — server wins (overwrite local)
            pullResponse.rpgAttributes?.let { rpg ->
                val characterClass = try {
                    CharacterClass.valueOf(rpg.characterClass ?: "PHOENIX")
                } catch (_: IllegalArgumentException) {
                    CharacterClass.PHOENIX
                }
                val rpgProfile = RpgProfile(
                    strength = rpg.strength,
                    power = rpg.power,
                    stamina = rpg.stamina,
                    consistency = rpg.consistency,
                    mastery = rpg.mastery,
                    characterClass = characterClass,
                    lastComputed = currentTimeMillis(),
                )
                gamificationRepository.saveRpgProfile(rpgProfile, mergeProfileId)
                Logger.d("SyncManager") { "Merged portal RPG attributes: ${rpg.characterClass}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "RPG attribute pull merge failed; checkpoint will not advance." }
            return Result.failure(PortalApiException("RPG attribute pull merge failed: ${e.message}"))
        }

        // External activities are checkpoint-critical. If this write fails, fail the page so
        // lastSync and the delta marker stay unchanged and the activity is fetched again.
        if (pullResponse.externalActivities.isNotEmpty()) {
            try {
                val activities = pullResponse.externalActivities.map { dto ->
                    com.devil.phoenixproject.domain.model.ExternalActivity(
                        id = dto.id,
                        externalId = dto.externalId,
                        provider = IntegrationProvider.fromKey(dto.provider) ?: IntegrationProvider.UNKNOWN,
                        name = dto.name,
                        activityType = dto.activityType,
                        startedAt = try {
                            kotlin.time.Instant.parse(dto.startedAt).toEpochMilliseconds()
                        } catch (_: Exception) {
                            currentTimeMillis()
                        },
                        durationSeconds = dto.durationSeconds,
                        distanceMeters = dto.distanceMeters,
                        calories = dto.calories,
                        avgHeartRate = dto.avgHeartRate,
                        maxHeartRate = dto.maxHeartRate,
                        elevationGainMeters = dto.elevationGainMeters,
                        rawData = dto.rawData,
                        syncedAt = try {
                            kotlin.time.Instant.parse(dto.syncedAt).toEpochMilliseconds()
                        } catch (_: Exception) {
                            currentTimeMillis()
                        },
                        profileId = mergeProfileId,
                        needsSync = false,
                    )
                }
                externalActivityRepository.upsertActivities(activities)
                Logger.d("SyncManager") { "Merged ${activities.size} portal external activities" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "External activity pull merge failed; checkpoint will not advance." }
                return Result.failure(PortalApiException("External activity pull merge failed: ${e.message}"))
            }
        }

        return Result.success(Unit)
    }

    private fun getPlatformName(): String {
        // Guaranteed non-empty. The server's normalizeSyncPlatform rejects
        // anything that doesn't trim-lowercase-contain "android"/"ios", so
        // if the Platform actual ever returned an empty or odd string we'd
        // log a server-side "defaulting to unknown" warning. Fall back on
        // the compile-time isIosPlatform flag, then on "android" as the
        // most common device, so the wire value is never blank.
        val raw = getPlatform().name.lowercase().trim()
        return when {
            raw.contains("android") -> "android"
            raw.contains("ios") -> "ios"
            isIosPlatform -> "ios"
            else -> "android"
        }
    }

    private fun dedupeWorkoutSessionsById(
        sessions: List<WorkoutSession>,
        context: String,
    ): List<WorkoutSession> {
        if (sessions.size < 2) return sessions

        val countsById = sessions.groupingBy { it.id.lowercase() }.eachCount()
        val duplicateCounts = countsById.filterValues { count -> count > 1 }
        if (duplicateCounts.isEmpty()) return sessions

        val seen = mutableSetOf<String>()
        val deduped = sessions.filter { session -> seen.add(session.id.lowercase()) }
        val sample = duplicateCounts.entries
            .take(10)
            .joinToString { (id, count) -> "$id x$count" }
        val suffix = if (duplicateCounts.size > 10) ", ..." else ""
        Logger.w("SyncManager") {
            "$context: dropped ${sessions.size - deduped.size} duplicate workout session row(s) " +
                "before portal sync; duplicate IDs/counts=[$sample$suffix]"
        }
        return deduped
    }

    private fun rejectDuplicatePushPayloadKeys(
        payload: PortalSyncPayload,
    ): Result<PortalSyncPushResponse>? {
        val duplicate = findPushPayloadDuplicateKeys(payload).firstOrNull() ?: return null
        val message = duplicate.toExceptionMessage()
        Logger.e("SyncManager") { message }
        return Result.failure(PortalApiException(message, null, 400))
    }
}

internal data class PushPayloadDuplicateKeys(
    val table: String,
    val ids: List<String>,
) {
    fun toExceptionMessage(): String = "Duplicate IDs in local push payload: $table contains duplicate key(s): ${ids.joinToString()}"
}

internal fun findPushPayloadDuplicateKeys(
    payload: PortalSyncPayload,
): List<PushPayloadDuplicateKeys> {
    val reports = mutableListOf<PushPayloadDuplicateKeys>()

    reports.addDuplicateKeys(
        table = "workout_sessions",
        values = payload.sessions.map { session -> session.id },
    )
    reports.addDuplicateKeys(
        table = "routines",
        values = payload.routines.map { routine -> routine.id },
    )
    reports.addDuplicateKeys(
        table = "training_cycles",
        values = payload.cycles.map { cycle -> cycle.id },
    )
    reports.addDuplicateKeys(
        table = "exercise_catalog",
        values = payload.customExercises.map { exercise -> exercise.clientId },
    )
    reports.addDuplicateKeys(
        table = "exercises",
        values = payload.sessions.flatMap { session ->
            session.exercises.map { exercise -> exercise.id }
        },
    )
    reports.addDuplicateKeys(
        table = "sets",
        values = payload.sessions.flatMap { session ->
            session.exercises.flatMap { exercise -> exercise.sets.map { set -> set.id } }
        },
    )
    reports.addDuplicateKeys(
        table = "rep_summaries",
        values = payload.sessions.flatMap { session ->
            session.exercises.flatMap { exercise ->
                exercise.sets.flatMap { set -> set.repSummaries.map { rep -> rep.id } }
            }
        },
    )
    reports.addDuplicateKeys(
        table = "rep_telemetry",
        values = payload.telemetry.map { telemetry -> telemetry.id },
    )
    reports.addDuplicateKeys(
        table = "personal_records",
        values = payload.personalRecords.map { record ->
            val exerciseKey = record.exerciseId?.let { "id:$it" }
                ?: "name:${record.exerciseName}"
            listOf(
                record.localProfileId ?: "__no_profile__",
                exerciseKey,
                record.achievedAt,
                record.recordType,
                record.workoutPhase,
            ).joinToString("|")
        },
    )

    return reports
}

private fun MutableList<PushPayloadDuplicateKeys>.addDuplicateKeys(
    table: String,
    values: List<String>,
) {
    val seen = mutableSetOf<String>()
    val duplicates = linkedSetOf<String>()
    values.forEach { value ->
        val normalized = value.lowercase()
        if (normalized.isNotBlank() && !seen.add(normalized)) {
            duplicates.add(value)
        }
    }
    if (duplicates.isNotEmpty()) {
        add(PushPayloadDuplicateKeys(table, duplicates.toList()))
    }
}

/**
 * Builds push batches that respect BOTH the per-batch session cap
 * ([SyncManager.SYNC_BATCH_SIZE]) and the per-batch telemetry cap
 * ([SyncConfig.MAX_TELEMETRY_PER_BATCH]).
 *
 * A fixed chunk of 50 sessions can still blow past the server's rep_telemetry
 * array cap (10_000) when sessions carry heavy force-curve telemetry — the
 * client self-check then rejects the batch and sync gets stuck. This greedy
 * planner closes a batch early whenever adding another session would exceed
 * either cap.
 *
 * A single session whose own telemetry exceeds the cap is still placed
 * alone in its batch and logged as a warning. PortalApiClient will still
 * reject it, but batches around it continue to flow normally.
 */
internal fun planSessionBatches(
    sessions: List<PortalWorkoutSessionDto>,
    telemetryCountBySessionId: Map<String, Int>,
): List<List<PortalWorkoutSessionDto>> {
    if (sessions.isEmpty()) return listOf(emptyList<PortalWorkoutSessionDto>())
    val batches = mutableListOf<List<PortalWorkoutSessionDto>>()
    var current = mutableListOf<PortalWorkoutSessionDto>()
    var currentTelemetry = 0
    for (session in sessions) {
        val sessionTelemetry = telemetryCountBySessionId[session.id] ?: 0
        if (sessionTelemetry > SyncConfig.MAX_TELEMETRY_PER_BATCH) {
            Logger.w("SyncManager") {
                "Session ${session.id} has $sessionTelemetry telemetry points, " +
                    "exceeding per-batch cap ${SyncConfig.MAX_TELEMETRY_PER_BATCH}. " +
                    "Batch will likely be rejected by the server until telemetry is trimmed."
            }
        }
        val wouldExceedSessions = current.size + 1 > SyncManager.SYNC_BATCH_SIZE
        val wouldExceedTelemetry =
            currentTelemetry + sessionTelemetry > SyncConfig.MAX_TELEMETRY_PER_BATCH
        if (current.isNotEmpty() && (wouldExceedSessions || wouldExceedTelemetry)) {
            batches.add(current)
            current = mutableListOf<PortalWorkoutSessionDto>()
            currentTelemetry = 0
        }
        current.add(session)
        currentTelemetry += sessionTelemetry
    }
    if (current.isNotEmpty()) batches.add(current)
    return batches
}

/**
 * LWW timestamp for pulled session notes. Prefer the portal session's
 * last-edit ([updatedAtIso]); fall back to [startedAtIso], then wall-clock
 * only when neither ISO value can be parsed.
 */
internal fun sessionNotesLwwEpochMillis(
    updatedAtIso: String?,
    startedAtIso: String?,
    nowMillis: () -> Long = { currentTimeMillis() },
): Long = parseIso8601EpochMillis(updatedAtIso)
    ?: parseIso8601EpochMillis(startedAtIso)
    ?: nowMillis()

internal fun parseIso8601EpochMillis(iso: String?): Long? =
    iso?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
