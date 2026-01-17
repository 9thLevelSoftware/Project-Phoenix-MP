package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.getPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val syncRepository: SyncRepository
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(tokenStorage.getLastSyncTimestamp())
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = tokenStorage.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = tokenStorage.currentUser

    // === Authentication ===

    suspend fun login(email: String, password: String): Result<PortalUser> {
        return apiClient.login(email, password).map { response ->
            tokenStorage.saveAuth(response)
            response.user
        }
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalUser> {
        return apiClient.signup(email, password, displayName).map { response ->
            tokenStorage.saveAuth(response)
            response.user
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

        // First check status
        val statusResult = apiClient.getSyncStatus()
        if (statusResult.isFailure) {
            val error = statusResult.exceptionOrNull()
            if (error is PortalApiException && error.statusCode == 403) {
                _syncState.value = SyncState.NotPremium
            } else {
                _syncState.value = SyncState.Error(error?.message ?: "Unknown error")
            }
            return Result.failure(error ?: Exception("Status check failed"))
        }

        // Push local changes
        val pushResult = pushLocalChanges()
        if (pushResult.isFailure) {
            _syncState.value = SyncState.Error(pushResult.exceptionOrNull()?.message ?: "Push failed")
            return Result.failure(pushResult.exceptionOrNull() ?: Exception("Push failed"))
        }

        // Pull remote changes
        val pullResult = pullRemoteChanges()
        if (pullResult.isFailure) {
            _syncState.value = SyncState.Error(pullResult.exceptionOrNull()?.message ?: "Pull failed")
            return Result.failure(pullResult.exceptionOrNull() ?: Exception("Pull failed"))
        }

        val syncTime = pullResult.getOrThrow()
        tokenStorage.setLastSyncTimestamp(syncTime)
        _lastSyncTime.value = syncTime
        _syncState.value = SyncState.Success(syncTime)

        return Result.success(syncTime)
    }

    suspend fun checkStatus(): Result<SyncStatusResponse> {
        if (!tokenStorage.hasToken()) {
            return Result.failure(PortalApiException("Not authenticated"))
        }
        return apiClient.getSyncStatus()
    }

    // === Private Helpers ===

    private suspend fun pushLocalChanges(): Result<SyncPushResponse> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val platform = getPlatformName()

        // Gather local changes from repositories
        val sessions = syncRepository.getSessionsModifiedSince(lastSync)
        val records = syncRepository.getPRsModifiedSince(lastSync)
        val routines = syncRepository.getRoutinesModifiedSince(lastSync)
        val exercises = syncRepository.getCustomExercisesModifiedSince(lastSync)
        val badges = syncRepository.getBadgesModifiedSince(lastSync)
        val gamificationStats = syncRepository.getGamificationStatsForSync()

        val request = SyncPushRequest(
            deviceId = deviceId,
            deviceName = getDeviceName(),
            platform = platform,
            lastSync = lastSync,
            sessions = sessions,
            records = records,
            routines = routines,
            exercises = exercises,
            badges = badges,
            gamificationStats = gamificationStats
        )

        return apiClient.pushChanges(request).also { result ->
            // Update server IDs on success
            result.onSuccess { response ->
                syncRepository.updateServerIds(response.idMappings)
            }
        }
    }

    private suspend fun pullRemoteChanges(): Result<Long> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()

        val request = SyncPullRequest(
            deviceId = deviceId,
            lastSync = lastSync
        )

        return apiClient.pullChanges(request).map { response ->
            // Merge pulled data into local repositories
            syncRepository.mergeSessions(response.sessions)
            syncRepository.mergePRs(response.records)
            syncRepository.mergeRoutines(response.routines)
            syncRepository.mergeCustomExercises(response.exercises)
            syncRepository.mergeBadges(response.badges)
            syncRepository.mergeGamificationStats(response.gamificationStats)

            response.syncTime
        }
    }

    private fun getPlatformName(): String {
        // Returns platform name from expect/actual implementation
        val platformName = getPlatform().name.lowercase()
        return when {
            platformName.contains("android") -> "android"
            platformName.contains("ios") -> "ios"
            else -> platformName
        }
    }

    private fun getDeviceName(): String {
        return com.devil.phoenixproject.getDeviceName()
    }
}
