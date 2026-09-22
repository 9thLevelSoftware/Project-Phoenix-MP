package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * PR 10 acceptance: per-profile cursors, the multi-profile sync loop and the
 * pull-before-the-next-push stamp, against a real foreign-keys-ON [SqlDelightSyncRepository]
 * and a recording fake portal that models `replace_session_children` plus both of the
 * portal's pull query paths (parity-excluding-ids and the legacy
 * `local_profile_id.eq.P OR IS NULL` filter).
 */
class MultiProfileSyncIntegrationTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var syncRepository: SqlDelightSyncRepository
    private lateinit var tokenStorage: PortalTokenStorage
    private lateinit var settings: MapSettings
    private lateinit var api: RecordingPortalApi
    private lateinit var manager: SyncManager

    private val userId = "user-123"
    private val profileA = "profile-a"
    private val profileB = "profile-b"

    // Canonical UUIDs: SyncManager strips non-UUID ids from routine payloads and from
    // every known-id list, so anything that must survive those filters needs the shape.
    private val routineB = "11111111-1111-4111-8111-111111111111"
    private val pagedOne = "22222222-2222-4222-8222-222222222222"
    private val routineWorkoutOne = "33333333-3333-4333-8333-333333333333"

    private val baseTime = currentTimeMillis()

    @Before
    fun setup() {
        database = createTestDatabase()
        userProfileRepository = FakeUserProfileRepository()
        // Seed both profiles, leave A active: setActiveProfileForTest keeps the others.
        userProfileRepository.setActiveProfileForTest(id = profileA)
        userProfileRepository.setActiveProfileForTest(id = profileB)
        userProfileRepository.setActiveProfileForTest(id = profileA)
        syncRepository = SqlDelightSyncRepository(database, userProfileRepository)
        settings = MapSettings()
        tokenStorage = PortalTokenStorage(settings)
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = userId, email = "a@b.c"),
            ),
        )
        // Suppress both one-time repair pushes; scenario 5 re-enables the RCP one.
        tokenStorage.setRoutineGroupRepairCursor(profileA, 0L)
        tokenStorage.setRoutineGroupRepairCursor(profileB, 0L)
        tokenStorage.markRoutineCyclePrRepairPushDone(userId)
        api = RecordingPortalApi()
        manager = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = syncRepository,
            gamificationRepository = SqlDelightGamificationRepository(database),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = userProfileRepository,
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
            completedSetRepository = FakeCompletedSetRepository(),
        )
    }

    // ===== 1. A non-active profile's edit is pushed in the same loop =====

    @Test
    fun editingARoutineUnderBWhileAIsActivePushesBsEditInTheSameLoop() = runTest {
        insertRoutine(routineB, profileB)

        assertTrue(manager.sync().isSuccess)

        val bPush = api.pushPayloads.singleOrNull { it.profileId == profileB }
        assertTrue(
            bPush != null && bPush.routines.any { it.id == routineB },
            "profile B's routine edit must ride the same multi-profile loop as A's push " +
                "(payloads=${api.pushPayloads.map { it.profileId to it.routines.map { r -> r.id } }})",
        )
    }

    // ===== 2. An immediate second sync pushes nothing =====

    @Test
    fun anImmediateSecondSyncPushesNothing() = runTest {
        insertRoutine(routineB, profileB)
        insertSession("solo-1", groupId = null, timestamp = baseTime, profileId = profileA)
        assertTrue(manager.sync().isSuccess)

        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)

        assertTrue(
            api.pushPayloads.flatMap { it.sessions }.isEmpty(),
            "a second sync must not re-push already-acked sessions",
        )
        // Personal records are stamped with updatePRTimestamp after accept, so they too
        // fall out of the delta. (Routines with a NULL updatedAt always match
        // selectRoutinesModifiedSince — pre-existing, outside PR 10's ack path.)
        assertTrue(
            api.pushPayloads.flatMap { it.personalRecords }.isEmpty(),
            "a second sync must not re-push already-acked personal records",
        )
    }

    // ===== 3. The pull carries the stored cursor (− 5 min) or 0 when unset =====

    @Test
    fun thePullCarriesTheStoredCursorMinusTheOverlapAndZeroWhenUnset() = runTest {
        val firstPageSyncTime = 1_740_000_000_000L
        // No cursor stored yet: every profile's first pull must send 0.
        // Two profiles → two scripted pages per sync.
        api.pullResponses += PullScript(syncTime = firstPageSyncTime)
        api.pullResponses += PullScript(syncTime = firstPageSyncTime)
        assertTrue(manager.sync().isSuccess)
        assertTrue(api.pullCallLastSyncs.isNotEmpty())
        assertTrue(
            api.pullCallLastSyncs.all { it == 0L },
            "the first pull must ask for everything (lastSync=0), was ${api.pullCallLastSyncs}",
        )

        // A completed pull stores firstPage.syncTime − PULL_CURSOR_OVERLAP_MS, so the
        // next pull carries that, not the raw syncTime.
        api.pullResponses += PullScript(syncTime = firstPageSyncTime)
        api.pullResponses += PullScript(syncTime = firstPageSyncTime)
        api.pullCallLastSyncs.clear()
        assertTrue(manager.sync().isSuccess)

        val expected = firstPageSyncTime - SyncManager.PULL_CURSOR_OVERLAP_MS
        assertTrue(
            api.pullCallLastSyncs.isNotEmpty() && api.pullCallLastSyncs.all { it == expected },
            "the next pull must carry firstPage.syncTime − ${SyncManager.PULL_CURSOR_OVERLAP_MS} " +
                "(expected $expected, was ${api.pullCallLastSyncs})",
        )
    }

    // ===== 4. A pulled row with a future server updated_at never comes back in a push =====

    @Test
    fun aSessionPulledWithAFutureServerUpdatedAtNeverAppearsInAnyPush() = runTest {
        api.portalSessions += PortalSession(
            id = "pulled-1",
            localProfileId = profileA,
            updatedAt = currentTimeMillis() + 10 * 60_000L,
            routineSessionId = null,
            notes = "from the web",
        )

        assertTrue(manager.sync().isSuccess)
        assertTrue(manager.sync().isSuccess)

        val pushedIds = api.pushPayloads.flatMap { payload -> payload.sessions.map { it.id } }
        assertFalse(
            "pulled-1" in pushedIds,
            "a session that only ever arrived by pull must never be pushed back (saw $pushedIds)",
        )
    }

    // ===== 5. Upgrade seeding + the one-time routine/cycle/PR repair push =====

    @Test
    fun afterUpgradeSeedingANonActiveProfilePushesOnlyItsUnstampedSessionsPlusTheRepairPayload() = runTest {
        // Legacy global cursor, so migrateLegacyCursors splits it across the profiles.
        settings.putLong("portal_last_sync_timestamp", baseTime - 60_000L)
        // Re-open the one-time routines/cycles/PRs repair (setup closed it).
        settings.remove("portal_rcp_repair_done_$userId")

        // B holds one session that only ever arrived by pull (clean) and one local
        // row this device recorded and never uploaded (never stamped).
        insertSession(
            "b-pulled",
            groupId = null,
            timestamp = baseTime - 30_000L,
            profileId = profileB,
            stampedAt = baseTime - 20_000L,
        )
        database.phoenixDatabaseQueries.markSessionPulled("b-pulled")
        insertSession("b-local", groupId = null, timestamp = baseTime, profileId = profileB)
        insertRoutine(routineB, profileB)
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "bench",
            exerciseName = "Bench Press",
            weight = 40.0,
            reps = 8L,
            oneRepMax = 45.0,
            achievedAt = baseTime,
            workoutMode = "OldSchool",
            prType = "MAX_WEIGHT",
            volume = 320.0,
            phase = "COMBINED",
            profile_id = profileB,
            cable_count = 2L,
            uuid = "pr-b-1",
        )

        assertTrue(manager.sync().isSuccess)

        val bPushes = api.pushPayloads.filter { it.profileId == profileB }
        val bSessions = bPushes.flatMap { it.sessions }.map { it.id }.toSet()
        assertTrue(
            "b-local" in bSessions,
            "the never-stamped local row must be pushed (saw $bSessions)",
        )
        assertFalse(
            "b-pulled" in bSessions,
            "a pulled, already-synced row must not be re-pushed (saw $bSessions)",
        )
        assertTrue(
            bPushes.flatMap { it.routines }.any { it.id == routineB },
            "the one-time repair must carry profile B's routines",
        )
        assertTrue(
            bPushes.flatMap { it.personalRecords }.isNotEmpty(),
            "the one-time repair must carry profile B's personal records",
        )
    }

    // ===== 6. A session updated between pull pages is caught by the 5-minute overlap =====

    @Test
    fun aSessionUpdatedBetweenPullPagesIsCaughtByTheOverlapWindow() = runTest {
        val firstPageSyncTime = 1_740_000_000_000L
        // Sync 1, page 1 for A: s1 is already on the portal. The portal then bumps s1
        // to firstPageSyncTime − 1 min — inside the 5-minute overlap window, but before
        // a cursor that stored the raw syncTime.
        api.portalSessions += PortalSession(
            id = pagedOne,
            localProfileId = profileA,
            updatedAt = firstPageSyncTime - 10 * 60_000L,
            routineSessionId = null,
        )
        // Exactly three pages for sync 1: A page 1 (hasMore), A page 2, B page 1.
        api.pullResponses += PullScript(
            syncTime = firstPageSyncTime,
            nextCursor = "page-2",
            hasMore = true,
            sessions = listOf(api.portalSessions.single { it.id == pagedOne }),
        )
        api.pullResponses += PullScript(syncTime = firstPageSyncTime, hasMore = false)
        api.pullResponses += PullScript(syncTime = firstPageSyncTime)
        assertTrue(manager.sync().isSuccess)

        // Between the two syncs the portal updated s1 at syncTime − 1 min.
        val updatedInsideWindow = firstPageSyncTime - 60_000L
        api.portalSessions[api.portalSessions.indexOfFirst { it.id == pagedOne }] =
            PortalSession(
                id = pagedOne,
                localProfileId = profileA,
                updatedAt = updatedInsideWindow,
                routineSessionId = null,
                notes = "bumped between pages",
            )
        // Sync 2 uses the default query path, so the fake filters on lastSync — which is
        // exactly what the overlap window is for.
        api.defaultSyncTime = firstPageSyncTime + 30_000L
        api.pullResponses.clear()
        api.pullCallLastSyncs.clear()
        api.sessionsServed.clear()
        assertTrue(manager.sync().isSuccess)

        val expected = firstPageSyncTime - SyncManager.PULL_CURSOR_OVERLAP_MS
        assertTrue(
            api.pullCallLastSyncs.isNotEmpty() && api.pullCallLastSyncs.all { it == expected },
            "the next pull must sit in the overlap window (expected $expected, was ${api.pullCallLastSyncs})",
        )
        assertTrue(
            api.sessionsServed.any { list -> pagedOne in list },
            "a session updated inside the overlap window must be re-served by the next pull " +
                "(served=${api.sessionsServed})",
        )
    }

    // ===== 7. No empty known-id list; a non-default profile keeps its own scope =====

    @Test
    fun everyPullRequestCarriesNonEmptyKnownIdListsAndBDoesNotReceiveAsScopedSession() = runTest {
        // B has routines but no sessions. A session scoped to A sits on the portal.
        insertRoutine("b-routine", profileB)
        api.portalSessions += PortalSession(
            id = "a-scoped",
            localProfileId = profileA,
            updatedAt = currentTimeMillis() + 60_000L,
            routineSessionId = null,
        )
        // Real cursors so the delta query path (with the legacy OR-IS-NULL filter) runs.
        tokenStorage.setPullCursor(userId, profileB, baseTime)
        tokenStorage.setPullCursor(userId, profileA, baseTime)

        assertTrue(manager.sync().isSuccess)

        assertTrue(api.pullKnownEntityIdsHistory.isNotEmpty())
        api.pullKnownEntityIdsHistory.forEach { known ->
            assertTrue(
                known.sessionIds.isNotEmpty(),
                "sessionIds must never be empty (nil-UUID sentinel), was ${known.sessionIds}",
            )
            assertTrue(
                known.routineIds.isNotEmpty(),
                "routineIds must never be empty (nil-UUID sentinel), was ${known.routineIds}",
            )
            assertTrue(
                known.cycleIds.isNotEmpty(),
                "cycleIds must never be empty (nil-UUID sentinel), was ${known.cycleIds}",
            )
            assertTrue(
                known.personalRecordIds.isNotEmpty(),
                "personalRecordIds must never be empty (nil-UUID sentinel), was ${known.personalRecordIds}",
            )
        }
        val bMerged = database.phoenixDatabaseQueries
            .selectKnownPortalSessionIdsByProfile(profileB)
            .executeAsList()
        assertFalse(
            "a-scoped" in bMerged,
            "profile B must not receive a session scoped to profile A (saw $bMerged)",
        )
    }

    // ===== 8. A second sync with a real cursor gets zero already-known routine workouts =====

    @Test
    fun aSecondConsecutiveSyncWithARealCursorGetsZeroAlreadyKnownRoutineWorkouts() = runTest {
        // A stable server timestamp so the stored cursor (syncTime − 5 min) lands past it.
        val serverSyncTime = 1_740_000_000_000L
        api.defaultSyncTime = serverSyncTime
        api.portalSessions += PortalSession(
            id = routineWorkoutOne,
            localProfileId = profileA,
            updatedAt = serverSyncTime - 400_000L,
            routineSessionId = routineWorkoutOne,
            name = "Push Day",
        )
        assertTrue(manager.sync().isSuccess)
        assertTrue(
            api.sessionsServed.any { list -> routineWorkoutOne in list },
            "the first pull must serve the unknown routine workout",
        )

        api.sessionsServed.clear()
        assertTrue(manager.sync().isSuccess)

        assertTrue(
            api.sessionsServed.none { list -> routineWorkoutOne in list },
            "the second pull must not re-serve an already-known routine workout " +
                "(served=${api.sessionsServed})",
        )
    }

    // ===== 9. User-scoped badges/RPG follow the active profile only =====

    @Test
    fun twoProfilesWithDifferentBadgesAndRpgKeepBUnchangedWhenAPullsUserScopedData() = runTest {
        val q = database.phoenixDatabaseQueries
        // SqlDelightGamificationRepository keys the row on profileId.hashCode().
        q.upsertRpgAttributes(
            id = profileB.hashCode().toLong(),
            strength = 11L,
            power = 11L,
            stamina = 11L,
            consistency = 11L,
            mastery = 11L,
            characterClass = "TITAN",
            lastComputed = baseTime,
            profileId = profileB,
        )
        q.insertEarnedBadge(badgeId = "b-badge", earnedAt = baseTime, profileId = profileB)

        // Active profile A pulls first, so the first script carries the user-scoped data.
        api.pullResponses += PullScript(
            syncTime = 1_740_000_000_000L,
            rpg = PullRpgAttributesDto(
                strength = 99,
                power = 99,
                stamina = 99,
                consistency = 99,
                mastery = 99,
                characterClass = "TITAN",
                level = 9,
                experiencePoints = 900,
            ),
            badges = listOf(
                PullBadgeDto(
                    badgeId = "a-badge",
                    badgeName = "A",
                    earnedAt = "2026-03-02T12:00:00Z",
                ),
            ),
        )
        api.pullResponses += PullScript(syncTime = 1_740_000_000_000L)

        assertTrue(manager.sync().isSuccess)

        val bRpg = q.selectRpgAttributes(profileB).executeAsOne()
        assertEquals(11L, bRpg.strength, "profile B's RPG must be untouched when A pulls user-scoped data")
        assertEquals(11L, bRpg.power)
        assertEquals("TITAN", bRpg.characterClass)
        // getAllBadgeIds returns the integer row id as text, so assert by badgeId.
        assertTrue(
            q.selectEarnedBadgeById("b-badge", profileB).executeAsOneOrNull() != null,
            "profile B must keep its own badge",
        )
        assertTrue(
            q.selectEarnedBadgeById("a-badge", profileB).executeAsOneOrNull() == null,
            "profile B must not receive the active profile's badge",
        )

        // The active profile does receive them (so the test is not vacuous).
        val aRpg = q.selectRpgAttributes(profileA).executeAsOne()
        assertEquals(99L, aRpg.strength, "the active profile must receive the pulled RPG")
        assertTrue(
            q.selectEarnedBadgeById("a-badge", profileA).executeAsOneOrNull() != null,
            "the active profile must receive the pulled badge",
        )
    }

    // ===== Helpers =====

    private fun insertRoutine(id: String, profileId: String) {
        database.phoenixDatabaseQueries.insertRoutine(
            id = id,
            name = "Split",
            description = "",
            createdAt = baseTime,
            lastUsed = null,
            useCount = 0L,
            profile_id = profileId,
            groupId = null,
            deletedAt = null,
        )
    }

    private fun insertSession(
        id: String,
        groupId: String?,
        timestamp: Long,
        profileId: String,
        stampedAt: Long? = null,
    ) {
        val q = database.phoenixDatabaseQueries
        q.insertSession(
            id = id,
            timestamp = timestamp,
            mode = "OldSchool",
            targetReps = 8L,
            weightPerCableKg = 40.0,
            progressionKg = 0.0,
            duration = 45_000L,
            totalReps = 8L,
            warmupReps = 0L,
            workingReps = 8L,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 0L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            routineSessionId = groupId,
            routineName = groupId?.let { "Push Day" },
            routineId = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = 2L,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
            display_multiplier = 2L,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
        stampedAt?.let {
            q.updateSessionTimestamp(it, id)
            q.markSessionSynced(id)
        }
    }
}

// ===== Recording fake portal =====

/** A session as the fake portal stores it. [localProfileId] null models a legacy NULL-scoped row. */
data class PortalSession(
    val id: String,
    val localProfileId: String?,
    val updatedAt: Long,
    val routineSessionId: String?,
    val name: String? = null,
    val notes: String? = null,
)

/** One canned pull page. The queue is consumed first-in, first-out per pull call. */
data class PullScript(
    val syncTime: Long,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val sessions: List<PortalSession> = emptyList(),
    val rpg: PullRpgAttributesDto? = null,
    val badges: List<PullBadgeDto> = emptyList(),
)

/**
 * Recording fake portal. Push models `replace_session_children` (an accepted session
 * replaces its exercise list wholesale). Pull implements both of the real Edge Function's
 * query paths:
 *  - parity: every session not listed in `knownEntityIds.sessionIds`;
 *  - delta (a real cursor): sessions with `updatedAt > lastSync`, matched by the legacy
 *    `local_profile_id.eq.<P> OR local_profile_id IS NULL` filter.
 * Scripted pages in [pullResponses] take precedence, so pagination and cross-profile
 * isolation tests can pin exact payloads. [defaultSyncTime] is the unscripted page's
 * `syncTime`, which drives the stored pull cursor.
 */
class RecordingPortalApi : FakePortalApiClient() {

    val portalSessions: MutableList<PortalSession> = mutableListOf()
    val pullResponses: MutableList<PullScript> = mutableListOf()
    /** Session ids served per pull call, for "already-known" assertions. */
    val sessionsServed: MutableList<List<String>> = mutableListOf()
    var defaultSyncTime: Long = currentTimeMillis()

    override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
        super.pushPortalPayload(payload)
        for (dto in payload.sessions) {
            val incoming = dto.updatedAt?.let { parseIso(it) } ?: currentTimeMillis()
            val existing = portalSessions.find { it.id == dto.id }
            if (existing != null && existing.updatedAt > incoming) {
                continue
            }
            val stored = PortalSession(
                id = dto.id,
                localProfileId = payload.profileId,
                updatedAt = if (existing == null) incoming else currentTimeMillis(),
                routineSessionId = dto.routineSessionId,
                name = dto.name,
                notes = dto.notes,
            )
            portalSessions.removeAll { it.id == dto.id }
            portalSessions += stored
        }
        return Result.success(
            PortalSyncPushResponse(
                syncTime = isoOf(currentTimeMillis()),
                // Model the portal's accept list. Without this the device never acks
                // generations and every session is re-pushed forever.
                acknowledgedWorkoutSessionIds = payload.sessions.map { it.id },
            ),
        )
    }

    override suspend fun pullPortalPayload(
        knownEntityIds: KnownEntityIds,
        deviceId: String,
        profileId: String?,
        cursor: String?,
        pageSize: Int?,
        lastSync: Long,
    ): Result<PortalSyncPullResponse> {
        super.pullPortalPayload(knownEntityIds, deviceId, profileId, cursor, pageSize, lastSync)
        val script = pullResponses.removeFirstOrNull()
        if (script != null) {
            sessionsServed += script.sessions.map { it.id }
            return Result.success(
                PortalSyncPullResponse(
                    syncTime = script.syncTime,
                    nextCursor = script.nextCursor,
                    hasMore = script.hasMore,
                    sessions = script.sessions.map { it.toDto() },
                    rpgAttributes = script.rpg,
                    badges = script.badges,
                ),
            )
        }
        // Default query path, used by the unscripted scenarios.
        // `get_sessions_excluding_ids(p_known_ids, p_last_sync_at)` returns every session
        // the device does not know, plus known ids whose `updated_at` moved past the
        // cursor — which is exactly what the 5-minute pull overlap exists to catch.
        val known = knownEntityIds.sessionIds.toSet()
        val served = portalSessions.filter { session ->
            val inKnown = session.id in known
            val updatedSinceCursor = lastSync > 0L && session.updatedAt > lastSync
            if (inKnown && !updatedSinceCursor) return@filter false
            // Legacy filter on the delta path: `local_profile_id.eq.<P> OR local_profile_id IS NULL`.
            if (lastSync > 0L && session.localProfileId != null && session.localProfileId != profileId) {
                return@filter false
            }
            true
        }
        sessionsServed += served.map { it.id }
        return Result.success(
            PortalSyncPullResponse(
                syncTime = defaultSyncTime,
                sessions = served.map { it.toDto() },
            ),
        )
    }

    /**
     * [com.devil.phoenixproject.data.sync.PortalPullAdapter] drops a DTO with no
     * `startedAt` or no exercises, so every stored session becomes a one-exercise /
     * one-set workout. The local primary key is the exercise id; the portal parent id
     * is `routineSessionId ?: id`.
     */
    private fun PortalSession.toDto() = PullWorkoutSessionDto(
        id = id,
        name = name,
        startedAt = isoOf(updatedAt),
        routineSessionId = routineSessionId,
        notes = notes,
        updatedAt = isoOf(updatedAt),
        exercises = listOf(
            PullExerciseDto(
                id = "$id-ex1",
                sessionId = id,
                name = "Bench Press",
                sets = listOf(
                    PullSetDto(
                        id = "$id-set1",
                        exerciseId = "$id-ex1",
                        setNumber = 1,
                        actualReps = 8,
                        weightKg = 40f,
                    ),
                ),
            ),
        ),
    )

    private fun isoOf(epochMs: Long): String =
        kotlin.time.Instant.fromEpochMilliseconds(epochMs).toString()

    private fun parseIso(value: String): Long =
        kotlin.time.Instant.parse(value).toEpochMilliseconds()
}
