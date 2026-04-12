package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
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

    /**
     * Helper to simulate an authenticated user by saving GoTrue auth directly.
     * Sets a token that won't expire for 1 hour, so ensureValidToken() returns it directly.
     */
    private fun setupAuthenticated(userId: String = "user-123", email: String = "test@example.com") {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        val response = GoTrueAuthResponse(
            accessToken = "fake-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            expiresAt = nowSec + 3600, // 1 hour from now
            refreshToken = "fake-refresh-token",
            user = GoTrueUser(
                id = userId,
                email = email,
            ),
        )
        tokenStorage.saveGoTrueAuth(response)
    }

    /**
     * Helper to create a test GoTrueAuthResponse for login/signup tests.
     */
    private fun createAuthResponse(
        userId: String = "user-456",
        email: String = "new@example.com",
        displayName: String? = "Test User",
    ): GoTrueAuthResponse {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        val userMetadata = if (displayName != null) {
            kotlinx.serialization.json.buildJsonObject {
                put("display_name", kotlinx.serialization.json.JsonPrimitive(displayName))
            }
        } else {
            null
        }
        return GoTrueAuthResponse(
            accessToken = "new-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            expiresAt = nowSec + 3600,
            refreshToken = "new-refresh-token",
            user = GoTrueUser(
                id = userId,
                email = email,
                userMetadata = userMetadata,
            ),
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
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
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
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        manager.sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload, "Push payload should be captured")
        assertEquals(
            tokenStorage.getDeviceId(),
            payload.deviceId,
            "deviceId should match token storage",
        )
        // Platform should be one of the recognized platform names
        assertTrue(
            payload.platform in listOf("android", "ios") ||
                payload.platform.isNotEmpty(),
            "Platform should be set",
        )
    }

    @Test
    fun pushSyncTimeIsoParsedToEpochMillis() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        // Make pull succeed so we get full Success state
        val expectedEpoch = kotlinx.datetime.Instant.parse(
            "2026-03-02T12:00:00Z",
        ).toEpochMilliseconds()
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(syncTime = expectedEpoch),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val syncState = manager.syncState.value
        assertIs<SyncState.Success>(syncState)
        assertEquals(
            expectedEpoch,
            syncState.syncTime,
            "ISO 8601 syncTime should parse to correct epoch millis",
        )
    }

    @Test
    fun syncWithNoLocalDataSendsEmptyPayload() = runTest {
        setupAuthenticated()
        // fakeSyncRepo already returns empty lists by default
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        manager.sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertTrue(payload.sessions.isEmpty(), "Sessions should be empty")
        assertTrue(payload.routines.isEmpty(), "Routines should be empty")
        assertEquals(1, fakeApi.pushCallCount, "Push should still be called even with empty data")
    }

    @Test
    fun syncIncludesExternalActivitiesWhenLocalSubscriptionIsActive() = runTest {
        setupAuthenticated()
        fakeUserProfileRepo.setActiveProfileForTest(subscriptionStatus = SubscriptionStatus.ACTIVE)
        fakeExternalActivityRepo.activities += ExternalActivity(
            externalId = "hevy-activity-1",
            provider = IntegrationProvider.HEVY,
            name = "Push Day",
            startedAt = 1000L,
            profileId = "default",
            needsSync = true,
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        manager.sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload, "Push payload should be captured")
        assertEquals(
            1,
            payload.externalActivities.size,
            "Local paid status should allow external activity push",
        )
        assertEquals("hevy-activity-1", payload.externalActivities.single().externalId)
    }

    @Test
    fun syncMarksExternalActivitiesSyncedByProviderScopedKeys() = runTest {
        setupAuthenticated()
        fakeUserProfileRepo.setActiveProfileForTest(subscriptionStatus = SubscriptionStatus.ACTIVE)
        fakeExternalActivityRepo.activities += ExternalActivity(
            externalId = "shared-id",
            provider = IntegrationProvider.HEVY,
            name = "Hevy Push Day",
            startedAt = 1000L,
            profileId = "default",
            needsSync = true,
        )
        fakeExternalActivityRepo.activities += ExternalActivity(
            externalId = "shared-id",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Liftosaur Push Day",
            startedAt = 2000L,
            profileId = "default",
            needsSync = true,
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(
                syncTime = "2026-03-02T12:00:00Z",
                externalActivityKeys = listOf(
                    ExternalActivityAckDto(
                        externalId = "shared-id",
                        provider = IntegrationProvider.HEVY.key,
                    ),
                ),
            ),
        )
        val manager = createManager()

        manager.sync()

        assertEquals(1, fakeExternalActivityRepo.markedSyncedKeys.size)
        assertEquals("shared-id", fakeExternalActivityRepo.markedSyncedKeys.single().externalId)
        assertEquals(
            IntegrationProvider.HEVY,
            fakeExternalActivityRepo.markedSyncedKeys.single().provider,
        )
        assertTrue(
            fakeExternalActivityRepo.markedSyncedIds.isEmpty(),
            "Legacy ID-only sync stamping should not run when provider-scoped acknowledgements are present",
        )
    }

    // ===== Pull Success Flow =====

    @Test
    fun syncMergesRoutinesFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                routines = listOf(
                    PullRoutineDto(id = "r1", name = "Routine 1"),
                    PullRoutineDto(id = "r2", name = "Routine 2"),
                ),
            ),
        )
        val manager = createManager()

        manager.sync()

        assertEquals(
            1,
            fakeSyncRepo.mergePortalRoutinesCallCount,
            "mergePortalRoutines should be called once",
        )
        assertEquals(2, fakeSyncRepo.mergedPortalRoutines.size, "Should merge 2 routines")
    }

    @Test
    fun syncMergesBadgesFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                badges = listOf(
                    PullBadgeDto(
                        badgeId = "badge-1",
                        badgeName = "First Workout",
                        earnedAt = "2026-01-01T00:00:00Z",
                    ),
                ),
            ),
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
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                gamificationStats = PullGamificationStatsDto(
                    totalWorkouts = 50,
                    totalReps = 1000,
                    totalVolumeKg = 50000f,
                    longestStreak = 14,
                    currentStreak = 3,
                ),
            ),
        )
        val manager = createManager()

        manager.sync()

        assertEquals(
            1,
            fakeSyncRepo.mergeGamificationStatsCallCount,
            "mergeGamificationStats should be called",
        )
        assertNotNull(fakeSyncRepo.mergedGamificationStats, "Gamification stats should be merged")
    }

    @Test
    fun syncSavesRpgAttributesFromPull() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
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
                    characterClass = "TITAN",
                ),
            ),
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
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                routines = emptyList(),
                badges = emptyList(),
                gamificationStats = null,
                rpgAttributes = null,
            ),
        )
        val manager = createManager()

        manager.sync()

        assertEquals(
            0,
            fakeSyncRepo.mergePortalRoutinesCallCount,
            "Should not merge empty routines",
        )
        assertEquals(0, fakeSyncRepo.mergeBadgesCallCount, "Should not merge empty badges")
        assertEquals(
            0,
            fakeSyncRepo.mergeGamificationStatsCallCount,
            "Should not merge null gamification stats",
        )
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
    fun pullFailureResultsInPartialSuccessState() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T15:30:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        fakeApi.pullResult = Result.failure(PortalApiException("Network error"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "Sync should succeed despite pull failure (push succeeded)")
        val expectedEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        val state = manager.syncState.value
        assertIs<SyncState.PartialSuccess>(state)
        assertTrue(state.pushSucceeded, "Push should have succeeded")
        assertFalse(state.pullSucceeded, "Pull should have failed")
        assertEquals(expectedEpoch, state.lastSyncTime, "Should report push syncTime in partial success")
        assertEquals("Network error", state.pullError, "Should include pull error message")
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
        val expectedEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        // Pull succeeds so timestamp is updated
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(syncTime = expectedEpoch),
        )
        val manager = createManager()

        manager.sync()

        assertEquals(
            expectedEpoch,
            tokenStorage.getLastSyncTimestamp(),
            "lastSyncTimestamp should be updated on full success",
        )
    }

    @Test
    fun pullFailureDoesNotAdvanceLastSyncTimestamp() = runTest {
        setupAuthenticated()
        val initialTimestamp = 1000L
        tokenStorage.setLastSyncTimestamp(initialTimestamp)
        val pushSyncTimeIso = "2026-03-02T18:00:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        // Pull fails - timestamp should NOT be updated
        fakeApi.pullResult = Result.failure(PortalApiException("pull failed"))
        val manager = createManager()

        manager.sync()

        assertEquals(
            initialTimestamp,
            tokenStorage.getLastSyncTimestamp(),
            "lastSyncTimestamp should NOT be updated on pull failure (partial success)",
        )
    }

    @Test
    fun syncUsesPullSyncTimeWhenLargerThanPush() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        val pullSyncTimeEpoch = kotlinx.datetime.Instant.parse(
            "2026-03-02T13:00:00Z",
        ).toEpochMilliseconds()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(syncTime = pullSyncTimeEpoch),
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
            "Should use pull syncTime when pull succeeds",
        )
    }

    @Test
    fun syncReturnsSuccessWithPushTimeWhenPullFails() = runTest {
        setupAuthenticated()
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        fakeApi.pullResult = Result.failure(PortalApiException("pull error"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "Result should be success (push succeeded)")
        val expectedEpoch = kotlinx.datetime.Instant.parse(pushSyncTimeIso).toEpochMilliseconds()
        assertEquals(
            expectedEpoch,
            result.getOrThrow(),
            "Should return push syncTime in result when pull fails",
        )
        // But the timestamp in storage should NOT be updated
        assertEquals(
            0L,
            tokenStorage.getLastSyncTimestamp(),
            "lastSyncTimestamp should NOT be advanced on partial success",
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
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
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
    fun pullReceivesStoredLastSyncNotPushTimestamp() = runTest {
        setupAuthenticated()
        // Set a stored lastSync — pull should use THIS, not the push response time.
        // Using the push response time would ask "what changed since NOW" which
        // always returns 0 results. The stored lastSync asks "give me everything
        // that changed since my last successful sync."
        val storedLastSync = 1000L
        tokenStorage.setLastSyncTimestamp(storedLastSync)
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        val manager = createManager()

        manager.sync()

        // The pull should have been called with the stored lastSync, not the push timestamp
        assertNotNull(fakeApi.lastPullLastSync, "Pull should have been called")
        assertEquals(
            storedLastSync,
            fakeApi.lastPullLastSync,
            "Pull should receive stored lastSync, not push response timestamp",
        )
    }
}
