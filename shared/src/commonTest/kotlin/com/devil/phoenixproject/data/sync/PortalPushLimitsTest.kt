package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for push-side batch splitting defined by SyncManager.SYNC_BATCH_SIZE (=50).
 *
 * Contract being tested (see SyncManager.kt line 540-680, audit 02-mobile-wire-contract.md):
 *   - ≤ 50 sessions → single push request (fast path).
 *   - > 50 sessions → chunked into batches of 50 using List.chunked(50).
 *   - Non-session data (routines, cycles, badges, RPG, gamification, signatures, assessments,
 *     allProfiles, externalActivities) is attached to the FINAL batch only.
 *   - A failed batch causes the whole sync to fail WITHOUT advancing lastSync
 *     (next sync retries the full batch sequence from the beginning).
 *   - Partial success (e.g., batch 1 OK, batch 2 fails) is still a failure; subsequent
 *     batches are not attempted.
 */
class PortalPushLimitsTest {

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

    private fun authenticate(userId: String = "user-123") {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "tok",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "rtok",
                user = GoTrueUser(id = userId, email = "$userId@e.com"),
            ),
        )
    }

    /**
     * Build [count] standalone (no routineSessionId) WorkoutSession objects, each of which will
     * produce exactly 1 portal session in PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry.
     * Every session has an exerciseName so it maps to a real PortalExerciseDto.
     */
    private fun buildSessions(count: Int, startTime: Long = 1_740_000_000_000L): List<WorkoutSession> {
        return List(count) { i ->
            WorkoutSession(
                id = "sess-$i",
                timestamp = startTime + i,
                mode = "OldSchool",
                reps = 10,
                weightPerCableKg = 25f,
                totalReps = 10,
                exerciseId = "ex-$i",
                exerciseName = "Squat",
                routineSessionId = null, // standalone → 1 portal session per mobile session
                profileId = "default",
            )
        }
    }

    // ==================== Batch-Size Constant Contract ====================

    @Test
    fun batchSizeConstantIsFifty() {
        assertEquals(
            50,
            SyncManager.SYNC_BATCH_SIZE,
            "SYNC_BATCH_SIZE documented in audit 02 is 50 sessions/batch",
        )
    }

    @Test
    fun maxFullBatchRetriesIsThree() {
        assertEquals(
            3,
            SyncManager.MAX_FULL_BATCH_RETRIES,
            "MAX_FULL_BATCH_RETRIES documented in audit 02 is 3",
        )
    }

    // ==================== Single-Batch Fast Path ====================

    @Test
    fun fortyNineSessionsPushesAsOneBatch() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(49)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        createManager().sync()

        assertEquals(
            1,
            fakeApi.pushCallCount,
            "49 sessions ≤ 50 → single push (fast path)",
        )
    }

    @Test
    fun fiftySessionsPushesAsOneBatch() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(50)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        createManager().sync()

        assertEquals(
            1,
            fakeApi.pushCallCount,
            "Exactly 50 sessions is still a single batch (boundary)",
        )
    }

    // ==================== Chunked Batch Splitting ====================

    @Test
    fun fiftyOneSessionsSplitsIntoTwoBatches() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(51)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        createManager().sync()

        assertEquals(
            2,
            fakeApi.pushCallCount,
            "51 sessions → 50 + 1 → 2 batches (ceil(51/50))",
        )
    }

    @Test
    fun oneHundredTwentySessionsSplitsIntoThreeBatches() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(120)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        createManager().sync()

        assertEquals(
            3,
            fakeApi.pushCallCount,
            "120 sessions → ceil(120/50) = 3 batches",
        )
    }

    @Test
    fun largeHistoryOf500SessionsSplitsIntoTenBatches() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(500)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        createManager().sync()

        assertEquals(
            10,
            fakeApi.pushCallCount,
            "500 sessions → ceil(500/50) = 10 batches",
        )
    }

    // ==================== Per-Batch Payload Shape ====================

    @Test
    fun eachBatchContainsAtMostFiftySessions() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(73)

        // Track the size of every batch the fake sees.
        val batchSizes = mutableListOf<Int>()
        val wrapped = object : FakePortalApiClient() {
            override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
                batchSizes.add(payload.sessions.size)
                return Result.success(
                    PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
                )
            }
        }
        val mgr = SyncManager(
            apiClient = wrapped,
            tokenStorage = tokenStorage,
            syncRepository = fakeSyncRepo,
            gamificationRepository = fakeGamificationRepo,
            repMetricRepository = fakeRepMetricRepo,
            userProfileRepository = fakeUserProfileRepo,
            externalActivityRepository = fakeExternalActivityRepo,
        )

        mgr.sync()

        assertEquals(listOf(50, 23), batchSizes, "73 sessions → [50, 23]")
        assertTrue(
            batchSizes.all { it <= 50 },
            "No batch may exceed SYNC_BATCH_SIZE (=50)",
        )
    }

    @Test
    fun nonSessionDataAttachedOnlyToLastBatch() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(120) // 3 batches

        val capturedPayloads = mutableListOf<PortalSyncPayload>()
        val capturingApi = object : FakePortalApiClient() {
            override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
                capturedPayloads.add(payload)
                return Result.success(
                    PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
                )
            }
        }
        val mgr = SyncManager(
            apiClient = capturingApi,
            tokenStorage = tokenStorage,
            syncRepository = fakeSyncRepo,
            gamificationRepository = fakeGamificationRepo,
            repMetricRepository = fakeRepMetricRepo,
            userProfileRepository = fakeUserProfileRepo,
            externalActivityRepository = fakeExternalActivityRepo,
        )

        mgr.sync()

        assertEquals(3, capturedPayloads.size)

        // Batches 0 and 1 (non-final) must have empty non-session collections.
        for ((index, payload) in capturedPayloads.withIndex().take(2)) {
            assertEquals(
                emptyList<PortalRoutineSyncDto>(),
                payload.routines,
                "Routines should only be on the final batch, not batch $index",
            )
            assertEquals(
                emptyList<PortalTrainingCycleSyncDto>(),
                payload.cycles,
                "Cycles should only be on the final batch, not batch $index",
            )
            assertEquals(
                emptyList<PortalEarnedBadgeSyncDto>(),
                payload.badges,
                "Badges should only be on the final batch, not batch $index",
            )
            assertEquals(
                null,
                payload.rpgAttributes,
                "RPG should only be on the final batch, not batch $index",
            )
            assertEquals(
                null,
                payload.gamificationStats,
                "Gamification stats should only be on the final batch, not batch $index",
            )
            assertEquals(
                emptyList<ExternalActivitySyncDto>(),
                payload.externalActivities,
                "External activities should only be on the final batch, not batch $index",
            )
        }
    }

    // ==================== Failure Handling ====================

    @Test
    fun failedBatchAbortsSyncWithoutAdvancingTimestamp() = runTest {
        authenticate()
        val initial = 9999L
        tokenStorage.setLastSyncTimestamp(initial)
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(120) // 3 batches

        var callIndex = 0
        val failingApi = object : FakePortalApiClient() {
            override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
                callIndex++
                return if (callIndex == 2) {
                    Result.failure(PortalApiException("batch 2 exploded", null, 500))
                } else {
                    Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
                }
            }
        }
        val mgr = SyncManager(
            apiClient = failingApi,
            tokenStorage = tokenStorage,
            syncRepository = fakeSyncRepo,
            gamificationRepository = fakeGamificationRepo,
            repMetricRepository = fakeRepMetricRepo,
            userProfileRepository = fakeUserProfileRepo,
            externalActivityRepository = fakeExternalActivityRepo,
        )

        val result = mgr.sync()

        assertTrue(result.isFailure, "Batch failure propagates up as overall failure")
        assertEquals(
            initial,
            tokenStorage.getLastSyncTimestamp(),
            "lastSync must NOT advance when any batch fails (prevents data consistency gap)",
        )
    }

    @Test
    fun failedBatchPreventsSubsequentBatchesFromRunning() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(120) // 3 batches

        var callIndex = 0
        val failingApi = object : FakePortalApiClient() {
            override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
                callIndex++
                return if (callIndex == 1) {
                    Result.failure(PortalApiException("batch 1 failed", null, 500))
                } else {
                    // This branch should NOT be hit — the outer loop must abort on batch 1 failure.
                    Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
                }
            }
        }
        val mgr = SyncManager(
            apiClient = failingApi,
            tokenStorage = tokenStorage,
            syncRepository = fakeSyncRepo,
            gamificationRepository = fakeGamificationRepo,
            repMetricRepository = fakeRepMetricRepo,
            userProfileRepository = fakeUserProfileRepo,
            externalActivityRepository = fakeExternalActivityRepo,
        )

        mgr.sync()

        assertEquals(
            1,
            callIndex,
            "Once batch 1 fails, batches 2+ must NOT be attempted in the same sync cycle",
        )
    }

    @Test
    fun pushPayloadCapturesDeviceIdAndPlatform() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(10)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        createManager().sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertEquals(tokenStorage.getDeviceId(), payload.deviceId)
        assertTrue(payload.platform.isNotBlank(), "platform must be set on every push payload")
    }
}
