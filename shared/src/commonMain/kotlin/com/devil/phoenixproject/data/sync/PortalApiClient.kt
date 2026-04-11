package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

open class PortalApiClient(private val supabaseConfig: SupabaseConfig, private val tokenStorage: PortalTokenStorage) {

    private val refreshMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    // === GoTrue Auth Endpoints ===

    open suspend fun signIn(email: String, password: String): Result<GoTrueAuthResponse> = try {
        val response = httpClient.post("${supabaseConfig.authUrl}/token?grant_type=password") {
            header("apikey", supabaseConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(GoTruePasswordRequest(email, password))
        }
        handleGoTrueResponse(response)
    } catch (e: Exception) {
        Result.failure(PortalApiException("Sign-in failed: ${e.message}", e))
    }

    open suspend fun signUp(email: String, password: String, displayName: String?): Result<GoTrueAuthResponse> = try {
        val response = httpClient.post("${supabaseConfig.authUrl}/signup") {
            header("apikey", supabaseConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(
                GoTrueSignUpRequest(
                    email = email,
                    password = password,
                    data = displayName?.let { GoTrueUserMetadata(displayName = it) },
                ),
            )
        }
        handleGoTrueResponse(response)
    } catch (e: Exception) {
        Result.failure(PortalApiException("Sign-up failed: ${e.message}", e))
    }

    suspend fun refreshToken(refreshToken: String): Result<GoTrueAuthResponse> = try {
        val response = httpClient.post(
            "${supabaseConfig.authUrl}/token?grant_type=refresh_token",
        ) {
            header("apikey", supabaseConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(GoTrueRefreshRequest(refreshToken))
        }
        handleGoTrueResponse(response)
    } catch (e: Exception) {
        Result.failure(PortalApiException("Token refresh failed: ${e.message}", e))
    }

    suspend fun getUser(): Result<GoTrueUser> {
        return try {
            val token = tokenStorage.getToken() ?: return Result.failure(
                PortalApiException("Not authenticated", null, 401),
            )
            val response = httpClient.get("${supabaseConfig.authUrl}/user") {
                header("apikey", supabaseConfig.anonKey)
                bearerAuth(token)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body<GoTrueUser>())
            } else {
                val error = try {
                    response.body<GoTrueErrorResponse>()
                } catch (_: Exception) {
                    GoTrueErrorResponse(
                        error = "unknown",
                        errorDescription = "HTTP ${response.status.value}",
                    )
                }
                Result.failure(
                    PortalApiException(error.resolvedMessage, null, response.status.value),
                )
            }
        } catch (e: Exception) {
            Result.failure(PortalApiException("Get user failed: ${e.message}", e))
        }
    }

    suspend fun signOut(): Result<Unit> = try {
        val token = tokenStorage.getToken()
        if (token != null) {
            httpClient.post("${supabaseConfig.authUrl}/logout") {
                header("apikey", supabaseConfig.anonKey)
                bearerAuth(token)
                contentType(ContentType.Application.Json)
            }
        }
        Result.success(Unit)
    } catch (_: Exception) {
        // Sign-out failure is non-critical — we clear local state regardless
        Result.success(Unit)
    }

    // === Portal Sync Endpoints (Supabase Edge Functions) ===

    open suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> = authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-push") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(payload)
        }
    }

    open suspend fun pullPortalPayload(lastSync: Long, deviceId: String, profileId: String? = null): Result<PortalSyncPullResponse> = authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-pull") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(
                PortalSyncPullRequest(
                    deviceId = deviceId,
                    lastSync = lastSync,
                    profileId = profileId,
                ),
            )
        }
    }

    open suspend fun callIntegrationSync(request: IntegrationSyncRequest): Result<IntegrationSyncResponse> = authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-integration-sync") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(request)
        }
    }

    // === Private Helpers ===

    /**
     * Ensures the access token is valid before making an authenticated request.
     * If expired, attempts a single refresh (serialized by Mutex).
     */
    private suspend fun ensureValidToken(): String? {
        val currentToken = tokenStorage.getToken() ?: return null

        if (!tokenStorage.isTokenExpired()) return currentToken

        // Token expired — attempt refresh (serialized)
        return refreshMutex.withLock {
            // Double-check after acquiring lock (another coroutine may have refreshed)
            if (!tokenStorage.isTokenExpired()) {
                return@withLock tokenStorage.getToken()
            }

            val storedRefreshToken = tokenStorage.getRefreshToken()
                ?: run {
                    tokenStorage.clearAuth()
                    return@withLock null
                }

            val result = refreshToken(storedRefreshToken)
            result.fold(
                onSuccess = { response ->
                    tokenStorage.saveGoTrueAuth(response)
                    response.accessToken
                },
                onFailure = {
                    Logger.w("PortalApiClient") { "Token refresh failed: ${it.message}" }
                    tokenStorage.clearAuth()
                    null
                },
            )
        }
    }

    /**
     * Force a token refresh regardless of local expiry state.
     * Used when server returns 401 despite local token appearing valid.
     */
    private suspend fun forceRefresh(): String? {
        return refreshMutex.withLock {
            val storedRefreshToken = tokenStorage.getRefreshToken() ?: run {
                tokenStorage.clearAuth()
                return@withLock null
            }
            refreshToken(storedRefreshToken).fold(
                onSuccess = { response ->
                    tokenStorage.saveGoTrueAuth(response)
                    response.accessToken
                },
                onFailure = {
                    Logger.w("PortalApiClient") { "Force refresh failed: ${it.message}" }
                    tokenStorage.clearAuth()
                    null
                },
            )
        }
    }

    private suspend inline fun <reified T> authenticatedRequest(block: (token: String) -> HttpResponse): Result<T> {
        val token = ensureValidToken() ?: return Result.failure(
            PortalApiException("Not authenticated - please log in again", null, 401),
        )
        return try {
            val response = block(token)
            if (response.status.value == 401) {
                // Token was valid by our clock but server rejected — force one refresh
                val retryToken = forceRefresh()
                    ?: return Result.failure(
                        PortalApiException("Session expired - please log in again", null, 401),
                    )
                val retryResponse = block(retryToken)
                handleResponse(retryResponse)
            } else {
                handleResponse(response)
            }
        } catch (e: Exception) {
            Result.failure(PortalApiException("Request failed: ${e.message}", e))
        }
    }

    private suspend inline fun <reified T> handleGoTrueResponse(response: HttpResponse): Result<T> = if (response.status.isSuccess()) {
        Result.success(response.body<T>())
    } else {
        val errorBody = try {
            response.body<GoTrueErrorResponse>()
        } catch (_: Exception) {
            GoTrueErrorResponse(
                error = "unknown",
                errorDescription = "HTTP ${response.status.value}",
            )
        }
        Result.failure(
            PortalApiException(errorBody.resolvedMessage, null, response.status.value),
        )
    }

    private suspend inline fun <reified T> handleResponse(response: HttpResponse): Result<T> = when (response.status) {
        HttpStatusCode.OK, HttpStatusCode.Created -> {
            Result.success(response.body<T>())
        }

        HttpStatusCode.Unauthorized -> {
            Result.failure(PortalApiException("Unauthorized - please log in again", null, 401))
        }

        HttpStatusCode.Forbidden -> {
            Result.failure(PortalApiException("Premium subscription required", null, 403))
        }

        else -> {
            val error = try {
                response.body<PortalErrorResponse>().error
            } catch (_: Exception) {
                "Unknown error"
            }
            Result.failure(PortalApiException(error, null, response.status.value))
        }
    }
}

class PortalApiException(message: String, cause: Throwable? = null, val statusCode: Int? = null) : Exception(message, cause)
