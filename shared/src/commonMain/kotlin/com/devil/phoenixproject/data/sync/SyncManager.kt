package com.devil.phoenixproject.data.sync

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
    private val tokenStorage: PortalTokenStorage
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

        // TODO: Gather local changes from repositories
        val request = SyncPushRequest(
            deviceId = deviceId,
            deviceName = getDeviceName(),
            platform = platform,
            lastSync = lastSync,
            sessions = emptyList(), // TODO: Get from WorkoutRepository
            records = emptyList(),  // TODO: Get from PersonalRecordRepository
            routines = emptyList(), // TODO: Get from RoutineRepository
            exercises = emptyList(), // TODO: Get from ExerciseRepository (custom only)
            badges = emptyList(),   // TODO: Get from GamificationRepository
            gamificationStats = null // TODO: Get from GamificationRepository
        )

        return apiClient.pushChanges(request)
    }

    private suspend fun pullRemoteChanges(): Result<Long> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()

        val request = SyncPullRequest(
            deviceId = deviceId,
            lastSync = lastSync
        )

        return apiClient.pullChanges(request).map { response ->
            // TODO: Merge pulled data into local repositories
            // - WorkoutRepository.mergeFromSync(response.sessions)
            // - PersonalRecordRepository.mergeFromSync(response.records)
            // etc.

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

    private fun getDeviceName(): String? {
        // TODO: Get actual device name from platform
        return null
    }
}
