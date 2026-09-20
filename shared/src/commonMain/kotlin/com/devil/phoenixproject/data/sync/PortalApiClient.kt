package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ProfileRecoverySourceSnapshot
import com.devil.phoenixproject.data.repository.ProfileRecoverySourceVerification
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Subscription tier precedence (high → low). The portal may return multiple
 * active/trialing rows for the same user across upgrade windows; callers use
 * this order to pick the highest-entitlement tier. Tier strings not present in
 * this map are treated as unknown and ignored.
 */
private val TIER_PRECEDENCE: Map<String, Int> = mapOf(
    "INFERNO" to 3,
    "FLAME" to 2,
    "EMBER" to 1,
)

/**
 * Returns the highest-ranked tier across the active subscription rows, or null
 * when the list is empty or contains only unknown tier strings. Exposed as a
 * pure function so tier precedence can be unit-tested without an HTTP stack.
 */
internal fun highestKnownTier(subscriptions: List<SubscriptionCheckDto>): String? = subscriptions
    .mapNotNull { sub -> TIER_PRECEDENCE[sub.tier]?.let { rank -> sub.tier to rank } }
    .maxByOrNull { (_, rank) -> rank }
    ?.first

private fun HttpClientConfig<*>.configurePortalHttpClient() {
    install(ContentNegotiation) {
        json(PortalWireJson)
    }
    install(HttpTimeout) {
        // Issue 4.4: Increased from 30s to 60s for large payloads on slow connections.
        // Batch size of 50 sessions with nested telemetry can be several MB.
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 60_000
    }
    defaultRequest {
        contentType(ContentType.Application.Json)
    }
}

private fun createPortalHttpClient(engine: HttpClientEngine?): HttpClient =
    if (engine == null) {
        HttpClient { configurePortalHttpClient() }
    } else {
        HttpClient(engine) { configurePortalHttpClient() }
    }

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
): ClassifiedSyncError = when (statusCode) {
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
    400, 404, 413 -> ClassifiedSyncError(
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

/** GoTrue error codes meaning the refresh token or its session is gone for good. */
private val DEFINITIVE_REFRESH_ERROR_CODES = setOf(
    "invalid_grant",
    "refresh_token_not_found",
    "refresh_token_already_used",
    "session_not_found",
    "session_expired",
)

/**
 * True when a `grant_type=refresh_token` failure means the session is revoked,
 * rotated or expired and retrying can never succeed, so auth must be cleared and
 * the user sent to sign in again:
 * - one of [DEFINITIVE_REFRESH_ERROR_CODES], whatever the status;
 * - 401/403;
 * A bare or unknown 400 (CDN/WAF/proxy) stays recoverable so an intermediary
 * incident can't sign everyone out.
 *
 * Everything else stays recoverable: 5xx, 429, and network/timeout errors, which
 * reach here as a [PortalApiException] with a null status code.
 */
internal fun isDefinitiveRefreshFailure(error: Throwable): Boolean {
    if (error !is PortalApiException) return false
    return error.errorCode in DEFINITIVE_REFRESH_ERROR_CODES ||
        error.statusCode == 401 ||
        error.statusCode == 403
}

open class PortalApiClient(
    private val supabaseConfig: SupabaseConfig,
    private val tokenStorage: PortalTokenStorage,
    httpClientEngine: HttpClientEngine? = null,
) {

    private val refreshMutex = Mutex()
    private val httpClient = createPortalHttpClient(httpClientEngine)

    // === GoTrue Auth Endpoints ===

    open suspend fun signIn(email: String, password: String): Result<GoTrueAuthResponse> = try {
        val response = httpClient.post("${supabaseConfig.authUrl}/token?grant_type=password") {
            header("apikey", supabaseConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(GoTruePasswordRequest(email, password))
        }
        handleGoTrueResponse(response)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
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
        if (e is CancellationException) throw e
        val classified = classifyError(e, "Sign-up")
        Result.failure(classified.toException())
    }

    /**
     * Exchange a PKCE auth code for a GoTrue session. Used by the OAuth
     * sign-in flow after the browser has redirected back with `?code=...`.
     *
     * The [codeVerifier] is the PKCE verifier generated before launching the
     * browser; GoTrue hashes it and compares to the challenge it received
     * in the authorize request.
     */
    open suspend fun exchangeOAuthCode(authCode: String, codeVerifier: String): Result<GoTrueAuthResponse> = try {
        val response = httpClient.post("${supabaseConfig.authUrl}/token?grant_type=pkce") {
            header("apikey", supabaseConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(GoTruePkceExchangeRequest(authCode = authCode, codeVerifier = codeVerifier))
        }
        handleGoTrueResponse(response)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        val classified = classifyError(e, "OAuth code exchange")
        Result.failure(classified.toException())
    }

    // Private: every refresh must go through refreshMutex + the auth generation
    // (refreshIfNeeded / authenticatedRequest), never straight to GoTrue.
    private suspend fun refreshToken(refreshToken: String): Result<GoTrueAuthResponse> = try {
        val response = httpClient.post(
            "${supabaseConfig.authUrl}/token?grant_type=refresh_token",
        ) {
            header("apikey", supabaseConfig.anonKey)
            contentType(ContentType.Application.Json)
            setBody(GoTrueRefreshRequest(refreshToken))
        }
        handleGoTrueResponse(response)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            // Network failures return failure to allow callers to preserve existing premium status
            val classified = classifyError(e, "Subscription check")
            Result.failure(classified.toException())
        }
    }

    /**
     * Resolves the highest active subscription tier for the current user.
     *
     * Returns `Result.success(tier)` where `tier` is one of "INFERNO", "FLAME",
     * "EMBER", or `null` (no active subscription). When the user holds multiple
     * active/trialing subscriptions simultaneously, the highest-ranked tier wins
     * per [TIER_PRECEDENCE] (INFERNO > FLAME > EMBER). Unknown tier strings are
     * ignored.
     *
     * On 401 this returns an AUTH failure; on any other network or HTTP error it
     * returns a classified failure so callers can preserve the previously known
     * tier rather than downgrading paid users on a transient hiccup.
     *
     * Mirrors [checkPremiumStatus] end-to-end but returns the tier string instead
     * of collapsing to a boolean. Used by [SyncManager] to gate Inferno-only
     * features (50 Hz force-curve telemetry sync).
     */
    open suspend fun getActiveSubscriptionTier(): Result<String?> {
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
                Result.success(highestKnownTier(subscriptions))
            } else if (response.status.value == 401) {
                Result.failure(PortalApiException("Unauthorized", null, 401))
            } else {
                Result.failure(
                    PortalApiException(
                        "Subscription tier check failed: ${response.status}",
                        null,
                        response.status.value,
                    ),
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val classified = classifyError(e, "Subscription tier check")
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

        val encoded = encodePortalSyncPayload(payload)
        if (payload.profilePreferenceSections != null) {
            encoded.preferenceElementSpans.forEach { span ->
                if (encoded.preferenceElementByteCount(span) > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
                    return Result.failure(
                        PortalApiException(
                            "SECTION_TOO_LARGE: cap=$MAX_PROFILE_PREFERENCE_SECTION_BYTES",
                            statusCode = 413,
                        ),
                    )
                }
            }
            if (encoded.rawBytes.size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
                return Result.failure(
                    PortalApiException(
                        "REQUEST_TOO_LARGE: cap=$MAX_PROFILE_PREFERENCE_REQUEST_BYTES",
                        statusCode = 413,
                    ),
                )
            }
        }
        if (encoded.rawBytes.size > SyncConfig.MAX_PAYLOAD_BYTES) {
            return Result.failure(
                PortalApiException(
                    "Push payload is ${encoded.rawBytes.size} bytes; " +
                        "cap is ${SyncConfig.MAX_PAYLOAD_BYTES} bytes. Caller must split.",
                    statusCode = 413,
                ),
            )
        }

        return authenticatedRequest { token ->
            httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-push") {
                bearerAuth(token)
                header("apikey", supabaseConfig.anonKey)
                contentType(ContentType.Application.Json)
                setBody(encoded.rawBytes)
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

    internal suspend fun verifyProfileRecoverySource(
        source: ProfileRecoverySourceSnapshot,
    ): Result<ProfileRecoverySourceVerification> = authenticatedRequest<List<ProfileRecoverySourceRpcRow>> { token ->
        httpClient.post("${supabaseConfig.url}/rest/v1/rpc/verify_profile_recovery_source") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(
                ProfileRecoverySourceRpcRequest(
                    sourceProfileId = source.sourceProfileId,
                    workoutSessionIds = source.workoutSessionIds,
                    routineIds = source.routineIds,
                    cycleIds = source.cycleIds,
                    personalRecordIds = source.personalRecordIds,
                    proofWorkoutSessionIds = source.proofWorkoutSessionIds,
                    proofRoutineIds = source.proofRoutineIds,
                    proofCycleIds = source.proofCycleIds,
                    proofPersonalRecordIds = source.proofPersonalRecordIds,
                ),
            )
        }
    }.mapCatching { rows ->
        val row = rows.singleOrNull()
            ?: throw PortalApiException("Recovery source verification returned ${rows.size} rows")
        ProfileRecoverySourceVerification(
            verified = row.verified,
            authenticatedOwnerUserId = row.authenticatedOwnerUserId,
            verifiedProofCount = row.verifiedProofCount,
        )
    }

    open suspend fun callIntegrationSync(request: IntegrationSyncRequest): Result<IntegrationSyncResponse> = authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-integration-sync") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(request)
        }
    }

    open suspend fun callIntegrationPlaygroundSimulation(
        request: IntegrationPlaygroundSimulationRequest,
    ): Result<IntegrationPlaygroundSimulationResponse> = authenticatedRequest { token ->
        httpClient.post("${supabaseConfig.url}/functions/v1/mobile-integration-playground") {
            bearerAuth(token)
            header("apikey", supabaseConfig.anonKey)
            setBody(request)
        }
    }

    // === Private Helpers ===

    /**
     * Refreshes the session if the stored access token is missing its validity
     * window. Shares [refreshMutex] with the authenticated-request path so app
     * start-up ([com.devil.phoenixproject.data.repository.PortalAuthRepository.refreshSession])
     * and a concurrent sync never send the same refresh token twice.
     *
     * Definitive refresh rejections clear auth (see [isDefinitiveRefreshFailure]);
     * transient/network failures keep the tokens and return the error.
     */
    open suspend fun refreshIfNeeded(): Result<Unit> = try {
        if (ensureValidToken() != null) {
            Result.success(Unit)
        } else {
            Result.failure(PortalApiException("Session expired - please log in again", null, 401))
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.failure(classifyError(e, "Token refresh").toException())
    }

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
            refreshWithStoredTokenLocked()
        }
    }

    /**
     * Force a token refresh regardless of local expiry state.
     * Used when server returns 401 despite local token appearing valid.
     *
     * [tokenThatFailed] is the access token that received the 401. After
     * acquiring the mutex we re-read storage: if the stored token is already
     * different, a concurrent coroutine completed the refresh while this one
     * was blocked on the lock, so we return the fresh token immediately
     * without making a second network call (double-check-after-lock pattern).
     */
    private suspend fun forceRefresh(tokenThatFailed: String): String? {
        return refreshMutex.withLock {
            // Double-check: another coroutine may have refreshed while we waited.
            val currentToken = tokenStorage.getToken()
            if (currentToken != null && currentToken != tokenThatFailed) {
                Logger.d("PortalApiClient") { "forceRefresh: token already refreshed by concurrent coroutine, reusing" }
                return@withLock currentToken
            }
            refreshWithStoredTokenLocked()
        }
    }

    /**
     * Exchanges the stored refresh token for a new session. Caller must hold
     * [refreshMutex].
     *
     * Returns the new access token, or null when the session is definitively
     * gone (auth cleared, including by a sign-out mid-flight). If a sign-in
     * replaced the session while the request was in flight, the stale result is
     * dropped and that newer session's token is returned instead.
     *
     * Rethrows transient/network failures with tokens preserved (F020/F077) so
     * callers classify them as retryable instead of a 401.
     */
    private suspend fun refreshWithStoredTokenLocked(): String? {
        // Token and generation are read atomically: a clearAuth/sign-in landing
        // at any point after this bumps the generation, so neither the success
        // write nor a definitive-failure clear from this refresh can touch the
        // newer auth state.
        val (storedRefreshToken, generation) = tokenStorage.refreshTokenWithGeneration()
        if (storedRefreshToken == null) {
            tokenStorage.clearAuthWithEvent(
                AuthEvent.SessionExpired("Session expired - no refresh token available"),
                expectedGeneration = generation,
            )
            return tokenStorage.getToken()
        }
        val error = refreshToken(storedRefreshToken).fold(
            onSuccess = { response ->
                return if (tokenStorage.saveGoTrueAuth(response, expectedGeneration = generation)) {
                    response.accessToken
                } else {
                    // Auth changed while in flight: signed out (null) or a new
                    // sign-in, whose token the caller should use instead.
                    Logger.i("PortalApiClient") { "Token refresh result dropped - auth changed while in flight" }
                    tokenStorage.getToken()
                }
            },
            onFailure = { it },
        )
        Logger.w("PortalApiClient") { "Token refresh failed: ${error.message}" }
        if (isDefinitiveRefreshFailure(error)) {
            val cleared = tokenStorage.clearAuthWithEvent(
                AuthEvent.SessionExpired(error.message ?: "Session expired - please log in again"),
                expectedGeneration = generation,
            )
            // Not cleared: auth already changed while in flight (a sign-out, so
            // no token, or a newer sign-in whose token the caller should use).
            return if (cleared) null else tokenStorage.getToken()
        }
        tokenStorage.emitAuthEvent(
            AuthEvent.RefreshFailed(
                reason = error.message ?: "Token refresh failed",
                isRecoverable = true,
            ),
        )
        throw error
    }

    private suspend inline fun <reified T> authenticatedRequest(block: (token: String) -> HttpResponse): Result<T> {
        return try {
            // ensureValidToken() may rethrow a transient refresh error; keep it
            // inside this try so classifyError reports it as transient/network
            // instead of letting it escape as an unhandled exception. A null token
            // means a definitive auth failure → surface as 401.
            val token = ensureValidToken() ?: return Result.failure(
                PortalApiException("Not authenticated - please log in again", null, 401),
            )
            Logger.d("PortalApiClient") { "AUTH REQUEST: tokenLen=${token.length}" }
            val response = block(token)
            if (response.status.value == 401) {
                // Token was valid by our clock but server rejected — force one refresh.
                // Pass the stale token so forceRefresh can skip the network call if a
                // concurrent coroutine already completed the refresh (double-check pattern).
                Logger.e("PortalApiClient") { "GOT 401 - attempting forceRefresh" }
                val retryToken = forceRefresh(token)
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
            if (e is CancellationException) throw e
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
            // Unparseable body (e.g. an HTML page from a proxy): no GoTrue code.
            GoTrueErrorResponse(errorDescription = "HTTP ${response.status.value}")
        }
        Result.failure(
            PortalApiException(
                errorBody.resolvedMessage,
                null,
                response.status.value,
                // `error_code`, or the legacy `error` field (e.g. "invalid_grant").
                errorCode = errorBody.errorCode ?: errorBody.error,
            ),
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

        HttpStatusCode.TooManyRequests -> {
            val errorBody = try {
                response.body<PortalRateLimitResponse>()
            } catch (_: Exception) {
                PortalRateLimitResponse()
            }
            val retryAfterSeconds = response.headers["Retry-After"]?.trim()?.toIntOrNull() ?: errorBody.retryAfterSeconds
            val message = errorBody.error.ifBlank { "Rate limited" }
            Result.failure(PortalApiException(message, null, 429, retryAfterSeconds))
        }

        HttpStatusCode.ServiceUnavailable -> {
            val errorBody = try {
                response.body<PortalRateLimitResponse>()
            } catch (_: Exception) {
                PortalRateLimitResponse()
            }
            val retryAfterSeconds = response.headers["Retry-After"]?.trim()?.toIntOrNull() ?: 30
            val message = errorBody.error.ifBlank { "Service unavailable" }
            Result.failure(PortalApiException(message, null, 503, retryAfterSeconds))
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

class PortalApiException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val retryAfterSeconds: Int? = null,
    /** GoTrue `error_code` (or legacy `error`), e.g. `refresh_token_not_found`, when the auth server sent one. */
    val errorCode: String? = null,
) : Exception(message, cause)
