package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.PhasePRBackfillResult
import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutPhase
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val json = Json { encodeDefaults = true }

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
    fun syncBackfillsPhaseSpecificPRsBeforeCollectingPushRecords() = runTest {
        setupAuthenticated()
        fakeSyncRepo.phaseBackfillResultToReturn = PhasePRBackfillResult(
            changedRows = 2,
            maxScannedSessionTimestamp = 1_700_000_000_000L,
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("backfillPhaseSpecificPRs", "getFullPRsModifiedSince"),
            fakeSyncRepo.callLog.filter {
                it == "backfillPhaseSpecificPRs" || it == "getFullPRsModifiedSince"
            },
            "Historical phase PR backfill must run before building the dedicated PR payload",
        )
        assertEquals(0L, fakeSyncRepo.lastPhaseBackfillFromTimestamp)
        assertEquals(
            1_700_000_000_000L,
            tokenStorage.getPhasePRBackfillCheckpoint("default"),
            "Successful backfill progress should be checkpointed by profile",
        )
    }

    @Test
    fun syncUsesStoredPhasePRBackfillCheckpoint() = runTest {
        setupAuthenticated()
        tokenStorage.setPhasePRBackfillCheckpoint("default", 1_700_000_000_000L)
        fakeSyncRepo.phaseBackfillResultToReturn = PhasePRBackfillResult(
            changedRows = 0,
            maxScannedSessionTimestamp = 1_700_000_100_000L,
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            1_700_000_000_000L,
            fakeSyncRepo.lastPhaseBackfillFromTimestamp,
            "Backfill should resume from the stored checkpoint instead of scanning from zero",
        )
        assertEquals(
            1_700_000_100_000L,
            tokenStorage.getPhasePRBackfillCheckpoint("default"),
            "Backfill checkpoint should advance to the newest scanned session",
        )
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
    fun syncDeduplicatesDuplicateWorkoutSessionIdsBeforePush() = runTest {
        setupAuthenticated()
        val sessionId = "773e35b1-57be-42f8-9d64-69d127cadd3c"
        val firstSession = makeWorkoutSession(
            id = sessionId,
            timestamp = 1000L,
            reps = 8,
            totalReps = 8,
            exerciseName = "Bench Press",
        )
        val duplicateSession = firstSession.copy(
            id = sessionId.uppercase(),
            timestamp = 2000L,
            reps = 12,
            totalReps = 12,
            exerciseName = "Duplicate Bench Press",
        )
        fakeSyncRepo.workoutSessionsToReturn = listOf(firstSession, duplicateSession)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeApi.pushCallCount)
        val payload = fakeApi.lastPushPayload
        assertNotNull(payload, "Push payload should be captured")
        assertEquals(1, payload.sessions.size, "Duplicate session IDs should be sent once")
        val pushedSession = payload.sessions.single()
        assertEquals(sessionId, pushedSession.id)
        assertEquals(sessionId, pushedSession.exercises.single().id)
        assertEquals(
            8,
            pushedSession.exercises.single().sets.single().actualReps,
            "The first repository row should win deterministically",
        )
        assertEquals(
            listOf(sessionId),
            fakeSyncRepo.updateSessionTimestampCalls,
            "Post-push timestamp stamping should run once for the deduped session",
        )
    }

    @Test
    fun syncPushesAllPhaseSpecificPersonalRecordsForSameExerciseTimestamp() = runTest {
        setupAuthenticated()
        val exerciseId = "bicep-curl"
        val timestamp = 1_740_916_800_000L
        fakeSyncRepo.workoutSessionsToReturn = listOf(
            makeWorkoutSession(
                id = "session-phase-pr",
                timestamp = timestamp,
                reps = 8,
                totalReps = 8,
                exerciseId = exerciseId,
                exerciseName = "Bicep Curl",
            ),
        )
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePersonalRecord(
                id = 1,
                exerciseId = exerciseId,
                exerciseName = "Bicep Curl",
                weightPerCableKg = 20f,
                reps = 8,
                timestamp = timestamp,
                prType = PRType.MAX_WEIGHT,
                phase = WorkoutPhase.CONCENTRIC,
            ),
            makePersonalRecord(
                id = 2,
                exerciseId = exerciseId,
                exerciseName = "Bicep Curl",
                weightPerCableKg = 42f,
                reps = 8,
                timestamp = timestamp,
                prType = PRType.MAX_WEIGHT,
                phase = WorkoutPhase.ECCENTRIC,
            ),
            makePersonalRecord(
                id = 3,
                exerciseId = exerciseId,
                exerciseName = "Bicep Curl",
                weightPerCableKg = 20f,
                reps = 8,
                timestamp = timestamp,
                prType = PRType.MAX_VOLUME,
                phase = WorkoutPhase.CONCENTRIC,
                volume = 160f,
            ),
            makePersonalRecord(
                id = 4,
                exerciseId = exerciseId,
                exerciseName = "Bicep Curl",
                weightPerCableKg = 42f,
                reps = 8,
                timestamp = timestamp,
                prType = PRType.MAX_VOLUME,
                phase = WorkoutPhase.ECCENTRIC,
                volume = 336f,
            ),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val payload = assertNotNull(fakeApi.lastPushPayload, "Push payload should be captured")
        assertEquals(
            setOf(
                WorkoutPhase.CONCENTRIC.name to PRType.MAX_WEIGHT.name,
                WorkoutPhase.ECCENTRIC.name to PRType.MAX_WEIGHT.name,
                WorkoutPhase.CONCENTRIC.name to PRType.MAX_VOLUME.name,
                WorkoutPhase.ECCENTRIC.name to PRType.MAX_VOLUME.name,
            ),
            payload.personalRecords.map { it.workoutPhase to it.recordType }.toSet(),
            "Dedicated PR payload rows should preserve phase and PR type instead of collapsing by timestamp",
        )
        assertEquals(
            WorkoutPhase.CONCENTRIC.name,
            payload.sessions.single().exercises.single().sets.single().prPhase,
            "Legacy set-level hint should prefer the normal concentric weight PR when present",
        )
    }

    @Test
    fun syncStampsPushedPersonalRecordsToPreventResend() = runTest {
        // Issue #528 regression: pushLocalChanges must stamp PersonalRecord.updatedAt
        // the same way it stamps WorkoutSession.updatedAt, otherwise
        // getFullPRsModifiedSince(lastSync, ...) keeps re-shipping the same PRs
        // on every push.
        setupAuthenticated()
        val exerciseId = "squat"
        val timestamp = 1_740_916_800_000L
        val firstSession = makeWorkoutSession(
            id = "session-pr-stamp",
            timestamp = timestamp,
            reps = 5,
            totalReps = 5,
            exerciseId = exerciseId,
            exerciseName = "Squat",
        )
        val pushedPr = makePersonalRecord(
            id = 7,
            exerciseId = exerciseId,
            exerciseName = "Squat",
            weightPerCableKg = 100f,
            reps = 5,
            timestamp = timestamp,
            prType = PRType.MAX_WEIGHT,
            phase = WorkoutPhase.COMBINED,
        )
        fakeSyncRepo.workoutSessionsToReturn = listOf(firstSession)
        fakeSyncRepo.fullPRsToReturn = listOf(pushedPr)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            1,
            fakeSyncRepo.updatePersonalRecordTimestampCalls.size,
            "Pushed PRs are stamped exactly once per sync (Issue #528)",
        )
        assertEquals(
            listOf(pushedPr.id),
            fakeSyncRepo.updatePersonalRecordTimestampCalls.single(),
            "The stamp call must carry the same PR id that was just pushed",
        )
        assertNotNull(
            fakeSyncRepo.lastUpdatePersonalRecordTimestamp,
            "PR stamping must use the same stampTime as session stamping so " +
                "next-sync parity (lastSync) lines up across both tables",
        )
    }

    @Test
    fun syncDeduplicatesPersonalRecordIdsBeforeStamping() = runTest {
        // Issue #528 dedupe guard: even if getFullPRsModifiedSince returns the
        // same PR id twice (e.g. one row from the recent delta and one from the
        // historical session-id resolution), the stamp call should carry each
        // id exactly once.
        setupAuthenticated()
        val exerciseId = "bench"
        val timestamp = 1_740_916_800_000L
        fakeSyncRepo.workoutSessionsToReturn = listOf(
            makeWorkoutSession(
                id = "session-pr-dup",
                timestamp = timestamp,
                reps = 5,
                totalReps = 5,
                exerciseId = exerciseId,
                exerciseName = "Bench",
            ),
        )
        val prA = makePersonalRecord(
            id = 11,
            exerciseId = exerciseId,
            exerciseName = "Bench",
            weightPerCableKg = 80f,
            reps = 5,
            timestamp = timestamp,
            prType = PRType.MAX_WEIGHT,
            phase = WorkoutPhase.COMBINED,
        )
        // Simulate a duplicate local PR id in the recent-PRs query result while
        // keeping distinct portal payload keys so push validation succeeds and
        // this test isolates the post-push stamping behavior.
        fakeSyncRepo.fullPRsToReturn = listOf(
            prA,
            prA.copy(
                prType = PRType.MAX_VOLUME,
                volume = 640f,
            ),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(prA.id),
            fakeSyncRepo.updatePersonalRecordTimestampCalls.single(),
            "Duplicate PR ids in the delta query result must be deduped before stamping",
        )
    }

    @Test
    fun syncEnsuresDefaultProfileBeforeSendingDefaultScopedPersonalRecords() = runTest {
        setupAuthenticated()
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePersonalRecord(
                id = 42,
                exerciseId = "squat",
                exerciseName = "Squat",
                weightPerCableKg = 100f,
                reps = 1,
                timestamp = 1_740_916_800_000L,
                prType = PRType.MAX_WEIGHT,
                phase = WorkoutPhase.COMBINED,
            ),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val payload = assertNotNull(fakeApi.lastPushPayload, "Push payload should be captured")
        assertEquals(
            "default",
            payload.profileId,
            "Default-scoped PR rows must not be pushed without matching top-level profile context",
        )
        assertEquals(
            listOf(LocalProfileDto(id = "default", name = "Default", colorIndex = 0)),
            payload.allProfiles,
            "The payload must include the local_profiles row required by the portal FK",
        )
        assertEquals("default", payload.personalRecords.single().localProfileId)
    }

    @Test
    fun syncResolvesSessionIdForDedicatedPROutsideDeltaSessionBatch() = runTest {
        setupAuthenticated()
        val exerciseId = "bicep-curl"
        val timestamp = 1_740_916_800_000L
        val sessionKey = "$exerciseId:$timestamp"
        val pr = makePersonalRecord(
            id = 42,
            exerciseId = exerciseId,
            exerciseName = "Bicep Curl",
            weightPerCableKg = 42f,
            reps = 8,
            timestamp = timestamp,
            prType = PRType.MAX_WEIGHT,
            phase = WorkoutPhase.ECCENTRIC,
        )
        fakeSyncRepo.fullPRsToReturn = listOf(pr)
        fakeSyncRepo.sessionIdsForPersonalRecordsToReturn = mapOf(sessionKey to "historical-session")
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val payload = assertNotNull(fakeApi.lastPushPayload, "Push payload should be captured")
        assertEquals("historical-session", payload.personalRecords.single().sessionId)
        assertEquals(listOf(pr), fakeSyncRepo.lastSessionIdPersonalRecords)
    }

    @Test
    fun syncFailsBeforePushWhenPortalPayloadHasDuplicateRoutineIds() = runTest {
        setupAuthenticated()
        val routineId = "8db61128-c19d-48dc-a05e-ade968afa87e"
        val upperId = routineId.uppercase()
        fakeSyncRepo.routinesToReturn = listOf(
            makeRoutine(id = routineId, name = "Strength A"),
            makeRoutine(id = upperId, name = "Strength B"),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<PortalApiException>(error)
        assertEquals(400, error.statusCode)
        assertTrue(
            error.message.orEmpty().contains("routines contains duplicate key(s): $upperId"),
            "Local preflight should name the duplicate routine key",
        )
        val state = manager.syncState.value
        assertIs<SyncState.Error>(state)
        assertTrue(state.message.contains("Duplicate IDs in local push payload: routines"))
        assertEquals(0, fakeApi.pushCallCount, "Payload with duplicate keys should not be pushed")
        assertEquals(0, fakeApi.pullCallCount, "Failed push preflight should not start pull")
        assertNull(fakeApi.lastPushPayload, "No doomed payload should be sent")
        assertTrue(
            fakeSyncRepo.updateSessionTimestampCalls.isEmpty(),
            "Local validation failures must not stamp sessions as synced",
        )
        assertEquals(0L, tokenStorage.getLastSyncTimestamp())
    }

    @Test
    fun syncIncludesCustomExercisesBeforeRoutinesThatReferenceThem() = runTest {
        setupAuthenticated()
        val customExerciseId = "custom_1740000000000"
        fakeSyncRepo.customExercisesToReturn = listOf(
            CustomExerciseSyncDto(
                clientId = customExerciseId,
                name = "Garage Press",
                displayName = "Garage Press",
                muscleGroup = "Chest",
                equipment = "HANDLES",
                defaultCableConfig = "DOUBLE",
                createdAt = 1_740_000_000_000L,
                updatedAt = 1_740_000_000_000L,
            ),
        )
        fakeSyncRepo.routinesToReturn = listOf(
            Routine(
                id = "8db61128-c19d-48dc-a05e-ade968afa87e",
                name = "Custom Day",
                profileId = "default",
                exercises = listOf(
                    RoutineExercise(
                        id = "9db61128-c19d-48dc-a05e-ade968afa87e",
                        exercise = Exercise(
                            id = customExerciseId,
                            name = "Garage Press",
                            muscleGroup = "Chest",
                            equipment = "HANDLES",
                            isCustom = true,
                        ),
                        orderIndex = 0,
                        weightPerCableKg = 20f,
                    ),
                ),
            ),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        val result = createManager().sync()

        assertTrue(result.isSuccess)
        val payload = assertNotNull(fakeApi.lastPushPayload, "Push payload should be captured")
        val serialized = json.encodeToString(PortalSyncPayload.serializer(), payload)
        assertTrue(
            serialized.contains("\"customExercises\""),
            "Routine exercise FK targets must be sent as custom catalog rows first",
        )
        assertTrue(
            serialized.contains("\"clientId\":\"$customExerciseId\""),
            "The custom exercise referenced by the routine must be present in the portal payload",
        )
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
    fun pullIsCalledWithKnownEntityIdsAfterPush() = runTest {
        setupAuthenticated()
        // With parity-based sync, pull no longer uses a lastSync timestamp.
        // Instead it sends the local entity IDs and the server returns what's missing.
        // This test verifies that pull is called with knownEntityIds (not a timestamp).
        tokenStorage.setLastSyncTimestamp(1000L)
        val pushSyncTimeIso = "2026-03-02T12:00:00Z"
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = pushSyncTimeIso),
        )
        val manager = createManager()

        manager.sync()

        // The pull should have been called with a KnownEntityIds object (parity-based sync)
        assertNotNull(fakeApi.lastPullKnownEntityIds, "Pull should have been called with knownEntityIds")
        assertEquals(1, fakeApi.pullCallCount, "Pull should be called once")
    }

    // ===== Pagination Tests (Plan 03-05) =====

    @Test
    fun pullPaginatesUntilHasMoreIsFalse() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        // Set up paginated responses: page 1 has hasMore=true, page 2 has hasMore=false
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1740916800000L,
                    hasMore = true,
                    nextCursor = "page2cursor",
                    routines = listOf(PullRoutineDto(id = "r1", name = "Routine 1")),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1740916900000L,
                    hasMore = false,
                    nextCursor = null,
                    routines = listOf(PullRoutineDto(id = "r2", name = "Routine 2")),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(2, fakeApi.pullCallCount, "Pull should be called twice (two pages)")
        // Verify second call used the cursor from first response
        assertEquals("page2cursor", fakeApi.lastPullCursor, "Second pull should use cursor from first response")
    }

    @Test
    fun pullStopsOnEmptyPageWithHasMoreTrue() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        // Empty page with hasMore=true should break to prevent infinite loop
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                hasMore = true, // Normally would continue, but...
                nextCursor = "nextpage",
                // All entity lists are empty — edge case protection
            ),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, fakeApi.pullCallCount, "Pull should stop after empty page despite hasMore=true")
    }

    @Test
    fun pullFailureMidPaginationDoesNotAdvanceTimestamp() = runTest {
        setupAuthenticated()
        val initialTimestamp = 1000L
        tokenStorage.setLastSyncTimestamp(initialTimestamp)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        // Page 1 succeeds, page 2 fails
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1740916800000L,
                    hasMore = true,
                    nextCursor = "page2cursor",
                    routines = listOf(PullRoutineDto(id = "r1", name = "Routine 1")),
                ),
            ),
            Result.failure(PortalApiException("Network error on page 2")),
        )
        val manager = createManager()

        manager.sync()

        // Timestamp should NOT be advanced because pull didn't complete all pages
        assertEquals(
            initialTimestamp,
            tokenStorage.getLastSyncTimestamp(),
            "lastSyncTimestamp should NOT be updated on partial pull failure",
        )
    }

    @Test
    fun pullUpdatesTimestampOnlyAfterAllPagesComplete() = runTest {
        setupAuthenticated()
        tokenStorage.setLastSyncTimestamp(0L)
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        val finalSyncTime = 1740916900000L
        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1740916800000L, // Page 1 sync time (intermediate)
                    hasMore = true,
                    nextCursor = "page2cursor",
                    routines = listOf(PullRoutineDto(id = "r1", name = "Routine 1")),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = finalSyncTime, // Page 2 sync time (final)
                    hasMore = false,
                    routines = listOf(PullRoutineDto(id = "r2", name = "Routine 2")),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            finalSyncTime,
            tokenStorage.getLastSyncTimestamp(),
            "lastSyncTimestamp should be updated to final page's syncTime",
        )
    }

    @Test
    fun pullReportsProgressDuringPagination() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )

        fakeApi.pullResultsQueue = mutableListOf(
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1740916800000L,
                    hasMore = true,
                    nextCursor = "page2cursor",
                    routines = listOf(PullRoutineDto(id = "r1", name = "Routine 1")),
                ),
            ),
            Result.success(
                PortalSyncPullResponse(
                    syncTime = 1740916900000L,
                    hasMore = false,
                    routines = listOf(PullRoutineDto(id = "r2", name = "Routine 2")),
                ),
            ),
        )
        val manager = createManager()

        manager.sync()

        // After multi-page sync completes, verify that 2 pages were processed
        // by checking pull was called twice
        assertEquals(
            2,
            fakeApi.pullCallCount,
            "Should have processed 2 pages during pagination",
        )

        // The final state should be Success (not SyncingWithProgress)
        assertIs<SyncState.Success>(manager.syncState.value)
    }

    @Test
    fun pullPassesPageSizeToApi() = runTest {
        setupAuthenticated()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        manager.sync()

        // Verify pageSize was passed to the API
        assertEquals(
            com.devil.phoenixproject.data.sync.SyncConfig.DEFAULT_PAGE_SIZE,
            fakeApi.lastPullPageSize,
            "Pull should pass default page size to API",
        )
    }

    // ===== Parity Sync Tests =====

    @Test
    fun pullSendsKnownEntityIdsToServer() = runTest {
        setupAuthenticated()
        // Set up fake repository with known entity IDs (simulating local database content).
        // IDs must be canonical UUIDs — SyncManager filters non-UUIDs before send to
        // satisfy the server-side validator in mobile-sync-pull.
        val sess1 = "66666666-6666-4666-a666-666666666666"
        val sess2 = "77777777-7777-4777-a777-777777777777"
        val rout1 = "88888888-8888-4888-a888-888888888888"
        val badge1 = "99999999-9999-4999-a999-999999999999"
        val badge2 = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
        val badge3 = "bbbbbbbb-bbbb-4bbb-abbb-bbbbbbbbbbbb"
        val pr1 = "cccccccc-cccc-4ccc-accc-cccccccccccc"
        val pr2 = "dddddddd-dddd-4ddd-addd-dddddddddddd"
        fakeSyncRepo.sessionIds = listOf(sess1, sess2)
        fakeSyncRepo.routineIds = listOf(rout1)
        fakeSyncRepo.cycleIds = emptyList()
        fakeSyncRepo.badgeIds = listOf(badge1, badge2, badge3)
        fakeSyncRepo.personalRecordIds = listOf(pr1, pr2)

        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        manager.sync()

        // Verify the API received the correct entity IDs for parity comparison
        val knownIds = fakeApi.lastPullKnownEntityIds
        assertNotNull(knownIds, "Pull should have been called with knownEntityIds")
        assertEquals(listOf(sess1, sess2), knownIds.sessionIds)
        assertEquals(listOf(rout1), knownIds.routineIds)
        assertEquals(emptyList<String>(), knownIds.cycleIds)
        assertEquals(listOf(badge1, badge2, badge3), knownIds.badgeIds)
        assertEquals(listOf(pr1, pr2), knownIds.personalRecordIds)
    }

    @Test
    fun pullDropsNonUuidBadgeAndPersonalRecordIdsBeforeSend() = runTest {
        setupAuthenticated()
        val badgeId = "eeeeeeee-eeee-4eee-aeee-eeeeeeeeeeee"
        val personalRecordId = "ffffffff-ffff-4fff-afff-ffffffffffff"
        fakeSyncRepo.badgeIds = listOf("1", badgeId, "2")
        fakeSyncRepo.personalRecordIds = listOf("10", personalRecordId, "20")

        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        val manager = createManager()

        manager.sync()

        val knownIds = fakeApi.lastPullKnownEntityIds
        assertNotNull(knownIds, "Pull should have been called with knownEntityIds")
        assertEquals(
            listOf(badgeId),
            knownIds.badgeIds,
            "Local numeric badge ids must be filtered before send; portal parity uses UUID row ids",
        )
        assertEquals(
            listOf(personalRecordId),
            knownIds.personalRecordIds,
            "Local numeric personal record ids must be filtered before send; portal parity uses UUID row ids",
        )
    }

    @Test
    fun pullMergesEntitiesNotInLocalDatabase() = runTest {
        setupAuthenticated()
        // Local has session-1 only
        fakeSyncRepo.sessionIds = listOf("session-1")

        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"),
        )
        // Server returns session-2 (which we don't have locally — server-side parity diff).
        // Include one exercise so the PortalPullAdapter produces a WorkoutSession row
        // (adapter returns empty when exercises list is empty).
        fakeApi.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = 1740916800000L,
                sessions = listOf(
                    PullWorkoutSessionDto(
                        id = "session-2",
                        userId = "user-123",
                        startedAt = "2026-01-01T12:00:00Z",
                        exercises = listOf(
                            PullExerciseDto(
                                id = "exercise-1",
                                sessionId = "session-2",
                                name = "Squat",
                                muscleGroup = "Legs",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val manager = createManager()

        manager.sync()

        // Verify atomic merge was called and contained the converted session (routineSessionId
        // maps the session-level id "session-2")
        assertEquals(1, fakeSyncRepo.atomicMergeCallCount, "mergeAllPullData should be called once")
        assertTrue(
            fakeSyncRepo.lastAtomicMergeSessions.any { it.routineSessionId == "session-2" },
            "Session returned by server (session-2) should be converted and merged into local database",
        )
    }

    private fun makeWorkoutSession(
        id: String,
        timestamp: Long,
        reps: Int = 10,
        totalReps: Int = 10,
        exerciseId: String = "exercise-$id",
        exerciseName: String = "Test Exercise",
        routineSessionId: String? = null,
    ) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = "OldSchool",
        reps = reps,
        weightPerCableKg = 20f,
        duration = 60_000L,
        totalReps = totalReps,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        profileId = "default",
    )

    private fun makePersonalRecord(
        id: Long,
        exerciseId: String,
        exerciseName: String,
        weightPerCableKg: Float,
        reps: Int,
        timestamp: Long,
        prType: PRType,
        phase: WorkoutPhase,
        volume: Float = weightPerCableKg * reps,
    ) = PersonalRecord(
        id = id,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        weightPerCableKg = weightPerCableKg,
        reps = reps,
        oneRepMax = weightPerCableKg,
        timestamp = timestamp,
        workoutMode = "OldSchool",
        prType = prType,
        volume = volume,
        phase = phase,
        profileId = "default",
        cableCount = 2,
    )

    private fun makeRoutine(
        id: String,
        name: String,
    ) = Routine(
        id = id,
        name = name,
        profileId = "default",
    )
}
