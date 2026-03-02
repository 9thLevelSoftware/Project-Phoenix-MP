package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.SyncRepository
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
        val syncTimeEpoch = kotlinx.datetime.Instant.parse(pushResponse.syncTime).toEpochMilliseconds()

        // Pull is short-circuited (Railway abandoned) -- Phase 27 will wire pull to Edge Function
        Logger.i("SyncManager") { "Pull skipped (Railway abandoned, Phase 27 TODO). Using push syncTime." }

        tokenStorage.setLastSyncTimestamp(syncTimeEpoch)
        _lastSyncTime.value = syncTimeEpoch
        _syncState.value = SyncState.Success(syncTimeEpoch)

        return Result.success(syncTimeEpoch)
    }

    suspend fun checkStatus(): Result<SyncStatusResponse> {
        return Result.failure(PortalApiException("Status check not available during portal migration"))
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
                earnedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(earned.earnedAt).toString()
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

    private fun getPlatformName(): String {
        val platformName = getPlatform().name.lowercase()
        return when {
            platformName.contains("android") -> "android"
            platformName.contains("ios") -> "ios"
            else -> platformName
        }
    }
}
