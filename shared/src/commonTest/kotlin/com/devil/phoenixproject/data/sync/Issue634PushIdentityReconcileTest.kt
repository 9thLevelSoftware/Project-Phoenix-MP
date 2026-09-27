package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Issue #634 (reopened): push payload identity reconciliation.
 *
 * The pre-fix push preflight hard-failed the whole sync on repeated payload keys
 * ("Duplicate IDs in local push payload") with no dedup/quarantine/recovery,
 * leaving the device permanently sync-blocked (the tester's screenshots). Worse,
 * its personal-record key omitted workoutMode AND the record UUID, so two
 * legitimate local rows with distinct UUIDs — which the deployed portal's F335
 * identity keeps distinct (`personalRecordIdentityKey` keys dedicated rows on
 * their id) — falsely tripped it.
 *
 * These tests pin the fixed contract:
 *  - distinct PR workoutMode/UUID rows stay distinct, both pushed, both stamped,
 *    never merged or deleted (RCA acceptance criterion 1);
 *  - identical / projection / case-variant payload duplicates repair by
 *    collapsing to one canonical entry without losing content (criterion 2);
 *  - genuinely conflicting versions are held back, reported across ALL offending
 *    tables, kept local and retryable, with no false stamp/ack/watermark
 *    advancement, while unaffected rows still push (criterion 3).
 */
class Issue634PushIdentityReconcileTest {

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
    private val fakeCompletedSetRepo = FakeCompletedSetRepository()

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
        completedSetRepository = fakeCompletedSetRepo,
        ownershipEventApplier = null,
    )

    private fun setupAuthenticated(userId: String = "user-123") {
        val nowSec = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "fake-access-token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = nowSec + 3600,
                refreshToken = "fake-refresh-token",
                user = GoTrueUser(id = userId, email = "test@example.com"),
            ),
        )
    }

    private fun makePr(
        id: Long,
        exerciseId: String = "Standing_Calf_Raises",
        exerciseName: String = "Standing Calf Raises",
        timestamp: Long,
        prType: PRType = PRType.MAX_VOLUME,
        phase: WorkoutPhase = WorkoutPhase.COMBINED,
        workoutMode: String = "OldSchool",
        uuid: String?,
        weightPerCableKg: Float = 60f,
    ) = PersonalRecord(
        id = id,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        weightPerCableKg = weightPerCableKg,
        reps = 12,
        oneRepMax = weightPerCableKg,
        timestamp = timestamp,
        workoutMode = workoutMode,
        prType = prType,
        volume = weightPerCableKg * 12,
        phase = phase,
        profileId = "default",
        cableCount = 2,
        uuid = uuid,
    )

    private fun makeCycle(id: String, name: String) =
        PortalSyncAdapter.CycleWithContext(cycle = TrainingCycle.create(id = id, name = name))

    private fun makeRoutine(id: String, name: String) = Routine(id = id, name = name, profileId = "default")

    // ─── Acceptance criterion 1: distinct mode/UUID PR rows stay distinct ───

    @Test
    fun `distinct PR workoutMode and UUID rows are both pushed and stamped and never merged`() = runTest {
        setupAuthenticated()
        val achievedAtMs = 1_770_569_713_326L
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePr(
                id = 1,
                timestamp = achievedAtMs,
                workoutMode = "OldSchool",
                uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ),
            makePr(
                id = 2,
                timestamp = achievedAtMs,
                workoutMode = "TUTBeast",
                uuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "Distinct identities must not block the sync")
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertEquals(
            listOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            payload.personalRecords.mapNotNull { it.id },
            "Both dedicated PR rows must survive the push (F335 keys them on UUID)",
        )
        assertEquals(
            2,
            payload.personalRecords.map { it.workoutMode }.distinct().size,
            "Distinct workoutMode values must not be collapsed",
        )
        assertEquals(
            setOf(1L, 2L),
            fakeSyncRepo.updatedPersonalRecordTimestamps.keys,
            "Both delivered rows are stamped",
        )
        assertTrue(fakeSyncRepo.clearedPersonalRecordUpdatedAtIds.isEmpty(), "Nothing is held back")
        val state = manager.syncState.value
        assertIs<SyncState.Success>(state)
        assertNull(state.heldBackSummary, "No held-back rows means no warning")
    }

    // ─── Acceptance criterion 2: identical / case-variant duplication repairs ───

    @Test
    fun `identical duplicate PR payload entries collapse to one canonical entry`() = runTest {
        setupAuthenticated()
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePr(id = 1, timestamp = 1_770_569_713_326L, uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            // Same server identity and content gathered twice (projection duplicate).
            makePr(id = 2, timestamp = 1_770_569_713_326L, uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertEquals(1, payload.personalRecords.size, "One canonical entry carries the identical content")
        assertEquals(
            setOf(1L, 2L),
            fakeSyncRepo.updatedPersonalRecordTimestamps.keys,
            "Both local rows are stamped: the collapsed twin's content was delivered byte-identically",
        )
        val state = manager.syncState.value
        assertIs<SyncState.Success>(state)
    }

    @Test
    fun `case-variant cycle UUID duplicates with identical content repair to one entry`() = runTest {
        setupAuthenticated()
        fakeSyncRepo.cyclesToReturn = listOf(
            makeCycle(id = "3C2C452C-6B1A-45DE-BA25-FDAC4AFF8CBE", name = "Volume block"),
            // Same UUID modulo case and identical content: one server row (uuid PKs
            // fold case) reached the payload twice.
            makeCycle(id = "3c2c452c-6b1a-45de-ba25-fdac4aff8cbe", name = "Volume block"),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "Projection duplication must not block the sync")
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertEquals(1, payload.cycles.size, "Repaired to one canonical cycle entry")
        assertEquals("3C2C452C-6B1A-45DE-BA25-FDAC4AFF8CBE", payload.cycles.single().id)
    }

    // ─── Acceptance criterion 3: conflicts stay visible + retryable, never stamped ───

    @Test
    fun `conflicting same-identity PR rows are held back reported and re-armed and never merged`() = runTest {
        setupAuthenticated()
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePr(id = 1, timestamp = 1_770_569_713_326L, uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", weightPerCableKg = 60f),
            // Same UUID, different content: two genuinely conflicting versions of
            // one server row. The portal would silently last-write-wins merge them.
            makePr(id = 2, timestamp = 1_770_569_713_326L, uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", weightPerCableKg = 80f),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "A local conflict must not hard-block the whole sync")
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertTrue(payload.personalRecords.isEmpty(), "No winner is picked; neither version is sent")
        assertTrue(
            fakeSyncRepo.updatedPersonalRecordTimestamps.isEmpty(),
            "Unacked rows must never be stamped as synced",
        )
        assertEquals(
            setOf(1L, 2L),
            fakeSyncRepo.clearedPersonalRecordUpdatedAtIds,
            "Both rows re-arm (updatedAt cleared) so they stay retryable",
        )
        val state = manager.syncState.value
        assertIs<SyncState.Success>(state)
        val summary = assertNotNull(state.heldBackSummary, "The held-back rows must be visible to the user")
        assertTrue(summary.contains("personal_records"), "The report names the offending table: $summary")
        assertTrue(
            summary.contains("nothing was merged or deleted"),
            "The report promises non-destructive handling: $summary",
        )
    }

    @Test
    fun `conflicting same-identity cycles hold back both versions without deleting or blanket dedup`() = runTest {
        setupAuthenticated()
        fakeSyncRepo.cyclesToReturn = listOf(
            makeCycle(id = "3c2c452c-6b1a-45de-ba25-fdac4aff8cbe", name = "Cycle copy A"),
            makeCycle(id = "3c2c452c-6b1a-45de-ba25-fdac4aff8cbe", name = "Cycle copy B"),
        )
        fakeSyncRepo.routinesToReturn = listOf(
            makeRoutine(id = "3db61128-c19d-48dc-a05e-ade968afa87e", name = "Unaffected"),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "The screenshot error is gone: the sync proceeds")
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertTrue(payload.cycles.isEmpty(), "Conflicting versions are neither merged nor deleted nor sent")
        assertEquals(
            listOf("3db61128-c19d-48dc-a05e-ade968afa87e"),
            payload.routines.map { it.id },
            "Unaffected entities still push (bounded safe recovery)",
        )
        val state = manager.syncState.value
        assertIs<SyncState.Success>(state)
        assertTrue(
            assertNotNull(state.heldBackSummary).contains("training_cycles"),
            "The held-back cycle conflict is reported",
        )
    }

    @Test
    fun `every offending table is named in one held-back report`() = runTest {
        setupAuthenticated()
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePr(id = 1, timestamp = 1_770_569_713_326L, uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", weightPerCableKg = 60f),
            makePr(id = 2, timestamp = 1_770_569_713_326L, uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", weightPerCableKg = 80f),
        )
        fakeSyncRepo.routinesToReturn = listOf(
            makeRoutine(id = "8db61128-c19d-48dc-a05e-ade968afa87e", name = "Version one"),
            makeRoutine(id = "8db61128-c19d-48dc-a05e-ade968afa87e", name = "Version two"),
        )
        fakeSyncRepo.cyclesToReturn = listOf(
            makeCycle(id = "cycle-a", name = "Version one"),
            makeCycle(id = "cycle-a", name = "Version two"),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess)
        val summary = assertNotNull(manager.syncState.value.let { (it as? SyncState.Success)?.heldBackSummary })
        assertTrue(summary.contains("personal_records"), "personal_records reported: $summary")
        assertTrue(summary.contains("routines"), "routines reported: $summary")
        assertTrue(summary.contains("training_cycles"), "training_cycles reported: $summary")
    }

    // ─── The tester's exact screenshots, recovered ─────────────────────────

    @Test
    fun `issue634 repro derived-key PR collision with distinct UUIDs no longer blocks sync`() = runTest {
        setupAuthenticated()
        // Epoch ms for 2026-02-08T16:55:13.326Z — the tester's achievedAt; the two
        // rows below are the duplicate shape observed on the device (distinct row
        // ids AND distinct uuids, same derived identity key).
        val achievedAtMs = 1_770_569_713_326L
        fakeSyncRepo.fullPRsToReturn = listOf(
            makePr(
                id = 9001,
                timestamp = achievedAtMs,
                uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ),
            makePr(
                id = 9002,
                timestamp = achievedAtMs,
                uuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ),
        )
        fakeSyncRepo.sessionIdsForPersonalRecordsToReturn = emptyMap()
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "The pre-fix 400 'Duplicate IDs in local push payload' is gone")
        assertTrue(fakeApi.pushCallCount > 0, "The push now goes out")
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertEquals(
            setOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            payload.personalRecords.mapNotNull { it.id }.toSet(),
            "Both dedicated rows are sent: the portal's F335 identity keeps them distinct",
        )
        assertEquals(setOf(9001L, 9002L), fakeSyncRepo.updatedPersonalRecordTimestamps.keys)
    }

    @Test
    fun `issue634 repro duplicate training_cycles UUID no longer blocks the sync`() = runTest {
        setupAuthenticated()
        val duplicatedCycleUuid = "3c2c452c-6b1a-45de-ba25-fdac4aff8cbe"
        fakeSyncRepo.cyclesToReturn = listOf(
            makeCycle(id = duplicatedCycleUuid, name = "Cycle copy A"),
            makeCycle(id = duplicatedCycleUuid, name = "Cycle copy B"),
        )
        fakeApi.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-03-02T12:00:00Z"))
        val manager = createManager()

        val result = manager.sync()

        assertTrue(result.isSuccess, "The pre-fix 400 on training_cycles is gone")
        assertTrue(fakeApi.pushCallCount > 0, "Unaffected entities proceed")
        val payload = assertNotNull(fakeApi.lastPushPayload)
        assertTrue(payload.cycles.isEmpty(), "Neither conflicting cycle version is silently merged or deleted")
        val state = manager.syncState.value
        assertIs<SyncState.Success>(state)
        assertTrue(assertNotNull(state.heldBackSummary).contains("training_cycles"))
    }
}
