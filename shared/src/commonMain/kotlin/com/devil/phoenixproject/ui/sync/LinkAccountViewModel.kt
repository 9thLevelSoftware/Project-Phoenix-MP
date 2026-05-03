package com.devil.phoenixproject.ui.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.auth.OAuthProvider
import com.devil.phoenixproject.data.repository.AuthRepository
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

    /**
     * Loading state. [provider] identifies which OAuth provider triggered the
     * flow, or `null` for the email/password path. The UI uses this to render
     * a spinner inside the specific button the user tapped instead of just
     * dimming all three.
     */
    data class Loading(val provider: OAuthProvider? = null) : LinkAccountUiState()

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
class LinkAccountViewModel(
    private val syncManager: SyncManager,
    private val authRepository: AuthRepository,
) {
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
                _uiState.value = LinkAccountUiState.Loading(provider = null)

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

    /**
     * Link the portal account by signing in with Google OAuth.
     *
     * The flow runs against the same Supabase GoTrue backend the portal uses,
     * so a user who signed up on the web portal with Google lands in the
     * exact same account here — no separate provisioning required. After the
     * OAuth session is saved, we refresh premium/tier from the server so the
     * UI reflects the actual subscription state instead of defaulting to
     * free.
     *
     * @param fallbackErrorMessage localized error string used when the
     *        underlying provider didn't surface its own message.
     */
    fun loginWithGoogle(fallbackErrorMessage: String) {
        runOAuth(OAuthProvider.GOOGLE, fallbackErrorMessage) {
            authRepository.signInWithGoogle()
        }
    }

    /**
     * Link the portal account by signing in with Apple OAuth. Same
     * contract as [loginWithGoogle] — same GoTrue backend, same post-sign-in
     * premium/tier refresh.
     */
    fun loginWithApple(fallbackErrorMessage: String) {
        runOAuth(OAuthProvider.APPLE, fallbackErrorMessage) {
            authRepository.signInWithApple()
        }
    }

    private fun runOAuth(
        provider: OAuthProvider,
        fallbackErrorMessage: String,
        block: suspend () -> Result<*>,
    ) {
        scope.launch {
            try {
                _uiState.value = LinkAccountUiState.Loading(provider = provider)
                block()
                    .onSuccess { finishOAuthLink() }
                    .onFailure { error ->
                        _uiState.value = LinkAccountUiState.Error(
                            error.message ?: fallbackErrorMessage,
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /**
     * Shared post-OAuth tail. Two responsibilities:
     *
     * 1. Reset [SyncState.NotAuthenticated] left over from a prior
     *    [SyncManager.logout]. OAuth bypasses [SyncManager.login], which is
     *    where that reset normally happens, so without this the screen will
     *    render "Authentication failed" right after a successful sign-in.
     * 2. Pull premium + tier from the server so the PortalUser exposed by
     *    [currentUser] reflects real entitlement instead of the
     *    default-false values `saveGoTrueAuth` writes.
     *
     * The premium refresh is best-effort: a network failure here must NOT
     * leave the UI stuck at Loading or invalidate the sign-in itself —
     * the GoTrue session was already persisted. Wrap in `runCatching` and
     * always advance to Success/Error.
     */
    private suspend fun finishOAuthLink() {
        syncManager.resetSyncStateToIdle()
        runCatching { syncManager.refreshPremiumStatusFromServer() }
            .onFailure { e ->
                Logger.w("LinkAccountViewModel") {
                    "OAuth premium refresh failed (sign-in still succeeded): ${e.message}"
                }
            }
        val user = syncManager.currentUser.value
        _uiState.value = if (user != null) {
            LinkAccountUiState.Success(user)
        } else {
            LinkAccountUiState.Error("Signed in but user session is missing")
        }
    }

    fun signup(email: String, password: String, displayName: String) {
        scope.launch {
            try {
                _uiState.value = LinkAccountUiState.Loading(provider = null)

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
        scope.launch {
            try {
                syncManager.logout()
                _uiState.value = LinkAccountUiState.Initial
            } catch (e: CancellationException) {
                // Coroutine cancelled during clear() - preserve original cancellation
                throw e
            }
        }
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

    /**
     * Forces a complete re-sync by resetting the lastSync timestamp to 0.
     * This will pull ALL data from the server, not just changes since last sync.
     * Use this when previous syncs may have missed data.
     */
    fun forceFullResync() {
        scope.launch {
            try {
                syncManager.forceFullResync()
            } catch (e: CancellationException) {
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
