package com.devil.phoenixproject.data.repository

import kotlinx.coroutines.flow.StateFlow

data class AuthUser(val id: String, val email: String?, val displayName: String?)

sealed class AuthState {
    data object Loading : AuthState()
    data object NotAuthenticated : AuthState()
    data class Authenticated(val user: AuthUser) : AuthState()
    data class Error(val message: String) : AuthState()
}

interface AuthRepository {
    val authState: StateFlow<AuthState>
    val currentUser: AuthUser?

    suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser>
    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>
    suspend fun signInWithGoogle(): Result<AuthUser>
    suspend fun signInWithApple(): Result<AuthUser>
    suspend fun signOut(): Result<Unit>
    suspend fun refreshSession(): Result<Unit>
}
