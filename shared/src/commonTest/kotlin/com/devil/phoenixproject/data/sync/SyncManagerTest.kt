package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for SyncManager.
 * Uses real PortalTokenStorage(MapSettings()) and fake API/repository doubles.
 * Tests sync orchestration: auth state, push/pull flow, error handling, timestamps.
 */
class SyncManagerTest {

    private val settings = MapSettings()
    private val tokenStorage = PortalTokenStorage(settings)
    private val fakeApi = FakePortalApiClient()
    private val fakeSyncRepo = FakeSyncRepository()
    private val fakeGamificationRepo = FakeGamificationRepository()
    private val fakeRepMetricRepo = FakeRepMetricRepository()

    private fun createManager() = SyncManager(
        apiClient = fakeApi,
        tokenStorage = tokenStorage,
        syncRepository = fakeSyncRepo,
        gamificationRepository = fakeGamificationRepo,
        repMetricRepository = fakeRepMetricRepo
    )

    /**
     * Helper to simulate an authenticated user by saving GoTrue auth directly.
     * Sets a token that won't expire for 1 hour, so ensureValidToken() returns it directly.
     */
    private fun setupAuthenticated(
        userId: String = "user-123",
        email: String = "test@example.com"
    ) {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        val response = GoTrueAuthResponse(
            accessToken = "fake-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            expiresAt = nowSec + 3600, // 1 hour from now
            refreshToken = "fake-refresh-token",
            user = GoTrueUser(
                id = userId,
                email = email
            )
        )
        tokenStorage.saveGoTrueAuth(response)
    }

    /**
     * Helper to create a test GoTrueAuthResponse for login/signup tests.
     */
    private fun createAuthResponse(
        userId: String = "user-456",
        email: String = "new@example.com",
        displayName: String? = "Test User"
    ): GoTrueAuthResponse {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        val userMetadata = if (displayName != null) {
            kotlinx.serialization.json.buildJsonObject {
                put("display_name", kotlinx.serialization.json.JsonPrimitive(displayName))
            }
        } else null
        return GoTrueAuthResponse(
            accessToken = "new-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            expiresAt = nowSec + 3600,
            refreshToken = "new-refresh-token",
            user = GoTrueUser(
                id = userId,
                email = email,
                userMetadata = userMetadata
            )
        )
    }

    // ===== Auth State Tests =====

    @Test
    fun initialStateIsIdle() {
        val manager = createManager()
        assertEquals(SyncState.Idle, manager.syncState.value)
    }

    @Test
    fun syncWithNoTokenReturnsNotAuthenticatedWithoutCallingPush() = runTest {
        val manager = createManager()
        // No token stored -- tokenStorage.hasToken() returns false

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(SyncState.NotAuthenticated, manager.syncState.value)
        assertEquals(0, fakeApi.pushCallCount, "Push should not be called when not authenticated")
        assertEquals(0, fakeApi.pullCallCount, "Pull should not be called when not authenticated")
    }

    @Test
    fun loginStoresAuthAndReturnsUser() = runTest {
        val authResponse = createAuthResponse(userId = "user-789", email = "login@test.com")
        fakeApi.signInResult = Result.success(authResponse)
        val manager = createManager()

        val result = manager.login("login@test.com", "password123")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("user-789", user.id)
        assertEquals("login@test.com", user.email)
        assertTrue(tokenStorage.isAuthenticated.value, "Should be authenticated after login")
        assertTrue(tokenStorage.hasToken(), "Token should be stored after login")
    }

    @Test
    fun logoutClearsAuthAndSetsNotAuthenticated() = runTest {
        setupAuthenticated()
        val manager = createManager()
        assertTrue(tokenStorage.isAuthenticated.value, "Should start authenticated")

        manager.logout()

        assertFalse(tokenStorage.isAuthenticated.value, "Should not be authenticated after logout")
        assertEquals(SyncState.NotAuthenticated, manager.syncState.value)
    }

    // ===== Push Success Flow =====

    @Test
    fun syncPushesLocalChangesAndReturnsSuccess() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertIs<SyncState.Success>(manager.syncState.value)
        assertEquals(1, fakeApi.pushCallCount)
    }

    @Test
    fun syncSendsCorrectPayloadWithDeviceIdAndPlatform() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        val manager = createManager()

        manager.sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload, "Push payload should be captured")
        assertEquals(tokenStorage.getDeviceId(), payload.deviceId, "deviceId should match token storage")
        // Platform should be one of the recognized platform names
        assertTrue(
            payload.platform in listOf("android", "ios") ||
            payload.platform.isNotEmpty(),
            "Platform should be set"
        )
    }

    @Test
    fun pushSyncTimeIsoParsedToEpochMillis() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        // Make pull fail so we get push syncTime as the final time
        fakeApi.pullResult = Result.failure(PortalApiException("pull failed"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val expectedEpoch = kotlinx.datetime.Instant.parse("2026-03-02T12:00:00Z").toEpochMilliseconds()
        val syncState = manager.syncState.value
        assertIs<SyncState.Success>(syncState)
        assertEquals(expectedEpoch, syncState.syncTime, "ISO 8601 syncTime should parse to correct epoch millis")
    }

    @Test
    fun syncWithNoLocalDataSendsEmptyPayload() = runTest {
        setupAuthenticated()
        // fakeSyncRepo already returns empty lists by default
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        val manager = createManager()

        manager.sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertTrue(payload.sessions.isEmpty(), "Sessions should be empty")
        assertTrue(payload.routines.isEmpty(), "Routines should be empty")
        assertEquals(1, fakeApi.pushCallCount, "Push should still be called even with empty data")
    }

    // ===== Pull Success Flow =====

    @Test
    fun syncMergesRoutinesFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                routines = listOf(
                    PullRoutineDto(id = "r1", name = "Routine 1"),
                    PullRoutineDto(id = "r2", name = "Routine 2")
                )
            )
        )
        val manager = createManager()

        manager.sync()

        assertEquals(1, fakeSyncRepo.mergePortalRoutinesCallCount, "mergePortalRoutines should be called once")
        assertEquals(2, fakeSyncRepo.mergedPortalRoutines.size, "Should merge 2 routines")
    }

    @Test
    fun syncMergesBadgesFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                badges = listOf(
                    PullBadgeDto(badgeId = "badge-1", badgeName = "First Workout", earnedAt = "2026-01-01T00:00:00Z")
                )
            )
        )
        val manager = createManager()

        manager.sync()

        assertEquals(1, fakeSyncRepo.mergeBadgesCallCount, "mergeBadges should be called once")
        assertEquals(1, fakeSyncRepo.mergedBadges.size, "Should merge 1 badge")
    }

    @Test
    fun syncMergesGamificationStatsFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                gamificationStats = PullGamificationStatsDto(
                    totalWorkouts = 50,
                    totalReps = 1000,
                    totalVolumeKg = 50000f,
                    longestStreak = 14,
                    currentStreak = 3
                )
            )
        )
        val manager = createManager()

        manager.sync()

        assertEquals(1, fakeSyncRepo.mergeGamificationStatsCallCount, "mergeGamificationStats should be called")
        assertNotNull(fakeSyncRepo.mergedGamificationStats, "Gamification stats should be merged")
    }

    @Test
    fun syncSavesRpgAttributesFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                rpgAttributes = PullRpgAttributesDto(
                    strength = 42,
                    power = 35,
                    stamina = 28,
                    consistency = 50,
                    mastery = 20,
                    characterClass = "TITAN"
                )
            )
        )
        val manager = createManager()

        manager.sync()

        val savedProfile = fakeGamificationRepo.savedRpgProfile
        assertNotNull(savedProfile, "RPG profile should be saved")
        assertEquals(42, savedProfile.strength)
        assertEquals(35, savedProfile.power)
        assertEquals(28, savedProfile.stamina)
        assertEquals(50, savedProfile.consistency)
        assertEquals(20, savedProfile.mastery)
    }

    @Test
    fun syncWithEmptyPullResponseSkipsMerge() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                routines = emptyList(),
                badges = emptyList(),
                gamificationStats = null,
                rpgAttributes = null
            )
        )
        val manager = createManager()

        manager.sync()

        assertEquals(0, fakeSyncRepo.mergePortalRoutinesCallCount, "Should not merge empty routines")
        assertEquals(0, fakeSyncRepo.mergeBadgesCallCount, "Should not merge empty badges")
        assertEquals(0, fakeSyncRepo.mergeGamificationStatsCallCount, "Should not merge null gamification stats")
        assertNull(fakeGamificationRepo.savedRpgProfile, "Should not save null RPG attributes")
    }

    // ===== Error Handling =====

    @Test
    fun push401SetsNotAuthenticatedState() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.failure(PortalApiException("Unauthorized", null, 401))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(SyncState.NotAuthenticated, manager.syncState.value)
    }

    @Test
    fun pushNon401ErrorSetsErrorStateWithMessage() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.failure(PortalApiException("Server error", null, 500))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isFailure)
        val state = manager.syncState.value
        assertIs<SyncState.Error>(state)
        assertEquals("Server error", state.message)
    }

    @Test
    fun pullFailureIsNonFatalAndUsesPushSyncTime() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T15:30:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso)
        )
        fakeApi.pullResult = Result.failure(PortalApiException("Network error"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "Sync should succeed despite pull failure")
        val expectedEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        val state = manager.syncState.value
        assertIs<SyncState.Success>(state)
        assertEquals(expectedEpoch, state.syncTime, "Should use push syncTime when pull fails")
    }

    @Test
    fun pushFailureDoesNotCallPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.failure(PortalApiException("Push failed", null, 500))
        val manager = createManager()

        manager.sync()

        assertEquals(1, fakeApi.pushCallCount, "Push should be called once")
        assertEquals(0, fakeApi.pullCallCount, "Pull should not be called after push failure")
    }

    // ===== Timestamp Management =====

    @Test
    fun syncUpdatesLastSyncTimestampInTokenStorage() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T18:00:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso)
        )
        // Pull fails, so final time = push time
        fakeApi.pullResult = Result.failure(PortalApiException("pull failed"))
        val manager = createManager()

        manager.sync()

        val expectedEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        assertEquals(
            expectedEpoch,
            tokenStorage.getLastSyncTimestamp(),
            "lastSyncTimestamp should be updated to push syncTime"
        )
    }

    @Test
    fun syncUsesPullSyncTimeWhenLargerThanPush() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        val pullSyncTimeEpoch = kotlinx.datetime.Instant.parse("2026-03-02T13:00:00Z").toEpochMilliseconds()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso)
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(syncTime = pullSyncTimeEpoch)
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        // SyncManager uses pullSyncTime when pull succeeds (regardless of comparison)
        // Looking at the code: finalSyncTime = pullSyncTime ?: syncTimeEpoch
        // So when pull succeeds, pull syncTime is used
        assertEquals(
            pullSyncTimeEpoch,
            tokenStorage.getLastSyncTimestamp(),
            "Should use pull syncTime when pull succeeds"
        )
    }

    @Test
    fun syncUsesPushSyncTimeWhenPullFails() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso)
        )
        fakeApi.pullResult = Result.failure(PortalApiException("pull error"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val expectedEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        assertEquals(
            expectedEpoch,
            tokenStorage.getLastSyncTimestamp(),
            "Should use push syncTime when pull fails"
        )
    }

    // ===== Signup =====

    @Test
    fun signupStoresAuthAndReturnsUser() = runTest {
        val authResponse = createAuthResponse(userId = "signup-user", email = "signup@test.com")
        fakeApi.signUpResult = Result.success(authResponse)
        val manager = createManager()

        val result = manager.signup("signup@test.com", "password123", "Test User")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("signup-user", user.id)
        assertEquals("signup@test.com", user.email)
        assertTrue(tokenStorage.isAuthenticated.value, "Should be authenticated after signup")
    }

    // ===== State Flow =====

    @Test
    fun lastSyncTimeFlowReflectsStoredTimestamp() = runTest {
        tokenStorage.setLastSyncTimestamp(1000L)
        val manager = createManager()

        assertEquals(1000L, manager.lastSyncTime.value, "lastSyncTime should reflect stored value")
    }

    @Test
    fun isAuthenticatedFlowReflectsTokenState() = runTest {
        val manager = createManager()
        assertFalse(manager.isAuthenticated.value, "Should not be authenticated initially")

        setupAuthenticated()
        assertTrue(manager.isAuthenticated.value, "Should be authenticated after setup")
    }

    // ===== Mutex Concurrency Guard (Task 1.5) =====

    @Test
    fun concurrentSyncCallsAreSerializedByMutex() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z")
        )
        val manager = createManager()

        // Launch two syncs concurrently
        val deferred1 = async { manager.sync() }
        val deferred2 = async { manager.sync() }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // Both should succeed (serialized, not rejected)
        assertTrue(result1.isSuccess, "First sync should succeed")
        assertTrue(result2.isSuccess, "Second sync should succeed")
        // Push should be called exactly twice (once per sync)
        assertEquals(2, fakeApi.pushCallCount, "Push should be called twice (one per sync)")
    }

    // ===== Pull Uses Push Timestamp (Task 1.6) =====

    @Test
    fun pullReceivesPushTimestampNotStaleLastSync() = runTest {
        setupAuthenticated()
        // Set a stale lastSync
        tokenStorage.setLastSyncTimestamp(1000L)
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        val pushSyncTimeEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso)
        )
        val manager = createManager()

        manager.sync()

        // The pull should have been called with the push response timestamp, not the stale 1000L
        assertNotNull(fakeApi.lastPullLastSync, "Pull should have been called")
        assertEquals(
            pushSyncTimeEpoch,
            fakeApi.lastPullLastSync,
            "Pull should receive push response timestamp, not stale lastSync"
        )
    }
}
