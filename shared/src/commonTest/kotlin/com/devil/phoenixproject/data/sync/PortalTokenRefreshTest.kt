package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Tests for the token-refresh contract documented in audit 02:
 *
 *  - PortalTokenStorage.isTokenExpired() uses a 60-second buffer:
 *      (currentTimeMillis()/1000) >= (expiresAt - 60)  → expired
 *  - GoTrueAuthResponse round-trips through kotlinx.serialization using the documented
 *    snake_case field names (required so the portal refresh endpoint can parse it).
 *  - GoTrueRefreshRequest uses `refresh_token` field name on the wire.
 *  - A sync that receives HTTP 401 from the portal transitions SyncManager.state to
 *    NotAuthenticated (the documented terminal-auth behavior).
 *  - Two concurrent sync calls that each see 401 are serialized via the internal syncMutex
 *    — no thundering herd of concurrent refresh attempts.
 *
 *  - Refresh failure policy over HTTP (MockEngine against a real PortalApiClient):
 *    revoked/rotated refresh tokens (GoTrue 400 + error_code, or an error_code alone)
 *    clear auth and emit SessionExpired; 5xx, 429 and a bare non-GoTrue 400 keep the
 *    tokens and emit a recoverable RefreshFailed.
 *  - Refresh serialization and the auth generation: concurrent refreshes send one
 *    request, and a refresh in flight across a sign-out or sign-in never writes back
 *    or clears the newer auth state.
 */
class PortalTokenRefreshTest {

    private val settings = MapSettings()
    private val tokenStorage = PortalTokenStorage(settings)
    private val fakeApi = FakePortalApiClient()
    private val fakeSyncRepo = FakeSyncRepository()
    private val fakeGamificationRepo = FakeGamificationRepository()
    private val fakeRepMetricRepo = FakeRepMetricRepository()
    private val fakeUserProfileRepo = FakeUserProfileRepository()
    private val fakeExternalActivityRepo = FakeExternalActivityRepository()
    private val fakeVelocityRepo = FakeVelocityOneRepMaxRepository()
    private val fakeProfilePreferenceSyncRepo = FakeProfilePreferenceSyncRepository()

    private fun createManager() = SyncManager(
        apiClient = fakeApi,
        tokenStorage = tokenStorage,
        syncRepository = fakeSyncRepo,
        gamificationRepository = fakeGamificationRepo,
        repMetricRepository = fakeRepMetricRepo,
        userProfileRepository = fakeUserProfileRepo,
        profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
        externalActivityRepository = fakeExternalActivityRepo,
        velocityOneRepMaxRepository = fakeVelocityRepo,
        isProfilePreferenceMigrationReady = { true },
    )

    // ==================== isTokenExpired Buffer ====================

    @Test
    fun freshTokenIsNotExpired() {
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600, // 1 hour from now
                refreshToken = "refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        assertFalse(tokenStorage.isTokenExpired(), "Token valid for 1h must not be marked expired")
    }

    @Test
    fun tokenExpiringInFiftyNineSecondsIsConsideredExpired() {
        // The 60s buffer means tokens that expire within the next minute are already expired.
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 59,
                expiresAt = nowSec + 59,
                refreshToken = "refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        assertTrue(
            tokenStorage.isTokenExpired(),
            "Token that expires in 59s must be considered expired (60s buffer)",
        )
    }

    @Test
    fun tokenExpiringInTwoMinutesIsNotExpired() {
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 120,
                expiresAt = nowSec + 120,
                refreshToken = "refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        assertFalse(
            tokenStorage.isTokenExpired(),
            "Token valid for 2min must not be marked expired (>60s buffer)",
        )
    }

    @Test
    fun absentExpiresAtMeansExpired() {
        // When expiresAt is never set (0L), isTokenExpired should short-circuit to true.
        assertTrue(
            tokenStorage.isTokenExpired(),
            "Missing expiresAt → expired (prevents using a never-initialised token)",
        )
    }

    // ==================== Refresh Token Storage ====================

    @Test
    fun refreshTokenIsPersistedFromGoTrueAuthResponse() {
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access-123",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "refresh-abc",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        assertEquals("refresh-abc", tokenStorage.getRefreshToken())
        assertEquals("access-123", tokenStorage.getToken())
    }

    @Test
    fun clearAuthRemovesRefreshToken() {
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        assertNotNull(tokenStorage.getRefreshToken())

        tokenStorage.clearAuth()

        assertNull(
            tokenStorage.getRefreshToken(),
            "Logout/clearAuth must wipe the refresh token (prevents stale re-use)",
        )
        assertNull(tokenStorage.getToken(), "Access token also cleared")
        assertFalse(tokenStorage.hasToken())
    }

    // ==================== Wire Format ====================

    @Test
    fun refreshRequestUsesSnakeCaseRefreshTokenField() {
        // The portal expects {"refresh_token": "..."} per GoTrue spec.
        val body = Json.encodeToString(
            GoTrueRefreshRequest.serializer(),
            GoTrueRefreshRequest(refreshToken = "abc123"),
        )
        assertTrue(
            body.contains("\"refresh_token\""),
            "Wire field name must be snake_case 'refresh_token', got: $body",
        )
        assertTrue(body.contains("abc123"))
    }

    @Test
    fun goTrueAuthResponseParsesSnakeCaseFields() {
        val json = """
            {
              "access_token": "access-xyz",
              "token_type": "bearer",
              "expires_in": 3600,
              "expires_at": 1700000000,
              "refresh_token": "refresh-xyz",
              "user": { "id": "u-1", "email": "u@e.com" }
            }
        """.trimIndent()
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(
            GoTrueAuthResponse.serializer(),
            json,
        )
        assertEquals("access-xyz", parsed.accessToken)
        assertEquals("refresh-xyz", parsed.refreshToken)
        assertEquals(3600, parsed.expiresIn)
        assertEquals(1700000000L, parsed.expiresAt)
    }

    // ==================== Sync 401 Behavior ====================

    @Test
    fun syncReceivingUnauthorizedSetsNotAuthenticatedState() = runTest {
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        // Simulate a 401 surfacing from the portal after refresh-retry exhausted.
        fakeApi.pushResult = Result.failure(PortalApiException("Unauthorized", null, 401))

        val manager = createManager()
        val result = manager.sync()

        assertTrue(result.isFailure, "401 surfaces as failure")
        assertEquals(
            SyncState.NotAuthenticated,
            manager.syncState.value,
            "401 → SyncState.NotAuthenticated (re-login required)",
        )
    }

    @Test
    fun syncReceivingUnauthorizedClassifiesAsAuthError() {
        // The error classifier in PortalApiClient should flag 401 as AUTH so the trigger
        // manager knows to emit requiresReLogin instead of retrying.
        val classified = classifyByStatusCode(401, "Unauthorized")
        assertEquals(SyncErrorCategory.AUTH, classified.category)
        assertFalse(classified.isRetryable, "Auth errors are NOT retryable without user action")
    }

    // ==================== Concurrent Sync Calls ====================

    @Test
    fun concurrentSyncCallsAreSerializedBySyncMutex() = runTest {
        val nowSec = currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val a = async { manager.sync() }
        val b = async { manager.sync() }
        val ra = a.await()
        val rb = b.await()

        assertTrue(ra.isSuccess, "concurrent sync 1 succeeds")
        assertTrue(rb.isSuccess, "concurrent sync 2 succeeds")
        assertEquals(
            2,
            fakeApi.pushCallCount,
            "syncMutex serializes concurrent sync() calls; each gets its own push (no thundering herd, no races)",
        )
    }

    @Test
    fun syncWhenNotAuthenticatedDoesNotCallPushOrPull() = runTest {
        // No token saved.
        val manager = createManager()
        val result = manager.sync()

        assertTrue(result.isFailure, "sync without credentials fails")
        assertIs<SyncState.NotAuthenticated>(manager.syncState.value)
        assertEquals(0, fakeApi.pushCallCount, "push must not fire when unauthenticated")
        assertEquals(0, fakeApi.pullCallCount, "pull must not fire when unauthenticated")
    }

    // ==================== Refresh failure policy (HTTP) ====================

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun runHttpTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    /** Stored session whose access token is already expired, so a refresh is due. */
    private fun saveExpiredSession() {
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "old-access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = 1L,
                refreshToken = "old-refresh",
                user = GoTrueUser(id = "u", email = "u@e.com"),
            ),
        )
    }

    private var refreshRequests = 0

    private fun clientRespondingToRefresh(
        onRefresh: suspend () -> Unit = {},
        status: HttpStatusCode,
        body: String,
        headers: Headers = jsonHeaders,
    ): PortalApiClient {
        val engine = MockEngine { request ->
            assertEquals("refresh_token", request.url.parameters["grant_type"])
            refreshRequests++
            onRefresh()
            respond(body, status, headers)
        }
        return PortalApiClient(
            SupabaseConfig("https://fake.supabase.co", "anon"),
            tokenStorage,
            httpClientEngine = engine,
        )
    }

    private val refreshedSessionJson = """
        {"access_token":"new-access","token_type":"bearer","expires_in":3600,
         "expires_at":4102444800,"refresh_token":"new-refresh",
         "user":{"id":"u","email":"u@e.com"}}
    """.trimIndent()

    @Test
    fun revokedRefreshToken400ClearsAuthAndEmitsSessionExpired() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            status = HttpStatusCode.BadRequest,
            body = """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token: Refresh Token Not Found"}""",
        )
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { tokenStorage.authEvents.first() }

        val result = client.refreshIfNeeded()

        assertTrue(result.isFailure)
        assertNull(tokenStorage.getRefreshToken(), "revoked refresh token must be cleared")
        assertNull(tokenStorage.getToken())
        assertFalse(tokenStorage.isAuthenticated.value, "user must be sent back to sign-in")
        assertIs<AuthEvent.SessionExpired>(withTimeout(5_000) { firstEvent.await() }, "definitive failure emits SessionExpired")
    }

    @Test
    fun refreshErrorCodeIsDefinitiveEvenWithoutStatus400() {
        assertTrue(
            isDefinitiveRefreshFailure(
                PortalApiException("Session expired", null, 422, errorCode = "session_expired"),
            ),
        )
        assertFalse(isDefinitiveRefreshFailure(PortalApiException("Network error", null, null)))
        assertFalse(isDefinitiveRefreshFailure(PortalApiException("Rate limited", null, 429)))
    }

    @Test
    fun refresh400OnlyClearsAuthForKnownGoTrueCodes() {
        assertTrue(
            isDefinitiveRefreshFailure(
                PortalApiException("Invalid grant", null, 400, errorCode = "invalid_grant"),
            ),
        )
        assertFalse(
            isDefinitiveRefreshFailure(
                PortalApiException("Proxy rejected request", null, 400, errorCode = "proxy_error"),
            ),
        )
    }

    @Test
    fun unknownRefresh400PreservesAuthForRecovery() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            status = HttpStatusCode.BadRequest,
            body = """{"error_code":"proxy_error","msg":"upstream rejected request"}""",
        )

        val result = client.refreshIfNeeded()

        assertEquals(400, (result.exceptionOrNull() as? PortalApiException)?.statusCode)
        assertEquals("old-refresh", tokenStorage.getRefreshToken())
        assertTrue(tokenStorage.isAuthenticated.value)
    }

    @Test
    fun serverError503DuringRefreshPreservesTokens() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            status = HttpStatusCode.ServiceUnavailable,
            body = """{"msg":"upstream unavailable"}""",
        )

        val result = client.refreshIfNeeded()

        assertEquals(503, (result.exceptionOrNull() as? PortalApiException)?.statusCode)
        assertEquals("old-refresh", tokenStorage.getRefreshToken(), "transient failure keeps the refresh token")
        assertTrue(tokenStorage.isAuthenticated.value)
    }

    @Test
    fun signOutDuringInFlightRefreshLeavesUserSignedOut() = runHttpTest {
        saveExpiredSession()
        // The sign-out lands after the refresh request left but before its response
        // is written; the late response must not resurrect the session.
        val client = clientRespondingToRefresh(
            onRefresh = { tokenStorage.clearAuth() },
            status = HttpStatusCode.OK,
            body = refreshedSessionJson,
        )

        val result = client.refreshIfNeeded()

        assertTrue(result.isFailure)
        assertNull(tokenStorage.getToken(), "stale refresh must not write tokens back")
        assertNull(tokenStorage.getRefreshToken())
        assertFalse(tokenStorage.isAuthenticated.value)
    }

    @Test
    fun successfulRefreshStoresNewSession() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(status = HttpStatusCode.OK, body = refreshedSessionJson)

        assertTrue(client.refreshIfNeeded().isSuccess)
        assertEquals("new-access", tokenStorage.getToken())
        assertEquals("new-refresh", tokenStorage.getRefreshToken())
    }

    // ----- error_code parsing, bare 400s, serialization, stale failures -----

    private val otherAccountSession = GoTrueAuthResponse(
        accessToken = "other-access",
        tokenType = "bearer",
        expiresIn = 3600,
        expiresAt = 4_102_444_800L,
        refreshToken = "other-refresh",
        user = GoTrueUser(id = "other", email = "o@e.com"),
    )

    private val revoked400Body =
        """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}"""

    @Test
    fun goTrueErrorCodeFromResponseBodyIsDefinitiveOutside400() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            status = HttpStatusCode.UnprocessableEntity,
            body = """{"code":422,"error_code":"session_expired","msg":"Session expired"}""",
        )
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { tokenStorage.authEvents.first() }

        assertTrue(client.refreshIfNeeded().isFailure)

        assertNull(tokenStorage.getRefreshToken(), "error_code parsed from the body must clear auth")
        assertIs<AuthEvent.SessionExpired>(withTimeout(5_000) { firstEvent.await() })
    }

    @Test
    fun legacyInvalidGrant400IsDefinitive() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            status = HttpStatusCode.BadRequest,
            body = """{"error":"invalid_grant","error_description":"Invalid Refresh Token"}""",
        )

        assertTrue(client.refreshIfNeeded().isFailure)
        assertNull(tokenStorage.getRefreshToken())
    }

    @Test
    fun bare400WithoutGoTrueBodyIsRecoverable() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            status = HttpStatusCode.BadRequest,
            body = "<html>Bad Request</html>",
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
        )

        val result = client.refreshIfNeeded()

        assertEquals(400, (result.exceptionOrNull() as? PortalApiException)?.statusCode)
        assertEquals("old-refresh", tokenStorage.getRefreshToken(), "a proxy/CDN 400 must not sign the user out")
        assertTrue(tokenStorage.isAuthenticated.value)
    }

    @Test
    fun serverError503EmitsRecoverableRefreshFailed() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(status = HttpStatusCode.ServiceUnavailable, body = """{"msg":"down"}""")
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { tokenStorage.authEvents.first() }

        client.refreshIfNeeded()

        val event = assertIs<AuthEvent.RefreshFailed>(withTimeout(5_000) { firstEvent.await() })
        assertTrue(event.isRecoverable)
    }

    @Test
    fun rateLimited429DuringRefreshPreservesTokens() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(status = HttpStatusCode.TooManyRequests, body = """{"msg":"slow down"}""")

        val result = client.refreshIfNeeded()

        assertEquals(429, (result.exceptionOrNull() as? PortalApiException)?.statusCode)
        assertEquals("old-refresh", tokenStorage.getRefreshToken())
    }

    @Test
    fun missingRefreshTokenClearsAuthWithSessionExpired() = runHttpTest {
        saveExpiredSession()
        settings.remove("portal_refresh_token")
        val client = clientRespondingToRefresh(status = HttpStatusCode.OK, body = refreshedSessionJson)
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { tokenStorage.authEvents.first() }

        assertTrue(client.refreshIfNeeded().isFailure)

        assertFalse(tokenStorage.hasToken())
        assertEquals(0, refreshRequests, "no refresh request without a refresh token")
        assertIs<AuthEvent.SessionExpired>(withTimeout(5_000) { firstEvent.await() })
    }

    @Test
    fun concurrentRefreshesSendOneRefreshRequest() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            onRefresh = { delay(50) },
            status = HttpStatusCode.OK,
            body = refreshedSessionJson,
        )

        val a = async { client.refreshIfNeeded() }
        val b = async { client.refreshIfNeeded() }

        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
        assertEquals(1, refreshRequests, "refreshMutex + double-check: one refresh token use, not two")
    }

    @Test
    fun signInDuringInFlightRefreshKeepsNewSessionAndSucceeds() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            onRefresh = { tokenStorage.saveGoTrueAuth(otherAccountSession) },
            status = HttpStatusCode.OK,
            body = refreshedSessionJson,
        )

        val result = client.refreshIfNeeded()

        assertTrue(result.isSuccess, "the new sign-in is valid; the caller must not see 'not authenticated'")
        assertEquals("other-access", tokenStorage.getToken(), "stale refresh must not overwrite the new account")
    }

    @Test
    fun stale400AfterSignOutAndNewSignInKeepsNewSessionAndEmitsNothing() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            onRefresh = {
                tokenStorage.clearAuth()
                tokenStorage.saveGoTrueAuth(otherAccountSession)
            },
            status = HttpStatusCode.BadRequest,
            body = revoked400Body,
        )
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { tokenStorage.authEvents.first() }

        val result = client.refreshIfNeeded()
        tokenStorage.emitAuthEvent(AuthEvent.LoggedOut) // sentinel: must be the first event seen

        assertTrue(result.isSuccess)
        assertEquals("other-access", tokenStorage.getToken(), "stale 400 must not wipe the new session")
        assertEquals(AuthEvent.LoggedOut, withTimeout(5_000) { firstEvent.await() }, "no spurious SessionExpired")
    }

    @Test
    fun stale400AfterDeliberateSignOutEmitsNoSessionExpired() = runHttpTest {
        saveExpiredSession()
        val client = clientRespondingToRefresh(
            onRefresh = { tokenStorage.clearAuth() },
            status = HttpStatusCode.BadRequest,
            body = revoked400Body,
        )
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { tokenStorage.authEvents.first() }

        assertTrue(client.refreshIfNeeded().isFailure)
        tokenStorage.emitAuthEvent(AuthEvent.LoggedOut)

        assertFalse(tokenStorage.isAuthenticated.value)
        assertEquals(AuthEvent.LoggedOut, withTimeout(5_000) { firstEvent.await() }, "sign-out must not be reported as expiry")
    }
}
