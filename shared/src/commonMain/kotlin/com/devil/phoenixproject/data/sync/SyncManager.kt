package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import com.devil.phoenixproject.getPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
    private val repMetricRepository: RepMetricRepository
) {
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
            goTrueResponse.toPortalAuthResponse().user
        }
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalUser> {
        return apiClient.signUp(email, password, displayName).map { goTrueResponse ->
            tokenStorage.saveGoTrueAuth(goTrueResponse)
            goTrueResponse.toPortalAuthResponse().user
        }
    }

    fun logout() {
        tokenStorage.clearAuth()
        _syncState.value = SyncState.NotAuthenticated
    }

    // === Sync Operations ===

    suspend fun sync(): Result<Long> {
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing

        // Push local changes (no status check -- Railway backend abandoned)
        val pushResult = pushLocalChanges()
        if (pushResult.isFailure) {
            val error = pushResult.exceptionOrNull()
            if (error is PortalApiException && error.statusCode == 401) {
                _syncState.value = SyncState.NotAuthenticated
            } else {
                _syncState.value = SyncState.Error(error?.message ?: "Push failed")
            }
            return Result.failure(error ?: Exception("Push failed"))
        }

        // Parse syncTime from ISO 8601 to epoch millis
        val pushResponse = pushResult.getOrThrow()
        val syncTimeEpoch = kotlin.time.Instant.parse(pushResponse.syncTime).toEpochMilliseconds()

        // Pull remote changes (non-fatal — if pull fails, push syncTime is used)
        val pullSyncTime = pullRemoteChanges(lastSync = tokenStorage.getLastSyncTimestamp())
        val finalSyncTime = pullSyncTime ?: syncTimeEpoch

        tokenStorage.setLastSyncTimestamp(finalSyncTime)
        _lastSyncTime.value = finalSyncTime
        _syncState.value = SyncState.Success(finalSyncTime)

        return Result.success(finalSyncTime)
    }

    // === Private Helpers ===

    private suspend fun pushLocalChanges(): Result<PortalSyncPushResponse> {
        val userId = tokenStorage.currentUser.value?.id
            ?: return Result.failure(PortalApiException("Not authenticated", null, 401))
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val platform = getPlatformName()

        // 1. Gather workout sessions as full domain objects
        val sessions = syncRepository.getWorkoutSessionsModifiedSince(lastSync)

        // 2. Fetch PRs to determine which sessions are PRs
        val recentPRs = syncRepository.getPRsModifiedSince(lastSync)
        val prSessionKeys = recentPRs.map { pr -> "${pr.exerciseId}:${pr.achievedAt}" }.toSet()

        // 3. Build SessionWithReps (fetch rep metrics per session, detect PRs)
        val sessionsWithReps = sessions.map { session ->
            val repMetrics = repMetricRepository.getRepMetrics(session.id)
            val sessionKey = "${session.exerciseId}:${session.timestamp}"
            PortalSyncAdapter.SessionWithReps(
                session = session,
                repMetrics = repMetrics,
                muscleGroup = "General",
                isPr = sessionKey in prSessionKeys
            )
        }

        // 4. Gather routines as full domain objects
        val routines = syncRepository.getFullRoutinesModifiedSince(lastSync)

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
                totalVolumeKg = stats.totalVolumeKg.toFloat(),
                longestStreak = stats.longestStreak,
                currentStreak = stats.currentStreak,
                totalTimeSeconds = 0
            )
        }

        // 6. Build portal payload
        val payload = PortalSyncPayload(
            deviceId = deviceId,
            platform = platform,
            lastSync = lastSync,
            sessions = PortalSyncAdapter.toPortalWorkoutSessions(sessionsWithReps, userId),
            routines = routines.map { PortalSyncAdapter.toPortalRoutine(it, userId) },
            rpgAttributes = rpgDto,
            badges = badgeDtos,
            gamificationStats = gamStatsDto
        )

        // 7. Send to Edge Function
        Logger.d("SyncManager") { "Pushing portal payload: ${payload.sessions.size} sessions, ${payload.routines.size} routines, ${payload.badges.size} badges" }
        return apiClient.pushPortalPayload(payload)
        // No updateServerIds() -- portal uses client-provided UUIDs
    }

    /**
     * Pull portal data and merge into local database.
     * Sessions are skipped (immutable/push-only per PULL-03).
     * Returns the pull response syncTime on success, or null on failure.
     */
    private suspend fun pullRemoteChanges(lastSync: Long): Long? {
        val deviceId = tokenStorage.getDeviceId()

        // 1. Call pull Edge Function
        val pullResult = apiClient.pullPortalPayload(lastSync, deviceId)
        if (pullResult.isFailure) {
            Logger.w("SyncManager") {
                "Pull failed (non-fatal): ${pullResult.exceptionOrNull()?.message}"
            }
            return null
        }

        val pullResponse = pullResult.getOrThrow()
        Logger.d("SyncManager") {
            "Pull response: ${pullResponse.routines.size} routines, " +
            "${pullResponse.badges.size} badges, " +
            "sessions=${pullResponse.sessions.size} (skipped)"
        }

        // 2. Sessions — SKIPPED (immutable/push-only per PULL-03)
        // pullResponse.sessions is deserialized but not merged.

        // 3. Routines — merge with local preference (PULL-03)
        if (pullResponse.routines.isNotEmpty()) {
            syncRepository.mergePortalRoutines(pullResponse.routines, lastSync)
            Logger.d("SyncManager") { "Merged ${pullResponse.routines.size} portal routines" }
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
