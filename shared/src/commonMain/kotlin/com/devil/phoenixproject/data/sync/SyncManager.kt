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
import com.devil.phoenixproject.data.repository.PendingCycleDeletion
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.WorkoutDeletionMutation
import com.devil.phoenixproject.data.repository.OwnershipTransferMutation
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
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import com.devil.phoenixproject.getPlatform
import com.devil.phoenixproject.isIosPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.absoluteValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User-visible summary of a destructive server-reported delete (delete wins, KD-4).
 */
data class ServerDeletionNotice(
    val discardedRoutineEditIds: List<String> = emptyList(),
    val discardedCycleEditIds: List<String> = emptyList(),
    val deletedActiveCycleIds: List<String> = emptyList(),
) {
    val message: String
        get() = buildList {
            if (deletedActiveCycleIds.isNotEmpty()) {
                add("A training cycle in progress was deleted on the portal and removed from this device.")
            }
            if (discardedRoutineEditIds.isNotEmpty()) {
                add("A routine deleted on the portal had unsynced changes on this device; those changes were discarded.")
            }
            if (discardedCycleEditIds.isNotEmpty()) {
                add("A training cycle deleted on the portal had unsynced changes on this device; those changes were discarded.")
            }
        }.joinToString(" ")
}

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

    /**
     * The just-signed-in portal account is not the one this device's rows already
     * belong to (PR 11 / KD-7). Sync is blocked until the user picks how to treat
     * the pre-switch rows via [SyncManager.resolveAccountMismatch].
     *
     * @param previousUserId last account a push landed in, or a profile's linked id.
     * @param previousUserLabel display label for [previousUserId] (email or id).
     * @param newUserId the account just signed in.
     * @param newUserLabel display label for [newUserId].
     */
    data class AccountMismatch(
        val previousUserId: String,
        val previousUserLabel: String,
        val newUserId: String,
        val newUserLabel: String,
    ) : SyncState()

    /**
     * A push was refused because an entity belongs to a different portal user.
     * Terminal: the upload loop ends and does not auto-retry. The recovery action
     * ("Stop uploading data from before the account switch") is offered by the UI.
     */
    data class OwnershipConflict(val message: String) : SyncState()
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
    private val pendingAccountMismatch: PendingAccountMismatch = PendingAccountMismatch(),
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

        /**
         * Up to this many PRs, every session batch carries the full `personalRecords` list
         * (the historical behaviour). Beyond it a batch carries only its own sessions' PRs
         * and the rest ride the trailing requests, so no request can exceed the portal's
         * 10,000-item cap on `personalRecords`.
         */
        const val PR_FULL_LIST_PER_BATCH = 500

        /** PR 10 step 6 (R-18): fixed overlap subtracted from the first page's `syncTime`. */
        const val PULL_CURSOR_OVERLAP_MS = 5 * 60 * 1000L

        /** PR 10 step 6 (R-22): UUID-valid id that matches nothing, for empty known-id lists. */
        const val NIL_UUID_SENTINEL = "00000000-0000-0000-0000-000000000000"

        private const val COMPLETED_SET_IDENTITY_CHUNK = 500

        /**
         * Routine groups the one-time portal-history repair re-sends per sync. Small
         * enough that a device with years of history spreads the rebuild over many
         * syncs instead of one huge push.
         */
        const val ROUTINE_GROUP_REPAIR_BATCH = 20

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
     * local row was edited after the push watermark; otherwise the "local wins" routine merge
     * would keep the stale local copy and delta pulls would never re-send the server
     * row. Entries are account/profile scoped and cleared only when that scope's
     * pull completes. Guarded by [syncMutex].
     */

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Minimum successful pull time across profiles (PR 10 step 9). Republished after
     * every profile loop; 0 while any profile has never completed a pull.
     */
    val lastSyncTime: StateFlow<Long> = tokenStorage.lastSyncTimestamp

    private val _serverDeletionNotice = MutableStateFlow<ServerDeletionNotice?>(null)

    /**
     * Set when a server-reported delete removed something the user will notice
     * (an unsynced routine/cycle edit, or the active / in-progress cycle). The UI can
     * show [ServerDeletionNotice.message] and then call [clearServerDeletionNotice]
     * with the notice it actually displayed.
     */
    val serverDeletionNotice: StateFlow<ServerDeletionNotice?> = _serverDeletionNotice.asStateFlow()

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

                    val commitOutcome = commitPortalIdentityUnderProfileMutationBarrier(
                        response = goTrueResponse,
                        tokenStorage = tokenStorage,
                        userProfileRepository = userProfileRepository,
                        // Applied to _syncState right here; nothing to hand off.
                        pendingAccountMismatch = null,
                    )
                    if (commitOutcome is PortalIdentityCommitOutcome.AccountMismatchDetected) {
                        // Do not relink and do not clear the hold: the dialog decides.
                        _syncState.value = commitOutcome.mismatch.toSyncState()
                        Logger.i("SyncManager") {
                            "Login detected a different portal account; sync paused pending user choice"
                        }
                        return@withProfileMutationBarrier Result.success(
                            tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user,
                        )
                    }
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
                    val commitOutcome = commitPortalIdentityUnderProfileMutationBarrier(
                        response = goTrueResponse,
                        tokenStorage = tokenStorage,
                        userProfileRepository = userProfileRepository,
                        // Applied to _syncState right here; nothing to hand off.
                        pendingAccountMismatch = null,
                    )
                    if (commitOutcome is PortalIdentityCommitOutcome.AccountMismatchDetected) {
                        _syncState.value = commitOutcome.mismatch.toSyncState()
                        Logger.i("SyncManager") {
                            "Signup detected a different portal account; sync paused pending user choice"
                        }
                        return@withProfileMutationBarrier Result.success(
                            tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user,
                        )
                    }
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
                // An unanswered ownership refusal is dropped with the session (the user can
                // sign back in and be refused again, which re-records it).
                tokenStorage.currentUser.value?.id?.let { tokenStorage.setOwnershipConflict(it, null) }
                tokenStorage.clearAuth()
                tokenStorage.emitLogoutEvent()
                // A mismatch the signed-out account never answered must not pause the next sign-in.
                pendingAccountMismatch.take()
                _syncState.value = SyncState.NotAuthenticated
            }
        }
    }

    /**
     * Drains a mismatch published by [com.devil.phoenixproject.data.repository.PortalAuthRepository]'s
     * identity commit (PR 11). That path cannot reach this class directly, so the shared
     * [PendingAccountMismatch] carries it here.
     */
    fun adoptPendingAccountMismatch(): Boolean {
        val pending = pendingAccountMismatch.take() ?: return false
        _syncState.value = pending.toSyncState()
        return true
    }

    /**
     * PR 11 pause gate, run before anything that talks to the portal. Returns the failure
     * to report while sync is paused, or null when it may run.
     *
     * The in-memory state alone is not enough: after a process restart it is back to
     * [SyncState.Idle] while the token already names the new account. So the mismatch is
     * also re-derived from durable state, the signed-in user against
     * [PortalTokenStorage.getLastSyncedPortalUserId] and the profiles' owners. Both still
     * name the old account until [resolveAccountMismatch] relinks them, and after that the
     * check is a no-op. A push while this holds would also overwrite the last-synced id and
     * erase the evidence, so it must run before any push.
     */
    private fun accountPauseFailure(): Result<Long>? {
        // OAuth (and any other identity commit outside login/signup) publishes its
        // mismatch here; drain it before deciding whether sync may run at all.
        adoptPendingAccountMismatch()
        val current = _syncState.value
        if (current !is SyncState.AccountMismatch && current !is SyncState.OwnershipConflict) {
            val mismatch = detectPersistedAccountMismatch()
            if (mismatch != null) {
                Logger.w("SyncManager") {
                    "Signed-in portal account differs from this device's data; sync paused pending user choice"
                }
                _syncState.value = mismatch.toSyncState()
            } else {
                // A refusal recorded before a restart still holds until recovery or sign-out.
                tokenStorage.currentUser.value?.id
                    ?.takeIf { tokenStorage.hasToken() }
                    ?.let { tokenStorage.getOwnershipConflict(it) }
                    ?.let { _syncState.value = SyncState.OwnershipConflict(it) }
            }
        }
        return when (val state = _syncState.value) {
            is SyncState.AccountMismatch -> Result.failure(
                IllegalStateException(
                    "Sync paused: signed in as a different portal account than this device's data",
                ),
            )
            is SyncState.OwnershipConflict -> Result.failure(PortalApiException(state.message))
            else -> null
        }
    }

    private fun detectPersistedAccountMismatch(): AccountMismatchCandidate? {
        if (!tokenStorage.hasToken()) return null
        val user = tokenStorage.currentUser.value ?: return null
        // A device upgraded from a pre-PR-11 build: the legacy cursor's owner is the
        // account its rows already reached. Record it before comparing, and before the
        // legacy generation seeding / cursor migration further down syncLocked consume it.
        tokenStorage.adoptLegacySyncOwner(user.id)
        return detectAccountMismatch(
            newUserId = user.id,
            newUserLabel = user.email.takeIf { it.isNotBlank() } ?: user.id,
            lastSyncedPortalUserId = tokenStorage.getLastSyncedPortalUserId(),
            lastSyncedPortalUserLabel = tokenStorage.getLastSyncedPortalUserLabel(),
            profileOwners = (
                userProfileRepository.allProfiles.value +
                    // A hidden pending-deletion profile keeps its original owner until that
                    // account pushes its tombstones. It is evidence of a previous account only
                    // while the device has no last-synced account; afterwards it must not re-open
                    // the dialog on every sync.
                    userProfileRepository.pendingDeletionProfiles.value
                        .takeIf { tokenStorage.getLastSyncedPortalUserId() == null }
                        .orEmpty()
                ).mapNotNull { profile ->
                profile.supabaseUserId?.takeIf { it.isNotBlank() }?.let { owner -> profile.id to owner }
            },
        )
    }

    /**
     * Applies the user's account-switch choice (PR 11 / KD-7).
     *
     * Both choices relink every profile to the just-signed-in account, reset every
     * profile's pull cursor under that account, and record which pre-switch rows must
     * never be uploaded to it. [AccountSwitchChoice.UPLOAD_NEVER_SYNCED] keeps rows
     * that never reached another account; [AccountSwitchChoice.EXCLUDE_ALL_EXISTING]
     * keeps everything local and uploads nothing that already existed.
     */
    suspend fun resolveAccountMismatch(choice: AccountSwitchChoice): Result<Unit> = syncMutex.withLock {
        adoptPendingAccountMismatch()
        val mismatch = _syncState.value as? SyncState.AccountMismatch
            ?: return@withLock Result.success(Unit)
        val newUserId = mismatch.newUserId
        val previousUserId = mismatch.previousUserId
        // The choice binds this device's rows to the account that is signed in now. A stale
        // dialog answered after a sign-out / sign-in as someone else must not bind them to
        // the account the mismatch named.
        if (tokenStorage.currentUser.value?.id != newUserId) {
            return@withLock Result.failure(
                IllegalStateException("The signed-in account changed before the account-switch choice was applied"),
            )
        }
        try {
            withProfileMutationBarrier {
                // Pending-deletion profiles (PR 20) are left out: they keep their original
                // owner, whose account alone can push their tombstones and finalize them.
                val profiles = userProfileRepository.allProfiles.value
                // Exclusions first, relink second: if the relink fails part-way, the profiles
                // and the last-synced id still name the old account, so the pause gate
                // re-detects the mismatch and the (idempotent) choice can be applied again.
                // The reverse order could leave rows relinked with no exclusions recorded.
                // Each profile is classified under its OWN previous owner: a device can hold
                // profiles synced to different accounts (codex #859). Unlinked profiles fall
                // back to the account the device last synced into.
                val ownerByProfile = profiles.associate { profile ->
                    profile.id to (profile.supabaseUserId?.takeIf { it.isNotBlank() && it != newUserId } ?: previousUserId)
                }
                val boundaries = profiles.associate { profile ->
                    profile.id to tokenStorage.getAccountSyncBoundary(ownerByProfile.getValue(profile.id), profile.id)
                }
                syncRepository.recordAccountSwitchExclusions(
                    portalUserId = newUserId,
                    profileIds = profiles.map { it.id },
                    excludeAllExisting = choice == AccountSwitchChoice.EXCLUDE_ALL_EXISTING,
                    previousPushWatermarks = boundaries,
                    previousPortalUserId = previousUserId,
                    previousPortalUserIdsByProfile = ownerByProfile,
                )
                for (profile in profiles) {
                    // Force-relink: the normal link path throws ProfileAccountBindingException
                    // when the profile already names a different owner.
                    userProfileRepository.reassignToSupabaseUnderProfileMutationBarrier(
                        profileId = profile.id,
                        supabaseUserId = newUserId,
                    )
                }
                // Cursors are namespaced by user id, so the new account starts clean.
                tokenStorage.resetAllPullCursors(newUserId)
            }
            // The device's rows now belong to the new account (or are excluded from it),
            // so a later sign-in as this user must not re-open the dialog.
            tokenStorage.setLastSyncedPortalUserId(newUserId)
            tokenStorage.setLastSyncedPortalUserLabel(mismatch.newUserLabel)
            _syncState.value = SyncState.Idle
            Logger.i("SyncManager") {
                "Account switch resolved (choice=$choice); exclusions recorded for the new account"
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * Recovery action for [SyncState.OwnershipConflict]: "Stop uploading data from
     * before the account switch". Applies the same exclusion as
     * [AccountSwitchChoice.EXCLUDE_ALL_EXISTING] for the current account and clears
     * the terminal state so only rows created afterwards are uploaded.
     */
    suspend fun applyOwnershipConflictRecovery(): Result<Unit> = syncMutex.withLock {
        val conflict = _syncState.value as? SyncState.OwnershipConflict
            ?: return@withLock Result.success(Unit)
        val userId = tokenStorage.currentUser.value?.id
            ?: return@withLock Result.failure(IllegalStateException("Not authenticated"))
        try {
            withProfileMutationBarrier {
                val profiles = userProfileRepository.allProfiles.value
                // Only data that predates this account on the device can belong to another
                // account; rows made for this account since then keep syncing (codex #859).
                // The catalog-collision refusal concerns custom exercises only.
                val entityTypes = if (conflict.message.contains(CATALOG_COLLISION_MARKER, ignoreCase = true)) {
                    setOf(SyncExcludedEntityTypes.CUSTOM_EXERCISE)
                } else {
                    SyncExcludedEntityTypes.ALL.toSet()
                }
                syncRepository.recordOwnershipRecoveryExclusions(
                    portalUserId = userId,
                    profileIds = profiles.map { it.id },
                    // Unknown first-seen time (an install signed in before this build):
                    // everything on disk counts as pre-existing, as before.
                    createdAtOrBefore = tokenStorage.getAccountFirstSeenAt(userId) ?: Long.MAX_VALUE,
                    entityTypes = entityTypes,
                )
            }
            tokenStorage.setOwnershipConflict(userId, null)
            _syncState.value = SyncState.Idle
            Logger.i("SyncManager") {
                "Ownership conflict resolved: pre-switch rows excluded from upload (${conflict.message})"
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
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
        if (adoptPendingAccountMismatch()) return

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
     * Forces a complete re-sync by resetting every profile's pull cursor to 0.
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
            Logger.i("SyncManager") { "Forcing full resync - resetting every profile's pull cursor to 0" }
            tokenStorage.currentUser.value?.id?.let { userId -> tokenStorage.resetAllPullCursors(userId) }
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

    /**
     * PR 10 step 2: every local profile gets its own push + pull, pending-deletion
     * profiles first (forward-compatible hook for PR 20 — none exist yet), then the
     * active profile, then the rest. A failure for one profile is recorded and the
     * loop continues; the overall [SyncState] reflects the worst outcome. An auth
     * failure (401) aborts the loop — the token is gone for every profile.
     */
    private suspend fun syncLocked(): Result<Long> {
        accountPauseFailure()?.let { return it }

        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing
        Logger.d("SyncManager") { "Sync starting: hasToken=${tokenStorage.hasToken()}" }

        val userId = tokenStorage.currentUser.value?.id
        if (userId == null) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        // Upgrade seeding (step 8) runs once, before any per-profile work, so the first
        // PR 10 sync sees the seeded cursors rather than inventing fresh ones.
        //
        // The profiles this account may sync: bound to it, or unbound (bound below).
        val candidateProfiles = syncProfileOrder(userId)

        // First (codex #856): migration 49 left every pre-existing workout row dirty
        // (generation 1/0). Before the all-profile loop can see them, acknowledge the rows
        // the old client provably synchronized — a non-null updatedAt at or before the
        // legacy cursor, or a pulled row (portalOrigin = 1). Scoped to THIS account's
        // profiles and namespaced per account in the ledger, so account B's cursor can never
        // mark account A's offline edits as synced, and A still gets its own seeding when it
        // signs in. Unbound profiles are included: this sync binds them to this account, and
        // the legacy cursor (only used when not attributed to another user) is this
        // account's. Must run before migrateLegacyCursors consumes the legacy key.
        // The cursor rule applies only to the profile the legacy marker names (the one the
        // old client synced); every other profile of this account gets pulled-provenance
        // only. No marker → no profile gets the cursor rule (its rows re-push, safely).
        syncRepository.seedLegacySyncedGenerationsOnce(
            accountId = userId,
            legacyLastSync = tokenStorage.legacyLastSyncFor(userId),
            cursorProfileId = tokenStorage.legacyCursorProfileFor(userId),
            profileIds = candidateProfiles.map { it.id },
        )?.let { marked ->
            Logger.i("SyncManager") { "Upgrade seeding: acknowledged $marked legacy workout row(s) already on the portal" }
        }
        val profiles = bindUnboundProfiles(userId, candidateProfiles)
        val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"
        val seeded = tokenStorage.migrateLegacyCursors(
            userId = userId,
            allProfileIds = profiles.map { it.id },
        )
        if (seeded) {
            Logger.i("SyncManager") {
                "Upgrade seeding: legacy global cursor split across ${profiles.size} profile(s); " +
                    "only the profile named by the legacy delta-pull marker keeps its pull cursor, others start at 0"
            }
        }
        // One-time routines/cycles/PRs repair push for every profile (step 8). Survives
        // across syncs until every profile has contributed its batch in one sync.
        val repairPushActive = tokenStorage.needsRoutineCyclePrRepairPush(userId)

        val outcomes = mutableListOf<ProfileSyncOutcome>()
        for (profile in profiles) {
            val includeUserScoped = profile.id == activeProfileId
            val outcome = syncProfileLocked(
                userId = userId,
                profile = profile,
                includeUserScoped = includeUserScoped,
                repairPushActive = repairPushActive,
                pendingDeletion = isPendingDeletionProfile(profile),
            )
            outcomes += outcome
            if (outcome.authFailure) {
                Logger.w("SyncManager") {
                    "Auth failure while syncing profile ${profile.id}; aborting the remaining profiles"
                }
                break
            }
            // An ownership refusal is definitive evidence of another account's rows on this
            // device: no later profile may push before the user chooses (codex #859).
            // combineProfileOutcomes publishes and persists the terminal state.
            if (outcome.ownershipConflict) {
                Logger.w("SyncManager") {
                    "Ownership refusal while syncing profile ${profile.id}; aborting the remaining profiles"
                }
                break
            }
        }
        // The repair is account-wide and one-time: complete it only when EVERY profile in
        // this loop delivered its repair push. An aborted loop (a 401 on any push or pull,
        // or any early break) leaves later profiles without theirs, so the repair stays
        // owed (codex #856 P1). Cancellation throws and never reaches this line.
        val everyProfileDeliveredRepair = outcomes.size == profiles.size &&
            outcomes.all { it.pushSucceeded && !it.repairPushFailed }
        if (repairPushActive && everyProfileDeliveredRepair) {
            tokenStorage.markRoutineCyclePrRepairPushDone(userId)
            Logger.i("SyncManager") {
                "One-time routines/cycles/PRs repair push completed for every profile"
            }
        }

        // Step 9: the UI's lastSyncTime is the minimum successful pull time across
        // profiles. Never-pulled profiles (cursor 0) must not drag the floor to
        // "never synced" — G-4: take the min over profiles that have pulled, and
        // fall back to 0 only when none have.
        val minPull = profiles
            .map { tokenStorage.getPullCursor(userId, it.id) }
            .filter { it > 0L }
            .minOrNull() ?: 0L
        tokenStorage.publishMinPullCursor(minPull)

        return combineProfileOutcomes(outcomes)
    }

    /**
     * Pending-deletion profiles first (PR 20 hook), then the active profile, then the rest.
     *
     * Only profiles this portal account may speak for: unbound profiles and profiles
     * already linked to [userId]. A profile linked to a different portal account is
     * never pushed or pulled through this token; doing so would upload that account's
     * workouts into this one (codex #856 P1).
     */
    private suspend fun syncProfileOrder(userId: String): List<UserProfile> {
        // Seed the default profile only when the table is empty. Calling ensureDefaultProfile
        // on every sync republishes the active context as a side effect, which would leak
        // unpublished local-safety state into the UI on each sync.
        if (userProfileRepository.allProfiles.value.isEmpty()) {
            userProfileRepository.ensureDefaultProfile()
        }
        val activeId = userProfileRepository.activeProfile.value?.id
        // PR 20: permanently deleted profiles are hidden from allProfiles but still owe
        // the portal their tombstones. They go first, so their own push lands while they
        // are still listed in allProfiles (R-11); later pushes then omit them.
        val pendingDeletion = userProfileRepository.pendingDeletionProfiles.value
            .filter { isSyncableByPortalUser(it, userId) }
        // allProfiles and pendingDeletionProfiles partition the profile rows, so they never overlap.
        val rest = userProfileRepository.allProfiles.value
            .filter { isSyncableByPortalUser(it, userId) }
        val (active, others) = rest.partition { it.id == activeId }
        return pendingDeletion + active + others
    }

    /**
     * Binds every unbound profile to [userId] before any of its data is pushed or pulled
     * (codex #856): syncing an unbound profile without an owner let a later account bind
     * it and receive its data. Runs under the profile-mutation barrier sync already holds,
     * through the same guarded link the identity commit uses. A profile whose binding fails
     * (e.g. another account claimed it concurrently) is dropped from this sync.
     *
     * PR 11 reads profile owners to detect an account switch; a profile bound here to the
     * account that is syncing it is exactly the ownership that detection must see.
     */
    private suspend fun bindUnboundProfiles(userId: String, profiles: List<UserProfile>): List<UserProfile> =
        profiles.mapNotNull { profile ->
            if (!profile.supabaseUserId.isNullOrBlank()) return@mapNotNull profile
            try {
                userProfileRepository.linkToSupabaseUnderProfileMutationBarrier(profile.id, userId)
                profile.copy(supabaseUserId = userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("SyncManager") {
                    "Could not bind profile ${profile.id} to the signed-in account; skipping it this sync: ${e.message}"
                }
                null
            }
        }

    private fun isSyncableByPortalUser(profile: UserProfile, userId: String): Boolean =
        profile.supabaseUserId.isNullOrBlank() || profile.supabaseUserId == userId

    /** A 401 means the token is gone for every profile: abort the profile loop. */
    private fun isAuthFailure(error: Throwable?): Boolean =
        error is PortalApiException && error.statusCode == 401

    private fun isPendingDeletionProfile(profile: UserProfile): Boolean =
        userProfileRepository.pendingDeletionProfiles.value.any { it.id == profile.id }

    /** What one profile's push+pull contributed to the overall sync. */
    private data class ProfileSyncOutcome(
        val profileId: String,
        /** Push or pull failed with 401 — the token is gone; abort the remaining profiles. */
        val authFailure: Boolean = false,
        /** Push was refused because an entity belongs to a different portal user (PR 11). */
        val ownershipConflict: Boolean = false,
        val repairPushFailed: Boolean = false,
        val pushSucceeded: Boolean = false,
        val pullSucceeded: Boolean = false,
        val syncTimeEpoch: Long = 0L,
        val pullSyncTime: Long? = null,
        val error: Throwable? = null,
    )

    private fun combineProfileOutcomes(outcomes: List<ProfileSyncOutcome>): Result<Long> {
        val authFailure = outcomes.firstOrNull { it.authFailure }
        if (authFailure != null) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(authFailure.error ?: PortalApiException("Not authenticated"))
        }
        val ownership = outcomes.firstOrNull { it.ownershipConflict }
        if (ownership != null) {
            // Terminal: the upload loop ends and does not auto-retry. The recovery
            // action ("Stop uploading data from before the account switch") is offered
            // by the UI via applyOwnershipConflictRecovery().
            val message = ownership.error?.message ?: "Portal refused an entity owned by another user"
            _syncState.value = SyncState.OwnershipConflict(message)
            // Durable, per account: a restart must not resume pushing into the refusal.
            tokenStorage.currentUser.value?.id?.let { tokenStorage.setOwnershipConflict(it, message) }
            return Result.failure(ownership.error ?: PortalApiException(message))
        }
        val notPremium = outcomes.firstOrNull {
            it.error is PortalApiException &&
                ((it.error as PortalApiException).statusCode == 402 ||
                    (it.error as PortalApiException).statusCode == 403)
        }
        if (notPremium != null && outcomes.none { it.pushSucceeded }) {
            _syncState.value = SyncState.NotPremium
            return Result.failure(notPremium.error ?: PortalApiException("Not premium"))
        }

        val anyPush = outcomes.any { it.pushSucceeded }
        val allPull = outcomes.isNotEmpty() && outcomes.all { it.pullSucceeded }
        val anyPull = outcomes.any { it.pullSucceeded }
        val firstError = outcomes.firstOrNull { it.error != null }?.error
        val reportTime = outcomes.maxOfOrNull { it.syncTimeEpoch }?.takeIf { it > 0 } ?: currentTimeMillis()

        return when {
            outcomes.isEmpty() -> {
                _syncState.value = SyncState.Error("No local profiles to sync")
                Result.failure(Exception("No local profiles to sync"))
            }
            !anyPush -> {
                _syncState.value = SyncState.Error(firstError?.message ?: "Push failed")
                Result.failure(firstError ?: Exception("Push failed"))
            }
            anyPush && allPull -> {
                val minPull = outcomes.mapNotNull { it.pullSyncTime }
                    .filter { it > 0L }
                    .minOrNull() ?: reportTime
                _syncState.value = SyncState.Success(minPull)
                Result.success(minPull)
            }
            anyPush && anyPull -> {
                _syncState.value = SyncState.PartialSuccess(
                    pushSucceeded = true,
                    pullSucceeded = false,
                    lastSyncTime = reportTime,
                    pullError = firstError?.message ?: "Pull failed for at least one profile",
                )
                Result.success(reportTime)
            }
            else -> {
                val pullErrorMsg = firstError?.message ?: "Pull failed"
                _syncState.value = SyncState.PartialSuccess(
                    pushSucceeded = true,
                    pullSucceeded = false,
                    lastSyncTime = reportTime,
                    pullError = pullErrorMsg,
                )
                Result.success(reportTime)
            }
        }
    }

    /** One profile's push + pull. The body of the pre-PR-10 [syncLocked], now profile-scoped. */
    private suspend fun syncProfileLocked(
        userId: String,
        profile: UserProfile,
        includeUserScoped: Boolean,
        repairPushActive: Boolean,
        pendingDeletion: Boolean,
    ): ProfileSyncOutcome {
        // Push local changes (no status check -- Railway backend abandoned)
        if (pendingDeletion) {
            syncRepository.restampPersonalRecordTombstones(profile.id, currentTimeMillis())
        }
        val pushResult = pushLocalChanges(
            userId = userId,
            profile = profile,
            includeUserScoped = includeUserScoped,
            repairPushActive = repairPushActive,
            pendingDeletion = pendingDeletion,
        )
        if (pushResult.isFailure) {
            val error = pushResult.exceptionOrNull()
            Logger.e("SyncManager") {
                "Push FAILED for profile ${profile.id}: status=${(error as? PortalApiException)?.statusCode}, msg=${error?.message}"
            }
            val authFailure = isAuthFailure(error)
            val ownershipConflict = (error as? Exception)
                ?.let { classifyError(it, "Push").category == SyncErrorCategory.OWNERSHIP_CONFLICT }
                ?: false
            return ProfileSyncOutcome(
                profileId = profile.id,
                authFailure = authFailure,
                ownershipConflict = ownershipConflict,
                repairPushFailed = repairPushActive,
                error = error,
            )
        }
        Logger.i("SyncManager") { "Push succeeded for profile ${profile.id}" }
        // PR 11: remember which portal account the last successful push landed in, so
        // the next sign-in can tell "same account" from "different account". The step that
        // justifies it is "rows of this device landed in this account", which this push
        // just completed, so it is written here and not deferred to a fully completed loop:
        // deferring it would leave a later sign-in as a third account blind to the rows
        // already uploaded here. A loop that aborts before any push landed never writes it.
        tokenStorage.setLastSyncedPortalUserId(userId)
        tokenStorage.currentUser.value?.let { user ->
            tokenStorage.setLastSyncedPortalUserLabel(
                user.email.takeIf { it.isNotBlank() } ?: user.id,
            )
        }

        // Inspect per-entity LWW rejections (Phase 3.2 contract: the server
        // rejects an incoming row when it already holds a newer updated_at,
        // and mobile is expected to log the conflict and let the next pull
        // repair convergence). Without this, rejections were decoded but never
        // surfaced (audit F025).
        val pushOutcome = pushResult.getOrThrow()
        // pushLocalChanges returns success only after EVERY batch landed, so this is the
        // point where the gather's device time becomes this profile's push watermark.
        // Rows edited after gatherStartedAt keep a newer updatedAt and stay in the next
        // delta; a failed push returned above and leaves the watermark untouched.
        // PR 11 also reads it as the account-switch "already synced" boundary.
        tokenStorage.setPushWatermark(userId, profile.id, pushOutcome.gatherStartedAt)
        if (pendingDeletion) {
            // Routine and workout tombstones are applied unconditionally or exact-acked; a
            // cycle deletion can lose the portal's clocked gate to a newer web edit. Finalizing
            // then would drop this profile from allProfiles and re-scope that cycle to Default.
            val outstandingCycleDeletions = trainingCycleRepository
                ?.getPendingCycleDeletions(userId, profile.id)
                .orEmpty()
                .filter { CANONICAL_UUID_REGEX.matches(it.id) }
            // The portal returns no per-PR ack, only `personalRecordsInserted`: dedicated rows
            // that passed its LWW gate. Every PR row a pending profile sends is a tombstone
            // (re-stamped just before this push, so each should pass); fewer written than sent
            // means the portal kept a live copy, and finalizing would re-scope it to Default.
            val prTombstonesDropped = pushOutcome.personalRecordTombstonesSent >
                pushOutcome.personalRecordsWritten
            if (prTombstonesDropped) {
                Logger.w("SyncManager") {
                    "Pending-deletion profile ${profile.id}: portal wrote ${pushOutcome.personalRecordsWritten} of " +
                        "${pushOutcome.personalRecordTombstonesSent} PR tombstone(s); kept pending for the next sync"
                }
                return ProfileSyncOutcome(profileId = profile.id, pushSucceeded = true, pullSucceeded = true)
            }
            if (outstandingCycleDeletions.isNotEmpty()) {
                val restampAt = currentTimeMillis()
                outstandingCycleDeletions.forEach { deletion ->
                    trainingCycleRepository?.restampPendingCycleDeletion(deletion.id, restampAt)
                }
                Logger.w("SyncManager") {
                    "Pending-deletion profile ${profile.id}: ${outstandingCycleDeletions.size} cycle deletion(s) " +
                        "not accepted yet; re-stamped and kept pending for the next sync"
                }
                return ProfileSyncOutcome(profileId = profile.id, pushSucceeded = true, pullSucceeded = true)
            }
            return finishPendingProfileDeletion(userId, profile, pushOutcome.response.syncTime)
        }
        val pushResponse = pushOutcome.response
        val rejections = pushOutcome.rejections
        val rejectedRoutineIds = rejections.routines.mapTo(mutableSetOf()) { it.id }
        // Persisted, not in memory (codex #856): the watermark just moved past these
        // routines, so a restart before the next completed pull must not lose the marker.
        tokenStorage.addPendingServerWinsRoutineIds(userId, profile.id, rejectedRoutineIds)
        val rejectedSessionIds = rejections.sessions.mapTo(mutableSetOf()) { it.id }
        val totalRejections = rejections.sessions.size + rejections.routines.size +
            rejections.cycles.size + rejections.externalActivities.size +
            rejections.rpgAttributes.size + rejections.gamificationStats.size
        if (totalRejections > 0) {
            Logger.w("SyncManager") {
                "Push LWW rejections for profile ${profile.id} ($totalRejections): " +
                    "sessions=${rejections.sessions.size}, routines=${rejections.routines.size}, " +
                    "cycles=${rejections.cycles.size}, externalActivities=${rejections.externalActivities.size}, " +
                    "rpgAttributes=${rejections.rpgAttributes.size}, gamificationStats=${rejections.gamificationStats.size}. " +
                    "Next pull will repair convergence."
            }
        }

        // Stamp EXACTLY the local rows this push sent and the portal accepted.
        // Sessions with NULL updatedAt would match every delta query indefinitely, but
        // a row that never went out — because its routine group was held, or because it
        // was saved while the push was in flight — must stay in the next delta.
        //
        // Never stamp a session the server rejected under LWW. A rejection can mean
        // the portal skipped this session's set/rep data (its children are gated on
        // acceptance), or that a local edit never landed; stamping would mark that
        // unsent data as synced for good. Rejected rows stay pending; the re-push
        // below gets one attempt at them after the pull, and anything still rejected
        // waits for the next sync. A rejection id is the portal session id
        // (routineSessionId for grouped routine sessions, else the local session id),
        // which is exactly how the push indexed the rows it sent.
        val gatherStartedAt = pushOutcome.gatherStartedAt
        val acceptedRowIds = pushOutcome.localRowIdsByPortalSessionId
            .filterKeys { it !in rejectedSessionIds }
            .values
            .flatten()
        val rejectedRowCount = pushOutcome.localRowIdsByPortalSessionId
            .filterKeys { it in rejectedSessionIds }
            .values
            .sumOf { it.size }
        val stampedCount = syncRepository.updateSessionTimestamps(
            sessionIds = acceptedRowIds,
            timestamp = gatherStartedAt,
            gatherStartedAt = gatherStartedAt,
        )
        if (acceptedRowIds.isNotEmpty() || rejectedRowCount > 0) {
            Logger.d("SyncManager") {
                "Stamped $stampedCount of ${acceptedRowIds.size} accepted session row(s) with updatedAt=$gatherStartedAt" +
                    (
                        if (acceptedRowIds.size > stampedCount) {
                            "; ${acceptedRowIds.size - stampedCount} row(s) were edited during the push and stay pending"
                        } else {
                            ""
                        }
                        ) +
                    (if (rejectedRowCount > 0) "; left $rejectedRowCount LWW-rejected session row(s) unstamped" else "")
            }
        }
        // Record what was sent so a rejected row with unchanged content can be stamped
        // instead of re-pushed forever (step 11). Only accepted rows get a hash: a
        // rejected row whose content is unchanged must still match the hash of what was
        // actually uploaded, and a never-accepted row must have no hash at all.
        // S-1: the hash is namespaced by (userId, profileId) so account A's accept
        // cannot make account B's never-accepted row look accepted.
        // Only sessions the portal explicitly acknowledged: a session missing from
        // `acknowledgedWorkoutSessionIds` without an LWW rejection was NOT applied, and a
        // hash for it would later let rePushRejectedSessions stamp never-accepted
        // content as synced (codex #856 P1).
        pushOutcome.sentSessionsById
            .filterKeys { it in pushOutcome.acknowledgedPortalSessionIds && it !in rejectedSessionIds }
            .forEach { (portalSessionId, dto) ->
                tokenStorage.setSessionSentHash(
                    userId,
                    profile.id,
                    portalSessionId,
                    sessionContentFingerprint(dto, pushOutcome.telemetryByPortalSessionId[portalSessionId].orEmpty()),
                )
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

        // Pull remote changes using parity-based sync (entity IDs plus the profile's pull cursor).
        // Entity IDs are collected inside pullRemoteChangesWithResult to ensure we send
        // the current state of local storage after the push has completed.
        val pullResult = pullRemoteChangesWithResult(
            userId = userId,
            profile = profile,
            includeUserScoped = includeUserScoped,
            followsSuccessfulPush = true,
        )

        // One retry for sessions the portal's LWW gate turned away. The pull has just
        // run, so any note written on the website is now in SessionNotes and will be
        // sent back rather than erased. Ordering matters: pull first, re-push second.
        if (rejections.sessions.isNotEmpty()) {
            if (pullResult.isSuccess) {
                runCatching { rePushRejectedSessions(userId, profile.id, pushOutcome, rejections.sessions) }
                    .onFailure { error ->
                        Logger.w("SyncManager") {
                            "LWW re-push threw; rejected rows stay pending for the next sync: ${error.message}"
                        }
                        // Belt and braces: if the throw landed before the stamp/clear
                        // transaction, repair-group rows among the rejections still need
                        // re-arming or the cursor has already walked past them.
                        reArmRejectedRepairRows(pushOutcome, rejections.sessions.map { it.id })
                    }
            } else {
                // Pull failed, so the re-push is skipped. Repair-group rows among the
                // rejections still need re-arming: the cursor may already have walked
                // past their group, so without this they would never be sent again.
                Logger.w("SyncManager") {
                    "Pull failed; skipping the LWW re-push and re-arming any rejected repair-group rows"
                }
                reArmRejectedRepairRows(pushOutcome, rejections.sessions.map { it.id })
            }
        }

        // Garbage-collect sent hashes of workouts that are gone (deleted here, or tombstoned
        // by the pull just applied). Best-effort: a stale hash is harmless, only unbounded.
        runCatching {
            tokenStorage.retainSessionSentHashes(
                userId,
                profile.id,
                syncRepository.getLivePortalSessionIds(profile.id),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Logger.w("SyncManager") { "Sent-hash garbage collection skipped: ${error.message}" }
        }

        return if (pullResult.isSuccess) {
            val completedPull = pullResult.getOrThrow()
            recordCompletedPull(completedPull)
            ProfileSyncOutcome(
                profileId = profile.id,
                pushSucceeded = true,
                pullSucceeded = true,
                syncTimeEpoch = syncTimeEpoch,
                pullSyncTime = completedPull.syncTime,
            )
        } else {
            // Partial success: push succeeded but pull failed
            // CRITICAL: Do NOT advance this profile's pull cursor on pull failure.
            // This ensures:
            // 1. The same sessions won't be pushed again (they're already stamped)
            // 2. The next pull will still retrieve remote changes from the correct checkpoint
            // 3. The user is notified that sync is incomplete
            val pullError = pullResult.exceptionOrNull()
            Logger.w("SyncManager") {
                "Partial sync for profile ${profile.id}: push succeeded but pull failed. " +
                    "Not advancing the pull cursor. Error: ${pullError?.message}"
            }
            ProfileSyncOutcome(
                profileId = profile.id,
                // A pull 401 means the token is gone for every profile too.
                authFailure = isAuthFailure(pullError),
                pushSucceeded = true,
                pullSucceeded = false,
                syncTimeEpoch = syncTimeEpoch,
                error = pullError,
            )
        }
    }

    /**
     * PR 20: a permanently deleted profile's push has landed its tombstones (workout
     * deletions are exact-acked or the push failed; routine, cycle and PR tombstones rode
     * the same push). Remove the hidden row now, so every later push omits it from
     * allProfiles and the portal re-scopes whatever it still holds to Default. No pull:
     * pulling into a profile that is being removed would only re-create rows in it.
     * A failed finalize leaves the profile pending; the next sync pushes it again.
     */
    private suspend fun finishPendingProfileDeletion(
        userId: String,
        profile: UserProfile,
        rawSyncTime: String,
    ): ProfileSyncOutcome {
        val syncTimeEpoch = runCatching {
            kotlin.time.Instant.parse(rawSyncTime).toEpochMilliseconds()
        }.getOrElse { currentTimeMillis() }
        val finalized = runCatching { userProfileRepository.finalizePendingProfileDeletion(profile.id) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Logger.w("SyncManager") {
                    "Pending deletion of profile ${profile.id} could not be finalized; retrying next sync: ${error.message}"
                }
            }
            .getOrDefault(false)
        if (finalized) {
            // The profile never syncs again: drop its per-profile secure-settings state
            // rather than leave it behind for good.
            runCatching {
                tokenStorage.retainSessionSentHashes(userId, profile.id, emptySet())
                tokenStorage.resetPullCursor(userId, profile.id)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Logger.w("SyncManager") { "Cleanup of deleted profile ${profile.id} sync state skipped: ${error.message}" }
            }
        }
        Logger.i("SyncManager") {
            "Pending-deletion profile ${profile.id} pushed its tombstones (finalized=$finalized)"
        }
        return ProfileSyncOutcome(
            profileId = profile.id,
            pushSucceeded = true,
            // Nothing to pull for a profile being removed; count it as complete so it does
            // not turn an otherwise clean sync into a partial one.
            pullSucceeded = true,
            syncTimeEpoch = syncTimeEpoch,
        )
    }

    /**
     * Retry just the pull operation after a partial sync.
     * Use when push succeeded but pull failed.
     */
    suspend fun retryPull(): Result<Long> = syncMutex.withLock {
        withProfileMutationBarrier { retryPullLocked() }
    }

    private suspend fun retryPullLocked(): Result<Long> {
        accountPauseFailure()?.let { return it }
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing
        val userId = tokenStorage.currentUser.value?.id
            ?: return Result.failure<Long>(PortalApiException("Not authenticated")).also {
                _syncState.value = SyncState.NotAuthenticated
            }
        // A pending-deletion profile never pulls (PR 20): its rows are being removed.
        val profiles = bindUnboundProfiles(userId, syncProfileOrder(userId).filterNot { isPendingDeletionProfile(it) })
        val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"

        val outcomes = mutableListOf<ProfileSyncOutcome>()
        for (profile in profiles) {
            val includeUserScoped = profile.id == activeProfileId
            val lastSync = tokenStorage.getPullCursor(userId, profile.id)
            val pullResult = pullRemoteChangesWithResult(
                userId = userId,
                profile = profile,
                includeUserScoped = includeUserScoped,
                // No push ran, so local rows created since the last push are still
                // unuploaded; they must not fall below the account-switch boundary.
                followsSuccessfulPush = false,
            )
            if (pullResult.isSuccess) {
                val completedPull = pullResult.getOrThrow()
                recordCompletedPull(completedPull)
                outcomes += ProfileSyncOutcome(
                    profileId = profile.id,
                    pushSucceeded = true,
                    pullSucceeded = true,
                    syncTimeEpoch = completedPull.syncTime,
                    pullSyncTime = completedPull.syncTime,
                )
            } else {
                val pullError = pullResult.exceptionOrNull()
                Logger.w("SyncManager") {
                    "Pull retry failed for profile ${profile.id}: ${pullError?.message}"
                }
                outcomes += ProfileSyncOutcome(
                    profileId = profile.id,
                    // Same classification as the ordinary sync path (codex #856 P2), so
                    // combineProfileOutcomes publishes NotAuthenticated.
                    authFailure = isAuthFailure(pullError),
                    pushSucceeded = true,
                    pullSucceeded = false,
                    syncTimeEpoch = lastSync,
                    error = pullError,
                )
                if (isAuthFailure(pullError)) {
                    break
                }
            }
        }
        val minPull = profiles
            .map { tokenStorage.getPullCursor(userId, it.id) }
            .filter { it > 0L }
            .minOrNull() ?: 0L
        tokenStorage.publishMinPullCursor(minPull)
        val combined = combineProfileOutcomes(outcomes)
        // retryPull is pull-only: "pushSucceeded = true" above is a placeholder, so
        // combineProfileOutcomes' PartialSuccess branch would report success even when
        // every pull failed. The caller asked for a pull retry — surface the failure.
        if (combined.isFailure || outcomes.any { it.pullSucceeded }) return combined
        // Every pull failed without an auth failure: publish an error state that agrees
        // with the failed Result instead of combine's PartialSuccess.
        val error = outcomes.firstOrNull { it.error != null }?.error ?: Exception("Pull retry failed")
        _syncState.value = SyncState.Error(error.message ?: "Pull retry failed")
        return Result.failure(error)
    }

    // === Private Helpers ===

    /** Result of a pull whose every page merged (loop ended with `hasMore=false`). */
    private data class CompletedPull(
        /** Portal user whose profile this pull belongs to. */
        val userId: String,
        /** Local profile id the pull was scoped to. */
        val profileId: String,
        /** First page's server `syncTime` minus the overlap; the next pull's cursor. */
        val syncTime: Long,
        /** When true the next pull must be a full pull (external-activity truncation). */
        val forceFullPull: Boolean = false,
    )

    /**
     * Persists one profile's pull cursor and clears only that account/profile-scoped
     * LWW server-wins routine set this pull applied.
     */
    private fun recordCompletedPull(completedPull: CompletedPull) {
        if (completedPull.forceFullPull) {
            Logger.w("SyncManager") {
                "Pull: server truncated external activities (externalActivitiesHasMore=true); " +
                    "next pull for profile ${completedPull.profileId} will be a full pull. " +
                    "Activities beyond the server cap are not delivered."
            }
            tokenStorage.resetPullCursor(completedPull.userId, completedPull.profileId)
        } else {
            tokenStorage.recordCompletedPull(
                completedPull.userId,
                completedPull.profileId,
                completedPull.syncTime,
            )
        }
        tokenStorage.clearPendingServerWinsRoutineIds(completedPull.userId, completedPull.profileId)
    }

    private suspend fun <T> withProfileMutationBarrier(block: suspend () -> T): T =
        profileMutationBarrier?.withExclusive(block) ?: block()

    /**
     * What one push did, beyond the server's response.
     *
     * The caller needs all of it: which local rows actually went out (so it can stamp
     * exactly those the portal accepted), the session DTOs as sent (so a session the
     * portal LWW-rejected can be re-sent once with a timestamp it will take), and the
     * device time the gather started (so a row edited mid-push is left for next time).
     */
    private data class PushOutcome(
        val response: PortalSyncPushResponse,
        val gatherStartedAt: Long,
        /** Portal session id → the local WorkoutSession row ids sent under it. */
        val localRowIdsByPortalSessionId: Map<String, List<String>>,
        val sentSessionsById: Map<String, PortalWorkoutSessionDto>,
        val telemetryByPortalSessionId: Map<String, List<PortalRepTelemetryDto>>,
        /** Portal session ids the portal explicitly acknowledged as applied, across EVERY batch. */
        val acknowledgedPortalSessionIds: Set<String>,
        /**
         * The gather's generation snapshot. A parent settled later (the LWW re-push, or an
         * unchanged-content stamp) is acknowledged against THIS snapshot, so a row edited
         * after the gather keeps a newer generation and stays dirty.
         */
        val workoutSnapshot: WorkoutSyncSnapshot,
        /** Rejections from EVERY batch, not just the last response. */
        val rejections: SyncRejectionsDto,
        val rePushContext: RePushContext,
        /** PR tombstones this push carried across every request, id-less legacy rows included (PR 20). */
        val personalRecordTombstonesSent: Int = 0,
        /** Sum of the portal's `personalRecordsInserted` across every request. */
        val personalRecordsWritten: Int = 0,
    )

    /** The payload envelope fields a post-rejection re-push has to repeat. */
    private data class RePushContext(
        val deviceId: String,
        val platform: String,
        val lastSync: Long,
        val profileId: String,
        val profileName: String,
        /**
         * Dedicated PR rows from the push they accompany. Required on the re-push too:
         * an empty `personalRecords` makes the portal derive id-less rows from every
         * `set.isPr` and INSERT them (PORTAL ROW-DUPLICATION HAZARD).
         */
        val personalRecords: List<PortalPersonalRecordDto>,
        /**
         * routineSessionIds this push treated as one-time repair candidates. Rows of
         * these groups that the portal still rejected get their `updatedAt` cleared so
         * they re-enter the ordinary delta even after the repair cursor has walked past.
         */
        val repairGroupIds: Set<String>,
    )

    /** PR 10 step 3 (R-14): user-scoped singletons, gathered only for the active profile. */
    private data class UserScopedDtos(
        val rpgDto: PortalRpgAttributesSyncDto?,
        val badgeDtos: List<PortalEarnedBadgeSyncDto>,
        val gamStatsDto: PortalGamificationStatsSyncDto?,
    )

    private suspend fun gatherUserScopedDtos(
        userId: String,
        profileId: String,
        includeUserScoped: Boolean,
    ): UserScopedDtos {
        if (!includeUserScoped) return UserScopedDtos(null, emptyList(), null)
        val rpgDto = gamificationRepository.getRpgInput(profileId)?.let { input ->
            val rpgProfile = RpgAttributeEngine.computeProfile(input)
            PortalRpgAttributesSyncDto(
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
        }
        val badgeDtos = gamificationRepository.getEarnedBadges(profileId).first().map { earned ->
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
        val gamStatsDto = syncRepository.getGamificationStatsForSync(profileId)?.let { stats ->
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
        return UserScopedDtos(rpgDto, badgeDtos, gamStatsDto)
    }

    /** External activities, phase stats, assessments, custom exercises and VBT estimates. */
    private data class PushEnrichment(
        val externalActivityDtos: List<ExternalActivitySyncDto>,
        val phaseStatsBySessionId: Map<String, List<PortalPhaseStatisticsDto>>,
        val assessmentDtos: List<PortalAssessmentResultDto>,
        val customExerciseDtos: List<CustomExerciseSyncDto>,
        val velocityEstimatesByExerciseId: Map<String, List<PortalSyncAdapter.VelocityOneRepMaxPoint>>,
    )

    private suspend fun gatherPushEnrichment(
        profileId: String,
        activeProfile: UserProfile?,
        workoutSnapshot: WorkoutSyncSnapshot,
        pendingDeletion: Boolean,
    ): PushEnrichment {
        // 5b. External activities (paid users only)
        val localPaid = activeProfile?.subscriptionStatus == SubscriptionStatus.ACTIVE
        val portalPaid = tokenStorage.currentUser.value?.isPremium == true
        val isPremium = localPaid || portalPaid
        // A pending-deletion profile (PR 20) only sends its tombstones: none of its
        // remaining live profile data may be uploaded on its way out.
        val externalActivityDtos = if (isPremium && !pendingDeletion) {
            // F018 (deferred): getUnsyncedActivities intentionally excludes deletion
            // tombstones (deletedAt set). Neither ExternalActivitySyncDto nor the
            // portal mobile-sync-push handler carries a deletion field today, so
            // including tombstones here would make the server re-create the deleted
            // activity. Syncing external-activity deletions requires a coordinated
            // wire + Edge Function change before the query can return tombstones.
            externalActivityRepository.getUnsyncedActivities(profileId).map { activity ->
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
        val assessmentDtos = if (pendingDeletion) {
            emptyList()
        } else {
            syncRepository.getAllAssessments(profileId)
                .map { PortalSyncAdapter.toPortalAssessmentResult(it) }
        }
        // Send all custom catalog rows, not just rows modified since the push watermark.
        // Older portal-sync builds never sent this field, so existing custom
        // exercise IDs may be missing remotely even after a successful sync.
        val customExerciseDtos = syncRepository.getCustomExercisesModifiedSince(0L)

        // 7. Build the velocity-based 1RM map (catalog exerciseId → its passing
        // per-cable estimates, oldest first). Looked up here (suspend context with
        // repo + profile) so the pure adapter just attaches the value. Distinct from
        // the rep-based estimate the adapter computes inline.
        //
        // Single query for the whole profile (getAllPassing) instead of one
        // getLatestPassing per distinct exercise — avoids an N+1 during full sync.
        // The whole passing history is kept, ordered the same way getLatestPassing
        // orders it (computedAt, then id as the tie-break), so the adapter can attach
        // the estimate that was current when each session was recorded instead of
        // stamping today's number onto last year's workout. The adapter only reads
        // keys for exercises actually in this push, so a superset map is harmless.
        val velocityEstimatesByExerciseId = velocityOneRepMaxRepository
            .getAllPassing(profileId)
            .sortedWith(compareBy({ it.computedAt }, { it.id }))
            .groupBy { it.exerciseId }
            .mapValues { (_, estimates) ->
                estimates.map {
                    PortalSyncAdapter.VelocityOneRepMaxPoint(it.computedAt, it.estimatedPerCableKg)
                }
            }
            .toMap()

        return PushEnrichment(
            externalActivityDtos = externalActivityDtos,
            phaseStatsBySessionId = phaseStatsBySessionId,
            assessmentDtos = assessmentDtos,
            customExerciseDtos = customExerciseDtos,
            velocityEstimatesByExerciseId = velocityEstimatesByExerciseId,
        )
    }

    /**
     * Push one profile's local changes. PR 10 steps 1/3/4/8/10/11: the profile is
     * passed in (never read from `userProfileRepository.activeProfile`), user-scoped
     * singletons (RPG/gamification/badges) go out only when [includeUserScoped], and
     * [repairPushActive] re-sends routines/cycles/PRs + tombstones from 0. The delta
     * watermark is this profile's device-clock `pushWatermark`, captured before gather.
     */
    private suspend fun pushLocalChanges(
        userId: String,
        profile: UserProfile,
        includeUserScoped: Boolean,
        repairPushActive: Boolean,
        pendingDeletion: Boolean,
    ): Result<PushOutcome> {
        // Device time taken BEFORE anything is gathered. Two things key off it: the
        // post-push stamp skips rows edited after it (they hold data the portal never
        // saw), and a routine group with unsent rows is sent with at least this
        // timestamp so the portal's server-clock LWW gate accepts it.
        val gatherStartedAt = currentTimeMillis()
        val deviceId = tokenStorage.getDeviceId()
        // PR 10 step 4: this profile's device-clock push watermark, not a global lastSync.
        // Rows stamped after it stay in the next delta; routines/cycles/PRs modified
        // after it are the only ones the ordinary delta sends.
        val pushWatermark = tokenStorage.getPushWatermark(userId, profile.id)
        // Step 8 repair push: gather routines/cycles/PRs + tombstones from 0 once per user.
        // A pending-deletion profile (PR 20) is finalized after this push, so every routine
        // and PR tombstone must ride it even if the device clock moved behind the stored
        // watermark (a strict `> watermark` gather would otherwise skip them for good).
        val repairFrom = if (repairPushActive || pendingDeletion) 0L else pushWatermark
        val platform = getPlatformName()
        userProfileRepository.ensureDefaultProfile()
        // Profile metadata for another portal account's profiles must not be published
        // into this account either (same rule as syncProfileOrder).
        val allProfiles = (
            userProfileRepository.allProfiles.value +
                // PR 20: a pending-deletion profile stays listed until its own push lands.
                userProfileRepository.pendingDeletionProfiles.value
            )
            .distinctBy { it.id }
            .filter { isSyncableByPortalUser(it, userId) }
        val activeProfile = profile
        val activeProfileId = profile.id

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

        // PR 11: rows this portal user must never upload (account-switch exclusions).
        // Applied per entity because cycles / custom exercises / assessments push with
        // no delta, so a cursor alone cannot keep a pre-switch row out of the payload.
        val exclusions = SyncExclusionFilter.load(syncRepository, userId)

        // 1. Freeze the dirty workout generation snapshot, expanding each dirty
        // portal parent to all live component rows before any payload is built.
        // The snapshot is already group-complete (every live row under a dirty portal
        // parent). PR 8's group expansion below then adds repair rows and applies the
        // hold guard on top of it.
        val workoutSnapshot = syncRepository.getDirtyWorkoutSnapshot(activeProfileId)
        val delta = dedupeWorkoutSessionsById(
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

        // 1a. Group-complete gather (AF-3). One portal workout = every mobile row that
        // shares a routineSessionId, and the portal's replace_session_children DELETEs
        // all of a workout's exercises before re-inserting the payload. Sending only the
        // changed rows therefore deletes the sets pushed by earlier syncs — which, with
        // one sync per completed set, is every set but the last. Re-gather the whole
        // group whenever any of its rows is in the delta.
        val deltaGroupIds = delta.mapNotNullTo(linkedSetOf()) { it.routineSessionId }
        val repairGroupIds = routineGroupRepairCandidates(activeProfileId)
        // Repair only: a childless row cannot be rebuilt from this device, and the
        // repair's whole job is to restore portal history — sending the group would
        // replace that sibling's portal sets with an empty exercise and destroy the
        // portal's copy of its rep summaries (the last surviving copy, for rows F-001's
        // cascade stripped locally). Skip those groups here, but still walk the cursor
        // past them via the full `repairGroupIds` map below so the one-time repair
        // terminates. The ordinary delta is deliberately NOT filtered this way: a
        // manual or 0-rep set has no children and must still push.
        val childlessRepairGroupIds = if (repairGroupIds.isEmpty()) {
            emptySet()
        } else {
            syncRepository.getRoutineGroupsWithChildlessRows(repairGroupIds.keys, activeProfileId)
        }
        if (childlessRepairGroupIds.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Routine group repair: skipping ${childlessRepairGroupIds.size} group(s) that hold a row with " +
                    "no local measurements — re-sending them would rebuild that sibling as an empty exercise " +
                    "and destroy its portal rep summaries. Groups: " +
                    childlessRepairGroupIds.take(10).joinToString()
            }
        }
        val groupIds = deltaGroupIds + (repairGroupIds.keys - childlessRepairGroupIds)

        // 1b. Hold, never split. A sibling this device only pulled and has no
        // measurements for cannot be rebuilt from local data, so the group can only be
        // sent incomplete — and that deletes the sibling's exercise, sets and telemetry
        // on the portal. Hold the whole group instead: the portal keeps what it has and
        // the rows are not stamped this sync. A held row is retried on a later sync only
        // while its `updatedAt IS NULL` (or is newer than the push watermark) — a previously
        // stamped sibling of a permanently held group is not. In normal use this cannot
        // lose an upload, because every workout gets a fresh routineSessionId.
        val blockedSiblingsByGroup = syncRepository.getBlockedRoutineGroupSiblings(groupIds, activeProfileId)
        val heldGroupIds = blockedSiblingsByGroup.keys
        if (heldGroupIds.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Push payload: holding ${heldGroupIds.size} routine group(s) — a sibling was pulled from " +
                    "the portal and this device has no rep data for it, so sending the group would delete " +
                    "that sibling's portal sets. Groups/blocking rows: " +
                    blockedSiblingsByGroup.entries.take(10).joinToString { (group, blocking) ->
                        "$group<-[${blocking.joinToString()}]"
                    }
            }
        }

        val pushableGroupIds = groupIds - heldGroupIds
        val groupSiblings = syncRepository.getWorkoutSessionsByRoutineSessionIds(
            pushableGroupIds,
            activeProfileId,
        )
        val deltaIds = delta.mapTo(HashSet()) { it.id }
        val heldDeltaRowCount = delta.count { it.routineSessionId in heldGroupIds }
        if (heldDeltaRowCount > 0) {
            Logger.w("SyncManager") {
                "Push payload: $heldDeltaRowCount local row(s) are not stamped because their routine group is held " +
                    "(retried on a later sync only while their updatedAt is NULL or newer than the push watermark)"
            }
        }
        val sessions = dedupeWorkoutSessionsById(
            delta.filter { it.routineSessionId !in heldGroupIds } + groupSiblings,
            context = "Push payload (group-complete)",
        ).filter { session ->
            !exclusions.excludesWorkout(session.id, session.routineSessionId)
        }
        if (sessions.size > delta.size - heldDeltaRowCount) {
            Logger.d("SyncManager") {
                "Push payload: re-gathered ${sessions.size - (delta.size - heldDeltaRowCount)} routine sibling row(s) " +
                    "across ${pushableGroupIds.size} group(s) so no portal workout is sent partially"
            }
        }

        // Dedicated PR rows need the full snapshot so stable UUID, updatedAt, and
        // deletedAt all reach the portal. Reuse that same projection for legacy
        // set-level PR hints rather than reading a lossy second delta.
        val recentPRs = syncRepository.getFullPRsModifiedSince(repairFrom, activeProfileId)
            .filter { record ->
                !exclusions.excludesPersonalRecord(record.id.toString()) &&
                    (record.uuid == null || !exclusions.excludesPersonalRecord(record.uuid))
            }
        val prBySessionKey = recentPRs.groupBy { pr ->
            personalRecordSessionKey(pr.exerciseId, pr.timestamp)
        }
        val sessionIdByDeltaPrKey = sessions.mapNotNull { session ->
            val exerciseId = session.exerciseId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // The PORTAL workout id: buildPortalSession publishes a routine set under
            // its parent routineSessionId (the component id becomes an exercise id), so
            // a PR linked to the component id would reference no pushed workout and the
            // portal would null its session_id (codex 4081208473).
            personalRecordSessionKey(exerciseId, session.timestamp) to
                (session.routineSessionId?.takeIf { it.isNotBlank() } ?: session.id)
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
        // Telemetry gate decided BEFORE the rep-metric load: below Inferno the push
        // needs only the scalar summaries, and `getRepMetrics` deserializes the 50 Hz
        // curve arrays that are by far the bulk of a RepMetric row.
        val tier = tokenStorage.getSubscriptionTier()
        val telemetryAllowed = tier == TELEMETRY_SYNC_TIER
        // Prefer the gather's already-loaded maps. Dirty sessions arrive with their
        // RepMetric/CompletedSet children in the snapshot; only repair siblings (rows
        // pulled in by getWorkoutSessionsByRoutineSessionIds, outside the dirty set)
        // need a second lookup. Below Inferno we project summaries from the snapshot's
        // rows rather than calling getRepMetrics — R-5 forbids loading the 50 Hz curves
        // a second time, not summarising rows the gather already holds.
        val snapshotRepsBySessionId = workoutSnapshot.repMetricsByComponentId
        val snapshotCompletedSetsBySessionId = workoutSnapshot.completedSetsByComponentId
        val missingCompletedSetSessionIds = sessions
            .map { it.id }
            .filter { it !in snapshotCompletedSetsBySessionId }
        val completedSetsBySessionId =
            snapshotCompletedSetsBySessionId + logicalSetCompletedSetsBySessionId(missingCompletedSetSessionIds)
        val sessionsWithReps = sessions.map { session ->
            val snapshotReps = snapshotRepsBySessionId[session.id].orEmpty()
            val repMetrics = if (telemetryAllowed) {
                snapshotReps.ifEmpty { repMetricRepository.getRepMetrics(session.id) }
            } else {
                emptyList()
            }
            val repSummaries = when {
                repMetrics.isNotEmpty() -> repMetrics.map { it.toSummary() }
                snapshotReps.isNotEmpty() -> snapshotReps.map { it.toSummary() }
                else -> repMetricRepository.getRepMetricSummaries(session.id)
            }
            val sessionKey = session.exerciseId
                ?.takeIf { it.isNotBlank() }
                ?.let { exerciseId -> personalRecordSessionKey(exerciseId, session.timestamp) }
            // Set-level PR flags mirror `CompletedSet.is_pr` exactly (F-058): the
            // COMBINED weight/volume records only. A phase (peak-force) break is a
            // different metric; letting it set `isPr` made the portal's legacy
            // set-derived row land as MAX_WEIGHT/CONCENTRIC valued at the commanded
            // load rather than the peak force. The phase records still reach the
            // portal in full through the dedicated `personalRecords` array below.
            val prRecords = sessionKey
                ?.let { prBySessionKey[it] }
                ?.filter { it.phase == WorkoutPhase.COMBINED }
                ?: emptyList()

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
                repSummaries = repSummaries,
                muscleGroup = muscleGroup,
                isPr = prRecords.isNotEmpty(),
                prRecords = prRecords,
                logicalSetIdentity = logicalSetIdentityFor(
                    session,
                    completedSetsBySessionId[session.id].orEmpty(),
                ),
                isPendingUpload = session.id in deltaIds,
            )
        }
        // Each PR is paired with the session key it belongs to so its DTO carries the
        // portal session id it was set in (PR↔session link).
        val personalRecordDtosByPrKey = recentPRs.map { pr ->
            val sessionKey = personalRecordSessionKey(pr.exerciseId, pr.timestamp)
            val muscleGroup =
                syncRepository.getExerciseMuscleGroup(pr.exerciseId, pr.exerciseName)
                    ?: "General"
            sessionKey to PortalSyncAdapter.toPortalPersonalRecord(
                record = pr,
                sessionId = sessionIdByPrKey[sessionKey],
                muscleGroup = muscleGroup,
            )
        }
        val personalRecordDtos = personalRecordDtosByPrKey.map { it.second }

        // 4. Gather routines as full domain objects, but only ship canonical UUID IDs.
        // Local template-derived cycle routines use "cycle_routine_<uuid>" and must never
        // reach the server's UUID ownership checks.
        val rawRoutines = syncRepository.getFullRoutinesModifiedSince(repairFrom, activeProfileId)
        val routines = rawRoutines.filter { routine ->
            CANONICAL_UUID_REGEX.matches(routine.id) && !exclusions.excludesRoutine(routine.id)
        }
        val droppedRoutineCount = rawRoutines.size - routines.size
        if (droppedRoutineCount > 0) {
            Logger.w("SyncManager") {
                "Push payload: dropped $droppedRoutineCount non-UUID routines before send"
            }
        }

        // 4a. Gather soft-deleted routine IDs for server-side deletion propagation.
        val deletedRoutineIds = syncRepository.getDeletedRoutineIdsSince(repairFrom, activeProfileId)
            .filter { CANONICAL_UUID_REGEX.matches(it) && !exclusions.excludesRoutine(it) }
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

        // 4b. Freeze dirty complete-cycle generations before payload construction. Each
        // cycle carries its stored server_updated_at as baseUpdatedAt for the portal merge.
        // Cycle days may still point at local-only template routines that are hidden from
        // the main routines list via the "cycle_routine_<uuid>" prefix. Null those
        // references for server push so one bad local ID cannot fail the entire sync.
        val cycleSnapshot = if (repairPushActive) {
            syncRepository.getAllCyclesForRepair(activeProfileId)
        } else {
            syncRepository.getDirtyCycleSnapshot(activeProfileId)
        }
        val rawCyclesWithContext = cycleSnapshot.cycles
            .filter { ctx -> !exclusions.excludesCycle(ctx.cycle.id) }
        var droppedCycleRoutineRefs = 0
        val cyclesWithContext = rawCyclesWithContext.map { ctx ->
            val sanitizedDays = ctx.cycle.days.map { day ->
                val routineId = day.routineId
                if (routineId != null &&
                    (!CANONICAL_UUID_REGEX.matches(routineId) || exclusions.excludesRoutine(routineId))
                ) {
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

        // 5. Gather gamification data. PR 10 step 3 (R-14): RPG/gamification/badges are
        // one row per portal user, so they go out only with the active profile's push.
        val userScoped = gatherUserScopedDtos(userId, activeProfileId, includeUserScoped)
        // PR 11 (codex #859): RPG attributes and gamification stats are aggregates of this
        // device's workout history. While any workout on the device is excluded from this
        // account (another account's history), those aggregates are not this account's and
        // are not pushed. Badges earned under another account are excluded one by one.
        val aggregatesIncludeForeignHistory = exclusions.workouts.isNotEmpty()
        val rpgDto = userScoped.rpgDto.takeUnless { aggregatesIncludeForeignHistory }
        val badgeDtos = userScoped.badgeDtos.filter { !exclusions.excludesEarnedBadge(it.badgeId) }
        val gamStatsDto = userScoped.gamStatsDto.takeUnless { aggregatesIncludeForeignHistory }

        // A pending-deletion profile (PR 20) uploads no live enrichment on its way out.
        val enrichment = gatherPushEnrichment(activeProfileId, activeProfile, workoutSnapshot, pendingDeletion)
        // External activities (imported health data) follow the same account-switch
        // exclusions as every other pushed entity (codex #859).
        val externalActivityDtos = enrichment.externalActivityDtos.filter { activity ->
            !exclusions.excludesExternalActivity(activity.id)
        }
        val phaseStatsBySessionId = enrichment.phaseStatsBySessionId
        val assessmentDtos = enrichment.assessmentDtos.filter { assessment ->
            !exclusions.excludesAssessment(assessment.id.toString())
        }
        val customExerciseDtos = enrichment.customExerciseDtos.filter { exercise ->
            !exclusions.excludesCustomExercise(exercise.clientId)
        }
        val velocityEstimatesByExerciseId = enrichment.velocityEstimatesByExerciseId

        // Gate telemetry push behind the Inferno tier (decided above, before the rep
        // load and the DTO build). Force-curve / 50 Hz session replay is an Inferno-only
        // feature per the subscription matrix. Other tiers (Ember, Flame) and users
        // whose tier is unresolved (offline login, network error during subscription
        // check) fail closed — no telemetry on the wire. Rep summaries still ship in
        // `sessions` regardless of tier so Ember/Flame users get full history, PRs, and
        // analytics without the raw per-sample payload that blows past the server cap.
        // When Inferno launches, this gate automatically opens for those subscribers
        // with no further code changes.

        // 7a. Notes the portal already holds for these workouts. The portal's session
        // upsert writes notes = EXCLUDED.notes on every accepted push, so a push that
        // omits them deletes a note written on the website (AF-4).
        val portalSessionIds = sessions.mapTo(linkedSetOf()) { it.routineSessionId ?: it.id }
        val notesByPortalSessionId = syncRepository.getSessionNotesForIds(portalSessionIds)

        // 7b. Build portal session + telemetry DTOs (telemetry setIds match generated exercise set IDs)
        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(
            sessionsWithReps,
            userId,
            velocityEstimatesByExerciseId,
            notesByPortalSessionId = notesByPortalSessionId,
            groupPushFloorEpochMs = gatherStartedAt,
            includeTelemetry = telemetryAllowed,
        )
        val effectiveTelemetry = buildResult.telemetry
        // Main's post-build notes floor: a note newer than the session's own updatedAt
        // must push the session's updatedAt forward so the portal's LWW gate takes the
        // note. PR 8's adapter already filled `notes` from notesByPortalSessionId; this
        // only raises updatedAt when SessionNotes is newer.
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
        if (!telemetryAllowed) {
            Logger.d("SyncManager") {
                "Telemetry push gated off: tier=${tier ?: "unknown"} " +
                    "($TELEMETRY_SYNC_TIER required); rep summaries still sync."
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
        // Every profile name on the wire is bounded: an unbounded name in the request
        // envelope would make even an empty request oversized (codex #856 P2).
        val payloadProfileName = boundedProfileName(activeProfile?.name ?: "Default")
        val profileDtos = allProfiles
            .map { LocalProfileDto(it.id, boundedProfileName(it.name), it.colorIndex) }
            .let { dtos ->
                if (activeProfile != null && dtos.none { it.id == activeProfile.id }) {
                    dtos + LocalProfileDto(activeProfile.id, boundedProfileName(activeProfile.name), activeProfile.colorIndex)
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
        // allProfiles is all-or-nothing: the portal deletes every registered profile missing
        // from it, so it can never be trimmed. When the whole list cannot fit a request it is
        // omitted everywhere this sync (the portal then upserts each request's profileId and
        // deletes nothing), instead of wedging every request that carries it.
        val sendableProfileDtos: List<LocalProfileDto>? = profileDtos.takeIf {
            PushPlanner.fits(
                PortalSyncPayload(
                    deviceId = deviceId,
                    platform = platform,
                    lastSync = pushWatermark,
                    profileId = payloadProfileId,
                    profileName = payloadProfileName,
                    allProfiles = it,
                ),
            )
        }
        if (sendableProfileDtos == null) {
            Logger.w("SyncManager") {
                "Push: profile metadata for ${profileDtos.size} profile(s) exceeds the push size cap; " +
                    "sending without allProfiles this sync"
            }
        }

        var lastResponse: PortalSyncPushResponse? = null
        val acknowledgedPortalSessionIds = linkedSetOf<String>()

        // Durable mutations (ownership transfers, workout and cycle deletions) commit
        // before ordinary uploads. Extracted to keep pushLocalChanges under the JVM
        // 64 KB method limit.
        val durableResult = pushDurableMutations(
            userId = userId,
            deviceId = deviceId,
            platform = platform,
            lastSync = pushWatermark,
            payloadProfileId = payloadProfileId,
            payloadProfileName = payloadProfileName,
            profileDtos = sendableProfileDtos,
            allProfiles = allProfiles,
            pendingOwnershipTransfers = pendingOwnershipTransfers,
            pendingWorkoutDeletions = pendingWorkoutDeletions,
            pendingCycleDeletions = pendingCycleDeletions,
        )
        if (durableResult.isFailure) return Result.failure(durableResult.exceptionOrNull()!!)
        durableResult.getOrNull()?.let { lastResponse = it }

        // 8. Chunked push -- batch sessions to stay under Edge Function body limit (~1 MB)
        //    AND under the server-side rep_telemetry array cap (MAX_TELEMETRY_PER_BATCH),
        //    and every request under SyncConfig.MAX_PAYLOAD_BYTES and the 10,000-item
        //    per-array cap (codex #856: a large one-time repair must not become one
        //    permanently oversized request).
        //    Non-session data rides on the final session batch when it fits (the common
        //    case). Otherwise it moves to trailing requests AFTER every session batch —
        //    routines, then deleted-routine ids, then cycles (whose days reference
        //    routines), then PRs (whose session_id must already exist on the portal) —
        //    each packed under both caps; the single-valued fields go on the last one.
        //    An item that cannot fit in any request is skipped and logged: it is never
        //    sent, never stamped and never acknowledged (see PushPlanner).
        //    IMPORTANT: We do NOT advance the push watermark until ALL requests succeed.
        val allSessions = portalSessions
        val telemetryCountBySessionId = allSessions.associate { session ->
            val count = (sessionSetIds[session.id] ?: emptySet()).sumOf { setId ->
                telemetryBySetId[setId]?.size ?: 0
            }
            session.id to count
        }
        fun telemetryFor(sessions: List<PortalWorkoutSessionDto>) = sessions.flatMap { session ->
            (sessionSetIds[session.id] ?: emptySet()).flatMap { setId -> telemetryBySetId[setId] ?: emptyList() }
        }
        fun phaseStatsFor(sessions: List<PortalWorkoutSessionDto>) = sessions.flatMap { session ->
            phaseStatsBySessionId[session.id] ?: emptyList()
        }
        // Every session batch carries a non-empty `personalRecords` when any exist (PORTAL
        // ROW-DUPLICATION HAZARD: an empty list makes the portal derive id-less rows from
        // every set.isPr and INSERT them). Up to PR_FULL_LIST_PER_BATCH the whole list goes
        // on every batch, as before. Beyond that, a batch carries the PRs of its own
        // sessions (or one PR as the suppressor); the rest ride the trailing requests.
        // The portal upserts dedicated PR rows on id, so a re-send is a no-op.
        fun prsForSessionBatch(sessions: List<PortalWorkoutSessionDto>): List<PortalPersonalRecordDto> {
            if (personalRecordDtos.size <= PR_FULL_LIST_PER_BATCH) return personalRecordDtos
            val ids = sessions.mapTo(hashSetOf()) { it.id }
            return personalRecordDtos.filter { it.sessionId in ids }
                .ifEmpty { personalRecordDtos.take(1) }
        }
        fun sessionBatchPayload(sessions: List<PortalWorkoutSessionDto>) = PortalSyncPayload(
            deviceId = deviceId,
            platform = platform,
            lastSync = pushWatermark,
            sessions = sessions,
            telemetry = telemetryFor(sessions),
            phaseStatistics = phaseStatsFor(sessions),
            profileId = payloadProfileId,
            profileName = payloadProfileName,
            personalRecords = prsForSessionBatch(sessions),
        )
        // Items too large for any request are skipped (logged at warn with type and id by
        // PushPlanner) and counted here so the push summary makes them discoverable.
        val skippedOversized = mutableListOf<String>()
        val onSkip = PushPlanner.SkipListener { type, id -> skippedOversized += "$type $id" }
        val sessionBatches = PushPlanner.splitSessionBatchesByBytes(
            planSessionBatches(allSessions, telemetryCountBySessionId).filter { it.isNotEmpty() },
            ::sessionBatchPayload,
            onSkip,
        )
        val deliverableSessions = sessionBatches.flatten()
        val deliverableSessionIds = deliverableSessions.mapTo(hashSetOf()) { it.id }

        // Everything that is not a session, as the final request would carry it.
        fun finalFields(
            base: PortalSyncPayload,
            routines: List<PortalRoutineSyncDto>,
            deletedRoutines: List<String>,
            cycles: List<PortalTrainingCycleSyncDto>,
            prs: List<PortalPersonalRecordDto>,
            includeSingletons: Boolean,
        ) = base.copy(
            routines = routines,
            deletedRoutineIds = deletedRoutines,
            cycles = cycles,
            personalRecords = prs,
            rpgAttributes = if (includeSingletons) rpgDto else null,
            badges = if (includeSingletons) badgeDtos else emptyList(),
            gamificationStats = if (includeSingletons) gamStatsDto else null,
            assessments = if (includeSingletons) assessmentDtos else emptyList(),
            customExercises = if (includeSingletons) customExerciseDtos else emptyList(),
            allProfiles = if (includeSingletons) sendableProfileDtos else null,
            externalActivities = if (includeSingletons) externalActivityDtos else emptyList(),
        )
        val emptyEnvelope = sessionBatchPayload(emptyList()).copy(personalRecords = emptyList())
        val lastSessions = sessionBatches.lastOrNull().orEmpty()
        val lastSessionBase = sessionBatchPayload(lastSessions)
        val combinedFinal = finalFields(
            base = lastSessionBase,
            routines = routineDtos,
            deletedRoutines = deletedRoutineIds,
            cycles = cycleDtos,
            prs = personalRecordDtos,
            includeSingletons = true,
        )
        val requests: List<PortalSyncPayload> = if (PushPlanner.fits(combinedFinal)) {
            sessionBatches.dropLast(1).map(::sessionBatchPayload) + combinedFinal
        } else {
            val prsAlreadyOnSessionBatches = if (personalRecordDtos.size <= PR_FULL_LIST_PER_BATCH) {
                sessionBatches.isNotEmpty()
            } else {
                false
            }
            val tails = PushPlanner.planTailRequests(
                envelope = emptyEnvelope,
                routines = routineDtos,
                deletedRoutineIds = deletedRoutineIds,
                cycles = cycleDtos,
                personalRecords = if (prsAlreadyOnSessionBatches) emptyList() else personalRecordDtos,
                customExercises = customExerciseDtos,
                assessments = assessmentDtos,
                badges = badgeDtos,
                externalActivities = externalActivityDtos,
                rpgAttributes = rpgDto,
                gamificationStats = gamStatsDto,
                allProfiles = sendableProfileDtos,
                onSkip = onSkip,
            )
            sessionBatches.map(::sessionBatchPayload) + tails
        }
        val totalBatches = requests.size.coerceAtLeast(1)
        // What actually goes out: anything the planner skipped is never stamped or acked.
        val sentPrDtos = requests.flatMapTo(hashSetOf()) { it.personalRecords }
        val sentRoutineIds = requests.flatMapTo(hashSetOf()) { r -> r.routines.map { it.id } }

        Logger.d("SyncManager") {
            "Pushing portal payload: ${allSessions.size} sessions ($totalBatches request(s)), " +
                "${effectiveTelemetry.size} telemetry points, " +
                "${routineDtos.size} routines, ${cycleDtos.size} cycles, " +
                "${customExerciseDtos.size} custom exercises, " +
                "${personalRecordDtos.size} personal records, " +
                "${phaseStatsBySessionId.size} sessions with phase stats, " +
                "${assessmentDtos.size} assessments"
        }

        // Rejections from EVERY request. Reading only the last response's list would
        // silently stamp rows an earlier batch's rejection covered.
        val collectedRejections = mutableListOf<SyncRejectionsDto>()
        var personalRecordTombstonesSent = 0
        var personalRecordsWritten = 0
        val skippedDeletedRoutines = linkedSetOf<String>()
        val skippedDeletedCycles = linkedSetOf<String>()
        requests.forEachIndexed { index, payload ->
            Logger.i("SyncManager") {
                "Sync request ${index + 1}/$totalBatches: ${payload.sessions.size} sessions, " +
                    "${payload.routines.size} routines, ${payload.cycles.size} cycles, " +
                    "${payload.personalRecords.size} personal records"
            }
            rejectDuplicatePushPayloadKeys(payload)?.let { return Result.failure(it) }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                val batchSessionIds = payload.sessions.map { it.id }.take(3)
                val batchSummary = "sessions=${payload.sessions.size}, " +
                    "ids=[${batchSessionIds.joinToString()}${if (payload.sessions.size > 3) "..." else ""}]"

                Logger.e("SyncManager") {
                    "Request ${index + 1}/$totalBatches failed: ${error?.message} | $batchSummary"
                }

                // Track retry attempts for this specific request to prevent retry storms.
                if (totalBatches > 1) {
                    val batchHash = (
                        payload.sessions.map { it.id } + payload.routines.map { it.id } +
                            payload.cycles.map { it.id } + payload.personalRecords.map { it.id }
                        ).hashCode()
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
                        consecutiveFullRetries = 1
                        lastFailedBatchHash = batchHash
                    }
                }

                // CRITICAL: Do NOT advance the push watermark on failure.
                // All requests must succeed before we advance the timestamp.
                return Result.failure(result.pushError())
            }

            val response = result.getOrThrow()
            lastResponse = response
            collectedRejections += response.rejections
            // Counted, not collected by id: a legacy PR without a uuid is sent with a null id.
            // A pending profile's PR rows ride disjoint trailing requests, so a count is exact.
            personalRecordTombstonesSent += payload.personalRecords.count { it.deletedAt != null }
            personalRecordsWritten += response.personalRecordsInserted
            skippedDeletedRoutines += response.skippedDeleted.routines
            skippedDeletedCycles += response.skippedDeleted.cycles
            acknowledgedPortalSessionIds += acknowledgeAcceptedWorkoutParents(
                workoutSnapshot = workoutSnapshot,
                sentPortalSessionIds = payload.sessions.mapTo(linkedSetOf()) { it.id },
                response = response,
            )
            if (payload.cycles.isNotEmpty()) {
                acknowledgeAcceptedCycles(
                    cycleSnapshot = cycleSnapshot,
                    sentCycleIds = payload.cycles.mapTo(linkedSetOf()) { it.id },
                    response = response,
                )
            }
            // External activities are acknowledged from the response of the request that
            // carried them (they may be split across requests).
            if (payload.externalActivities.isNotEmpty()) {
                acknowledgeExternalActivities(payload.externalActivities, response, activeProfileId)
            }
            Logger.d("SyncManager") {
                "Request ${index + 1}/$totalBatches pushed successfully (timestamp deferred)"
            }
        }
        // Every request succeeded - reset retry tracking
        consecutiveFullRetries = 0
        lastFailedBatchHash = null
        val skippedDeleted = SkippedDeletedDto(
            routines = skippedDeletedRoutines.toList(),
            cycles = skippedDeletedCycles.toList(),
        )
        if (skippedOversized.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Push summary: ${skippedOversized.size} oversized item(s) skipped (too large for any request): " +
                    skippedOversized.take(20).joinToString() + if (skippedOversized.size > 20) ", ..." else ""
            }
        }

        // Without allProfiles only this push's profileId was registered on the portal.
        // A pending-deletion profile (PR 20) stays in the metadata for tombstone routing, but
        // its live preference sections must not be uploaded on its way out.
        val pendingDeletionIds = userProfileRepository.pendingDeletionProfiles.value.mapTo(hashSetOf()) { it.id }
        val sentMetadataProfileIds = (
            sendableProfileDtos?.mapTo(linkedSetOf()) { it.id }
                ?: linkedSetOf(payloadProfileId)
            ).filterTo(linkedSetOf()) { it !in pendingDeletionIds }
        pushDirtyProfilePreferences(
            deviceId = deviceId,
            platform = platform,
            lastSync = pushWatermark,
            sentMetadataProfileIds = sentMetadataProfileIds,
        )

        // Mark external activities as synced based on server acknowledgement.
        // Only mark activities the server confirmed it persisted — prevents silently
        // dropping activities that the server soft-failed on.
        val finalResponse = lastResponse ?: return Result.failure(
            PortalApiException("Push produced no response", null, 500),
        )
        // Routines/cycles the server skipped because they were deleted there
        // (PR 16 `skippedDeleted`). Delete the local copy so they stop being pushed.
        // Non-fatal: the next pull reports the same ids via deletedRoutineIds/deletedCycleIds.
        skippedDeleted.let { skipped ->
            try {
                applyServerDeletions(
                    ownerUserId = userId,
                    syncProfileId = activeProfile?.id,
                    routineIds = skipped.routines,
                    cycleIds = skipped.cycles,
                    lastSync = pushWatermark,
                    source = "push skippedDeleted",
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(e) { "Applying push skippedDeleted failed; next pull will retry the delete" }
            }
        }

        // Stamp pushed PRs (Issue #528) so getFullPRsModifiedSince doesn't keep
        // re-shipping the same rows on every push. Re-use the exact recentPRs
        // collected for this payload, deduped by id, and stamp only after the
        // server confirmed the push. This gives PersonalRecord rows the same
        // post-confirmation resend protection that WorkoutSession rows get from
        // the caller's post-push stamping block.
        // Only PRs a request actually carried: one skipped as oversized was never sent.
        val pushedPrIds = recentPRs
            .filterIndexed { index, pr ->
                pr.deletedAt == null && pr.id >= 0L && personalRecordDtos[index] in sentPrDtos
            }
            .map { it.id }
            .distinct()
        if (pushedPrIds.isNotEmpty()) {
            // Stamp with gatherStartedAt, the value that becomes this profile's push
            // watermark: the next gather selects `updatedAt > watermark`, so a `now` stamp
            // would re-select and re-stamp the same PR on every sync (codex #856 P2). The
            // query skips rows edited after the gather, so those are re-sent.
            syncRepository.updatePersonalRecordTimestamp(
                prIds = pushedPrIds,
                timestamp = gatherStartedAt,
                gatherStartedAt = gatherStartedAt,
            )
            Logger.d("SyncManager") {
                "Stamped ${pushedPrIds.size} pushed personal records with updatedAt=$gatherStartedAt"
            }
        }

        // Routines with no updatedAt (backup-restored / legacy) match `updatedAt IS NULL`
        // on every gather. Stamp the ones this push delivered and the portal did not turn
        // away (LWW-rejected, or skipped because it deleted them) so they stop re-sending.
        val unacceptedRoutineIds = collectedRejections.flatMapTo(mutableSetOf()) { rejections ->
            rejections.routines.map { it.id }
        } + skippedDeleted.routines
        val deliveredRoutineIds = routineDtos.map { it.id }
            .filter { it in sentRoutineIds && it !in unacceptedRoutineIds }
        if (deliveredRoutineIds.isNotEmpty()) {
            syncRepository.stampPushedRoutinesWithoutTimestamp(deliveredRoutineIds, gatherStartedAt)
        }

        // Walk the repair cursor past the groups this push handled — including ones it
        // had to hold, skip as childless, or saw rejected — so the one-time history
        // repair works backwards a batch at a time instead of re-reading the same groups
        // forever. Rejected repair rows are re-armed by clearing their `updatedAt` (see
        // rePushRejectedSessions), which is what puts them back in the next ordinary
        // delta after the cursor has moved on. Only after the push succeeded: a failure
        // returns above and the cursor is retried untouched. The FULL `repairGroupIds`
        // map is passed (not the childless-filtered one) so `processed.size` still
        // reflects every candidate the query returned — otherwise a batch of dropped
        // groups would undercount and mark the repair complete early.
        advanceRoutineGroupRepairCursor(activeProfileId, repairGroupIds)

        return Result.success(
            PushOutcome(
                response = finalResponse,
                gatherStartedAt = gatherStartedAt,
                // Only sessions a request carried: one skipped as oversized is never stamped.
                localRowIdsByPortalSessionId = sessions.groupBy(
                    keySelector = { it.routineSessionId ?: it.id },
                    valueTransform = { it.id },
                ).filterKeys { it in deliverableSessionIds },
                sentSessionsById = deliverableSessions.associateBy { it.id },
                telemetryByPortalSessionId = allSessions.associate { session ->
                    val setIds = sessionSetIds[session.id] ?: emptySet()
                    session.id to setIds.flatMap { setId -> telemetryBySetId[setId] ?: emptyList() }
                },
                acknowledgedPortalSessionIds = acknowledgedPortalSessionIds,
                workoutSnapshot = workoutSnapshot,
                rejections = mergeRejections(collectedRejections),
                personalRecordTombstonesSent = personalRecordTombstonesSent,
                personalRecordsWritten = personalRecordsWritten,
                rePushContext = RePushContext(
                    deviceId = deviceId,
                    platform = platform,
                    lastSync = pushWatermark,
                    profileId = payloadProfileId,
                    profileName = payloadProfileName,
                    personalRecords = personalRecordDtos,
                    repairGroupIds = repairGroupIds.keys,
                ),
            ),
        )
        // No updateServerIds() -- portal uses client-provided UUIDs
    }

    /**
     * Pushes the durable mutation queues (ownership transfers, workout deletions,
     * cycle deletions) ahead of ordinary uploads. Acks only mutation ids the portal
     * returns; a successful HTTP response without an exact ack leaves the local queue
     * pending for retry. Returns the last response received, or null if nothing was sent.
     */
    private suspend fun pushDurableMutations(
        userId: String,
        deviceId: String,
        platform: String,
        lastSync: Long,
        payloadProfileId: String,
        payloadProfileName: String,
        profileDtos: List<LocalProfileDto>?,
        allProfiles: List<UserProfile>,
        pendingOwnershipTransfers: List<OwnershipTransferMutation>,
        pendingWorkoutDeletions: List<WorkoutDeletionMutation>,
        pendingCycleDeletions: List<PendingCycleDeletion>,
    ): Result<PortalSyncPushResponse?> {
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
            rejectDuplicatePushPayloadKeys(payload)?.let { return Result.failure(it) }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return Result.failure(result.pushError())
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
            val routingProfileName = boundedProfileName(
                allProfiles.firstOrNull { it.id == routingProfileId }?.name ?: "Recovered profile",
            )
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
            rejectDuplicatePushPayloadKeys(payload)?.let { return Result.failure(it) }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return Result.failure(result.pushError())
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
            rejectDuplicatePushPayloadKeys(payload)?.let { return Result.failure(it) }
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) return Result.failure(result.pushError())
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

        return Result.success(lastResponse)
    }

    /** Acknowledges the parents this response applied; returns exactly those portal session ids. */
    private suspend fun acknowledgeAcceptedWorkoutParents(
        workoutSnapshot: WorkoutSyncSnapshot,
        sentPortalSessionIds: Set<String>,
        response: PortalSyncPushResponse,
    ): Set<String> {
        if (sentPortalSessionIds.isEmpty()) return emptySet()
        val acceptedPortalSessionIds = response.acknowledgedWorkoutSessionIds
            .filterTo(linkedSetOf()) { it in sentPortalSessionIds }
        syncRepository.acknowledgeWorkoutSnapshot(workoutSnapshot, acceptedPortalSessionIds)
        // PR 11 (codex #859): an accepted workout now belongs to this account. Record that,
        // so an ownership-400 on a LATER request of the same push cannot make recovery
        // exclude rows that already landed here.
        val accountId = tokenStorage.currentUser.value?.id
        if (accountId != null && acceptedPortalSessionIds.isNotEmpty()) {
            syncRepository.insertSyncExcludedEntities(
                accountId,
                SyncExcludedEntityTypes.reached(SyncExcludedEntityTypes.WORKOUT),
                acceptedPortalSessionIds,
            )
        }
        return acceptedPortalSessionIds
    }

    /** Marks exactly the external activities [response] confirmed for the request that carried [sent]. */
    private suspend fun acknowledgeExternalActivities(
        sent: List<ExternalActivitySyncDto>,
        response: PortalSyncPushResponse,
        profileId: String,
    ) {
        val acknowledgedSyncKeys = response.externalActivityKeys.mapNotNull { ack ->
            IntegrationProvider.fromKey(ack.provider)?.let { provider ->
                ExternalActivitySyncKey(externalId = ack.externalId, provider = provider)
            }
        }
        if (acknowledgedSyncKeys.isNotEmpty()) {
            // Server confirmed exact provider-scoped keys — mark only those.
            externalActivityRepository.markSyncedBySyncKeys(
                syncKeys = acknowledgedSyncKeys,
                profileId = profileId,
            )
            Logger.d("SyncManager") {
                "Marked ${acknowledgedSyncKeys.size} external activities as synced (by server-confirmed provider/externalId keys)"
            }
        } else if (response.externalActivityIds.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Server returned legacy externalActivityIds without provider scoping; skipping optimistic sync stamping"
            }
        } else if (response.externalActivitiesUpserted > 0) {
            // Backward compat: server confirmed a count but no IDs list — only for THIS request.
            val syncedIds = sent.map { it.id }
            externalActivityRepository.markSynced(syncedIds)
            Logger.d("SyncManager") {
                "Marked ${syncedIds.size} external activities as synced (backward compat, server confirmed ${response.externalActivitiesUpserted})"
            }
        } else {
            // Server did not confirm any activities were persisted — do NOT mark as synced
            Logger.w("SyncManager") {
                "Pushed ${sent.size} external activities but server confirmed 0 — will retry on next sync"
            }
        }
    }

    private suspend fun acknowledgeAcceptedCycles(
        cycleSnapshot: com.devil.phoenixproject.data.repository.CycleSyncSnapshot,
        sentCycleIds: Set<String>,
        response: PortalSyncPushResponse,
    ) {
        if (sentCycleIds.isEmpty()) return
        val acceptedCycleIds = response.acknowledgedCycleIds
            .filterTo(linkedSetOf()) { it in sentCycleIds }
        // A server version is safe to adopt only for a cycle this exact request sent and
        // the portal explicitly acknowledged as applied. A rejected cycle keeps its old
        // base until pull convergence, so a failed pull cannot turn the rejection into an
        // overwrite on retry. Unexpected ids also cannot change another profile's base.
        val acceptedCycleVersions = response.cycleVersions.filterKeys { it in acceptedCycleIds }
        if (acceptedCycleVersions.isNotEmpty()) {
            try {
                syncRepository.updateCycleServerVersions(acceptedCycleVersions)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // An old base only makes the portal keep its own edits; the pull repairs it.
                Logger.w(e) { "Failed to store ${acceptedCycleVersions.size} cycle server version(s)" }
            }
        }
        val rejections = response.rejections.cycles.associateBy { it.id }
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

    /**
     * Re-send, once, the **grouped** sessions the portal's LWW gate rejected.
     *
     * Why a rejection is not self-healing: the portal's `sessions_updated_at` trigger
     * stamps `updated_at = now()` on every accepted update, and the gate then takes the
     * next push only if that server time is `<= EXCLUDED.updated_at`. A routine set
     * whose local timestamp is the moment the set STARTED can sit behind the server
     * clock indefinitely — short rests, a slow network, or a device clock that lags.
     * Re-sending the same payload would be rejected again forever, and the workout's
     * last sets would never reach the portal.
     *
     * So this re-push uses the server's own number: `serverUpdatedAt + 1 ms`, straight
     * off the rejection. It cannot steamroll a concurrent web edit, because that edit's
     * trigger time is already past `serverUpdatedAt + 1 ms` and the gate rejects us
     * again. It also re-reads SessionNotes after the pull, so the note the portal holds
     * goes back untouched.
     *
     * Standalone sessions (no `routineSessionId`) are deliberately NOT re-pushed here.
     * Their portal session id is the local row id, so `notes = freshNotes[dto.id]`
     * would send back whatever (usually nothing) this device has under that id and
     * erase a note the website wrote. They stay in the ordinary delta and are retried
     * on the next sync with their web note intact.
     *
     * Bounded on purpose: one attempt per sync. Anything still rejected is not stamped;
     * it is retried on a later sync only while `updatedAt IS NULL` (or newer than
     * the push watermark) — repair-group rows additionally get their stamp cleared below so the
     * already-walked-past cursor cannot strand them.
     */
    private suspend fun rePushRejectedSessions(
        userId: String,
        profileId: String,
        outcome: PushOutcome,
        sessionRejections: List<SyncRejectionDto>,
    ) {
        val unchangedContent = mutableListOf<String>()
        val retryable = sessionRejections.mapNotNull { rejection ->
            val dto = outcome.sentSessionsById[rejection.id] ?: return@mapNotNull null
            // Step 11: a rejected row whose content is unchanged from what was last
            // accepted is stamped, not re-pushed. Never stamp a row that was never
            // uploaded — the hash is written only when this account gets an accept
            // (S-1: namespaced by userId:profileId, so another account's accept cannot
            // satisfy this lookup).
            val sentHash = tokenStorage.getSessionSentHash(userId, profileId, rejection.id)
            val currentHash = sessionContentFingerprint(dto, outcome.telemetryByPortalSessionId[rejection.id].orEmpty())
            if (sentHash != null && sentHash == currentHash) {
                unchangedContent += rejection.id
                return@mapNotNull null
            }
            // M2: grouped sessions only. See the KDoc above for why a standalone
            // re-push erases its web note.
            if (dto.routineSessionId.isNullOrBlank()) return@mapNotNull null
            val serverMillis = rejection.serverUpdatedAt
                ?.let { iso -> runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull() }
                ?: return@mapNotNull null
            dto to serverMillis
        }
        if (unchangedContent.isNotEmpty()) {
            val unchangedRows = unchangedContent
                .flatMap { outcome.localRowIdsByPortalSessionId[it].orEmpty() }
                .distinct()
            if (unchangedRows.isNotEmpty()) {
                // Step 11: content unchanged from what was uploaded → stamp so the row
                // stops matching every delta query. Never stamps a never-uploaded row
                // (those never enter this branch: their hash lookup is empty).
                syncRepository.updateSessionTimestamps(
                    sessionIds = unchangedRows,
                    timestamp = outcome.gatherStartedAt,
                    gatherStartedAt = outcome.gatherStartedAt,
                )
                // The gather is generation-based: without the snapshot ack these parents
                // stay dirty and are pushed (and rejected) again on every sync.
                syncRepository.acknowledgeWorkoutSnapshot(outcome.workoutSnapshot, unchangedContent.toSet())
                Logger.i("SyncManager") {
                    "LWW re-push: ${unchangedRows.size} rejected session row(s) had unchanged content " +
                        "and were stamped instead of re-sent"
                }
            }
        }
        // Repair-group rows the portal rejected are re-armed whether or not we can
        // re-push them this sync: the repair cursor has already walked past their group.
        val repairRowsToClear = repairRowIdsFor(outcome, sessionRejections.map { it.id })
        if (retryable.isEmpty()) {
            Logger.w("SyncManager") {
                "Push LWW: ${sessionRejections.size} rejected session(s) cannot be re-pushed this sync " +
                    "(standalone, no server timestamp, or not part of this payload); they are not stamped " +
                    "and retry only while updatedAt is NULL or newer than the push watermark."
            }
            if (repairRowsToClear.isNotEmpty()) {
                syncRepository.updateSessionTimestamps(
                    sessionIds = emptyList(),
                    timestamp = outcome.gatherStartedAt,
                    gatherStartedAt = outcome.gatherStartedAt,
                    clearIds = repairRowsToClear,
                )
            }
            return
        }

        val freshNotes = syncRepository.getSessionNotesForIds(retryable.map { it.first.id })
        val retrySessions = retryable.map { (dto, serverMillis) ->
            dto.copy(
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(serverMillis + 1).toString(),
                notes = freshNotes[dto.id],
            )
        }
        val retryTelemetry = retrySessions.flatMap { outcome.telemetryByPortalSessionId[it.id].orEmpty() }

        Logger.i("SyncManager") {
            "Re-pushing ${retrySessions.size} LWW-rejected session(s) with serverUpdatedAt+1ms after the pull"
        }

        val retryTelemetryCounts = retrySessions.associate {
            it.id to outcome.telemetryByPortalSessionId[it.id].orEmpty().size
        }
        val stillRejected = mutableSetOf<String>()
        val retryAcknowledged = mutableSetOf<String>()
        var anyFailure = false
        val allRetryPrs = outcome.rePushContext.personalRecords
        fun retryPayload(batch: List<PortalWorkoutSessionDto>): PortalSyncPayload {
            val ids = batch.mapTo(hashSetOf()) { it.id }
            return PortalSyncPayload(
                deviceId = outcome.rePushContext.deviceId,
                platform = outcome.rePushContext.platform,
                lastSync = outcome.rePushContext.lastSync,
                sessions = batch,
                telemetry = retryTelemetry.filter { point ->
                    batch.any { session ->
                        session.exercises.any { ex -> ex.sets.any { set -> set.id == point.setId } }
                    }
                },
                profileId = outcome.rePushContext.profileId,
                profileName = outcome.rePushContext.profileName,
                // Never empty when PRs exist: an empty `personalRecords` makes the portal
                // derive id-less rows from every `set.isPr` and INSERT them (PORTAL
                // ROW-DUPLICATION HAZARD). Over PR_FULL_LIST_PER_BATCH only this batch's
                // own PRs (or one suppressor) go, so the retry can never exceed the
                // portal's 10,000-item cap and 400 forever (codex #856 self-review).
                personalRecords = if (allRetryPrs.size <= PR_FULL_LIST_PER_BATCH) {
                    allRetryPrs
                } else {
                    allRetryPrs.filter { it.sessionId in ids }.ifEmpty { allRetryPrs.take(1) }
                },
            )
        }
        val retryBatches = PushPlanner.splitSessionBatchesByBytes(
            planSessionBatches(retrySessions, retryTelemetryCounts).filter { it.isNotEmpty() },
            ::retryPayload,
        )
        // A retry session that cannot fit any request on its own stays rejected (pending).
        stillRejected += retrySessions.map { it.id } - retryBatches.flatten().mapTo(hashSetOf()) { it.id }
        for (batch in retryBatches) {
            val batchIds = batch.mapTo(mutableSetOf()) { it.id }
            val payload = retryPayload(batch)
            val result = pushPayloadWithRateLimit(payload)
            if (result.isFailure) {
                anyFailure = true
                Logger.w("SyncManager") {
                    "LWW re-push failed for ${batch.size} session(s): ${result.exceptionOrNull()?.message}. " +
                        "Their rows are not stamped and retry only while updatedAt is NULL or newer than the push watermark."
                }
                stillRejected += batchIds
                continue
            }
            val retryResponse = result.getOrThrow()
            stillRejected += retryResponse.rejections.sessions.map { it.id }
            retryAcknowledged += retryResponse.acknowledgedWorkoutSessionIds.filter { it in batchIds }
        }

        val acceptedRowIds = retrySessions
            .map { it.id }
            .filter { it !in stillRejected }
            .flatMap { outcome.localRowIdsByPortalSessionId[it].orEmpty() }
        // S-3: a version first delivered by the re-push must record its content hash too,
        // otherwise a later rejection of that same version compares against the older
        // hash and re-pushes (clobbering any web note written in between). The retry
        // DTO's fingerprint equals the next gather's for the same content: `updatedAt`
        // is excluded from the hash and `notes` comes from the same SessionNotes table.
        retrySessions
            .filter { it.id !in stillRejected && it.id in retryAcknowledged }
            .forEach { dto ->
                tokenStorage.setSessionSentHash(
                    userId,
                    profileId,
                    dto.id,
                    sessionContentFingerprint(dto, outcome.telemetryByPortalSessionId[dto.id].orEmpty()),
                )
            }
        // Parents the retry landed are acknowledged against the gather snapshot, exactly
        // like the first push's ack. Otherwise local_sync_generation stays ahead of
        // synced_sync_generation and the next sync re-pushes the group before pulling,
        // which can overwrite a web note written after this retry (codex #856 P1). Rows
        // edited after the gather carry a newer generation and stay dirty.
        val retryAccepted = retryAcknowledged - stillRejected
        if (retryAccepted.isNotEmpty()) {
            syncRepository.acknowledgeWorkoutSnapshot(outcome.workoutSnapshot, retryAccepted)
        }
        // Only rows still rejected (or whose batch failed) get re-armed; accepted ones
        // are stamped in the same transaction.
        val stillRejectedRepairRows = repairRowIdsFor(outcome, stillRejected)
        val stamped = syncRepository.updateSessionTimestamps(
            sessionIds = acceptedRowIds,
            timestamp = outcome.gatherStartedAt,
            gatherStartedAt = outcome.gatherStartedAt,
            clearIds = stillRejectedRepairRows,
        )
        if (acceptedRowIds.isNotEmpty()) {
            Logger.i("SyncManager") {
                "LWW re-push accepted ${acceptedRowIds.size} session row(s); stamped $stamped"
            }
        }
        if (stillRejectedRepairRows.isNotEmpty()) {
            Logger.i("SyncManager") {
                "Re-armed ${stillRejectedRepairRows.size} still-rejected repair-group row(s) " +
                    "(updatedAt cleared) so the next sync's delta retries them after the cursor moved on"
            }
        }
        if (stillRejected.isNotEmpty() && !anyFailure) {
            Logger.w("SyncManager") {
                "LWW re-push still rejected ${stillRejected.size} session(s); they are not stamped and retry " +
                    "only while updatedAt is NULL or newer than lastSync."
            }
        }
    }

    /** Local row ids of rejected portal sessions that belong to this push's repair groups. */
    private fun repairRowIdsFor(outcome: PushOutcome, rejectionIds: Collection<String>): List<String> {
        val repairGroups = outcome.rePushContext.repairGroupIds
        if (repairGroups.isEmpty()) return emptyList()
        return rejectionIds
            .filter { it in repairGroups }
            .flatMap { outcome.localRowIdsByPortalSessionId[it].orEmpty() }
            .distinct()
    }

    /**
     * Clear `updatedAt` on rejected repair-group rows without re-pushing them. Used
     * when the re-push is skipped (pull failed) or threw before its stamping
     * transaction, so the repair cursor cannot permanently strand those groups.
     */
    private suspend fun reArmRejectedRepairRows(outcome: PushOutcome, rejectionIds: Collection<String>) {
        val rows = repairRowIdsFor(outcome, rejectionIds)
        if (rows.isEmpty()) return
        syncRepository.updateSessionTimestamps(
            sessionIds = emptyList(),
            timestamp = outcome.gatherStartedAt,
            gatherStartedAt = outcome.gatherStartedAt,
            clearIds = rows,
        )
        Logger.i("SyncManager") {
            "Re-armed ${rows.size} rejected repair-group row(s) (updatedAt cleared) after the re-push was skipped"
        }
    }

    private fun mergeRejections(batches: List<SyncRejectionsDto>): SyncRejectionsDto {
        if (batches.size <= 1) return batches.firstOrNull() ?: SyncRejectionsDto()
        fun merge(select: (SyncRejectionsDto) -> List<SyncRejectionDto>) = batches.flatMap(select).distinctBy { it.id }
        return SyncRejectionsDto(
            sessions = merge { it.sessions },
            routines = merge { it.routines },
            cycles = merge { it.cycles },
            externalActivities = merge { it.externalActivities },
            rpgAttributes = merge { it.rpgAttributes },
            gamificationStats = merge { it.gamificationStats },
        )
    }

    /**
     * Step 11: a content fingerprint of what was uploaded, so a rejected row whose
     * content is unchanged can be stamped instead of re-pushed forever. Timestamps are
     * deliberately excluded — they change every stamp and would make every row look
     * modified. Exercises (name, muscle group, sets, per-cable weights, reps) and notes
     * are the content the portal's LWW gate is actually arbitrating.
     */
    /**
     * Content fingerprint of every field the push payload actually sends (G-2).
     * Identity fields (`id`, `sessionId`, `routineSessionId`, `startedAt`, `updatedAt`,
     * `userId`) are excluded: they identify the row, they are not its content. If a
     * field reaches the portal wire it MUST be here — a rejected session whose only
     * local edit is in an unhashed field would match the last-accepted hash, get
     * stamped as synced, and never upload the edit.
     *
     * 64-bit FNV-1a rather than 32-bit so a collision cannot mark a changed row
     * synced (the gate stamps on equality).
     */
    internal fun sessionContentFingerprint(
        dto: PortalWorkoutSessionDto,
        telemetry: List<PortalRepTelemetryDto>,
    ): String {
        val content = buildString {
            // Session-level content (PortalWorkoutSessionDto).
            append(dto.name ?: ""); append('|')
            append(dto.notes ?: ""); append('|')
            append(dto.workoutMode ?: ""); append('|')
            append(dto.routineName ?: ""); append('|')
            append(dto.durationSeconds); append('|')
            append(dto.totalVolume); append('|')
            append(dto.setCount); append('|')
            append(dto.exerciseCount); append('|')
            append(dto.prCount); append('|')
            append(dto.avgVelocityMps ?: 0f); append('|')
            append(dto.avgAsymmetryPct ?: 0f); append('|')
            append(dto.velocityLossPct ?: 0f); append('|')
            append(dto.dominantSide ?: ""); append('|')
            append(dto.strengthProfile ?: ""); append('|')
            append(dto.formScore ?: 0); append('|')
            append(dto.deloadWarnings ?: 0); append('|')
            append(dto.romViolations ?: 0); append('|')
            append(dto.spotterActivations ?: 0); append('|')
            append(dto.peakForceN ?: 0f); append('|')
            append(dto.estimatedCalories ?: 0f); append('|')
            append(dto.heaviestLiftKg ?: 0f); append('|')
            append(dto.eccentricLoad ?: 0); append('|')
            append(dto.echoLevel ?: 0); append('|')
            append(dto.warmupReps ?: 0); append('|')
            append(dto.workingReps ?: 0); append('|')
            dto.exercises.sortedBy { it.orderIndex }.forEach { ex ->
                // Exercise-level content (PortalExerciseDto).
                append(ex.name); append('~')
                append(ex.muscleGroup); append('~')
                append(ex.orderIndex); append('~')
                append(ex.exerciseId ?: ""); append('~')
                append(ex.estimatedOneRepMaxKg ?: 0f); append('~')
                append(ex.velocityEstimatedOneRepMaxKg ?: 0f); append('~')
                append(ex.cableCount ?: 0); append('~')
                ex.sets.sortedBy { it.setNumber }.forEach { set ->
                    // Set-level content (PortalSetDto).
                    append(set.setNumber); append(',')
                    append(set.targetReps ?: 0); append(',')
                    append(set.actualReps); append(',')
                    append(set.weightKg); append(',')
                    append(set.rpe ?: 0); append(',')
                    append(set.isPr); append(',')
                    append(set.prType ?: ""); append(',')
                    append(set.prPhase ?: ""); append(',')
                    append(set.prVolume ?: 0f); append(',')
                    append(set.notes ?: ""); append(',')
                    append(set.workoutMode ?: ""); append(',')
                    set.repSummaries.forEach { rep ->
                        append(rep.repNumber); append('/')
                        append(rep.meanVelocityMps ?: 0f); append('/')
                        append(rep.peakVelocityMps ?: 0f); append('/')
                        append(rep.meanForceN ?: 0f); append('/')
                        append(rep.peakForceN ?: 0f); append('/')
                        append(rep.powerWatts ?: 0f); append('/')
                        append(rep.romMm ?: 0f); append('/')
                        append(rep.tutMs ?: 0); append('/')
                        append(rep.leftForceAvg ?: 0f); append('/')
                        append(rep.rightForceAvg ?: 0f); append('/')
                        append(rep.asymmetryPct ?: 0f); append('/')
                        append(rep.vbtZone ?: ""); append('%')
                    }
                    append(';')
                }
                append('|')
            }
            // Raw telemetry sent with this portal session (Inferno tier). A change to the
            // force curve alone must not look like already-accepted content (codex #856 P1).
            telemetry.sortedWith(compareBy({ it.setId }, { it.timestampMs }, { it.id })).forEach { point ->
                append(point.id); append('^')
                append(point.setId); append('^')
                append(point.timestampMs); append('^')
                append(point.forceN ?: 0f); append('^')
                append(point.velocityMps ?: 0f); append('^')
                append(point.positionMm ?: 0f); append('^')
                append(point.cable ?: ""); append('#')
            }
        }
        // 64-bit FNV-1a. Wider than the previous 32-bit digest so a hash collision
        // cannot satisfy the `sentHash == currentHash` stamp gate.
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 as signed Long
        for (ch in content) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }

    /**
     * One-time repair for routine workouts the pre-fix per-set push truncated on the
     * portal. A group qualifies when it has at least two live rows and at least one
     * already-stamped row: the portal saw it, but only ever one set at a time. Sending
     * the group complete rebuilds it, and is idempotent because the portal replaces a
     * workout's children with exactly what the payload carries.
     *
     * Capped per sync and walked newest-first behind a stored cursor, so a long history
     * is rebuilt over several syncs instead of one enormous push. Held groups (a pulled
     * sibling with no local data) are skipped by the same guard as the ordinary push.
     *
     * @return routineSessionId → newest member timestamp for this sync's batch.
     */
    private suspend fun routineGroupRepairCandidates(profileId: String): Map<String, Long> {
        val cursor = tokenStorage.getRoutineGroupRepairCursor(profileId)
        if (cursor <= 0L) return emptyMap()
        val candidates = syncRepository
            .getRoutineGroupRepairCandidates(cursor, ROUTINE_GROUP_REPAIR_BATCH, profileId)
            .toMap()
        if (candidates.isNotEmpty()) {
            Logger.i("SyncManager") {
                "Routine group repair: re-sending ${candidates.size} complete routine workout(s) " +
                    "older than $cursor to rebuild portal history lost to the per-set push"
            }
        }
        return candidates
    }

    private fun advanceRoutineGroupRepairCursor(profileId: String, processed: Map<String, Long>) {
        val cursor = tokenStorage.getRoutineGroupRepairCursor(profileId)
        if (cursor <= 0L) return
        if (processed.size < ROUTINE_GROUP_REPAIR_BATCH) {
            // The query returned less than a full batch, so nothing older is left.
            tokenStorage.setRoutineGroupRepairCursor(profileId, 0L)
            Logger.i("SyncManager") { "Routine group repair: complete for profile $profileId" }
            return
        }
        val oldest = processed.values.min()
        tokenStorage.setRoutineGroupRepairCursor(profileId, oldest)
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

    /**
     * Every logical push waits for the shared client window (the same limit the server
     * enforces) — there is no fail-fast path (codex #856). Failing fast let the next
     * waiting ordinary request take each freed slot, so LWW re-pushes and dirty
     * preference chunks could get a local 429 on every sync and never reach the portal,
     * and profiles walked in a fixed order could starve behind one another.
     *
     * [ClientRateLimiter.acquireWithWait] releases its mutex before each (cancellable)
     * delay and records a slot only when it is granted, so a waiting call holds no slot
     * and the wait is bounded by the window per slot.
     */
    private suspend fun pushPayloadWithRateLimit(
        payload: PortalSyncPayload,
    ): Result<PortalSyncPushResponse> {
        rateLimiter.acquireWithWait("push", SyncConfig.PUSH_RATE_LIMIT_PER_MIN)
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
     * PR 10 step 6 (R-18): the request carries this profile's stored `pullCursor`
     * (server clock) as `lastSync`, captured once before the page loop so every page
     * of this pull sends the same value. The caller persists the new value only after
     * the final page (`hasMore=false`). The new value is the FIRST page's `syncTime`
     * minus a fixed 5-minute overlap — a known row edited while later pages were being
     * fetched is then re-sent by the next pull instead of being skipped for good.
     *
     * `lastSync=0` (full pull) is sent when the profile has no stored cursor, or while
     * a duration backfill is still pending. [forceFullResync] resets every profile's
     * cursor to 0 and so also sends 0.
     *
     * PR 10 step 6 (R-22): never send an empty known-id list with a non-zero cursor.
     * Each empty list is replaced by the sentinel nil UUID.
     *
     * @return Result with the completed pull on success, or failure with classified error
     */
    private suspend fun pullRemoteChangesWithResult(
        userId: String,
        profile: UserProfile,
        includeUserScoped: Boolean,
        followsSuccessfulPush: Boolean,
    ): Result<CompletedPull> {
        val deviceId = tokenStorage.getDeviceId()
        val mergeProfileId = profile.id
        val pullCursor = tokenStorage.getPullCursor(userId, mergeProfileId)
        val durationBackfillRoutineIds = syncRepository
            .getRoutineIdsNeedingDurationBackfill(mergeProfileId)
            // Template-derived cycle routines are local-only and cannot converge through
            // the portal's UUID contract. They must not pin every later pull to lastSync=0.
            .filter(CANONICAL_UUID_REGEX::matches)
            .toHashSet()
        // Legacy rows require one complete server snapshot to establish whether duration was
        // explicitly null or merely absent from older sync payloads. Keep routine IDs in parity
        // so tombstones still converge, but bypass the delta timestamp until backfill completes.
        val requestLastSync = if (durationBackfillRoutineIds.isNotEmpty()) {
            0L
        } else {
            pullCursor
        }
        val mergeLastSync = pullCursor
        val serverWinsRoutineIds = tokenStorage.pendingServerWinsRoutineIds(userId, mergeProfileId)
        Logger.i("SyncManager") {
            "Pull mode: requestLastSync=$requestLastSync (stored=$pullCursor, " +
                "mergeLastSync=$mergeLastSync, profile=$mergeProfileId, " +
                "serverWinsRoutines=${serverWinsRoutineIds.size}, includeUserScoped=$includeUserScoped)"
        }

        // Collect local entity IDs for parity comparison. PR 10 step 7 (R-19): the
        // session list is the profile's known *portal* session ids — distinct
        // `routineSessionId`s (grouped) plus standalone session ids, plus tombstone
        // portal ids from soft-deleted rows — newest-first so the cap drops the oldest.
        //
        // fix(audit #7): cap each list at MAX_PARITY_IDS to stay within the
        // server's enforced HTTP 413 threshold. If a user has more than
        // MAX_PARITY_IDS entities, we send the most recent window and rely on
        // the mobile-side dedupe against local DB to handle the tail. This is
        // strictly better than the prior server behavior which silently
        // returned empty for over-cap lists.
        val rawSessionIds = syncRepository.getKnownPortalSessionIds(mergeProfileId)
        val rawRoutineIds = syncRepository.getAllRoutineIds(mergeProfileId)
        val rawCycleIds = syncRepository.getAllCycleIds(mergeProfileId)
        // PR 10 step 3 (R-14): badges are user-scoped singletons — only the active
        // profile's pull sends and merges them.
        val rawBadgeIds = if (includeUserScoped) {
            syncRepository.getAllBadgeIds(mergeProfileId)
        } else {
            emptyList()
        }
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

        // PR 10 step 7: lists are newest-first, so `take` drops the oldest. (The pre-PR-10
        // `takeLast` on an oldest-first list did the same thing; on a newest-first list it
        // would have dropped the newest.)
        fun <T> capParity(list: List<T>, label: String): List<T> = if (list.size <= SyncConfig.MAX_PARITY_IDS) {
            list
        } else {
            Logger.w("SyncManager") {
                "Parity list '$label' has ${list.size} entries; truncating to first " +
                    "${SyncConfig.MAX_PARITY_IDS} (newest-first) to stay within server cap. " +
                    "Local dedupe will handle the older tail."
            }
            list.take(SyncConfig.MAX_PARITY_IDS)
        }

        // PR 10 step 6 (R-22): never send an empty known-id list with a non-zero cursor.
        // The portal's `get_*_excluding_ids` RPCs treat an empty list as "exclude nothing"
        // and can return every known row again, or loop. The sentinel nil UUID is a
        // UUID-valid id that matches nothing, so the exclusion set is non-empty and the
        // cursor does the real filtering.
        fun <T> nonEmptyOrSentinel(list: List<T>, label: String): List<T> = if (list.isNotEmpty()) {
            list
        } else {
            Logger.d("SyncManager") {
                "Parity list '$label' is empty; substituting the nil-UUID sentinel so the " +
                    "request never carries an empty known-id list with a non-zero cursor"
            }
            @Suppress("UNCHECKED_CAST")
            listOf(NIL_UUID_SENTINEL as T)
        }

        // PR ids fetched by THIS pull go to the front: the cap keeps the first
        // MAX_PARITY_IDS, so appended ids would be dropped once the profile already holds
        // that many, and the next page would re-deliver them until MAX_PAGES (codex #856).
        // Session/routine/cycle/badge lists are fixed for the whole pull, so only this list
        // grows inside the paging loop.
        val knownPersonalRecordIds = ArrayDeque(
            capParity(filterUuids(rawPersonalRecordIds, "personalRecordIds"), "personalRecordIds"),
        )
        val knownPersonalRecordIdSet = knownPersonalRecordIds.toHashSet()

        fun currentKnownEntityIds(): KnownEntityIds = KnownEntityIds(
            sessionIds = nonEmptyOrSentinel(capParity(filteredSessionIds, "sessionIds"), "sessionIds"),
            routineIds = nonEmptyOrSentinel(capParity(filteredRoutineIds, "routineIds"), "routineIds"),
            cycleIds = nonEmptyOrSentinel(capParity(filteredCycleIds, "cycleIds"), "cycleIds"),
            badgeIds = nonEmptyOrSentinel(capParity(filteredBadgeIds, "badgeIds"), "badgeIds"),
            // Send known PR UUIDs so the portal can page with
            // get_personal_records_excluding_ids and still return tombstones
            // via get_personal_record_tombstones. Empty lists force the server
            // to rely only on the cursor, which loops when many PRs share one
            // microsecond timestamp.
            personalRecordIds = nonEmptyOrSentinel(
                capParity(knownPersonalRecordIds.toList(), "personalRecordIds"),
                "personalRecordIds",
            ),
        )

        val entityIds = currentKnownEntityIds()
        Logger.i("SyncManager") {
            "Parity sync: sending ${entityIds.sessionIds.size} session IDs, " +
                "${entityIds.routineIds.size} routine IDs, ${entityIds.cycleIds.size} cycle IDs, " +
                "${entityIds.badgeIds.size} badge IDs, " +
                "${entityIds.personalRecordIds.size} personal record IDs; " +
                "${durationBackfillRoutineIds.size} routines pending duration backfill"
        }

        var pagesProcessed = 0
        var totalEntitiesFetched = 0
        var currentCursor: String? = null
        // PR 10 step 6 (R-18): first page's server syncTime is the snapshot start;
        // stored cursor = that minus PULL_CURSOR_OVERLAP_MS.
        var firstPageSyncTime: Long? = null
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
                "PULL REQUEST: deviceId=$deviceId, profileId=$mergeProfileId, " +
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
                pullResponse.externalActivities.size +
                pullResponse.workoutDeletions.size +
                pullResponse.ownershipEvents.size +
                pullResponse.deletedRoutineIds.size +
                pullResponse.deletedCycleIds.size
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
                activeSyncProfileId = profile.id,
                isFirstPage = pagesProcessed == 1,
                serverWinsRoutineIds = serverWinsRoutineIds,
                includeUserScoped = includeUserScoped,
                pushWatermark = tokenStorage.getPushWatermark(userId, mergeProfileId),
                stampPullMerge = followsSuccessfulPush,
            )
            if (mergeResult.isFailure) {
                // Map Result<Unit> to Result<Long> for consistent return type
                return Result.failure(mergeResult.exceptionOrNull() ?: PortalApiException("Merge failed"))
            }

            for (record in pullResponse.personalRecords) {
                val id = record.id
                if (CANONICAL_UUID_REGEX.matches(id) && knownPersonalRecordIdSet.add(id)) {
                    knownPersonalRecordIds.addFirst(id)
                }
            }

            // Update pagination state. PR 10 step 6: keep the FIRST page's syncTime, not
            // the minimum across pages — the first page's value plus the overlap is the
            // boundary the whole pull is safe from.
            if (firstPageSyncTime == null) {
                firstPageSyncTime = pullResponse.syncTime
            }
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
        // to the client or updated since the pull cursor. Missing known IDs therefore
        // do not prove deletion. Server-side routine/cycle deletes arrive only
        // through the explicit tombstone keys (pull deletedRoutineIds /
        // deletedCycleIds, push skippedDeleted), applied by applyServerDeletions
        // in mergePullPage and in the push skippedDeleted handling.

        // PR 10 step 6: the next pull cursor is the FIRST page's syncTime minus a fixed
        // 5-minute overlap. Fall back to the stored cursor unchanged if no page produced
        // one — subtracting the overlap again would ratchet the cursor backwards on every
        // occurrence (G-9).
        val pullSyncTime = if (firstPageSyncTime != null) {
            if (firstPageSyncTime > PULL_CURSOR_OVERLAP_MS) firstPageSyncTime - PULL_CURSOR_OVERLAP_MS else 0L
        } else {
            pullCursor
        }

        return Result.success(
            CompletedPull(
                userId = userId,
                profileId = mergeProfileId,
                syncTime = pullSyncTime,
                // The server caps external activities at 500 per pull (oldest synced_at
                // first) and has no cursor for the rest. A delta cursor would skip the
                // truncated tail for good, so force a full pull next time. Delivering the
                // tail needs a server cursor.
                forceFullPull = externalActivitiesTruncated,
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
     * The caller leaves the pull cursor unchanged and retries the page; revision guards make replay
     * idempotent and dirty-section predicates preserve concurrent local edits.
     */
    /**
     * PR 11: every row a pull merges came from this portal account. Handed to
     * [SyncRepository.mergeAllPullData] so the provenance commits in the merge transaction,
     * for any pull (a pull-only retry included), and an account switch never classifies a
     * pulled row as never-synced local data (codex #859).
     */
    private fun pulledProvenance(
        pullResponse: PortalSyncPullResponse,
        mobileSessions: List<com.devil.phoenixproject.domain.model.WorkoutSession>,
    ): Map<String, Collection<String>> {
        val types = SyncExcludedEntityTypes
        val sessionIds = mobileSessions.flatMap { listOfNotNull(it.id, it.routineSessionId?.takeIf { id -> id.isNotBlank() }) }
        return mapOf(
            types.WORKOUT to sessionIds + pullResponse.sessions.map { it.id },
            types.ROUTINE to pullResponse.routines.map { it.id },
            types.CYCLE to pullResponse.cycles.map { it.id },
            types.PERSONAL_RECORD to pullResponse.personalRecords.map { it.id },
            types.EARNED_BADGE to pullResponse.badges.map { it.badgeId },
        ).filterValues { it.isNotEmpty() }
    }

    private suspend fun mergePullPage(
        pullResponse: PortalSyncPullResponse,
        lastSync: Long,
        mergeProfileId: String,
        activeSyncProfileId: String?,
        isFirstPage: Boolean,
        serverWinsRoutineIds: Set<String>,
        includeUserScoped: Boolean,
        pushWatermark: Long,
        stampPullMerge: Boolean = false,
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
        // PR 10 step 3 (R-14): badges follow the active profile only.
        val badgeDtos = if (includeUserScoped) {
            pullResponse.badges.map { PortalPullAdapter.toBadgeSyncDto(it) }
        } else {
            emptyList()
        }
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
        // PR 10 step 3 (R-14): gamification stats are the third user-scoped singleton
        // (with badges and RPG) and follow the active profile only (S-2). The portal
        // returns them unfiltered, so without this gate every profile's pull would
        // overwrite its own row with the user's.
        val gamificationStatsDto = if (includeUserScoped) {
            pullResponse.gamificationStats?.let {
                PortalPullAdapter.toGamificationStatsSyncDto(it)
            }
        } else {
            null
        }

        // 2b. Phase 3.5: extract session-level notes for the SessionNotes
        // side-table. Keyed on the portal `routineSessionId` (== portal
        // session id). Sessions without notes are skipped. Prefer
        // session.updatedAt for LWW; fall back to startedAt, then now, when
        // older Edge Function versions omit updatedAt.
        //
        // Blank notes are carried too, not filtered out: the portal writes NULL when a
        // note is cleared on the website, and the push now re-sends whatever
        // SessionNotes holds. Dropping the clear here would make the phone hand the
        // portal back the text the user just deleted. mergeSessionNotes applies it as a
        // timestamp-ordered deletion and ignores a blank note for an id it never had.
        val sessionNotesMap: Map<String, com.devil.phoenixproject.data.repository.SessionNotesEntry> =
            pullResponse.sessions
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
                pushWatermark = pushWatermark,
                profileId = mergeProfileId,
                serverWinsRoutineIds = serverWinsRoutineIds,
                sessionNotes = sessionNotesMap,
                sessionUpdatedAtById = sessionUpdatedAtById,
                pulledProvenance = pulledProvenance(pullResponse, mobileSessions),
            )
            // PR 11: every row this merge committed came from this portal account. Record
            // that provenance for any pull (including a pull-only retry, which does not
            // advance the pull-merge stamp below), so an account switch never classifies a
            // pulled row as never-synced local data (codex #859).
            // PR 11: a pulled routine / cycle gets its local createdAt at merge time, later
            // than the push watermark. Record when this account's rows landed so an account
            // switch does not mistake them for never-synced local rows. Stamped only once the
            // merge transaction above has committed (a failed merge rolls everything back and
            // leaves the stamp untouched), and only for a pull that followed a successful push,
            // so no unpushed local row falls below the boundary except one made during this sync.
            if (stampPullMerge) {
                tokenStorage.setPullMergeWatermark(ownerUserId, mergeProfileId, currentTimeMillis())
            }

            // Server-reported deletions (PR 16 keys; first page only, absent on older
            // servers). Apply after the atomic ordinary merge so a page carrying the
            // same routine or cycle cannot resurrect it. A failure fails the pull, which
            // keeps the checkpoint unchanged and makes the next pull report the ids again.
            applyServerDeletions(
                ownerUserId = ownerUserId,
                syncProfileId = activeSyncProfileId,
                routineIds = pullResponse.deletedRoutineIds,
                cycleIds = pullResponse.deletedCycleIds,
                lastSync = lastSync,
                source = "pull",
            )

            Logger.d("SyncManager") {
                "Ordinary pull merge complete: ${mobileSessions.size} sessions (${mobileSessions.count { it.exerciseId != null }} with exerciseId), " +
                    "${pullResponse.routines.size} routines, ${pullResponse.cycles.size} cycles, " +
                    "${pullResponse.badges.size} badges, ${prDtos.size} PRs, " +
                    "${sessionNotesMap.size} session notes"
            }
        } catch (e: Exception) {
            Logger.e(e) {
                "Ordinary pull merge failed; the pull cursor will not advance and earlier per-repository " +
                    "merges may remain for idempotent retry."
            }
            return Result.failure(PortalApiException("Pull merge failed: ${e.message}"))
        }

        // RPG attributes are checkpoint-critical because the server filters them by lastSync.
        // A failure does not roll back earlier repository commits, but it must fail the page so
        // the checkpoint stays unchanged and the idempotent retry receives the RPG row again.
        // PR 10 step 3 (R-14): RPG is one row per portal user; merge it only for the active
        // profile's pull so a non-active profile cannot clobber the active one's stats.
        try {
            // RPG attributes — server wins (overwrite local)
            pullResponse.rpgAttributes?.takeIf { includeUserScoped }?.let { rpg ->
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
        // the pull cursor stays unchanged and the activity is fetched again.
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

    /**
     * Hard-delete routines/cycles the server reports as deleted. No push tombstone
     * is left behind (the server already knows). Delete wins over unsynced local
     * edits (KD-4); discarded edits are logged.
     */
    private suspend fun applyServerDeletions(
        ownerUserId: String,
        syncProfileId: String?,
        routineIds: List<String>,
        cycleIds: List<String>,
        lastSync: Long,
        source: String,
    ) {
        if (routineIds.isEmpty() && cycleIds.isEmpty()) return
        val result = syncRepository.applyServerDeletions(
            ownerUserId = ownerUserId,
            syncProfileId = syncProfileId,
            routineIds = routineIds,
            cycleIds = cycleIds,
            lastSync = lastSync,
        )
        if (result.discardedRoutineEditIds.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Server deleted ${result.discardedRoutineEditIds.size} routine(s) with unsynced local edits " +
                    "($source); local edits discarded (delete wins): ${result.discardedRoutineEditIds.joinToString()}"
            }
        }
        if (result.deletedActiveCycleIds.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Server deleted ${result.deletedActiveCycleIds.size} active/in-progress cycle(s) ($source); " +
                    "local cycle progress removed (delete wins): ${result.deletedActiveCycleIds.joinToString()}"
            }
        }
        if (result.discardedCycleEditIds.isNotEmpty()) {
            Logger.w("SyncManager") {
                "Server deleted ${result.discardedCycleEditIds.size} cycle(s) with unsynced local edits " +
                    "($source); local edits discarded (delete wins): ${result.discardedCycleEditIds.joinToString()}"
            }
        }
        if (
            result.discardedRoutineEditIds.isNotEmpty() ||
            result.discardedCycleEditIds.isNotEmpty() ||
            result.deletedActiveCycleIds.isNotEmpty()
        ) {
            _serverDeletionNotice.update { existing ->
                ServerDeletionNotice(
                    discardedRoutineEditIds =
                        (existing?.discardedRoutineEditIds.orEmpty() + result.discardedRoutineEditIds).distinct(),
                    discardedCycleEditIds =
                        (existing?.discardedCycleEditIds.orEmpty() + result.discardedCycleEditIds).distinct(),
                    deletedActiveCycleIds =
                        (existing?.deletedActiveCycleIds.orEmpty() + result.deletedActiveCycleIds).distinct(),
                )
            }
        }
        Logger.i("SyncManager") {
            "Applied server deletions ($source): reported routines=${routineIds.size}, cycles=${cycleIds.size}; " +
                "removed locally routines=${result.deletedRoutineIds.size}, cycles=${result.deletedCycleIds.size}, " +
                "template routines=${result.deletedTemplateRoutineIds.size}"
        }
    }

    /** Clears [notice] only if it is still the exact notice the UI displayed. */
    fun clearServerDeletionNotice(notice: ServerDeletionNotice) {
        _serverDeletionNotice.update { current -> if (current == notice) null else current }
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
    ): PortalApiException? {
        val duplicate = findPushPayloadDuplicateKeys(payload).firstOrNull() ?: return null
        val message = duplicate.toExceptionMessage()
        Logger.e("SyncManager") { message }
        return PortalApiException(message, null, 400)
    }

    /** The failure a failed push Result carries. */
    private fun Result<PortalSyncPushResponse>.pushError(): Throwable = exceptionOrNull() ?: PortalApiException("Push failed")
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

/**
 * Keeps every push request under [SyncConfig.MAX_PAYLOAD_BYTES] (measured with the same
 * encoder [PortalApiClient.pushPortalPayload] uses) and every array under the portal's
 * 10,000-item cap (`MAX_ENTITIES_PER_TYPE` in mobile-sync-push).
 *
 * An item that does not fit in a request on its own can never be accepted: resending it
 * would 413 forever and wedge every other row behind it. The planner SKIPS such an item
 * (logged) instead: it is not sent, so the caller neither stamps nor acknowledges it — a
 * session stays dirty and is re-evaluated each sync; a routine/PR is re-selected after
 * its next local edit.
 */
/** Longest profile name sent to the portal; local names are unbounded. */
internal const val MAX_PORTAL_PROFILE_NAME_CHARS = 100

/**
 * Caps a profile name for the wire so profile metadata cannot grow a push request
 * without bound. Never splits a surrogate pair.
 */
internal fun boundedProfileName(name: String): String {
    if (name.length <= MAX_PORTAL_PROFILE_NAME_CHARS) return name
    var end = MAX_PORTAL_PROFILE_NAME_CHARS
    if (name[end - 1].isHighSurrogate()) end--
    return name.substring(0, end)
}

internal object PushPlanner {
    /** Portal `MAX_ENTITIES_PER_TYPE`. */
    const val MAX_ITEMS_PER_ARRAY = 10_000

    /** Injectable for tests; production uses the wire encoder. */
    internal var byteSize: (PortalSyncPayload) -> Long = { encodePortalSyncPayload(it).rawBytes.size.toLong() }

    /** Injectable for tests. */
    internal var maxBytes: Long = SyncConfig.MAX_PAYLOAD_BYTES

    fun fits(payload: PortalSyncPayload): Boolean =
        payload.sessions.size <= MAX_ITEMS_PER_ARRAY &&
            payload.routines.size <= MAX_ITEMS_PER_ARRAY &&
            payload.deletedRoutineIds.size <= MAX_ITEMS_PER_ARRAY &&
            payload.cycles.size <= MAX_ITEMS_PER_ARRAY &&
            payload.personalRecords.size <= MAX_ITEMS_PER_ARRAY &&
            payload.assessments.size <= MAX_ITEMS_PER_ARRAY &&
            payload.customExercises.size <= MAX_ITEMS_PER_ARRAY &&
            payload.badges.size <= MAX_ITEMS_PER_ARRAY &&
            payload.externalActivities.size <= MAX_ITEMS_PER_ARRAY &&
            payload.telemetry.size <= SyncConfig.MAX_TELEMETRY_PER_BATCH &&
            byteSize(payload) <= maxBytes

    /**
     * Called once per item that cannot fit in any request on its own. Logged at warn with
     * the entity type and id; the caller also counts them into its sync summary.
     */
    fun interface SkipListener {
        fun skipped(entityType: String, id: String)
    }

    private fun warnSkip(onSkip: SkipListener, entityType: String, id: String) {
        Logger.w("SyncManager") {
            "Push skipped $entityType $id: it exceeds the push size cap on its own and can never be accepted"
        }
        onSkip.skipped(entityType, id)
    }

    /** Splits any session batch whose request would be oversized; drops a session that can never fit. */
    fun splitSessionBatchesByBytes(
        batches: List<List<PortalWorkoutSessionDto>>,
        toPayload: (List<PortalWorkoutSessionDto>) -> PortalSyncPayload,
        onSkip: SkipListener = SkipListener { _, _ -> },
    ): List<List<PortalWorkoutSessionDto>> {
        val out = mutableListOf<List<PortalWorkoutSessionDto>>()
        fun place(batch: List<PortalWorkoutSessionDto>) {
            if (batch.isEmpty()) return
            if (fits(toPayload(batch))) {
                out += batch
            } else if (batch.size == 1) {
                warnSkip(onSkip, "session", batch.single().id)
            } else {
                val mid = batch.size / 2
                place(batch.subList(0, mid))
                place(batch.subList(mid, batch.size))
            }
        }
        batches.forEach(::place)
        return out
    }

    /**
     * Packs non-session entities into requests after the session batches, in dependency
     * order: routines, deleted-routine ids, cycles, PRs, then custom exercises (catalog
     * rows first), assessments, badges and external activities. Each is upserted on its
     * own by mobile-sync-push, so none has to travel with another. The truly singular
     * fields — [rpgAttributes], [gamificationStats] and [allProfiles] — stay together on
     * the last request (merged into the last packed request when it fits there, else sent
     * on their own). No request is ever enqueued oversized: [allProfiles] is dropped from
     * the singular request when it alone makes it too large (see below).
     */
    fun planTailRequests(
        envelope: PortalSyncPayload,
        routines: List<PortalRoutineSyncDto>,
        deletedRoutineIds: List<String>,
        cycles: List<PortalTrainingCycleSyncDto>,
        personalRecords: List<PortalPersonalRecordDto>,
        customExercises: List<CustomExerciseSyncDto>,
        assessments: List<PortalAssessmentResultDto>,
        badges: List<PortalEarnedBadgeSyncDto>,
        externalActivities: List<ExternalActivitySyncDto>,
        rpgAttributes: PortalRpgAttributesSyncDto?,
        gamificationStats: PortalGamificationStatsSyncDto?,
        allProfiles: List<LocalProfileDto>?,
        onSkip: SkipListener = SkipListener { _, _ -> },
    ): List<PortalSyncPayload> {
        val requests = mutableListOf<PortalSyncPayload>()
        val envelopeBytes = byteSize(envelope)
        val curRoutines = mutableListOf<PortalRoutineSyncDto>()
        val curDeleted = mutableListOf<String>()
        val curCycles = mutableListOf<PortalTrainingCycleSyncDto>()
        val curPrs = mutableListOf<PortalPersonalRecordDto>()
        val curCustom = mutableListOf<CustomExerciseSyncDto>()
        val curAssessments = mutableListOf<PortalAssessmentResultDto>()
        val curBadges = mutableListOf<PortalEarnedBadgeSyncDto>()
        val curExternal = mutableListOf<ExternalActivitySyncDto>()
        val all = listOf(curRoutines, curDeleted, curCycles, curPrs, curCustom, curAssessments, curBadges, curExternal)
        // Bytes are summed per item (each measured once against the small envelope, plus a
        // separating comma) instead of re-encoding the growing request for every item.
        var currentBytes = envelopeBytes
        fun build() = envelope.copy(
            routines = curRoutines.toList(),
            deletedRoutineIds = curDeleted.toList(),
            cycles = curCycles.toList(),
            personalRecords = curPrs.toList(),
            customExercises = curCustom.toList(),
            assessments = curAssessments.toList(),
            badges = curBadges.toList(),
            externalActivities = curExternal.toList(),
        )
        fun hasItems() = all.any { it.isNotEmpty() }
        fun close() {
            if (hasItems()) requests += build()
            all.forEach { it.clear() }
            currentBytes = envelopeBytes
        }
        fun <T> pack(
            items: List<T>,
            entityType: String,
            into: MutableList<T>,
            idOf: (T) -> String,
            alone: (T) -> PortalSyncPayload,
        ) {
            for (item in items) {
                val itemBytes = byteSize(alone(item)) - envelopeBytes + 1
                if (envelopeBytes + itemBytes > maxBytes) {
                    warnSkip(onSkip, entityType, idOf(item))
                    continue
                }
                if (currentBytes + itemBytes > maxBytes || into.size >= MAX_ITEMS_PER_ARRAY) close()
                into += item
                currentBytes += itemBytes
            }
        }
        pack(routines, "routine", curRoutines, { it.id }) { envelope.copy(routines = listOf(it)) }
        pack(deletedRoutineIds, "deleted routine", curDeleted, { it }) { envelope.copy(deletedRoutineIds = listOf(it)) }
        pack(cycles, "cycle", curCycles, { it.id }) { envelope.copy(cycles = listOf(it)) }
        pack(personalRecords, "personal record", curPrs, { it.id.orEmpty() }) { envelope.copy(personalRecords = listOf(it)) }
        pack(customExercises, "custom exercise", curCustom, { it.clientId }) { envelope.copy(customExercises = listOf(it)) }
        pack(assessments, "assessment", curAssessments, { it.id }) { envelope.copy(assessments = listOf(it)) }
        pack(badges, "badge", curBadges, { it.badgeId }) { envelope.copy(badges = listOf(it)) }
        pack(externalActivities, "external activity", curExternal, { it.id }) { envelope.copy(externalActivities = listOf(it)) }
        val current = build()

        // The one place the singular fields are attached, so a new one cannot reach only
        // one of the two requests below.
        fun withSingular(p: PortalSyncPayload, profiles: List<LocalProfileDto>? = allProfiles) = p.copy(
            rpgAttributes = rpgAttributes,
            gamificationStats = gamificationStats,
            allProfiles = profiles,
        )
        val merged = withSingular(current)
        if (fits(merged)) {
            requests += merged
            return requests
        }
        close()
        var singular = withSingular(envelope)
        // An oversized singular request would 413 forever (codex #856). allProfiles is the
        // only unbounded field left (names are already capped); it must never be sent
        // partially, because the portal deletes every registered profile missing from it.
        // Omitting it is safe: the portal then upserts just this request's profileId and
        // deletes nothing. Every profile, pending-deletion ones included, still registers
        // through its own push's profileId.
        if (!fits(singular) && allProfiles != null) {
            Logger.w("SyncManager") {
                "Push: profile metadata for ${allProfiles.size} profile(s) exceeds the push size cap; " +
                    "sending without allProfiles this sync"
            }
            singular = withSingular(envelope, profiles = null)
        }
        if (fits(singular)) {
            requests += singular
        } else {
            warnSkip(onSkip, "singular sync fields", "rpg/gamification")
        }
        return requests
    }
}
