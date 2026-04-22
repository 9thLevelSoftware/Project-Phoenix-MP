package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.auth.OAuthProvider
import com.devil.phoenixproject.data.auth.generateOAuthPkce
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.toPortalAuthResponse
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * AuthRepository implementation using Supabase GoTrue REST API.
 *
 * ## Lifecycle Management
 * This repository is typically a **singleton** scoped to the application lifetime via Koin DI.
 * The internal [CoroutineScope] uses [SupervisorJob] for proper structured concurrency and is
 * cancelled via [close] when the repository is no longer needed (typically at app termination).
 *
 * For app-lifetime singletons, the scope leak risk is acceptable since the scope lives as long
 * as the process. However, [close] should still be called during DI cleanup or testing to ensure
 * clean shutdown of background jobs (session restoration, auth state derivation).
 */
class PortalAuthRepository(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage,
    private val userProfileRepository: UserProfileRepository,
    private val supabaseConfig: SupabaseConfig,
    private val oauthLauncher: OAuthLauncher,
) : AuthRepository {

    companion object {
        /**
         * Deep-link scheme used for OAuth redirects back to the mobile app.
         * Must match:
         *  - Android: the intent-filter scheme registered in [androidApp]'s
         *    AndroidManifest for the OAuth redirect activity.
         *  - iOS: the `callbackURLScheme` passed to ASWebAuthenticationSession.
         *  - Supabase: the redirect URL whitelist in the Supabase project's
         *    Auth → URL Configuration settings.
         */
        const val OAUTH_CALLBACK_SCHEME = "com.devil.phoenixproject"
        const val OAUTH_CALLBACK_URL = "$OAUTH_CALLBACK_SCHEME://auth-callback"
    }

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
                tokenStorage.currentUser,
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

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> = apiClient.signIn(email, password)
        .onSuccess { goTrueResponse ->
            tokenStorage.saveGoTrueAuth(goTrueResponse)
            linkUserProfile(goTrueResponse.user.id)
        }
        .map { goTrueResponse ->
            goTrueResponse.toPortalAuthResponse().user.toAuthUser()
        }

    override suspend fun signInWithGoogle(): Result<AuthUser> = signInWithOAuth(OAuthProvider.GOOGLE)

    override suspend fun signInWithApple(): Result<AuthUser> = signInWithOAuth(OAuthProvider.APPLE)

    /**
     * Run the browser-based PKCE OAuth sign-in flow against Supabase GoTrue.
     *
     * Sign-in only: if the authenticated user has no existing portal account,
     * Supabase's `/authorize` endpoint will still create one (same behavior
     * the portal uses). The mobile UI intentionally hides the OAuth buttons
     * on the sign-up tab so that mobile users always land on the sign-in
     * surface for OAuth, keeping portal and mobile identity aligned.
     */
    private suspend fun signInWithOAuth(provider: OAuthProvider): Result<AuthUser> {
        val pkce = generateOAuthPkce()
        val authorizeUrl = buildOAuthAuthorizeUrl(
            authUrl = supabaseConfig.authUrl,
            provider = provider,
            codeChallenge = pkce.challenge,
            redirectUrl = OAUTH_CALLBACK_URL,
        )

        val callbackResult = oauthLauncher.launch(authorizeUrl, OAUTH_CALLBACK_SCHEME)
        val callbackUrl = callbackResult.getOrElse { return Result.failure(it) }

        // Defence-in-depth: Android deep-link schemes can be invoked by any
        // app that targets the same intent filter, and iOS only filters by
        // scheme. Reject anything that isn't from the redirect we registered.
        if (!isExpectedOAuthCallback(callbackUrl)) {
            return Result.failure(Exception("Unexpected OAuth callback URL"))
        }

        val code = extractAuthCode(callbackUrl)
            ?: return Result.failure(
                Exception(extractErrorMessage(callbackUrl) ?: "OAuth callback missing auth code"),
            )

        return apiClient.exchangeOAuthCode(authCode = code, codeVerifier = pkce.verifier)
            .onSuccess { goTrueResponse ->
                tokenStorage.saveGoTrueAuth(goTrueResponse)
                linkUserProfile(goTrueResponse.user.id)
            }
            .map { goTrueResponse ->
                goTrueResponse.toPortalAuthResponse().user.toAuthUser()
            }
    }

    private fun isExpectedOAuthCallback(callbackUrl: String): Boolean {
        return isExpectedOAuthCallbackUrl(
            callbackUrl = callbackUrl,
            expectedScheme = OAUTH_CALLBACK_SCHEME,
        )
    }

    private fun extractAuthCode(callbackUrl: String): String? {
        return extractOAuthCallbackParam(callbackUrl, "code")
    }

    private fun extractErrorMessage(callbackUrl: String): String? {
        val description = extractOAuthCallbackParam(callbackUrl, "error_description")
        val error = extractOAuthCallbackParam(callbackUrl, "error")
        return description ?: error
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
                    it.message?.contains("invalid") == true
                ) {
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
        displayName = displayName,
    )
}

internal fun buildOAuthAuthorizeUrl(
    authUrl: String,
    provider: OAuthProvider,
    codeChallenge: String,
    redirectUrl: String,
): String {
    // Supabase Auth validates its own OAuth flow state server-side. Passing a
    // client-managed state here is redundant and breaks Apple, where Supabase
    // rewrites the provider state before the callback returns to the device.
    val redirect = urlEncodeOAuthValue(redirectUrl)
    return "$authUrl/authorize" +
        "?provider=${provider.wireName}" +
        "&redirect_to=$redirect" +
        "&code_challenge=$codeChallenge" +
        "&code_challenge_method=S256" +
        "&scopes=${urlEncodeOAuthValue(provider.scopes)}"
}

internal fun isExpectedOAuthCallbackUrl(
    callbackUrl: String,
    expectedScheme: String,
    expectedHost: String = "auth-callback",
): Boolean {
    val url = runCatching { Url(callbackUrl) }.getOrNull() ?: return false
    if (!url.protocol.name.equals(expectedScheme, ignoreCase = true)) return false
    if (!url.host.equals(expectedHost, ignoreCase = true)) return false
    return url.encodedPath.isEmpty() || url.encodedPath == "/"
}

internal fun extractOAuthCallbackParam(callbackUrl: String, key: String): String? {
    val url = runCatching { Url(callbackUrl) }.getOrNull() ?: return null
    url.parameters[key]?.let { return it }

    val fragment = callbackUrl.substringAfter('#', "")
    if (fragment.isBlank()) return null

    return fragment
        .split('&')
        .asSequence()
        .mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val parts = entry.split('=', limit = 2)
            val name = parts[0].decodeURLQueryComponent(plusIsSpace = true)
            val value = parts.getOrNull(1)?.decodeURLQueryComponent(plusIsSpace = true)
            name to value
        }
        .firstOrNull { (name, _) -> name == key }
        ?.second
}

internal fun urlEncodeOAuthValue(value: String): String = buildString {
    for (c in value) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
            else -> {
                for (b in c.toString().encodeToByteArray()) {
                    append('%')
                    append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                    append((b.toInt() and 0xF).toString(16).uppercase())
                }
            }
        }
    }
}
