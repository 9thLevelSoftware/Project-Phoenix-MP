package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Categorizes sync errors for appropriate retry handling.
 */
enum class SyncErrorCategory {
    /** Temporary server/network issues - retry with exponential backoff */
    TRANSIENT,
    /** Permanent errors (bad request, not found) - don't retry */
    PERMANENT,
    /** Authentication expired - trigger re-login */
    AUTH,
    /** Network connectivity issues - wait for connectivity */
    NETWORK,
}

/**
 * Classified error with retry context for intelligent error handling.
 */
data class ClassifiedSyncError(
    val category: SyncErrorCategory,
    val message: String,
    val statusCode: Int? = null,
    val isRetryable: Boolean,
    val cause: Throwable? = null,
) {
    fun toException(): PortalApiException = PortalApiException(message, cause, statusCode)
}

/**
 * Classifies exceptions into sync error categories for proper handling.
 */
fun classifyError(e: Exception, context: String = "Request"): ClassifiedSyncError {
    // Handle already classified PortalApiException first
    if (e is PortalApiException) {
        return classifyByStatusCode(e.statusCode, e.message ?: context, e)
    }

    // Check exception class names for multiplatform compatibility
    val exceptionName = e::class.simpleName ?: ""

    return when {
        // Timeout exceptions - transient, retry
        e is HttpRequestTimeoutException ||
            e is ConnectTimeoutException ||
            e is SocketTimeoutException ||
            exceptionName.contains("Timeout", ignoreCase = true) -> ClassifiedSyncError(
            category = SyncErrorCategory.TRANSIENT,
            message = "$context timed out: ${e.message}",
            isRetryable = true,
            cause = e,
        )

        // Network/connectivity exceptions (check by class name for multiplatform)
        exceptionName == "UnknownHostException" ||
            exceptionName.contains("UnknownHost", ignoreCase = true) -> ClassifiedSyncError(
            category = SyncErrorCategory.NETWORK,
            message = "$context failed: Unable to resolve host",
            isRetryable = true,
            cause = e,
        )

        exceptionName == "ConnectException" ||
            exceptionName.contains("Connection", ignoreCase = true) -> ClassifiedSyncError(
            category = SyncErrorCategory.NETWORK,
            message = "$context failed: Connection error - ${e.message}",
            isRetryable = true,
            cause = e,
        )

        exceptionName.contains("IOException") ||
            exceptionName == "IOException" -> ClassifiedSyncError(
            category = SyncErrorCategory.NETWORK,
            message = "$context failed: Network error - ${e.message}",
            isRetryable = true,
            cause = e,
        )

        // Generic exceptions - assume transient
        else -> ClassifiedSyncError(
            category = SyncErrorCategory.TRANSIENT,
            message = "$context failed: ${e.message}",
            isRetryable = true,
            cause = e,
        )
    }
}

/**
 * Classifies HTTP status codes into error categories.
 */
fun classifyByStatusCode(
    statusCode: Int?,
    message: String,
    cause: Throwable? = null,
): ClassifiedSyncError {
    return when (statusCode) {
        // Auth errors - don't retry, trigger re-login
        401 -> ClassifiedSyncError(
            category = SyncErrorCategory.AUTH,
            message = message,
            statusCode = statusCode,
            isRetryable = false,
            cause = cause,
        )

        // Forbidden (premium required) - permanent for this session
        402, 403 -> ClassifiedSyncError(
            category = SyncErrorCategory.PERMANENT,
            message = message,
            statusCode = statusCode,
            isRetryable = false,
            cause = cause,
        )

        // Bad request, not found - permanent errors, don't retry
        400, 404 -> ClassifiedSyncError(
            category = SyncErrorCategory.PERMANENT,
            message = message,
            statusCode = statusCode,
            isRetryable = false,
            cause = cause,
        )

        // Rate limited - transient, retry with backoff
        429 -> ClassifiedSyncError(
            category = SyncErrorCategory.TRANSIENT,
            message = message,
            statusCode = statusCode,
            isRetryable = true,
            cause = cause,
        )

        // Server errors (500, 502, 503, 504) - transient
        in 500..599 -> ClassifiedSyncError(
            category = SyncErrorCategory.TRANSIENT,
            message = message,
            statusCode = statusCode,
            isRetryable = true,
            cause = cause,
        )

        // Unknown status - treat as transient
        else -> ClassifiedSyncError(
            category = SyncErrorCategory.TRANSIENT,
            message = message,
            statusCode = statusCode,
            isRetryable = true,
            cause = cause,
        )
    }
}

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
            // Issue 4.4: Increased from 30s to 60s for large payloads on slow connections.
            // Batch size of 50 sessions with nested telemetry can be several MB.
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000 // Socket read timeout - must be set explicitly
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
        val classified = classifyError(e, "Sign-in")
        Result.failure(classified.toException())
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
        val classified = classifyError(e, "Sign-up")
        Result.failure(classified.toException())
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
        val classified = classifyError(e, "Token refresh")
        Result.failure(classified.toException())
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
            val classified = classifyError(e, "Get user")
            Result.failure(classified.toException())
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

    /**
     * Checks premium subscription status by querying the subscriptions table.
     * Returns true if the user has an active or trialing subscription at EMBER tier or above.
     *
     * On network failure, returns null to allow callers to preserve existing premium status.
     * This prevents downgrading paid users to free tier due to transient network issues.
     */
    suspend fun checkPremiumStatus(): Result<Boolean> {
        val token = tokenStorage.getToken() ?: return Result.failure(
            PortalApiException("Not authenticated", null, 401),
        )
        return try {
            val response = httpClient.get("${supabaseConfig.url}/rest/v1/subscriptions") {
                header("apikey", supabaseConfig.anonKey)
                bearerAuth(token)
                parameter("select", "tier,status")
                parameter("status", "in.(active,trialing)")
                header("Accept", "application/json")
            }
            if (response.status.isSuccess()) {
                val subscriptions = response.body<List<SubscriptionCheckDto>>()
                // User is premium if they have any active/trialing subscription at EMBER or above
                val isPremium = subscriptions.any { sub ->
                    sub.tier in listOf("EMBER", "FLAME", "INFERNO")
                }
                Result.success(isPremium)
            } else if (response.status.value == 401) {
                Result.failure(PortalApiException("Unauthorized", null, 401))
            } else {
                // Non-auth failures should preserve existing status
                Result.failure(
                    PortalApiException("Subscription check failed: ${response.status}", null, response.status.value),
                )
            }
        } catch (e: Exception) {
            // Network failures return failure to allow callers to preserve existing premium status
            val classified = classifyError(e, "Subscription check")
            Result.failure(classified.toException())
        }
    }

    // === Portal Sync Endpoints (Supabase Edge Functions) ===

    open suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
        // fix(audit #9): self-enforce array caps + payload size before network
        // so a misbehaving client fails fast locally instead of burning an
        // Edge Function invocation for a payload the server will reject with
        // HTTP 413. Limits mirror the server constants.
        if (payload.sessions.size > SyncConfig.MAX_SESSIONS_PER_BATCH) {
            return Result.failure(
                PortalApiException(
                    "Push payload has ${payload.sessions.size} sessions; " +
                        "cap is ${SyncConfig.MAX_SESSIONS_PER_BATCH}. Caller must batch.",
                ),
            )
        }
        if (payload.routines.size > SyncConfig.MAX_ROUTINES_PER_BATCH) {
            return Result.failure(
                PortalApiException(
                    "Push payload has ${payload.routines.size} routines; " +
                        "cap is ${SyncConfig.MAX_ROUTINES_PER_BATCH}.",
                ),
            )
        }
        if (payload.cycles.size > SyncConfig.MAX_CYCLES_PER_BATCH) {
            return Result.failure(
                PortalApiException(
                    "Push payload has ${payload.cycles.size} cycles; " +
                        "cap is ${SyncConfig.MAX_CYCLES_PER_BATCH}.",
                ),
            )
        }
        if (payload.telemetry.size > SyncConfig.MAX_TELEMETRY_PER_BATCH) {
            return Result.failure(
                PortalApiException(
                    "Push payload has ${payload.telemetry.size} telemetry points; " +
                        "cap is ${SyncConfig.MAX_TELEMETRY_PER_BATCH}.",
                ),
            )
        }

        // Serialize once to measure size. Reuse the serialized bytes so we
        // do not pay the JSON cost twice.
        val serialized = Json.encodeToString(PortalSyncPayload.serializer(), payload)
        if (serialized.encodeToByteArray().size > SyncConfig.MAX_PAYLOAD_BYTES) {
            return Result.failure(
                PortalApiException(
                    "Push payload is ${serialized.encodeToByteArray().size} bytes; " +
                        "cap is ${SyncConfig.MAX_PAYLOAD_BYTES} bytes. Caller must split.",
                ),
            )
        }

        return authenticatedRequest { token ->
            httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-push") {
                bearerAuth(token)
                header("apikey", supabaseConfig.anonKey)
                header("Content-Type", "application/json")
                setBody(serialized)
            }
        }
    }

    /**
     * Pull portal data using parity-based sync.
     *
     * @param knownEntityIds Entity IDs client already has. Server returns entities NOT in these lists.
     * @param deviceId Unique device identifier
     * @param profileId Optional profile UUID for profile-scoped filtering
     * @param cursor Optional pagination cursor from previous response's nextCursor
     * @param pageSize Optional page size; null uses server default (100)
     */
    open suspend fun pullPortalPayload(
        knownEntityIds: KnownEntityIds,
        deviceId: String,
        profileId: String? = null,
        cursor: String? = null,
        pageSize: Int? = null,
    ): Result<PortalSyncPullResponse> = authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-pull") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(
                PortalSyncPullRequest(
                    deviceId = deviceId,
                    lastSync = 0, // Deprecated, using knownEntityIds instead
                    profileId = profileId,
                    cursor = cursor,
                    pageSize = pageSize,
                    knownEntityIds = knownEntityIds,
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
                    tokenStorage.clearAuthWithEvent(
                        AuthEvent.SessionExpired("No refresh token available - please log in again"),
                    )
                    return@withLock null
                }

            val result = refreshToken(storedRefreshToken)
            result.fold(
                onSuccess = { response ->
                    tokenStorage.saveGoTrueAuth(response)
                    response.accessToken
                },
                onFailure = { error ->
                    Logger.w("PortalApiClient") { "Token refresh failed: ${error.message}" }
                    // Determine if this is a recoverable network error or permanent auth failure
                    val isRecoverable = error !is PortalApiException ||
                        (error.statusCode != 401 && error.statusCode != 403)
                    tokenStorage.clearAuthWithEvent(
                        AuthEvent.RefreshFailed(
                            reason = error.message ?: "Token refresh failed",
                            isRecoverable = isRecoverable,
                        ),
                    )
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
                tokenStorage.clearAuthWithEvent(
                    AuthEvent.SessionExpired("Session expired - no refresh token available"),
                )
                return@withLock null
            }
            refreshToken(storedRefreshToken).fold(
                onSuccess = { response ->
                    tokenStorage.saveGoTrueAuth(response)
                    response.accessToken
                },
                onFailure = { error ->
                    Logger.w("PortalApiClient") { "Force refresh failed: ${error.message}" }
                    // Determine if this is a recoverable network error or permanent auth failure
                    val isRecoverable = error !is PortalApiException ||
                        (error.statusCode != 401 && error.statusCode != 403)
                    tokenStorage.clearAuthWithEvent(
                        AuthEvent.RefreshFailed(
                            reason = error.message ?: "Session refresh failed",
                            isRecoverable = isRecoverable,
                        ),
                    )
                    null
                },
            )
        }
    }

    private suspend inline fun <reified T> authenticatedRequest(block: (token: String) -> HttpResponse): Result<T> {
        val token = ensureValidToken() ?: return Result.failure(
            PortalApiException("Not authenticated - please log in again", null, 401),
        )
        // TEMP DIAGNOSTIC: Log token being used for request
        Logger.e("PortalApiClient") { "AUTH REQUEST: tokenLen=${token.length}, prefix=${token.take(20)}" }
        return try {
            val response = block(token)
            if (response.status.value == 401) {
                // Token was valid by our clock but server rejected — force one refresh
                Logger.e("PortalApiClient") { "GOT 401 - attempting forceRefresh" }
                val retryToken = forceRefresh()
                if (retryToken == null) {
                    Logger.e("PortalApiClient") { "forceRefresh returned null - session expired" }
                    return Result.failure(
                        PortalApiException("Session expired - please log in again", null, 401),
                    )
                }
                Logger.e("PortalApiClient") { "forceRefresh succeeded, retrying with new token (len=${retryToken.length})" }
                val retryResponse = block(retryToken)
                Logger.e("PortalApiClient") { "Retry response status: ${retryResponse.status}" }
                handleResponse(retryResponse)
            } else {
                handleResponse(response)
            }
        } catch (e: Exception) {
            val classified = classifyError(e, "Request")
            Result.failure(classified.toException())
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
