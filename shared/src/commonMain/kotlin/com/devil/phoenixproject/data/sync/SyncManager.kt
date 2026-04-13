package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.ExternalActivitySyncKey
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import com.devil.phoenixproject.getPlatform
import kotlinx.coroutines.currentCoroutineContext
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

    /** Maximum pages to fetch in a single pull operation (prevents infinite loops). */
    const val MAX_PAGES = 100
}

class SyncManager(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage,
    private val syncRepository: SyncRepository,
    private val gamificationRepository: GamificationRepository,
    private val repMetricRepository: RepMetricRepository,
    private val userProfileRepository: UserProfileRepository,
    private val externalActivityRepository: ExternalActivityRepository,
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

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(tokenStorage.getLastSyncTimestamp())
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = tokenStorage.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = tokenStorage.currentUser

    /** Auth events for UI notification (session expiry, refresh failure, logout). */
    val authEvents = tokenStorage.authEvents

    // === Authentication ===

    suspend fun login(email: String, password: String): Result<PortalUser> {
        val signInResult = apiClient.signIn(email, password)
        if (signInResult.isFailure) return signInResult.map { it.toPortalAuthResponse().user }

        val goTrueResponse = signInResult.getOrThrow()
        tokenStorage.saveGoTrueAuth(goTrueResponse)
        // TEMP DIAGNOSTIC: Verify token was saved
        Logger.e("SyncManager") {
            "LOGIN: Token saved. Verify: hasToken=${tokenStorage.hasToken()}, " +
                "tokenPrefix=${tokenStorage.getToken()?.take(20)}, " +
                "accessTokenLen=${goTrueResponse.accessToken.length}"
        }

        // Serialize state change with sync operations to prevent race condition
        // (Issue 5.2: login() and sync() both modify _syncState)
        syncMutex.withLock {
            _syncState.value = SyncState.Idle // Reset stale NotAuthenticated state
        }

        // Check premium status from server immediately after auth.
        // On fresh install, existingPremium defaults to false — server is source of truth.
        // On network failure, preserve existing premium status to avoid downgrading paid users.
        val existingPremium = tokenStorage.currentUser.value?.isPremium ?: false
        val premiumResult = apiClient.checkPremiumStatus()
        val isPremium = premiumResult.getOrNull() ?: existingPremium
        tokenStorage.updatePremiumStatus(isPremium)
        Logger.i("SyncManager") {
            "Login successful for ${goTrueResponse.user.email}, premium=$isPremium (server check: ${premiumResult.isSuccess})"
        }

        return Result.success(tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user)
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalUser> {
        val signUpResult = apiClient.signUp(email, password, displayName)
        if (signUpResult.isFailure) return signUpResult.map { it.toPortalAuthResponse().user }

        val goTrueResponse = signUpResult.getOrThrow()
        tokenStorage.saveGoTrueAuth(goTrueResponse)

        // Serialize state change with sync operations to prevent race condition
        // (Issue 5.2: signup() and sync() both modify _syncState)
        syncMutex.withLock {
            _syncState.value = SyncState.Idle // Reset stale NotAuthenticated state
        }

        // New accounts start as free tier — no need to check premium status.
        // Premium status will be set after they subscribe via Paddle.
        tokenStorage.updatePremiumStatus(false)
        Logger.i("SyncManager") { "Signup successful for ${goTrueResponse.user.email}" }

        return Result.success(tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user)
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

        tokenStorage.clearAuth()
        tokenStorage.emitLogoutEvent()

        // Serialize state change with sync operations to prevent race condition
        // (Issue 5.2: logout() and sync() both modify _syncState)
        syncMutex.withLock {
            _syncState.value = SyncState.NotAuthenticated
        }
    }

    // === Sync Operations ===

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
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return@withLock Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing

        // Capture the pre-push lastSync timestamp BEFORE pushing. In the batched path,
        // each batch updates the sync timestamp, so by the time post-push stamping runs,
        // getLastSyncTimestamp() would reflect the LAST batch -- not the original value.
        // Sessions from earlier batches would be missed by the re-query.
        val prePushLastSync = tokenStorage.getLastSyncTimestamp()

        // Push local changes (no status check -- Railway backend abandoned)
        // TEMP DIAGNOSTIC: Elevated to Error level for release build visibility
        Logger.e("SyncManager") {
            "SYNC DEBUG: hasToken=${tokenStorage.hasToken()}, " +
                "tokenPrefix=${tokenStorage.getToken()?.take(20)}, " +
                "isExpired=${tokenStorage.isTokenExpired()}, " +
                "expiresAt=${tokenStorage.getExpiresAt()}, " +
                "hasRefresh=${tokenStorage.getRefreshToken() != null}"
        }
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
                tokenStorage.updatePremiumStatus(false)
                _syncState.value = SyncState.NotPremium
            } else {
                _syncState.value = SyncState.Error(error?.message ?: "Push failed")
            }
            return@withLock Result.failure(error ?: Exception("Push failed"))
        }
        Logger.i("SyncManager") { "Push succeeded" }

        // Successful push confirms premium status
        tokenStorage.updatePremiumStatus(true)

        // Stamp pushed sessions so they aren't re-sent on next sync.
        // Sessions with NULL updatedAt would match every delta query indefinitely.
        // Use prePushLastSync (captured before push) so batched push doesn't cause
        // earlier-batch sessions to be missed by the re-query.
        val stampTime = currentTimeMillis()
        val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"
        val pushedSessions = syncRepository.getWorkoutSessionsModifiedSince(
            prePushLastSync,
            activeProfileId,
        )
        pushedSessions.forEach { session ->
            syncRepository.updateSessionTimestamp(session.id, stampTime)
        }
        if (pushedSessions.isNotEmpty()) {
            Logger.d("SyncManager") {
                "Stamped ${pushedSessions.size} pushed sessions with updatedAt=$stampTime"
            }
        }

        // Parse syncTime from ISO 8601 to epoch millis
        val pushResponse = pushResult.getOrThrow()
        val syncTimeEpoch = try {
            kotlin.time.Instant.parse(pushResponse.syncTime).toEpochMilliseconds()
        } catch (e: Exception) {
            Logger.w(e) {
                "Failed to parse syncTime '${pushResponse.syncTime}', using current time"
            }
            currentTimeMillis()
        }

        // Pull remote changes using the PRE-PUSH lastSync, not the current stored value.
        // The batched push path (line ~428) advances tokenStorage.lastSyncTimestamp after
        // each batch, so re-reading it here would skip remote changes that arrived between
        // the original sync checkpoint and the last batch's server timestamp.
        // Fix for Codex P1: use prePushLastSync captured at line ~97 before any push.
        val pullResult = pullRemoteChangesWithResult(lastSync = prePushLastSync)

        return@withLock if (pullResult.isSuccess) {
            // Full success: both push and pull succeeded
            val finalSyncTime = pullResult.getOrThrow()
            tokenStorage.setLastSyncTimestamp(finalSyncTime)
            _lastSyncTime.value = finalSyncTime
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
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return@withLock Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing
        val lastSync = tokenStorage.getLastSyncTimestamp()

        val pullResult = pullRemoteChangesWithResult(lastSync = lastSync)

        return@withLock if (pullResult.isSuccess) {
            val finalSyncTime = pullResult.getOrThrow()
            tokenStorage.setLastSyncTimestamp(finalSyncTime)
            _lastSyncTime.value = finalSyncTime
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

    private suspend fun pushLocalChanges(): Result<PortalSyncPushResponse> {
        val userId = tokenStorage.currentUser.value?.id
            ?: return Result.failure(PortalApiException("Not authenticated", null, 401))
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val platform = getPlatformName()
        val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"

        // 1. Gather workout sessions as full domain objects (profile-scoped to prevent cross-profile leak)
        val sessions = syncRepository.getWorkoutSessionsModifiedSince(lastSync, activeProfileId)

        // 2. Fetch full PRs with type/phase/volume metadata (GAP 2 fix), profile-scoped
        val recentPRs = syncRepository.getFullPRsModifiedSince(lastSync, activeProfileId)
        val prBySessionKey = recentPRs.associateBy { pr -> "${pr.exerciseId}:${pr.timestamp}" }

        // 3. Build SessionWithReps (fetch rep metrics per session, detect PRs, attach PR metadata)
        val sessionsWithReps = sessions.map { session ->
            val repMetrics = repMetricRepository.getRepMetrics(session.id)
            val sessionKey = "${session.exerciseId}:${session.timestamp}"
            val prRecord = prBySessionKey[sessionKey]

            PortalSyncAdapter.SessionWithReps(
                session = session,
                repMetrics = repMetrics,
                muscleGroup = "General",
                isPr = prRecord != null,
                prRecord = prRecord,
            )
        }

        // 4. Gather routines as full domain objects (exclude internal cycle_routine_ entries), profile-scoped
        val routines = syncRepository.getFullRoutinesModifiedSince(lastSync, activeProfileId)
            .filterNot { it.id.startsWith("cycle_routine_") }

        // 4b. Gather training cycles (all — no delta, lacks updatedAt), profile-scoped
        val cyclesWithContext = syncRepository.getFullCyclesForSync(activeProfileId)

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
        val localPaid =
            userProfileRepository.activeProfile.value?.subscriptionStatus ==
                SubscriptionStatus.ACTIVE
        val portalPaid = tokenStorage.currentUser.value?.isPremium == true
        val isPremium = localPaid || portalPaid
        val externalActivityDtos = if (isPremium) {
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
        val sessionIds = sessions.map { it.id }
        val phaseStatsBySessionId = syncRepository.getPhaseStatisticsForSessions(sessionIds)
            .map { PortalSyncAdapter.toPortalPhaseStatistics(it) }
            .groupBy { it.sessionId }
        val signatureDtos = syncRepository.getAllExerciseSignatures()
            .map { PortalSyncAdapter.toPortalExerciseSignature(it) }
        val assessmentDtos = syncRepository.getAllAssessments(activeProfileId)
            .map { PortalSyncAdapter.toPortalAssessmentResult(it) }

        // 7. Build portal session + telemetry DTOs (telemetry setIds match generated exercise set IDs)
        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(
            sessionsWithReps,
            userId,
        )

        // Build a telemetry index keyed by set ID for batch slicing.
        // Each session's exercises contain sets whose IDs are referenced by telemetry rows.
        val sessionSetIds = buildResult.sessions.associate { session ->
            val setIds = session.exercises.flatMap { ex -> ex.sets.map { s -> s.id } }.toSet()
            session.id to setIds
        }
        val telemetryBySetId = buildResult.telemetry.groupBy { it.setId }

        // 7b. Profile data for portal tagging and profile-scoped filtering
        val activeProfile = userProfileRepository.activeProfile.value
        val allProfiles = userProfileRepository.allProfiles.value
        val routineDtos = routines.map { PortalSyncAdapter.toPortalRoutine(it, userId) }
        val cycleDtos = cyclesWithContext.map {
            PortalSyncAdapter.toPortalTrainingCycle(it, userId)
        }
        val profileDtos = allProfiles.map { LocalProfileDto(it.id, it.name, it.colorIndex) }

        // 8. Chunked push -- batch sessions to stay under Edge Function body limit (~1 MB).
        //    Non-session data (routines, cycles, badges, RPG, gamification, signatures, assessments)
        //    is included only in the final batch to avoid duplicate upserts.
        //    IMPORTANT: We do NOT update lastSync until ALL batches succeed. This prevents
        //    data consistency gaps where a partial batch sequence leaves the timestamp
        //    advanced but later batches uncommitted (audit 4.1 fix).
        val allSessions = buildResult.sessions
        val totalBatches = if (allSessions.size > SYNC_BATCH_SIZE) {
            (allSessions.size + SYNC_BATCH_SIZE - 1) / SYNC_BATCH_SIZE
        } else {
            1
        }

        Logger.d("SyncManager") {
            "Pushing portal payload: ${allSessions.size} sessions ($totalBatches batch(es)), " +
                "${buildResult.telemetry.size} telemetry points, " +
                "${routineDtos.size} routines, ${cycleDtos.size} cycles, " +
                "${phaseStatsBySessionId.size} sessions with phase stats, " +
                "${signatureDtos.size} signatures, " +
                "${assessmentDtos.size} assessments"
        }

        var lastResponse: PortalSyncPushResponse? = null

        if (allSessions.size <= SYNC_BATCH_SIZE) {
            // --- Single-push fast path (most common case) ---
            val payload = PortalSyncPayload(
                deviceId = deviceId,
                platform = platform,
                lastSync = lastSync,
                sessions = allSessions,
                telemetry = buildResult.telemetry,
                routines = routineDtos,
                cycles = cycleDtos,
                rpgAttributes = rpgDto,
                badges = badgeDtos,
                gamificationStats = gamStatsDto,
                phaseStatistics = phaseStatsBySessionId.values.flatten(),
                exerciseSignatures = signatureDtos,
                assessments = assessmentDtos,
                profileId = activeProfile?.id,
                profileName = activeProfile?.name,
                allProfiles = profileDtos,
                externalActivities = externalActivityDtos,
            )
            val result = apiClient.pushPortalPayload(payload)
            if (result.isFailure) return result
            lastResponse = result.getOrThrow()
            // Single-batch success - reset retry tracking
            consecutiveFullRetries = 0
            lastFailedBatchHash = null
        } else {
            // --- Batched push for large history syncs ---
            val batches = allSessions.chunked(SYNC_BATCH_SIZE)
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
                    cycles = if (isLastBatch) cycleDtos else emptyList(),
                    rpgAttributes = if (isLastBatch) rpgDto else null,
                    badges = if (isLastBatch) badgeDtos else emptyList(),
                    gamificationStats = if (isLastBatch) gamStatsDto else null,
                    phaseStatistics = batchPhaseStats,
                    exerciseSignatures = if (isLastBatch) signatureDtos else emptyList(),
                    assessments = if (isLastBatch) assessmentDtos else emptyList(),
                    profileId = activeProfile?.id,
                    profileName = activeProfile?.name,
                    allProfiles = if (isLastBatch) profileDtos else null,
                    externalActivities = if (isLastBatch) externalActivityDtos else emptyList(),
                )

                val result = apiClient.pushPortalPayload(payload)
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
                                (error as? PortalApiException)?.statusCode
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

        return Result.success(lastResponse!!)
        // No updateServerIds() -- portal uses client-provided UUIDs
    }

    /**
     * Pull portal data and merge into local database with pagination support.
     *
     * The method loops through pages until hasMore is false, merging each page atomically.
     * Only returns success with the final syncTime when ALL pages complete successfully.
     *
     * @param lastSync Unix epoch ms of last successful sync
     * @return Result with final syncTime on success, or failure with classified error
     */
    private suspend fun pullRemoteChangesWithResult(lastSync: Long): Result<Long> {
        val deviceId = tokenStorage.getDeviceId()
        val activeProfileId = userProfileRepository.activeProfile.value?.id
        val mergeProfileId = activeProfileId ?: "default"

        var pagesProcessed = 0
        var totalEntitiesFetched = 0
        var currentCursor: String? = null
        var finalSyncTime: Long = 0

        // Pagination loop: fetch pages until hasMore is false
        while (true) {
            // Early exit on coroutine cancellation
            currentCoroutineContext().ensureActive()

            // Infinite loop prevention
            if (pagesProcessed >= SyncConfig.MAX_PAGES) {
                val error = PortalApiException(
                    "Pull exceeded maximum page limit (${SyncConfig.MAX_PAGES}). " +
                        "Processed $totalEntitiesFetched entities across $pagesProcessed pages. " +
                        "This may indicate a data issue - please contact support.",
                )
                Logger.e("SyncManager") { error.message!! }
                return Result.failure(error)
            }

            // Emit progress state for UI feedback
            if (pagesProcessed > 0) {
                _syncState.value = SyncState.SyncingWithProgress(
                    pagesProcessed = pagesProcessed,
                    entitiesFetched = totalEntitiesFetched,
                )
            }

            // Fetch next page
            val pullResult = apiClient.pullPortalPayload(
                lastSync = lastSync,
                deviceId = deviceId,
                profileId = activeProfileId,
                cursor = currentCursor,
                pageSize = SyncConfig.DEFAULT_PAGE_SIZE,
            )

            if (pullResult.isFailure) {
                val error = pullResult.exceptionOrNull() ?: PortalApiException("Pull failed")
                Logger.w("SyncManager") {
                    "Pull page ${pagesProcessed + 1} failed (cursor=$currentCursor): ${error.message}"
                }
                // Note: We don't store cursor for resume here - the caller (retryPull) will
                // restart from the beginning. For resume-on-failure, we'd need to persist
                // the cursor to storage, which is a more complex feature.
                return Result.failure(error)
            }

            val pullResponse = pullResult.getOrThrow()
            pagesProcessed++

            // Count entities in this page
            val pageEntityCount = pullResponse.sessions.size +
                pullResponse.routines.size +
                pullResponse.cycles.size +
                pullResponse.badges.size +
                pullResponse.personalRecords.size +
                (if (pullResponse.rpgAttributes != null) 1 else 0) +
                (if (pullResponse.gamificationStats != null) 1 else 0) +
                pullResponse.externalActivities.size
            totalEntitiesFetched += pageEntityCount

            // Empty page warning (shouldn't happen in normal operation)
            if (pageEntityCount == 0 && pullResponse.hasMore) {
                Logger.w("SyncManager") {
                    "Pull page $pagesProcessed returned empty but hasMore=true. Breaking to prevent infinite loop."
                }
                // Treat as end of pagination
                finalSyncTime = pullResponse.syncTime
                break
            }

            Logger.d("SyncManager") {
                "Pull page $pagesProcessed: ${pullResponse.sessions.size} sessions, " +
                    "${pullResponse.routines.size} routines, ${pullResponse.cycles.size} cycles, " +
                    "${pullResponse.badges.size} badges, hasMore=${pullResponse.hasMore}"
            }

            // Merge this page atomically
            val mergeResult = mergePullPage(pullResponse, lastSync, mergeProfileId)
            if (mergeResult.isFailure) {
                // Map Result<Unit> to Result<Long> for consistent return type
                return Result.failure(mergeResult.exceptionOrNull() ?: PortalApiException("Merge failed"))
            }

            // Update pagination state
            finalSyncTime = pullResponse.syncTime

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
                // hasMore=true but no cursor - should not happen, break to prevent infinite loop
                Logger.w("SyncManager") {
                    "Pull page $pagesProcessed has hasMore=true but no nextCursor. Breaking."
                }
                break
            }
        }

        return Result.success(finalSyncTime)
    }

    /**
     * Merge a single pull page atomically into local database.
     * Extracted from pullRemoteChangesWithResult for pagination support.
     */
    private suspend fun mergePullPage(
        pullResponse: PortalSyncPullResponse,
        lastSync: Long,
        mergeProfileId: String,
    ): Result<Unit> {
        // ====================================================================================
        // ATOMIC MERGE: All SyncRepository-managed entities are merged in a single transaction.
        // This ensures all-or-nothing semantics: if any entity type fails, the entire page
        // rolls back to prevent partial state.
        // ====================================================================================

        // 1. Prepare sessions with exercise lookup (pre-transaction to avoid DB calls in transaction)
        var unmatchedExerciseCount = 0
        val unmatchedExerciseNames = mutableSetOf<String>()
        val mobileSessions = pullResponse.sessions.flatMap { portalSession ->
            PortalPullAdapter.toWorkoutSessionsWithLookup(
                portalSession,
                mergeProfileId,
            ) { name, muscleGroup ->
                val exerciseId = syncRepository.findExerciseId(name, muscleGroup)
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
        val prDtos = pullResponse.personalRecords.map { PortalPullAdapter.toPersonalRecordSyncDto(it) }
        val gamificationStatsDto = pullResponse.gamificationStats?.let {
            PortalPullAdapter.toGamificationStatsSyncDto(it)
        }

        // 3. Execute atomic merge (all or nothing)
        try {
            syncRepository.mergeAllPullData(
                sessions = mobileSessions,
                routines = pullResponse.routines,
                cycles = pullResponse.cycles,
                badges = badgeDtos,
                gamificationStats = gamificationStatsDto,
                personalRecords = prDtos,
                lastSync = lastSync,
                profileId = mergeProfileId,
            )

            Logger.d("SyncManager") {
                "Atomic merge complete: ${mobileSessions.size} sessions (${mobileSessions.count { it.exerciseId != null }} with exerciseId), " +
                    "${pullResponse.routines.size} routines, ${pullResponse.cycles.size} cycles, " +
                    "${pullResponse.badges.size} badges, ${prDtos.size} PRs"
            }
        } catch (e: Exception) {
            Logger.e(e) { "Atomic merge failed - transaction rolled back. No entities were persisted." }
            return Result.failure(PortalApiException("Pull merge failed: ${e.message}"))
        }

        // ====================================================================================
        // NON-ATOMIC MERGES: RPG attributes and external activities are managed by separate
        // repositories. They are merged after the atomic transaction since they have different
        // conflict resolution strategies and don't need to be atomic with core sync data.
        // If these fail, the core sync data is still preserved.
        // ====================================================================================

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

        // External activities — upsert from portal (needsSync = false since already on server)
        if (pullResponse.externalActivities.isNotEmpty()) {
            val activities = pullResponse.externalActivities.map { dto ->
                com.devil.phoenixproject.domain.model.ExternalActivity(
                    id = dto.id,
                    externalId = dto.externalId,
                    provider = IntegrationProvider.fromKey(
                        dto.provider,
                    ) ?: IntegrationProvider.HEVY,
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
        }

        return Result.success(Unit)
    }

    private fun getPlatformName(): String {
        val platformName = getPlatform().name.lowercase()
        return when {
            platformName.contains("android") -> "android"
            platformName.contains("ios") -> "ios"
            else -> platformName
        }
    }
}
