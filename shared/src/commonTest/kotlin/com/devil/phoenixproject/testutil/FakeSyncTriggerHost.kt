package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerHost
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ControllableClock(initialNowMillis: Long = 0L) {
    var nowMillis: Long = initialNowMillis

    fun advance(deltaMillis: Long) {
        nowMillis += deltaMillis
    }
}

class FakeSyncTriggerHost : SyncTriggerHost {
    private val _isAuthenticated = MutableStateFlow(true)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _currentUser = MutableStateFlow<PortalUser?>(
        PortalUser(id = "test", email = "test@test.com", displayName = "Test", isPremium = true),
    )
    override val currentUser: StateFlow<PortalUser?> = _currentUser

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState: StateFlow<SyncState> = _syncState

    private val _lastSyncTime = MutableStateFlow(1000L)
    override val lastSyncTime: StateFlow<Long> = _lastSyncTime

    var syncResult: Result<Long> = Result.success(currentTimeMillis())
    var syncCallCount = 0
    var preserveSyncState = false
    var refreshPremiumStatusThrows: Throwable? = null
    var refreshPremiumCallCount = 0

    override suspend fun sync(): Result<Long> {
        syncCallCount++
        if (syncResult.isSuccess && !preserveSyncState) {
            _syncState.value = SyncState.Success(syncResult.getOrThrow())
        }
        return syncResult
    }

    override suspend fun refreshPremiumStatusFromServer() {
        refreshPremiumCallCount++
        refreshPremiumStatusThrows?.let { throw it }
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

fun productionSyncTriggerManager(
    host: FakeSyncTriggerHost,
    isOnline: () -> Boolean = { true },
    clock: ControllableClock = ControllableClock(),
): SyncTriggerManager = SyncTriggerManager(
    syncManager = host,
    isOnline = isOnline,
    nowMillis = { clock.nowMillis },
)
