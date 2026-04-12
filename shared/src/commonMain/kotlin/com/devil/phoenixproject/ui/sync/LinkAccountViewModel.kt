package com.devil.phoenixproject.ui.sync

import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * ViewModel for account linking/authentication operations.
 *
 * ## Lifecycle Management
 * This ViewModel uses a [SupervisorJob]-backed coroutine scope for proper lifecycle management.
 * Callers MUST invoke [clear] when the ViewModel is no longer needed to cancel pending coroutines.
 *
 * ### Platform-Specific Usage
 *
 * **Android**: Wrap in an AndroidX ViewModel and call `clear()` from `onCleared()`:
 * ```kotlin
 * class AndroidLinkAccountViewModel(syncManager: SyncManager) : ViewModel() {
 *     private val delegate = LinkAccountViewModel(syncManager)
 *     val uiState = delegate.uiState
 *     // ... delegate other members
 *     override fun onCleared() { delegate.clear() }
 * }
 * ```
 *
 * **iOS (SwiftUI)**: Call `clear()` from `onDisappear` or view's `deinit`:
 * ```swift
 * .onDisappear { viewModel.clear() }
 * ```
 */
class LinkAccountViewModel(private val syncManager: SyncManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val _uiState = MutableStateFlow<LinkAccountUiState>(LinkAccountUiState.Initial)
    val uiState: StateFlow<LinkAccountUiState> = _uiState.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = syncManager.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = syncManager.currentUser
    val syncState: StateFlow<SyncState> = syncManager.syncState
    val lastSyncTime: StateFlow<Long> = syncManager.lastSyncTime

    /** Auth events for UI notification (session expiry, refresh failure, logout). */
    val authEvents = syncManager.authEvents

    /** Returns true if the ViewModel has been cleared and should not be used. */
    val isCleared: Boolean
        get() = !job.isActive

    fun login(email: String, password: String) {
        scope.launch {
            try {
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
            } catch (e: CancellationException) {
                // Coroutine cancelled during clear() - preserve original cancellation
                throw e
            }
        }
    }

    fun signup(email: String, password: String, displayName: String) {
        scope.launch {
            try {
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
            } catch (e: CancellationException) {
                // Coroutine cancelled during clear() - preserve original cancellation
                throw e
            }
        }
    }

    fun logout() {
        syncManager.logout()
        _uiState.value = LinkAccountUiState.Initial
    }

    fun sync() {
        scope.launch {
            try {
                syncManager.sync()
            } catch (e: CancellationException) {
                // Coroutine cancelled during clear() - preserve original cancellation
                throw e
            }
        }
    }

    fun clearError() {
        if (_uiState.value is LinkAccountUiState.Error) {
            _uiState.value = LinkAccountUiState.Initial
        }
    }

    /**
     * Clears the ViewModel, cancelling all pending coroutines.
     * After calling this method, the ViewModel should not be used.
     *
     * Must be called when the ViewModel is no longer needed to prevent memory leaks.
     */
    fun clear() {
        job.cancel()
    }
}
