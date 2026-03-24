package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.CharacterClass
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
    data class Error(val message: String) : SyncState()
    object NotAuthenticated : SyncState()
    object NotPremium : SyncState()
}

class SyncManager(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage,
    private val syncRepository: SyncRepository,
    private val gamificationRepository: GamificationRepository,
    private val repMetricRepository: RepMetricRepository,
    private val userProfileRepository: UserProfileRepository
) {
    companion object {
        /**
         * Maximum sessions per sync batch. Keeps HTTP payload well under the Edge Function
         * body limit (~1 MB). Each session includes nested exercises, sets, rep summaries,
         * and linked telemetry + phase stats, so 50 sessions is a safe upper bound.
         */
        const val SYNC_BATCH_SIZE = 50
    }

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(tokenStorage.getLastSyncTimestamp())
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = tokenStorage.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = tokenStorage.currentUser

    // === Authentication ===

    suspend fun login(email: String, password: String): Result<PortalUser> {
        return apiClient.signIn(email, password).map { goTrueResponse ->
            tokenStorage.saveGoTrueAuth(goTrueResponse)
            _syncState.value = SyncState.Idle // Reset stale NotAuthenticated state
            Logger.i("SyncManager") { "Login successful for ${goTrueResponse.user.email}" }
            goTrueResponse.toPortalAuthResponse().user
        }
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalUser> {
        return apiClient.signUp(email, password, displayName).map { goTrueResponse ->
            tokenStorage.saveGoTrueAuth(goTrueResponse)
            _syncState.value = SyncState.Idle // Reset stale NotAuthenticated state
            Logger.i("SyncManager") { "Signup successful for ${goTrueResponse.user.email}" }
            goTrueResponse.toPortalAuthResponse().user
        }
    }

    fun logout() {
        tokenStorage.clearAuth()
        _syncState.value = SyncState.NotAuthenticated
    }

    // === Sync Operations ===

    suspend fun sync(): Result<Long> = syncMutex.withLock {
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return@withLock Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing

        // Push local changes (no status check -- Railway backend abandoned)
        Logger.i("SyncManager") { "Token expired: ${tokenStorage.isTokenExpired()}, expiresAt: ${tokenStorage.getExpiresAt()}" }
        val pushResult = pushLocalChanges()
        if (pushResult.isFailure) {
            val error = pushResult.exceptionOrNull()
            Logger.e("SyncManager") { "Push FAILED: status=${(error as? PortalApiException)?.statusCode}, msg=${error?.message}" }
            if (error is PortalApiException && error.statusCode == 401) {
                _syncState.value = SyncState.NotAuthenticated
            } else if (error is PortalApiException && (error.statusCode == 402 || error.statusCode == 403)) {
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
        val stampTime = currentTimeMillis()
        val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"
        val lastSyncForStamp = tokenStorage.getLastSyncTimestamp()
        val pushedSessions = syncRepository.getWorkoutSessionsModifiedSince(lastSyncForStamp, activeProfileId)
        pushedSessions.forEach { session ->
            syncRepository.updateSessionTimestamp(session.id, stampTime)
        }
        if (pushedSessions.isNotEmpty()) {
            Logger.d("SyncManager") { "Stamped ${pushedSessions.size} pushed sessions with updatedAt=$stampTime" }
        }

        // Parse syncTime from ISO 8601 to epoch millis
        val pushResponse = pushResult.getOrThrow()
        val syncTimeEpoch = try {
            kotlin.time.Instant.parse(pushResponse.syncTime).toEpochMilliseconds()
        } catch (e: Exception) {
            Logger.w(e) { "Failed to parse syncTime '${pushResponse.syncTime}', using current time" }
            currentTimeMillis()
        }

        // Pull remote changes using the STORED lastSync (before push), not the push
        // response time. Using the push response time would ask "what changed since NOW"
        // which always returns 0 results. The stored lastSync tells the server "give me
        // everything that changed since my last successful sync."
        val storedLastSync = tokenStorage.getLastSyncTimestamp()
        val pullSyncTime = pullRemoteChanges(lastSync = storedLastSync)
        val finalSyncTime = pullSyncTime ?: syncTimeEpoch

        tokenStorage.setLastSyncTimestamp(finalSyncTime)
        _lastSyncTime.value = finalSyncTime
        _syncState.value = SyncState.Success(finalSyncTime)

        Result.success(finalSyncTime)
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
                prRecord = prRecord
            )
        }

        // 4. Gather routines as full domain objects (exclude internal cycle_routine_ entries), profile-scoped
        val routines = syncRepository.getFullRoutinesModifiedSince(lastSync, activeProfileId)
            .filterNot { it.id.startsWith("cycle_routine_") }

        // 4b. Gather training cycles (all — no delta, lacks updatedAt)
        val cyclesWithContext = syncRepository.getFullCyclesForSync()

        // 5. Gather gamification data
        val rpgInput = gamificationRepository.getRpgInput()
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
            experiencePoints = 0
        )

        val earnedBadges = gamificationRepository.getEarnedBadges().first()
        val badgeDtos = earnedBadges.map { earned ->
            val badgeDef = BadgeDefinitions.getBadgeById(earned.badgeId)
            PortalEarnedBadgeSyncDto(
                userId = userId,
                badgeId = earned.badgeId,
                badgeName = badgeDef?.name ?: earned.badgeId,
                badgeDescription = badgeDef?.description,
                badgeTier = badgeDef?.tier?.name?.lowercase() ?: "bronze",
                earnedAt = kotlin.time.Instant.fromEpochMilliseconds(earned.earnedAt).toString()
            )
        }

        val legacyStats = syncRepository.getGamificationStatsForSync()
        val gamStatsDto = legacyStats?.let { stats ->
            PortalGamificationStatsSyncDto(
                userId = userId,
                totalWorkouts = stats.totalWorkouts,
                totalReps = stats.totalReps,
                totalVolumeKg = stats.totalVolumeKg,
                longestStreak = stats.longestStreak,
                currentStreak = stats.currentStreak,
                totalTimeSeconds = 0
            )
        }

        // 6. Phase 3 extended metrics (GAPs 7-9)
        val sessionIds = sessions.map { it.id }
        val phaseStatsBySessionId = syncRepository.getPhaseStatisticsForSessions(sessionIds)
            .map { PortalSyncAdapter.toPortalPhaseStatistics(it) }
            .groupBy { it.sessionId }
        val signatureDtos = syncRepository.getAllExerciseSignatures()
            .map { PortalSyncAdapter.toPortalExerciseSignature(it) }
        val assessmentDtos = syncRepository.getAllAssessments()
            .map { PortalSyncAdapter.toPortalAssessmentResult(it) }

        // 7. Build portal session + telemetry DTOs (telemetry setIds match generated exercise set IDs)
        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(sessionsWithReps, userId)

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
        val cycleDtos = cyclesWithContext.map { PortalSyncAdapter.toPortalTrainingCycle(it, userId) }
        val profileDtos = allProfiles.map { LocalProfileDto(it.id, it.name, it.colorIndex) }

        // 8. Chunked push -- batch sessions to stay under Edge Function body limit (~1 MB).
        //    Non-session data (routines, cycles, badges, RPG, gamification, signatures, assessments)
        //    is included only in the final batch to avoid duplicate upserts.
        //    Each successful batch updates the sync timestamp so a mid-sequence failure
        //    does not force a full resend of already-pushed batches.
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
                allProfiles = profileDtos
            )
            val result = apiClient.pushPortalPayload(payload)
            if (result.isFailure) return result
            lastResponse = result.getOrThrow()
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
                    allProfiles = if (isLastBatch) profileDtos else null
                )

                val result = apiClient.pushPortalPayload(payload)
                if (result.isFailure) {
                    Logger.e("SyncManager") {
                        "Batch ${index + 1}/$totalBatches failed: ${result.exceptionOrNull()?.message}"
                    }
                    return result
                }

                lastResponse = result.getOrThrow()

                // Update sync timestamp after each successful batch so retries skip
                // already-pushed sessions (their modifiedAt < this new timestamp).
                val batchSyncEpoch = try {
                    kotlin.time.Instant.parse(lastResponse!!.syncTime).toEpochMilliseconds()
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse batch syncTime, using current time" }
                    currentTimeMillis()
                }
                tokenStorage.setLastSyncTimestamp(batchSyncEpoch)
                Logger.d("SyncManager") { "Batch ${index + 1}/$totalBatches committed, syncTime=$batchSyncEpoch" }
            }
        }

        return Result.success(lastResponse!!)
        // No updateServerIds() -- portal uses client-provided UUIDs
    }

    /**
     * Pull portal data and merge into local database.
     * Returns the pull response syncTime on success, or null on failure.
     */
    private suspend fun pullRemoteChanges(lastSync: Long): Long? {
        val deviceId = tokenStorage.getDeviceId()
        val activeProfileId = userProfileRepository.activeProfile.value?.id

        // Pull remote changes filtered by active profile to prevent cross-profile contamination.
        // The server filters by local_profile_id column; merge assigns the same profileId locally.
        val pullResult = apiClient.pullPortalPayload(lastSync, deviceId, profileId = activeProfileId)
        if (pullResult.isFailure) {
            Logger.w("SyncManager") {
                "Pull failed (non-fatal): ${pullResult.exceptionOrNull()?.message}"
            }
            return null
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
                Logger.d("SyncManager") { "Merged ${mobileSessions.size} portal sessions from ${pullResponse.sessions.size} workouts" }
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
            syncRepository.mergeBadges(badgeDtos)
            Logger.d("SyncManager") { "Merged ${pullResponse.badges.size} portal badges" }
        }

        // 5. Gamification stats — server wins (overwrite local, preserve local-only fields)
        pullResponse.gamificationStats?.let { stats ->
            val statsSyncDto = PortalPullAdapter.toGamificationStatsSyncDto(stats)
            syncRepository.mergeGamificationStats(statsSyncDto)
            Logger.d("SyncManager") { "Merged portal gamification stats" }
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
                lastComputed = currentTimeMillis()
            )
            gamificationRepository.saveRpgProfile(rpgProfile)
            Logger.d("SyncManager") { "Merged portal RPG attributes: ${rpg.characterClass}" }
        }

        return pullResponse.syncTime
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
