package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.PortalUser
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
 * AuthRepository implementation using the Railway backend Portal API.
 */
class PortalAuthRepository(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage
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
                        // Has token but no user loaded yet
                        AuthState.Loading
                    }
                    else -> AuthState.NotAuthenticated
                }
            }.collect { state ->
                _authState.value = state
            }
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> {
        val displayName = email.substringBefore("@")
        return apiClient.signup(email, password, displayName)
            .onSuccess { response ->
                tokenStorage.saveAuth(response)
            }
            .map { response ->
                response.user.toAuthUser()
            }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
        return apiClient.login(email, password)
            .onSuccess { response ->
                tokenStorage.saveAuth(response)
            }
            .map { response ->
                response.user.toAuthUser()
            }
    }

    override suspend fun signInWithGoogle(): Result<AuthUser> {
        // Platform-specific implementation needed - same as SupabaseAuthRepository
        return Result.failure(NotImplementedError("Google sign-in requires platform implementation"))
    }

    override suspend fun signInWithApple(): Result<AuthUser> {
        // Platform-specific implementation needed - same as SupabaseAuthRepository
        return Result.failure(NotImplementedError("Apple sign-in requires platform implementation"))
    }

    override suspend fun signOut(): Result<Unit> {
        tokenStorage.clearAuth()
        return Result.success(Unit)
    }

    override suspend fun refreshSession(): Result<Unit> {
        // Verify token is still valid by calling getMe()
        return apiClient.getMe()
            .map { /* Token is valid, nothing to do */ }
            .onFailure {
                // Token is invalid, clear auth
                if (it.message?.contains("Unauthorized") == true) {
                    tokenStorage.clearAuth()
                }
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
