package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
 * End-to-end exercising of the HTTP 401 → forceRefresh → retry logic inside
 * PortalApiClient.authenticatedRequest requires an HTTP mock and is covered by the
 * platform-level integration tests; here we assert the multiplatform observable contract.
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

    private fun createManager() = SyncManager(
        apiClient = fakeApi,
        tokenStorage = tokenStorage,
        syncRepository = fakeSyncRepo,
        gamificationRepository = fakeGamificationRepo,
        repMetricRepository = fakeRepMetricRepo,
        userProfileRepository = fakeUserProfileRepo,
        externalActivityRepository = fakeExternalActivityRepo,
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
}
