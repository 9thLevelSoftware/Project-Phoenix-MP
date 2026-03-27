package com.devil.phoenixproject.ui.sync

import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LinkAccountUiState {
    object Initial : LinkAccountUiState()
    object Loading : LinkAccountUiState()
    data class Success(val user: PortalUser) : LinkAccountUiState()
    data class Error(val message: String) : LinkAccountUiState()
}

class LinkAccountViewModel(private val syncManager: SyncManager) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _uiState = MutableStateFlow<LinkAccountUiState>(LinkAccountUiState.Initial)
    val uiState: StateFlow<LinkAccountUiState> = _uiState.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = syncManager.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = syncManager.currentUser
    val syncState: StateFlow<SyncState> = syncManager.syncState
    val lastSyncTime: StateFlow<Long> = syncManager.lastSyncTime

    fun login(email: String, password: String) {
        scope.launch {
            _uiState.value = LinkAccountUiState.Loading

            syncManager.login(email, password)
                .onSuccess { user ->
                    _uiState.value = LinkAccountUiState.Success(user)
                }
                .onFailure { error ->
                    _uiState.value = LinkAccountUiState.Error(
                        error.message ?: "Login failed",
                    )
                }
        }
    }

    fun signup(email: String, password: String, displayName: String) {
        scope.launch {
            _uiState.value = LinkAccountUiState.Loading

            syncManager.signup(email, password, displayName)
                .onSuccess { user ->
                    _uiState.value = LinkAccountUiState.Success(user)
                }
                .onFailure { error ->
                    _uiState.value = LinkAccountUiState.Error(
                        error.message ?: "Signup failed",
                    )
                }
        }
    }

    fun logout() {
        syncManager.logout()
        _uiState.value = LinkAccountUiState.Initial
    }

    fun sync() {
        scope.launch {
            syncManager.sync()
        }
    }

    fun clearError() {
        if (_uiState.value is LinkAccountUiState.Error) {
            _uiState.value = LinkAccountUiState.Initial
        }
    }
}
