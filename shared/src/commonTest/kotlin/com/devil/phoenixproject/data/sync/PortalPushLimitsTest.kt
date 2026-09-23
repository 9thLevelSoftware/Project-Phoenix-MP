package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private val fakeVelocityRepo = FakeVelocityOneRepMaxRepository()
    private val fakeProfilePreferenceSyncRepo = FakeProfilePreferenceSyncRepository()

    private fun createManager(
        rateLimiter: ClientRateLimiter = ClientRateLimiter(),
        apiClient: PortalApiClient = fakeApi,
    ) = SyncManager(
        apiClient = apiClient,
        tokenStorage = tokenStorage,
        syncRepository = fakeSyncRepo,
        gamificationRepository = fakeGamificationRepo,
        repMetricRepository = fakeRepMetricRepo,
        userProfileRepository = fakeUserProfileRepo,
        profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
        externalActivityRepository = fakeExternalActivityRepo,
        velocityOneRepMaxRepository = fakeVelocityRepo,
        rateLimiter = rateLimiter,
        isProfilePreferenceMigrationReady = { true },
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
    private fun buildSessions(count: Int, startTime: Long = 1_740_000_000_000L): List<WorkoutSession> = List(count) { i ->
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
            profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
            externalActivityRepository = fakeExternalActivityRepo,
            velocityOneRepMaxRepository = fakeVelocityRepo,
            isProfilePreferenceMigrationReady = { true },
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
        // One dedicated personal_records row matching sess-0/ex-0 so that session is
        // also flagged isPr. The portal derives an id-less personal_records row from
        // every set.isPr whenever a payload's `personalRecords` is empty (PORTAL
        // ROW-DUPLICATION HAZARD) — so every batch must carry the dedicated rows,
        // not just the last one.
        fakeSyncRepo.fullPRsToReturn = listOf(
            PersonalRecord(
                id = 1,
                exerciseId = "ex-0",
                exerciseName = "Squat",
                weightPerCableKg = 25f,
                reps = 10,
                oneRepMax = 25f,
                timestamp = 1_740_000_000_000L,
                workoutMode = "OldSchool",
                volume = 250f,
                phase = WorkoutPhase.COMBINED,
                profileId = "default",
                cableCount = 2,
                uuid = "pr-uuid-0",
            ),
        )

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
            profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
            externalActivityRepository = fakeExternalActivityRepo,
            velocityOneRepMaxRepository = fakeVelocityRepo,
            isProfilePreferenceMigrationReady = { true },
        )

        mgr.sync()

        assertEquals(3, capturedPayloads.size)

        // Every batch, not just the last: an empty `personalRecords` re-arms the
        // portal's id-less derived personal_records rows for that batch's isPr sets.
        for ((index, payload) in capturedPayloads.withIndex()) {
            assertTrue(
                payload.personalRecords.isNotEmpty(),
                "Batch $index must carry personalRecords (empty arms the portal " +
                    "row-duplication hazard for this batch's isPr sets)",
            )
        }
        assertTrue(
            capturedPayloads.first().sessions
                .flatMap { it.exercises }
                .flatMap { it.sets }
                .any { it.isPr },
            "Precondition: the seeded PR must flag its set isPr (that is the hazard source)",
        )

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
    fun failedBatchAbortsSyncWithoutAdvancingPushWatermark() = runTest {
        authenticate()
        val initial = 9999L
        tokenStorage.setPushWatermark("user-123", "default", initial)
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
            profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
            externalActivityRepository = fakeExternalActivityRepo,
            velocityOneRepMaxRepository = fakeVelocityRepo,
            isProfilePreferenceMigrationReady = { true },
        )

        val result = mgr.sync()

        assertTrue(result.isFailure, "Batch failure propagates up as overall failure")
        assertEquals(
            initial,
            tokenStorage.getPushWatermark("user-123", "default"),
            "push watermark must NOT advance when any batch fails (prevents data consistency gap)",
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
            profilePreferenceSyncRepository = fakeProfilePreferenceSyncRepo,
            externalActivityRepository = fakeExternalActivityRepo,
            velocityOneRepMaxRepository = fakeVelocityRepo,
            isProfilePreferenceMigrationReady = { true },
        )

        mgr.sync()

        assertEquals(
            1,
            callIndex,
            "Once batch 1 fails, batches 2+ must NOT be attempted in the same sync cycle",
        )
    }

    @Test
    fun preferenceChunksFollowTheFinalMetadataBatch() = runTest {
        authenticate()
        fakeUserProfileRepo.setActiveProfileForTest("profile-a")
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(73)
        fakeProfilePreferenceSyncRepo.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(coreSectionForSync()),
            unsyncable = emptyList(),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(
                syncTime = "2026-07-11T12:00:00Z",
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(coreCanonicalForSync()),
            ),
        )

        createManager().sync()

        val preferenceIndex = fakeApi.pushPayloads.indexOfFirst {
            it.profilePreferenceSections != null
        }
        val metadataIndex = fakeApi.pushPayloads.indexOfLast { it.allProfiles != null }
        assertTrue(metadataIndex >= 0)
        assertTrue(preferenceIndex > metadataIndex)
        assertTrue(
            "profile-a" in fakeApi.pushPayloads[metadataIndex].allProfiles.orEmpty().map { it.id },
        )
    }

    @Test
    fun everyLogicalPushCallConsumesTheSharedRateLimit() = runTest {
        authenticate()
        repeat(20) { index ->
            fakeUserProfileRepo.setActiveProfileForTest("profile-$index")
        }
        fakeUserProfileRepo.setActiveProfileForTest("profile-a")
        val ordinary = buildSessions(1).single()
        fakeSyncRepo.workoutSessionsToReturn = listOf(ordinary)
        fakeProfilePreferenceSyncRepo.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = List(20) { index ->
                ProfilePreferenceSectionSyncDto(
                    key = ProfilePreferenceSectionKey(
                        "profile-$index",
                        ProfilePreferenceSectionName.RACK,
                    ),
                    documentVersion = 1,
                    baseRevision = 0,
                    clientModifiedAtEpochMs = 1_783_771_200_000L,
                    localGeneration = 1,
                    payload = buildJsonObject { put("padding", "x".repeat(174_700)) },
                )
            },
            unsyncable = emptyList(),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(
                syncTime = "2026-07-11T12:00:00Z",
                profilePreferencesAccepted = true,
                acknowledgedWorkoutSessionIds = listOf(ordinary.id),
            ),
        )

        // A controllable clock: waiting for capacity advances it by exactly the wait.
        // Everything pushed before the first wait is the first 60 s window.
        var clock = 0L
        var firstWindow: List<PortalSyncPayload>? = null
        var firstWindowAcks: List<Set<String>>? = null
        var firstWindowAppliedPreferenceOutcomes: Int? = null
        val result = createManager(
            rateLimiter = ClientRateLimiter(
                nowMs = { clock },
                waitFor = { waitMs ->
                    if (firstWindow == null) {
                        firstWindow = fakeApi.pushPayloads.toList()
                        firstWindowAcks = fakeSyncRepo.acknowledgedWorkoutParentIdCalls.toList()
                        firstWindowAppliedPreferenceOutcomes = fakeProfilePreferenceSyncRepo.appliedPushOutcomes.size
                    }
                    clock += waitMs
                },
            ),
        ).sync()

        assertTrue(result.isSuccess)
        // Every logical push call (main payload and preference chunks) draws on the one
        // shared window: the first window holds exactly the limit, one ordinary push plus
        // the preference chunks that fit.
        val window = assertNotNull(firstWindow, "21 profiles must exhaust one window")
        assertEquals(SyncConfig.PUSH_RATE_LIMIT_PER_MIN, window.size)
        assertEquals(
            SyncConfig.PUSH_RATE_LIMIT_PER_MIN - 1,
            window.count { it.profilePreferenceSections != null },
        )
        assertEquals(listOf(setOf(ordinary.id)), firstWindowAcks)
        // codex #856: preference chunks past the first window WAIT for capacity instead of
        // taking a local 429 — every one of the 20 dirty sections reaches the portal.
        assertEquals(
            20,
            fakeApi.pushPayloads.flatMap { it.profilePreferenceSections.orEmpty() }
                .map { it.localProfileId }.toSet().size,
            "every dirty preference chunk must reach the portal, not only those in the first window",
        )
        // codex #856 P1: profiles past the window's capacity WAIT for it instead of being
        // failed fast. Profiles are walked in the same order every sync, so failing fast
        // would starve the same trailing profiles forever.
        val pushedProfileIds = fakeApi.pushPayloads
            .filter { it.profilePreferenceSections == null }
            .map { it.profileId }
            .toSet()
        val expectedProfiles = (List(20) { "profile-$it" } + "profile-a").toSet()
        assertEquals(expectedProfiles, pushedProfileIds.intersect(expectedProfiles))
    }

    // ==================== Telemetry-Aware Batching (audit: 36_852 point rejection) ====================

    /**
     * Sessions that individually fit under SYNC_BATCH_SIZE can still push a single
     * batch past the server-side rep_telemetry array cap when force-curve data is
     * heavy. The planner must close a batch early whenever the next session would
     * push cumulative telemetry past MAX_TELEMETRY_PER_BATCH.
     */
    @Test
    fun planSessionBatchesSplitsWhenCumulativeTelemetryExceedsCap() {
        val sessions = (0 until 50).map { stubPortalSession("sess-$it") }
        // Each session carries 800 telemetry points → 50 * 800 = 40_000, well past the
        // 10_000 cap. Should split into at least 5 batches (ceil(40_000 / 10_000)).
        val telemetryCounts = sessions.associate { it.id to 800 }

        val batches = planSessionBatches(sessions, telemetryCounts)

        assertTrue(
            batches.size >= 4,
            "40_000 telemetry points must split into multiple batches; got ${batches.size}",
        )
        batches.forEach { batch ->
            val tel = batch.sumOf { telemetryCounts[it.id] ?: 0 }
            assertTrue(
                tel <= SyncConfig.MAX_TELEMETRY_PER_BATCH,
                "Batch telemetry $tel must not exceed ${SyncConfig.MAX_TELEMETRY_PER_BATCH}",
            )
            assertTrue(
                batch.size <= SyncManager.SYNC_BATCH_SIZE,
                "Batch size ${batch.size} must not exceed SYNC_BATCH_SIZE",
            )
        }
        // No sessions lost or duplicated.
        assertEquals(sessions.size, batches.sumOf { it.size })
    }

    /** Sessions with zero telemetry should batch identically to the old 50-per-batch chunker. */
    @Test
    fun planSessionBatchesPreservesLegacyChunkingWhenTelemetryIsEmpty() {
        val sessions = (0 until 73).map { stubPortalSession("sess-$it") }
        val telemetryCounts = sessions.associate { it.id to 0 }

        val batches = planSessionBatches(sessions, telemetryCounts)

        assertEquals(listOf(50, 23), batches.map { it.size })
    }

    /**
     * A single session whose telemetry alone exceeds MAX_TELEMETRY_PER_BATCH should be
     * emitted in its own batch so surrounding batches still succeed. The oversized
     * batch will still be rejected by PortalApiClient's self-check, but that's isolated
     * to one session instead of poisoning a whole 50-session chunk.
     *
     * NOTE: After the INFERNO telemetry gate (#381 fix), this overflow path is only
     * reachable for INFERNO-tier users whose individual sessions carry > 10k points.
     * Kept as a defensive regression canary in case the gate is ever relaxed or a
     * future tier re-enables telemetry sync.
     */
    @Test
    fun planSessionBatchesIsolatesSingleSessionTelemetryOverflow() {
        val small1 = stubPortalSession("small-1")
        val giant = stubPortalSession("giant")
        val small2 = stubPortalSession("small-2")
        val telemetryCounts = mapOf(
            small1.id to 100,
            giant.id to SyncConfig.MAX_TELEMETRY_PER_BATCH + 5_000,
            small2.id to 100,
        )

        val batches = planSessionBatches(listOf(small1, giant, small2), telemetryCounts)

        // Expect 3 batches: [small1], [giant], [small2]
        assertEquals(3, batches.size, "Giant session must be isolated in its own batch")
        assertEquals(listOf(small1.id), batches[0].map { it.id })
        assertEquals(listOf(giant.id), batches[1].map { it.id })
        assertEquals(listOf(small2.id), batches[2].map { it.id })
    }

    @Test
    fun planSessionBatchesHandlesEmptyInput() {
        val batches = planSessionBatches(emptyList(), emptyMap())
        assertEquals(1, batches.size)
        assertTrue(batches[0].isEmpty())
    }

    @Test
    fun findPushPayloadDuplicateKeysReportsConfiguredPortalPushTablesCaseInsensitive() {
        val firstSession = PortalWorkoutSessionDto(
            id = "session-a",
            userId = "user-123",
            startedAt = "2026-03-02T12:00:00Z",
            exercises = listOf(
                PortalExerciseDto(
                    id = "exercise-a",
                    sessionId = "session-a",
                    name = "Bench Press",
                    sets = listOf(
                        PortalSetDto(
                            id = "set-a",
                            exerciseId = "exercise-a",
                            setNumber = 1,
                            repSummaries = listOf(
                                PortalRepSummaryDto(
                                    id = "rep-a",
                                    setId = "set-a",
                                    repNumber = 1,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val secondSession = firstSession.copy(
            id = "SESSION-A",
            exercises = listOf(
                firstSession.exercises.single().copy(
                    id = "EXERCISE-A",
                    sets = listOf(
                        firstSession.exercises.single().sets.single().copy(
                            id = "SET-A",
                            repSummaries = listOf(
                                firstSession.exercises.single().sets.single().repSummaries
                                    .single()
                                    .copy(id = "REP-A"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val payload = PortalSyncPayload(
            deviceId = "device-1",
            lastSync = 0L,
            sessions = listOf(firstSession, secondSession),
            routines = listOf(
                PortalRoutineSyncDto(id = "routine-a", userId = "user-123", name = "Routine A"),
                PortalRoutineSyncDto(id = "ROUTINE-A", userId = "user-123", name = "Routine B"),
            ),
            cycles = listOf(
                PortalTrainingCycleSyncDto(
                    id = "cycle-a",
                    userId = "user-123",
                    name = "Cycle A",
                ),
                PortalTrainingCycleSyncDto(
                    id = "CYCLE-A",
                    userId = "user-123",
                    name = "Cycle B",
                ),
            ),
            telemetry = listOf(
                PortalRepTelemetryDto(id = "telemetry-a", setId = "set-a", timestampMs = 1L),
                PortalRepTelemetryDto(id = "TELEMETRY-A", setId = "SET-A", timestampMs = 2L),
            ),
        )

        val duplicates = findPushPayloadDuplicateKeys(payload)

        assertEquals(
            listOf(
                "workout_sessions",
                "routines",
                "training_cycles",
                "exercises",
                "sets",
                "rep_summaries",
                "rep_telemetry",
            ),
            duplicates.map { duplicate -> duplicate.table },
        )
        assertEquals(listOf("SESSION-A"), duplicates[0].ids)
        assertEquals(listOf("ROUTINE-A"), duplicates[1].ids)
        assertEquals(listOf("CYCLE-A"), duplicates[2].ids)
        assertEquals(listOf("EXERCISE-A"), duplicates[3].ids)
        assertEquals(listOf("SET-A"), duplicates[4].ids)
        assertEquals(listOf("REP-A"), duplicates[5].ids)
        assertEquals(listOf("TELEMETRY-A"), duplicates[6].ids)
    }

    private fun coreSectionForSync() = ProfilePreferenceSectionSyncDto(
        key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
        documentVersion = 1,
        baseRevision = 0,
        clientModifiedAtEpochMs = 1_783_771_200_000L,
        localGeneration = 1,
        payload = buildJsonObject {
            put("bodyWeightKg", 80.0)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        },
    )

    private fun coreCanonicalForSync() = PortalProfilePreferenceSectionCanonicalDto(
        localProfileId = "profile-a",
        section = "CORE",
        documentVersion = 1,
        serverRevision = 1,
        serverUpdatedAt = "2026-07-11T12:00:00Z",
        payload = coreSectionForSync().payload,
    )

    private fun stubPortalSession(id: String) = PortalWorkoutSessionDto(
        id = id,
        userId = "user-123",
        startedAt = "2026-03-02T12:00:00Z",
    )

    // ==================== Inferno Telemetry Gate (#381) ====================
    //
    // Contract: 50Hz force-curve telemetry is an INFERNO-tier feature per the portal
    // subscription matrix. SyncManager must drop all telemetry from the push payload
    // for any tier other than INFERNO (including unknown/null tiers — fail closed).
    // Rep summaries, sessions, and all other data still ship regardless of tier.

    /**
     * Build a RepMetricData with a minimal but non-trivial force curve so that
     * PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry produces real telemetry
     * points. Point count per rep = 2 cables × (concentric samples + eccentric samples).
     */
    private fun repWithTelemetry(repNumber: Int = 1): RepMetricData = RepMetricData(
        repNumber = repNumber,
        isWarmup = false,
        startTimestamp = 1_700_000_000_000L,
        endTimestamp = 1_700_000_002_500L,
        durationMs = 2_500L,
        concentricDurationMs = 1_000L,
        concentricPositions = floatArrayOf(0f, 100f, 200f),
        concentricLoadsA = floatArrayOf(10f, 12f, 11f),
        concentricLoadsB = floatArrayOf(10f, 12f, 11f),
        concentricVelocities = floatArrayOf(400f, 500f, 600f),
        concentricTimestamps = longArrayOf(0L, 500L, 1_000L),
        eccentricDurationMs = 1_500L,
        eccentricPositions = floatArrayOf(200f, 100f, 0f),
        eccentricLoadsA = floatArrayOf(11f, 10f, 9f),
        eccentricLoadsB = floatArrayOf(11f, 10f, 9f),
        eccentricVelocities = floatArrayOf(300f, 400f, 350f),
        eccentricTimestamps = longArrayOf(0L, 500L, 1_000L),
        peakForceA = 15f,
        peakForceB = 15f,
        avgForceConcentricA = 10f,
        avgForceConcentricB = 10f,
        avgForceEccentricA = 8f,
        avgForceEccentricB = 8f,
        peakVelocity = 800f,
        avgVelocityConcentric = 500f,
        avgVelocityEccentric = 350f,
        rangeOfMotionMm = 300f,
        peakPowerWatts = 300f,
        avgPowerWatts = 200f,
    )

    /** Stage the fakes with a session that carries real telemetry-bearing rep metrics. */
    private fun seedSessionWithTelemetry() {
        val session = buildSessions(1).single()
        fakeSyncRepo.workoutSessionsToReturn = listOf(session)
        fakeSyncRepo.workoutRepMetricsByComponentId = mapOf(
            session.id to listOf(
                repWithTelemetry(1),
                repWithTelemetry(2),
                repWithTelemetry(3),
            ),
        )
    }

    @Test
    fun flameTierSkipsTelemetryButStillPushesSession() = runTest {
        authenticate()
        tokenStorage.updateSubscriptionTier("FLAME")
        seedSessionWithTelemetry()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        val result = createManager().sync()

        assertTrue(
            result.isSuccess || result.exceptionOrNull()?.message?.contains("Pull") == true,
            "Push must succeed on Flame tier even when rep metrics carry telemetry. " +
                "Result: ${result.exceptionOrNull()?.message}",
        )
        val payload = fakeApi.lastPushPayload
        assertNotNull(payload, "Push was invoked")
        assertTrue(
            payload.telemetry.isEmpty(),
            "Flame tier must not ship force-curve telemetry (Inferno-only). " +
                "Got ${payload.telemetry.size} points.",
        )
        assertFalse(
            payload.sessions.isEmpty(),
            "Flame tier still syncs sessions/rep-summaries — only raw telemetry is gated off",
        )
    }

    @Test
    fun emberTierSkipsTelemetry() = runTest {
        authenticate()
        tokenStorage.updateSubscriptionTier("EMBER")
        seedSessionWithTelemetry()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        createManager().sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertTrue(
            payload.telemetry.isEmpty(),
            "Ember tier must not ship telemetry — only Inferno does",
        )
    }

    @Test
    fun infernoTierPushesTelemetry() = runTest {
        authenticate()
        tokenStorage.updateSubscriptionTier("INFERNO")
        seedSessionWithTelemetry()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        createManager().sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertFalse(
            payload.telemetry.isEmpty(),
            "Inferno tier must ship telemetry — this is the whole reason it is a paid tier",
        )
    }

    @Test
    fun unknownTierFailsClosedAndSkipsTelemetry() = runTest {
        authenticate()
        // Deliberately do NOT call updateSubscriptionTier — simulates first-login /
        // offline-login / transient network error during subscription resolution.
        assertEquals(
            null,
            tokenStorage.getSubscriptionTier(),
            "Precondition: tier must be unresolved for this test",
        )
        seedSessionWithTelemetry()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        createManager().sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertTrue(
            payload.telemetry.isEmpty(),
            "Unknown tier must fail closed — better to skip a premium feature than to ship " +
                "Inferno-only payloads to a user whose entitlement cannot be confirmed",
        )
    }

    @Test
    fun cyclePayloadDropsNonUuidRoutineIdsBeforeSend() = runTest {
        authenticate()
        val cycleId = "66666666-6666-4666-a666-666666666666"
        val cycleRoutineId = "cycle_routine_${"55555555-5555-4555-a555-555555555555"}"
        fakeSyncRepo.cyclesToReturn = listOf(
            PortalSyncAdapter.CycleWithContext(
                cycle = TrainingCycle.create(
                    id = cycleId,
                    name = "Cycle With Local Template Routine",
                    days = listOf(
                        CycleDay.create(
                            id = "77777777-7777-4777-a777-777777777777",
                            cycleId = cycleId,
                            dayNumber = 1,
                            routineId = cycleRoutineId,
                        ),
                    ),
                ),
            ),
        )
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        createManager().sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertEquals(1, payload.cycles.size, "Cycle should still be pushed")
        assertEquals(
            null,
            payload.cycles.single().days.single().routineId,
            "Server-bound cycle days must not carry local-only cycle_routine_* IDs into UUID ownership checks",
        )
    }

    @Test
    fun caseSensitiveTierCheckRejectsLowercaseInferno() = runTest {
        authenticate()
        tokenStorage.updateSubscriptionTier("inferno") // wrong case
        seedSessionWithTelemetry()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        createManager().sync()

        val payload = fakeApi.lastPushPayload
        assertNotNull(payload)
        assertTrue(
            payload.telemetry.isEmpty(),
            "Tier comparison is case-sensitive — server stores uppercase, any drift fails closed",
        )
    }

    @Test
    fun repSummariesStillShipWhenTelemetryIsGatedOff() = runTest {
        // The gate must skip only the 50 Hz force curves. Rep summaries are part of
        // every tier's history and analytics, which is why the push still loads rep
        // metrics for an Ember/Flame user even though it builds no telemetry from them.
        authenticate()
        seedSessionWithTelemetry()
        fakeApi.pushResult = Result.success(
            PortalSyncPushResponse(syncTime = "2026-04-21T12:00:00Z"),
        )

        createManager().sync()

        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertTrue(payload.telemetry.isEmpty(), "Precondition: this user's tier cannot sync telemetry")
        assertTrue(
            payload.sessions.flatMap { it.exercises }.flatMap { it.sets }.any { it.repSummaries.isNotEmpty() },
            "Rep summaries must reach the portal on every tier",
        )
    }

    @Test
    fun telemetrySyncTierConstantIsInferno() {
        assertEquals(
            "INFERNO",
            SyncManager.TELEMETRY_SYNC_TIER,
            "Telemetry gate must pin to Inferno — changing this changes entitlement policy",
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


    // ==================== codex #856 P1: the rejection fingerprint covers telemetry ====================

    @Test
    fun sessionFingerprintChangesWhenOnlyTheRawTelemetryChanges() {
        val manager = createManager()
        val dto = stubPortalSession("sess-telemetry")
        val sent = listOf(
            PortalRepTelemetryDto(id = "t-1", setId = "set-1", timestampMs = 0L, forceN = 100f, cable = "A"),
            PortalRepTelemetryDto(id = "t-2", setId = "set-1", timestampMs = 20L, forceN = 110f, cable = "A"),
        )
        val changedCurve = listOf(
            sent[0],
            sent[1].copy(forceN = 111f),
        )

        assertEquals(
            manager.sessionContentFingerprint(dto, sent),
            manager.sessionContentFingerprint(dto, sent.reversed()),
            "the fingerprint must not depend on telemetry order",
        )
        assertTrue(
            manager.sessionContentFingerprint(dto, sent) != manager.sessionContentFingerprint(dto, changedCurve),
            "a force-curve change with an identical session DTO must not look like already-accepted content",
        )
        assertTrue(
            manager.sessionContentFingerprint(dto, sent) != manager.sessionContentFingerprint(dto, emptyList()),
            "telemetry added to an otherwise identical session must change the fingerprint",
        )
    }

    // ==================== codex #856 P1: the repair / delta is batched under the caps ====================

    private fun routineWithId(i: Int, name: String = "Routine $i") = com.devil.phoenixproject.domain.model.Routine(
        id = "00000000-0000-4000-8000-" + i.toString().padStart(12, '0'),
        name = name,
        profileId = "default",
        updatedAt = 100L,
    )

    private fun prWithId(i: Int) = PersonalRecord(
        id = i.toLong(),
        exerciseId = "ex-$i",
        exerciseName = "Squat",
        weightPerCableKg = 25f,
        reps = 10,
        oneRepMax = 25f,
        timestamp = 1_740_000_000_000L + i,
        workoutMode = "OldSchool",
        volume = 250f,
        phase = WorkoutPhase.COMBINED,
        profileId = "default",
        cableCount = 2,
        uuid = "10000000-0000-4000-8000-" + i.toString().padStart(12, '0'),
    )

    private inline fun withByteCap(cap: Long, block: () -> Unit) {
        val saved = PushPlanner.maxBytes
        PushPlanner.maxBytes = cap
        try {
            block()
        } finally {
            PushPlanner.maxBytes = saved
        }
    }

    @Test
    fun aRepairHistoryOverTheByteCapCompletesAcrossSeveralRequests() = runTest {
        authenticate()
        // Repair owed; every routine sits below the watermark, so only repairFrom = 0 sends them.
        tokenStorage.setPushWatermark("user-123", "default", 1_000L)
        fakeSyncRepo.routinesToReturn = List(40) { routineWithId(it) }
        fakeSyncRepo.fullPRsToReturn = List(40) { prWithId(it) }
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val single = PushPlanner.byteSize(
            PortalSyncPayload(deviceId = "d", platform = "p", lastSync = 0L, routines = listOf(
                PortalSyncAdapter.toPortalRoutine(routineWithId(0), "user-123"),
            )),
        )

        withByteCap(single * 8) {
            assertTrue(createManager().sync().isSuccess)

            assertTrue(fakeApi.pushPayloads.size > 2, "the history must be split (saw ${fakeApi.pushPayloads.size})")
            fakeApi.pushPayloads.forEachIndexed { i, payload ->
                assertTrue(
                    PushPlanner.byteSize(payload) <= PushPlanner.maxBytes,
                    "request $i is ${PushPlanner.byteSize(payload)} bytes, over the ${PushPlanner.maxBytes} cap",
                )
            }
            assertEquals(40, fakeApi.pushPayloads.flatMap { it.routines }.map { it.id }.toSet().size)
            assertEquals(40, fakeApi.pushPayloads.flatMap { it.personalRecords }.map { it.id }.toSet().size)
            assertFalse(tokenStorage.needsRoutineCyclePrRepairPush("user-123"), "the repair completes")
        }
    }

    @Test
    fun accountSwitchExclusionsHoldAcrossEveryPlannedRequest() = runTest {
        // PR 11: exclusions filter the gathered lists before the planner splits them, so an
        // excluded routine, deleted-routine id, cycle or PR can reach none of the requests,
        // including the trailing non-session ones.
        authenticate()
        tokenStorage.setPushWatermark("user-123", "default", 1_000L)
        val routines = List(40) { routineWithId(it) }
        val prs = List(40) { prWithId(it) }
        val cycleIds = List(6) { "60000000-0000-4000-8000-" + it.toString().padStart(12, '0') }
        fakeSyncRepo.routinesToReturn = routines
        fakeSyncRepo.fullPRsToReturn = prs
        fakeSyncRepo.cyclesToReturn = cycleIds.map { id ->
            PortalSyncAdapter.CycleWithContext(cycle = TrainingCycle.create(id = id, name = "Cycle $id", days = emptyList()))
        }
        val excludedRoutines = routines.filterIndexed { i, _ -> i % 2 == 0 }.map { it.id }
        val excludedPrs = prs.filterIndexed { i, _ -> i % 2 == 0 }
        val excludedCycles = cycleIds.filterIndexed { i, _ -> i % 2 == 0 }
        fakeSyncRepo.insertSyncExcludedEntities("user-123", SyncExcludedEntityTypes.ROUTINE, excludedRoutines)
        fakeSyncRepo.insertSyncExcludedEntities(
            "user-123",
            SyncExcludedEntityTypes.PERSONAL_RECORD,
            excludedPrs.map { it.id.toString() } + excludedPrs.mapNotNull { it.uuid },
        )
        fakeSyncRepo.insertSyncExcludedEntities("user-123", SyncExcludedEntityTypes.CYCLE, excludedCycles)
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val single = PushPlanner.byteSize(
            PortalSyncPayload(deviceId = "d", platform = "p", lastSync = 0L, routines = listOf(
                PortalSyncAdapter.toPortalRoutine(routineWithId(0), "user-123"),
            )),
        )

        withByteCap(single * 6) {
            assertTrue(createManager().sync().isSuccess)
            assertTrue(fakeApi.pushPayloads.size > 2, "the push must be split (saw ${fakeApi.pushPayloads.size})")

            val sentRoutines = fakeApi.pushPayloads.flatMap { it.routines }.map { it.id }
            val sentDeleted = fakeApi.pushPayloads.flatMap { it.deletedRoutineIds }
            val sentCycles = fakeApi.pushPayloads.flatMap { it.cycles }.map { it.id }
            val sentPrs = fakeApi.pushPayloads.flatMap { it.personalRecords }.mapNotNull { it.id }
            assertTrue(sentRoutines.none { it in excludedRoutines }, "excluded routine planned: $sentRoutines")
            assertTrue(sentDeleted.none { it in excludedRoutines }, "excluded deleted-routine id planned")
            assertTrue(sentCycles.none { it in excludedCycles }, "excluded cycle planned: $sentCycles")
            assertTrue(
                sentPrs.none { id -> excludedPrs.any { it.uuid == id || it.id.toString() == id } },
                "excluded PR planned: $sentPrs",
            )
            // The allowed half still goes out, so the filter is not simply emptying the push.
            assertTrue(sentRoutines.toSet() == (routines.map { it.id } - excludedRoutines.toSet()).toSet())
            assertTrue(sentCycles.toSet() == (cycleIds - excludedCycles.toSet()).toSet())
        }
    }

    @Test
    fun accountSwitchExclusionsHoldForCustomExercisesAndAssessmentsInTrailingRequests() = runTest {
        // PR 11 x PR 10's planner: custom exercises and assessments now ride their own
        // trailing requests. Exclusions are applied where both lists are gathered, so an
        // excluded row reaches none of those requests.
        authenticate()
        tokenStorage.setPushWatermark("user-123", "default", 1_000L)
        val customs = List(30) { i ->
            CustomExerciseSyncDto(
                clientId = "custom_${1_740_000_000_000L + i}",
                name = "Custom exercise $i",
                muscleGroup = "Chest",
                equipment = "Cable",
                defaultCableConfig = "DOUBLE",
                createdAt = 1_740_000_000_000L + i,
                updatedAt = 1_740_000_000_000L + i,
            )
        }
        val assessments = List(30) { i ->
            com.devil.phoenixproject.database.AssessmentResult(
                id = (i + 1).toLong(),
                exerciseId = "ex-$i",
                estimatedOneRepMaxKg = 60.0,
                loadVelocityData = "[]",
                assessmentSessionId = null,
                userOverrideKg = null,
                createdAt = 1_740_000_000_000L + i,
                profile_id = "default",
            )
        }
        fakeSyncRepo.customExercisesToReturn = customs
        fakeSyncRepo.assessmentsToReturn = assessments
        val excludedCustoms = customs.filterIndexed { i, _ -> i % 2 == 0 }.map { it.clientId }
        val excludedAssessments = assessments.filterIndexed { i, _ -> i % 2 == 0 }.map { it.id.toString() }
        fakeSyncRepo.insertSyncExcludedEntities("user-123", SyncExcludedEntityTypes.CUSTOM_EXERCISE, excludedCustoms)
        fakeSyncRepo.insertSyncExcludedEntities("user-123", SyncExcludedEntityTypes.ASSESSMENT, excludedAssessments)
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val single = PushPlanner.byteSize(
            PortalSyncPayload(deviceId = "d", platform = "p", lastSync = 0L, customExercises = listOf(customs[0])),
        )

        withByteCap(single * 5) {
            assertTrue(createManager().sync().isSuccess)
            assertTrue(fakeApi.pushPayloads.size > 2, "the push must be split (saw ${fakeApi.pushPayloads.size})")

            val sentCustoms = fakeApi.pushPayloads.flatMap { it.customExercises }.map { it.clientId }
            val sentAssessments = fakeApi.pushPayloads.flatMap { it.assessments }.map { it.id }
            assertTrue(sentCustoms.none { it in excludedCustoms }, "excluded custom exercise planned: $sentCustoms")
            assertTrue(sentAssessments.none { it in excludedAssessments }, "excluded assessment planned: $sentAssessments")
            assertEquals((customs.map { it.clientId } - excludedCustoms.toSet()).toSet(), sentCustoms.toSet())
            assertEquals(
                (assessments.map { it.id.toString() } - excludedAssessments.toSet()).toSet(),
                sentAssessments.toSet(),
            )
        }
    }

    @Test
    fun aSingleItemTooLargeForAnyRequestIsSkippedAndDoesNotWedgeSync() = runTest {
        authenticate()
        tokenStorage.setPushWatermark("user-123", "default", 1_000L)
        val small = List(5) { routineWithId(it) }
        val huge = routineWithId(99, name = "x".repeat(20_000))
        fakeSyncRepo.routinesToReturn = small + huge
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        withByteCap(10_000L) {
            assertTrue(createManager().sync().isSuccess, "an oversized item must not fail the whole push")
            val sent = fakeApi.pushPayloads.flatMap { it.routines }.map { it.id }.toSet()
            assertEquals(small.map { it.id }.toSet(), sent, "the oversized routine is skipped, the rest are sent")
            assertTrue(fakeApi.pushPayloads.all { PushPlanner.byteSize(it) <= 10_000L })

            fakeApi.pushPayloads.clear()
            assertTrue(createManager().sync().isSuccess, "the next sync is not wedged either")
        }
    }

    @Test
    fun moreThanTheArrayCapOfPrsIsSplitAndEverySessionBatchStillCarriesPrs() = runTest {
        authenticate()
        fakeSyncRepo.workoutSessionsToReturn = buildSessions(120)
        fakeSyncRepo.fullPRsToReturn = List(PushPlanner.MAX_ITEMS_PER_ARRAY + 5) { prWithId(it) }
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))

        assertTrue(createManager().sync().isSuccess)

        assertEquals(
            120,
            fakeApi.pushPayloads.flatMap { it.sessions }.map { it.id }.toSet().size,
            "every session is delivered: a huge PR list must not make session batches unsendable",
        )
        fakeApi.pushPayloads.forEach { payload ->
            assertTrue(payload.personalRecords.size <= PushPlanner.MAX_ITEMS_PER_ARRAY)
        }
        fakeApi.pushPayloads.filter { it.sessions.isNotEmpty() }.forEachIndexed { i, payload ->
            assertTrue(
                payload.personalRecords.isNotEmpty(),
                "session batch $i must carry personalRecords (PORTAL ROW-DUPLICATION HAZARD)",
            )
        }
        assertEquals(
            PushPlanner.MAX_ITEMS_PER_ARRAY + 5,
            fakeApi.pushPayloads.flatMap { it.personalRecords }.map { it.id }.toSet().size,
            "every PR is delivered exactly across the requests",
        )
    }

    // ==================== codex #856 P1: list-shaped final fields are split too ====================

    @Test
    fun listShapedFinalFieldsAreSplitAndEachExternalActivityAckReadsItsOwnRequest() = runTest {
        authenticate()
        fakeUserProfileRepo.setActiveProfileForTest(
            subscriptionStatus = com.devil.phoenixproject.data.repository.SubscriptionStatus.ACTIVE,
        )
        fakeSyncRepo.customExercisesToReturn = List(30) { i ->
            CustomExerciseSyncDto(
                clientId = "custom_$i",
                name = "Custom exercise number $i with a long descriptive name",
                muscleGroup = "Back",
                equipment = "Cable",
                defaultCableConfig = "DOUBLE",
                createdAt = 1L,
                updatedAt = 1L,
            )
        }
        repeat(30) { i ->
            fakeExternalActivityRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
                externalId = "hevy-$i",
                provider = com.devil.phoenixproject.domain.model.IntegrationProvider.HEVY,
                name = "Imported activity $i with a long descriptive name",
                startedAt = 1_000L + i,
                profileId = "default",
                needsSync = true,
            )
        }
        // Echo, for each request, exactly the external activities THAT request carried.
        val echoingApi = object : FakePortalApiClient() {
            override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
                super.pushPortalPayload(payload)
                return Result.success(
                    PortalSyncPushResponse(
                        syncTime = "2026-03-02T12:00:00Z",
                        externalActivityKeys = payload.externalActivities.map {
                            ExternalActivityAckDto(externalId = it.externalId, provider = it.provider)
                        },
                    ),
                )
            }
        }
        val single = PushPlanner.byteSize(
            PortalSyncPayload(
                deviceId = "d",
                platform = "p",
                lastSync = 0L,
                customExercises = listOf(fakeSyncRepo.customExercisesToReturn.first()),
            ),
        )

        withByteCap(single * 6) {
            assertTrue(createManager(apiClient = echoingApi).sync().isSuccess)

            val payloads = echoingApi.pushPayloads
            assertTrue(payloads.size > 2, "the final fields must be split (saw ${payloads.size})")
            payloads.forEachIndexed { i, payload ->
                assertTrue(PushPlanner.byteSize(payload) <= PushPlanner.maxBytes, "request $i is over the cap")
            }
            assertEquals(30, payloads.flatMap { it.customExercises }.map { it.clientId }.toSet().size)
            assertEquals(30, payloads.flatMap { it.externalActivities }.map { it.externalId }.toSet().size)
            assertTrue(
                payloads.filter { it.externalActivities.isNotEmpty() }.size > 1,
                "external activities must span several requests for this test to mean anything",
            )
            // allProfiles travels only on the last request.
            assertEquals(listOf(payloads.lastIndex), payloads.indices.filter { payloads[it].allProfiles != null })
            // Every activity is acknowledged from the response of the request that carried it.
            assertEquals(
                (0 until 30).map { "hevy-$it" }.toSet(),
                fakeExternalActivityRepo.markedSyncedKeys.map { it.externalId }.toSet(),
            )
        }
    }

    @Test
    fun assessmentsAreSplitUnderTheCapAndAnAssessmentTooLargeForAnyRequestIsSkippedWithItsId() {
        val envelope = PortalSyncPayload(deviceId = "d", platform = "p", lastSync = 0L)
        fun assessment(i: Int, data: String = "[]") = PortalAssessmentResultDto(
            id = "assessment-$i",
            exerciseId = "ex-$i",
            estimatedOneRepMaxKg = 50f,
            loadVelocityData = data,
            createdAt = "2026-03-02T12:00:00Z",
        )
        val normal = List(25) { assessment(it) }
        val huge = assessment(99, data = "[" + "1".repeat(5_000) + "]")
        val single = PushPlanner.byteSize(envelope.copy(assessments = listOf(normal.first())))
        val skipped = mutableListOf<Pair<String, String>>()

        val envelopeBytes = PushPlanner.byteSize(envelope)
        // Room for about six assessments per request on top of the envelope.
        withByteCap(envelopeBytes + (single - envelopeBytes + 1) * 6) {
            val requests = PushPlanner.planTailRequests(
                envelope = envelope,
                routines = emptyList(),
                deletedRoutineIds = emptyList(),
                cycles = emptyList(),
                personalRecords = emptyList(),
                customExercises = emptyList(),
                assessments = normal + huge,
                badges = emptyList(),
                externalActivities = emptyList(),
                rpgAttributes = null,
                gamificationStats = null,
                allProfiles = listOf(LocalProfileDto("default", "Default", 0)),
                onSkip = { type, id -> skipped += type to id },
            )

            assertTrue(requests.size > 2)
            requests.forEach { assertTrue(PushPlanner.byteSize(it) <= PushPlanner.maxBytes) }
            assertEquals(normal.map { it.id }.toSet(), requests.flatMap { it.assessments }.map { it.id }.toSet())
            assertEquals(listOf("assessment" to "assessment-99"), skipped)
            assertEquals(listOf(requests.lastIndex), requests.indices.filter { requests[it].allProfiles != null })
        }
    }

    // ==================== codex #856 P2: profile metadata can never wedge the push ====================

    @Test
    fun anAbsurdProfileNameAndManyProfilesNeverWedgeThePush() = runTest {
        authenticate()
        val hugeName = "N".repeat(5_000)
        repeat(24) { i -> fakeUserProfileRepo.seedReadyProfileForTest("profile-$i", name = "$hugeName-$i") }
        fakeUserProfileRepo.setActiveProfileForTest()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        // Names are bounded on the wire.
        assertEquals(MAX_PORTAL_PROFILE_NAME_CHARS, boundedProfileName(hugeName).length)

        // A cap that 25 bounded profiles do not fit in: allProfiles must be dropped, not
        // partially sent (the portal would delete every omitted profile) and not sent oversized.
        val oneProfile = PushPlanner.byteSize(
            PortalSyncPayload(
                deviceId = "d",
                platform = "p",
                lastSync = 0L,
                allProfiles = listOf(LocalProfileDto("profile-0", boundedProfileName(hugeName), 0)),
            ),
        )
        withByteCap(oneProfile * 10) {
            // 25 profiles push more than the 10/min client limit; drive the limiter on a
            // controllable clock (the default reads the real clock while runTest skips delays).
            var clock = 0L
            val limiter = ClientRateLimiter(nowMs = { clock }, waitFor = { clock += it })
            assertTrue(createManager(rateLimiter = limiter).sync().isSuccess, "oversized profile metadata must not fail the push")
            fakeApi.pushPayloads.forEachIndexed { i, payload ->
                assertTrue(PushPlanner.byteSize(payload) <= PushPlanner.maxBytes, "request $i is over the cap")
                payload.allProfiles?.forEach { assertTrue(it.name.length <= MAX_PORTAL_PROFILE_NAME_CHARS) }
                assertTrue(
                    payload.allProfiles == null || payload.allProfiles!!.size == 25,
                    "allProfiles is all-or-nothing (saw ${payload.allProfiles?.size})",
                )
            }
            // Every profile still reaches the portal through its own push's profileId.
            assertEquals(25, fakeApi.pushPayloads.mapNotNull { it.profileId }.toSet().size)
        }
    }
}
