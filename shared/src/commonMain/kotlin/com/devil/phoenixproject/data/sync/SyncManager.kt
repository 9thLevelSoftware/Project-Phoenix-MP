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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
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

    // === Authentication ===

    suspend fun login(email: String, password: String): Result<PortalUser> {
        val signInResult = apiClient.signIn(email, password)
        if (signInResult.isFailure) return signInResult.map { it.toPortalAuthResponse().user }

        val goTrueResponse = signInResult.getOrThrow()
        tokenStorage.saveGoTrueAuth(goTrueResponse)
        _syncState.value = SyncState.Idle // Reset stale NotAuthenticated state

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
        _syncState.value = SyncState.Idle // Reset stale NotAuthenticated state

        // New accounts start as free tier — no need to check premium status.
        // Premium status will be set after they subscribe via Paddle.
        tokenStorage.updatePremiumStatus(false)
        Logger.i("SyncManager") { "Signup successful for ${goTrueResponse.user.email}" }

        return Result.success(tokenStorage.currentUser.value ?: goTrueResponse.toPortalAuthResponse().user)
    }

    fun logout() {
        tokenStorage.clearAuth()
        tokenStorage.emitLogoutEvent()
        _syncState.value = SyncState.NotAuthenticated
    }

    // === Sync Operations ===

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
        Logger.i("SyncManager") {
            "Token expired: ${tokenStorage.isTokenExpired()}, expiresAt: ${tokenStorage.getExpiresAt()}"
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
     * Pull portal data and merge into local database.
     * Returns Result with the pull response syncTime on success, or failure with classified error.
     * This version provides full error context for proper PartialSuccess handling.
     */
    private suspend fun pullRemoteChangesWithResult(lastSync: Long): Result<Long> {
        val deviceId = tokenStorage.getDeviceId()
        val activeProfileId = userProfileRepository.activeProfile.value?.id

        // Pull remote changes filtered by active profile to prevent cross-profile contamination.
        // The server filters by local_profile_id column; merge assigns the same profileId locally.
        val pullResult = apiClient.pullPortalPayload(
            lastSync,
            deviceId,
            profileId = activeProfileId,
        )
        if (pullResult.isFailure) {
            val error = pullResult.exceptionOrNull() ?: PortalApiException("Pull failed")
            Logger.w("SyncManager") {
                "Pull failed: ${error.message}"
            }
            return Result.failure(error)
        }

        val pullResponse = pullResult.getOrThrow()
        Logger.d("SyncManager") {
            "Pull response: ${pullResponse.routines.size} routines, " +
                "${pullResponse.cycles.size} cycles, " +
                "${pullResponse.badges.size} badges, " +
                "sessions=${pullResponse.sessions.size}"
        }

        val mergeProfileId = activeProfileId ?: "default"

        // 2. Sessions — merge from portal (INSERT OR IGNORE, local data wins)
        if (pullResponse.sessions.isNotEmpty()) {
            val mobileSessions = pullResponse.sessions.flatMap { portalSession ->
                PortalPullAdapter.toWorkoutSessions(portalSession, mergeProfileId)
            }
            if (mobileSessions.isNotEmpty()) {
                syncRepository.mergePortalSessions(mobileSessions)
                Logger.d("SyncManager") {
                    "Merged ${mobileSessions.size} portal sessions from ${pullResponse.sessions.size} workouts"
                }
            }
        }

        // 3. Routines — merge with local preference (PULL-03)
        if (pullResponse.routines.isNotEmpty()) {
            syncRepository.mergePortalRoutines(pullResponse.routines, lastSync, mergeProfileId)
            Logger.d("SyncManager") { "Merged ${pullResponse.routines.size} portal routines" }
        }

        // 3b. Training cycles — server wins (portal-authoritative for cycles)
        if (pullResponse.cycles.isNotEmpty()) {
            syncRepository.mergePortalCycles(pullResponse.cycles, mergeProfileId)
            Logger.d("SyncManager") { "Merged ${pullResponse.cycles.size} portal training cycles" }
        }

        // 4. Badges — union merge (insert if not exists)
        if (pullResponse.badges.isNotEmpty()) {
            val badgeDtos = pullResponse.badges.map { PortalPullAdapter.toBadgeSyncDto(it) }
            syncRepository.mergeBadges(badgeDtos, mergeProfileId)
            Logger.d("SyncManager") { "Merged ${pullResponse.badges.size} portal badges" }
        }

        // 5. Gamification stats — server wins (overwrite local, preserve local-only fields)
        pullResponse.gamificationStats?.let { stats ->
            val statsSyncDto = PortalPullAdapter.toGamificationStatsSyncDto(stats)
            syncRepository.mergeGamificationStats(statsSyncDto, mergeProfileId)
            Logger.d("SyncManager") { "Merged portal gamification stats" }
        }

        // 5b. Personal records — merge from portal (insert if not exists, local wins)
        if (pullResponse.personalRecords.isNotEmpty()) {
            val prDtos = pullResponse.personalRecords.map { pr ->
                PortalPullAdapter.toPersonalRecordSyncDto(pr)
            }
            syncRepository.mergePersonalRecords(prDtos, mergeProfileId)
            Logger.d("SyncManager") {
                "Merged ${prDtos.size} personal records from portal"
            }
        }

        // 6. RPG attributes — server wins (overwrite local)
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

        // 7. External activities — upsert from portal (needsSync = false since already on server)
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

        return Result.success(pullResponse.syncTime)
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
