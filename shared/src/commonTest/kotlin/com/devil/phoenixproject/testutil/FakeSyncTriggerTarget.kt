package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Scriptable [SyncTriggerTarget] for exercising the production SyncTriggerManager (#869).
 * Defaults: authenticated premium user who has synced before.
 */
class FakeSyncTriggerTarget : SyncTriggerTarget {
    private val _isAuthenticated = MutableStateFlow(true)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _currentUser = MutableStateFlow<PortalUser?>(
        PortalUser(id = "test", email = "test@test.com", displayName = "Test", isPremium = true),
    )
    override val currentUser: StateFlow<PortalUser?> = _currentUser

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState: StateFlow<SyncState> = _syncState

    private val _lastSyncTime = MutableStateFlow(1_000L)
    override val lastSyncTime: StateFlow<Long> = _lastSyncTime

    /** What the next [sync] returns. */
    var syncResult: Result<Long> = Result.success(1L)

    /** Thrown (instead of returning [syncResult]) by the next [sync] when set. */
    var syncThrows: Throwable? = null

    /** Keep [syncState] as scripted instead of moving it to Success on a successful sync. */
    var preserveSyncState = false

    /** Thrown by [refreshPremiumStatusFromServer] when set. */
    var refreshPremiumStatusThrows: Throwable? = null

    var syncCallCount = 0
        private set
    var refreshPremiumCallCount = 0
        private set
    var markPausedNotPremiumCallCount = 0
        private set

    override suspend fun sync(): Result<Long> {
        syncCallCount++
        syncThrows?.let { throw it }
        if (syncResult.isSuccess && !preserveSyncState) {
            _syncState.value = SyncState.Success(syncResult.getOrThrow())
        }
        return syncResult
    }

    override suspend fun refreshPremiumStatusFromServer() {
        refreshPremiumCallCount++
        refreshPremiumStatusThrows?.let { throw it }
    }

    override fun markPausedNotPremium() {
        markPausedNotPremiumCallCount++
        _syncState.value = SyncState.NotPremium
    }

    fun setAuthenticated(value: Boolean) {
        _isAuthenticated.value = value
    }

    fun setPremium(isPremium: Boolean) {
        _currentUser.value = _currentUser.value?.copy(isPremium = isPremium)
    }

    fun setSyncState(state: SyncState) {
        _syncState.value = state
    }

    fun setLastSyncTime(time: Long) {
        _lastSyncTime.value = time
    }
}
