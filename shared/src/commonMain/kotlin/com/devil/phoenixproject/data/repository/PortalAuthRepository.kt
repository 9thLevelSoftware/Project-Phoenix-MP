package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.toPortalAuthResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AuthRepository implementation using Supabase GoTrue REST API.
 */
class PortalAuthRepository(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage,
    private val userProfileRepository: UserProfileRepository
) : AuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val currentUser: AuthUser?
        get() = tokenStorage.currentUser.value?.toAuthUser()

    init {
        // Derive auth state from token storage flows
        scope.launch {
            combine(
                tokenStorage.isAuthenticated,
                tokenStorage.currentUser
            ) { isAuthenticated, portalUser ->
                when {
                    portalUser != null && isAuthenticated -> {
                        AuthState.Authenticated(portalUser.toAuthUser())
                    }
                    isAuthenticated && portalUser == null -> {
                        AuthState.Loading
                    }
                    else -> AuthState.NotAuthenticated
                }
            }.collect { state ->
                _authState.value = state
            }
        }

        // Attempt session restoration on construction (non-blocking)
        scope.launch {
            restoreSession()
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> {
        val displayName = email.substringBefore("@")
        return apiClient.signUp(email, password, displayName)
            .onSuccess { goTrueResponse ->
                tokenStorage.saveGoTrueAuth(goTrueResponse)
                linkUserProfile(goTrueResponse.user.id)
            }
            .map { goTrueResponse ->
                goTrueResponse.toPortalAuthResponse().user.toAuthUser()
            }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
        return apiClient.signIn(email, password)
            .onSuccess { goTrueResponse ->
                tokenStorage.saveGoTrueAuth(goTrueResponse)
                linkUserProfile(goTrueResponse.user.id)
            }
            .map { goTrueResponse ->
                goTrueResponse.toPortalAuthResponse().user.toAuthUser()
            }
    }

    override suspend fun signInWithGoogle(): Result<AuthUser> {
        return Result.failure(NotImplementedError("Google sign-in requires platform implementation"))
    }

    override suspend fun signInWithApple(): Result<AuthUser> {
        return Result.failure(NotImplementedError("Apple sign-in requires platform implementation"))
    }

    override suspend fun signOut(): Result<Unit> {
        apiClient.signOut()
        tokenStorage.clearAuth()
        return Result.success(Unit)
    }

    override suspend fun refreshSession(): Result<Unit> {
        val refreshToken = tokenStorage.getRefreshToken()
            ?: return Result.failure(Exception("No refresh token available"))

        return apiClient.refreshToken(refreshToken)
            .onSuccess { goTrueResponse ->
                tokenStorage.saveGoTrueAuth(goTrueResponse)
            }
            .map { }
            .onFailure {
                if (it.message?.contains("Unauthorized") == true ||
                    it.message?.contains("invalid") == true) {
                    tokenStorage.clearAuth()
                }
            }
    }

    /**
     * Restores a previous session on app startup.
     * If access_token exists but is expired, attempts a silent refresh.
     */
    suspend fun restoreSession(): Result<Unit> {
        if (!tokenStorage.hasToken()) {
            return Result.success(Unit)
        }

        if (tokenStorage.isTokenExpired()) {
            return refreshSession()
        }

        return Result.success(Unit)
    }

    private suspend fun linkUserProfile(supabaseUserId: String) {
        try {
            val activeProfile = userProfileRepository.activeProfile.value
            if (activeProfile != null && activeProfile.supabaseUserId != supabaseUserId) {
                userProfileRepository.linkToSupabase(activeProfile.id, supabaseUserId)
            }
        } catch (e: Exception) {
            Logger.w("PortalAuthRepository") { "Failed to link user profile: ${e.message}" }
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun PortalUser.toAuthUser(): AuthUser = AuthUser(
        id = id,
        email = email,
        displayName = displayName
    )
}
