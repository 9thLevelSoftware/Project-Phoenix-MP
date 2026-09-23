package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.preferences.SettingsPendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * PR 20 / KD-12 acceptance: "Delete permanently" against a real foreign-keys-ON database,
 * the real profile and sync repositories, and a portal fake that acknowledges durable
 * deletions and then re-scopes whatever it still holds for the deleted profile to Default
 * (the portal's SET NULL once the profile leaves `allProfiles`).
 */
class ProfileDeletionPropagationTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var settings: MapSettings
    private lateinit var tokenStorage: PortalTokenStorage
    private lateinit var profiles: SqlDelightUserProfileRepository
    private lateinit var syncRepository: SqlDelightSyncRepository
    private lateinit var api: DeletionAwarePortalApi
    private lateinit var manager: SyncManager

    private val userId = "user-123"

    // Canonical UUIDs: the push strips non-UUID routine/cycle/session ids.
    private val sessionP = "44444444-4444-4444-8444-444444444444"
    private val routineP = "55555555-5555-4555-8555-555555555555"
    private val cycleP = "66666666-6666-4666-8666-666666666666"
    private val prUuidP = "77777777-7777-4777-8777-777777777777"
    private val prUuidDefault = "88888888-8888-4888-8888-888888888888"

    private val baseTime = currentTimeMillis() - 60_000L

    @Before
    fun setup() {
        database = createTestDatabase()
        settings = MapSettings()
        tokenStorage = PortalTokenStorage(MapSettings())
        signIn()
        tokenStorage.markRoutineCyclePrRepairPushDone(userId)
        profiles = SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
            profileLocalSafetyStore = SettingsProfileLocalSafetyStore(settings),
            gamificationRepository = SqlDelightGamificationRepository(database),
            pendingDeletionStore = SettingsPendingProfileDeletionStore(settings),
            signedInPortalUserId = { tokenStorage.currentUser.value?.id },
            lastSyncedPortalUserId = { tokenStorage.getLastSyncedPortalUserId() },
        )
        // A fresh install seeds Default's preferences before the context can become Ready.
        database.phoenixDatabaseQueries.insertDefaultProfilePreferences("default", 1L)
        syncRepository = SqlDelightSyncRepository(database, profiles)
        api = DeletionAwarePortalApi()
        manager = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = syncRepository,
            gamificationRepository = SqlDelightGamificationRepository(database),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = profiles,
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
            completedSetRepository = FakeCompletedSetRepository(),
            workoutDeletionRepository = com.devil.phoenixproject.data.repository.SqlDelightWorkoutDeletionRepository(database),
            trainingCycleRepository = com.devil.phoenixproject.data.repository.SqlDelightTrainingCycleRepository(database),
        )
    }

    @Test
    fun permanentDeleteRemovesEveryLiveRowAndHidesTheProfileUntilItsPushLands() = runTest {
        val p = createProfileWithData()

        assertTrue(profiles.deleteActiveProfilePermanently(p))

        val q = database.phoenixDatabaseQueries
        assertEquals("default", profiles.activeProfile.value?.id)
        assertFalse(profiles.allProfiles.value.any { it.id == p }, "a permanently deleted profile is hidden")
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id })
        assertNotNull(q.getProfileById(p).executeAsOneOrNull(), "the row stays until its push lands")
        assertNull(q.selectSessionById(sessionP).executeAsOneOrNull(), "workouts are removed")
        assertNotNull(q.selectRoutineById(routineP).executeAsOne().deletedAt, "routines become tombstones")
        assertNotNull(q.selectTrainingCycleById(cycleP).executeAsOne().deletedAt, "cycles become tombstones")
        assertTrue(q.selectAllRecords(p).executeAsList().isEmpty(), "no live PR remains")
        val deletion = q.selectAllWorkoutDeletions().executeAsList().single()
        assertEquals(sessionP, deletion.portal_session_id)
        assertEquals(userId, deletion.owner_user_id, "the tombstone must be pushable by the signed-in account")
    }

    @Test
    fun theNextPushCarriesEveryTombstoneListsTheProfileAndThenRemovesIt() = runTest {
        val p = createProfileWithData()
        assertTrue(profiles.deleteActiveProfilePermanently(p))

        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        val fromP = api.pushPayloads.filter { it.profileId == p }
        assertTrue(fromP.isNotEmpty(), "the pending-deletion profile must push its own tombstones")
        assertTrue(fromP.all { payload -> payload.allProfiles.orEmpty().any { it.id == p } }, "P stays in allProfiles for its own push")
        assertTrue(fromP.flatMap { it.workoutDeletions }.any { it.portalSessionId == sessionP }, "workout tombstone")
        assertTrue(fromP.flatMap { it.deletedRoutineIds }.contains(routineP), "routine tombstone")
        assertTrue(fromP.flatMap { it.deletedCycles }.any { it.id == cycleP }, "cycle tombstone")
        assertTrue(
            fromP.flatMap { it.personalRecords }.any { it.id == prUuidP && it.deletedAt != null },
            "PR tombstone (saw ${fromP.flatMap { it.personalRecords }.map { it.id to it.deletedAt }})",
        )
        // Pushed first, so every later push omits it and the portal re-scopes its rows.
        assertEquals(p, api.pushPayloads.first().profileId)
        val afterP = api.pushPayloads.dropWhile { it.profileId == p }
        assertTrue(afterP.isNotEmpty())
        assertTrue(afterP.none { payload -> payload.allProfiles.orEmpty().any { it.id == p } })
        assertTrue(api.pullProfileIds.none { it == p }, "a profile being removed is never pulled")

        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "row removed after the push")
        assertTrue(profiles.pendingDeletionProfiles.value.isEmpty())
    }

    @Test
    fun aFailedPushKeepsTheProfileHiddenAndTheNextSyncFinishesIt() = runTest {
        val p = createProfileWithData()
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        api.failPushesFor = p

        manager.sync()

        assertNotNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "kept after a failed push")
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id })
        assertFalse(profiles.allProfiles.value.any { it.id == p }, "still hidden")

        api.failPushesFor = null
        assertTrue(manager.sync().isSuccess)
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
    }

    @Test
    fun aFailedPendingPushStillListsTheProfileInTheSameLoopsLaterPushes() = runTest {
        val p = createProfileWithData()
        assertTrue(manager.sync().isSuccess, "seed sync failed: ${manager.syncState.value}")
        assertTrue(p in api.liveRowsByProfile, "P's rows must be on the portal before the delete")
        assertTrue(tokenStorage.getPullCursor(userId, p) > 0L)
        assertNotNull(tokenStorage.getSessionSentHash(userId, p, sessionP))
        Thread.sleep(5) // tombstone stamps must be strictly newer than the seed sync's watermark
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        // Default has its own change to upload in the same loop (an idle profile sends nothing).
        database.phoenixDatabaseQueries.insertRoutine(
            id = "99999999-9999-4999-8999-999999999999",
            name = "Default split",
            description = "",
            createdAt = baseTime,
            lastUsed = null,
            useCount = 0L,
            profile_id = "default",
            groupId = null,
            deletedAt = null,
        )

        api.pushPayloads.clear()
        // P goes first; its first request fails (transient 500) and the loop moves on to Default.
        api.failNextPushes = 1
        manager.sync()

        assertEquals(p, api.pushPayloads.first().profileId)
        val later = api.pushPayloads.drop(1)
        assertTrue(
            later.any { it.profileId == "default" },
            "Default must still push in the same loop (pushed=${api.pushPayloads.map { it.profileId }})",
        )
        assertTrue(
            later.all { payload -> payload.allProfiles.orEmpty().any { it.id == p } },
            "R-11: while P's own push has not landed, every push must keep P registered",
        )
        assertTrue(api.reScopedToDefault.isEmpty(), "P's live rows were re-scoped to Default: ${api.reScopedToDefault}")

        assertTrue(manager.sync().isSuccess)
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
        assertTrue(api.reScopedToDefault.isEmpty(), "nothing may reach Default once P is removed: ${api.reScopedToDefault}")
        // T-4: the removed profile's sync state goes with it.
        assertEquals(0L, tokenStorage.getPullCursor(userId, p))
        assertNull(tokenStorage.getSessionSentHash(userId, p, sessionP))
    }

    @Test
    fun aProfileDeletedWhileSignedOutIsTombstonedOnTheNextSignInAsThatAccount() = runTest {
        val p = createProfileWithData()
        assertTrue(manager.sync().isSuccess, "seed sync failed: ${manager.syncState.value}")
        tokenStorage.clearAuth()
        Thread.sleep(5)

        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id }, "must wait for the next sign-in")

        signIn()
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        val fromP = api.pushPayloads.filter { it.profileId == p }
        assertTrue(fromP.flatMap { it.workoutDeletions }.any { it.portalSessionId == sessionP }, "workout tombstone")
        assertTrue(fromP.flatMap { it.deletedRoutineIds }.contains(routineP), "routine tombstone")
        assertTrue(api.reScopedToDefault.isEmpty(), "P's rows were re-scoped to Default: ${api.reScopedToDefault}")
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
    }

    @Test
    fun aRejectedCycleDeletionKeepsTheProfilePendingUntilItIsAccepted() = runTest {
        val p = createProfileWithData()
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        Thread.sleep(5)
        // A web edit newer than the delete: the portal's clocked gate rejects the deletion.
        api.cycleServerClockMs = currentTimeMillis()

        manager.sync()
        assertNotNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "kept while a tombstone is outstanding")
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id })

        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        assertTrue(api.pushPayloads.flatMap { it.deletedCycles }.any { it.id == cycleP }, "re-stamped deletion re-sent")
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "finalized once accepted")
    }

    @Test
    fun aPendingProfilesOwnerStillCountsForAccountSwitchDetection() = runTest {
        profiles.reconcileActiveProfileContext()
        val p = profiles.createAndActivateProfile("Guest", 1).id
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", baseTime, p)
        profiles.refreshProfiles()
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertNull(tokenStorage.getLastSyncedPortalUserId(), "only the hidden profile records the old owner")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertTrue(manager.syncState.value is SyncState.AccountMismatch, "was ${manager.syncState.value}")
        assertTrue(api.pushPayloads.isEmpty(), "nothing may be pushed before the user chooses")
    }

    @Test
    fun retryPullNeverPullsIntoAPendingDeletionProfile() = runTest {
        val p = createProfileWithData()
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        api.failPushesFor = p
        manager.sync()
        api.pullProfileIds.clear()

        manager.retryPull()

        assertTrue(api.pullProfileIds.contains("default"), "retryPull must still pull the live profiles")
        assertFalse(api.pullProfileIds.contains(p), "retryPull pulled into a profile being removed")
    }

    @Test
    fun withNoPortalOwnerTheProfileRowIsRemovedImmediately() = runTest {
        tokenStorage.clearAuth()
        val p = createProfileWithData()

        assertTrue(profiles.deleteActiveProfilePermanently(p))

        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
        assertTrue(profiles.pendingDeletionProfiles.value.isEmpty())
        assertEquals("default", profiles.activeProfile.value?.id)
    }

    @Test
    fun rowsThePortalReScopesToDefaultAreNotResurrectedByThePull() = runTest {
        val p = createProfileWithData()
        val q = database.phoenixDatabaseQueries
        val defaultPrsBefore = q.selectAllRecords("default").executeAsList().map { it.exerciseId to it.weight }
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertTrue(manager.sync().isSuccess)

        // The portal SET NULL: P's rows come back unscoped, i.e. to a Default pull, still live.
        api.rescopeServedToDefault = true
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")
        assertTrue(api.pullProfileIds.contains("default"))

        assertNull(q.selectSessionById(sessionP).executeAsOneOrNull(), "P's workout must not reappear")
        assertTrue(
            q.selectSessionsByProfileForTest("default").none { it == sessionP },
            "P's workout must not reappear under Default",
        )
        val routine = q.selectRoutineById(routineP).executeAsOne()
        assertNotNull(routine.deletedAt, "P's routine must stay deleted")
        assertEquals(p, routine.profile_id, "P's routine must not move to Default")
        assertEquals(
            defaultPrsBefore,
            q.selectAllRecords("default").executeAsList().map { it.exerciseId to it.weight },
            "Default's % of PR baseline must be unchanged",
        )
    }

    @Test
    fun aLegacyIdKeyedPrTombstoneBlocksItsReScopedCopy() = runTest {
        val q = database.phoenixDatabaseQueries
        insertRecord(profileId = "gone", exerciseId = "deadlift", uuid = null, weight = 90.0)
        val legacy = q.selectAllRecords("gone").executeAsList().single()
        q.softDeletePRById(deletedAt = baseTime, updatedAt = baseTime, id = legacy.id, profileId = "gone")

        syncRepository.mergePersonalRecords(
            listOf(
                PersonalRecordSyncDto(
                    clientId = legacy.id.toString(),
                    serverId = legacy.id.toString(),
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 90f,
                    reps = 5,
                    oneRepMax = 0f,
                    achievedAt = baseTime,
                    workoutMode = "OldSchool",
                    prType = "MAX_WEIGHT",
                    phase = "COMBINED",
                    volume = 450f,
                    deletedAt = null,
                    createdAt = baseTime,
                    updatedAt = baseTime + 1,
                ),
            ),
            profileId = "default",
        )

        assertTrue(q.selectAllRecords("default").executeAsList().none { it.exerciseId == "deadlift" })
    }

    @Test
    fun mergingAProfileThatHoldsAPulledSessionNeverPushesThatSession() = runTest {
        profiles.reconcileActiveProfileContext()
        val p = profiles.createAndActivateProfile("Guest", 1).id
        val q = database.phoenixDatabaseQueries
        // A workout that only ever arrived by pull: portal-origin and generation-clean.
        insertSession(sessionP, p)
        q.markSessionPulled(sessionP)
        q.markSessionSynced(sessionP)

        assertTrue(profiles.deleteActiveProfile(p))
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        assertEquals("default", q.selectSessionById(sessionP).executeAsOne().profile_id)
        assertTrue(
            api.pushPayloads.none { payload -> payload.sessions.any { it.id == sessionP } },
            "a merged pulled session must not be re-pushed: replace_session_children would wipe its sets",
        )
    }

    // ===== Helpers =====

    private fun signIn() {
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
    }

    /** Default holds a bench PR; P (created and active) holds a workout, routine, cycle and squat PR. */
    private suspend fun createProfileWithData(): String {
        profiles.reconcileActiveProfileContext()
        insertRecord(profileId = "default", exerciseId = "bench", uuid = prUuidDefault, weight = 50.0)
        val p = profiles.createAndActivateProfile("Guest", 1).id
        val q = database.phoenixDatabaseQueries
        insertSession(sessionP, p)
        q.insertRoutine(
            id = routineP,
            name = "Split",
            description = "",
            createdAt = baseTime,
            lastUsed = null,
            useCount = 0L,
            profile_id = p,
            groupId = null,
            deletedAt = null,
        )
        q.insertTrainingCycle(cycleP, "Block", null, baseTime, 0L, p, null, 1L, baseTime)
        insertRecord(profileId = p, exerciseId = "squat", uuid = prUuidP, weight = 80.0)
        return p
    }

    private fun insertRecord(profileId: String, exerciseId: String, uuid: String?, weight: Double) {
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = exerciseId,
            exerciseName = exerciseId,
            weight = weight,
            reps = 5L,
            oneRepMax = weight,
            achievedAt = baseTime,
            workoutMode = "OldSchool",
            prType = "MAX_WEIGHT",
            volume = weight * 5,
            phase = "COMBINED",
            profile_id = profileId,
            cable_count = 2L,
            uuid = uuid,
        )
    }

    private fun com.devil.phoenixproject.database.PhoenixDatabaseQueries.selectSessionsByProfileForTest(
        profileId: String,
    ): List<String> = selectDistinctLiveWorkoutPortalParentsForProfile(profileId).executeAsList()

    private fun insertSession(id: String, profileId: String) {
        database.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = baseTime,
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
            exerciseId = "squat",
            exerciseName = "Squat",
            routineSessionId = null,
            routineName = null,
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
    }

    /**
     * Acknowledges every durable operation it receives (as the portal RPCs do after commit)
     * and models the portal's profile registration: rows are held per local profile, tombstones
     * remove them, and a push whose `allProfiles` omits a profile deletes its registration —
     * `ON DELETE SET NULL` moves its still-live rows to Default ([reScopedToDefault]).
     * Once [rescopeServedToDefault] is set, a Default pull is served the deleted profile's
     * workout, routine and PR, still live, as other devices would see them.
     */
    private inner class DeletionAwarePortalApi : FakePortalApiClient() {
        var failPushesFor: String? = null
        /** Fails this many upcoming push requests, whatever they carry (a transient 500). */
        var failNextPushes = 0
        var rescopeServedToDefault = false
        /** Stored LWW clock of every cycle; a clocked deletion older than it is rejected. */
        var cycleServerClockMs: Long? = null
        val pullProfileIds = mutableListOf<String?>()
        val liveRowsByProfile = mutableMapOf<String, MutableSet<String>>()
        val reScopedToDefault = mutableListOf<String>()

        override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
            super.pushPortalPayload(payload)
            if (payload.profileId != null && payload.profileId == failPushesFor) {
                return Result.failure(PortalApiException("portal unavailable", statusCode = 500))
            }
            if (failNextPushes > 0) {
                failNextPushes--
                return Result.failure(PortalApiException("portal unavailable", statusCode = 500))
            }
            val acceptedCycleDeletions = payload.deletedCycles.filter { deletion ->
                val clock = cycleServerClockMs ?: return@filter true
                kotlin.time.Instant.parse(deletion.updatedAt).toEpochMilliseconds() >= clock
            }
            // Durable deletions first, then stale-registration cleanup, then entity writes.
            val tombstoned = payload.workoutDeletions.map { it.portalSessionId } +
                payload.deletedRoutineIds +
                acceptedCycleDeletions.map { it.id } +
                payload.personalRecords.filter { it.deletedAt != null }.mapNotNull { it.id }
            liveRowsByProfile.values.forEach { it.removeAll(tombstoned.toSet()) }
            val registered = payload.allProfiles.orEmpty().map { it.id }.toSet()
            if (registered.isNotEmpty()) {
                liveRowsByProfile.keys.filter { it !in registered && it != "default" }.forEach { gone ->
                    reScopedToDefault += liveRowsByProfile.remove(gone).orEmpty()
                }
            }
            val owner = payload.profileId ?: "default"
            val written = payload.sessions.map { it.id } + payload.routines.map { it.id } +
                payload.cycles.map { it.id } +
                payload.personalRecords.filter { it.deletedAt == null }.mapNotNull { it.id }
            if (written.isNotEmpty()) liveRowsByProfile.getOrPut(owner) { mutableSetOf() } += written
            return Result.success(
                PortalSyncPushResponse(
                    syncTime = kotlin.time.Instant.fromEpochMilliseconds(currentTimeMillis()).toString(),
                    acknowledgedWorkoutDeletionIds = payload.workoutDeletions.map { it.mutationId },
                    acknowledgedOwnershipTransferIds = payload.ownershipTransfers.map { it.mutationId },
                    acknowledgedDeletedCycleIds = acceptedCycleDeletions.map { it.id },
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
            pullProfileIds += profileId
            val rescoped = rescopeServedToDefault && profileId == "default"
            val iso = kotlin.time.Instant.fromEpochMilliseconds(currentTimeMillis()).toString()
            return Result.success(
                PortalSyncPullResponse(
                    syncTime = currentTimeMillis(),
                    sessions = if (!rescoped) {
                        emptyList()
                    } else {
                        listOf(
                            PullWorkoutSessionDto(
                                id = sessionP,
                                startedAt = iso,
                                updatedAt = iso,
                                exercises = listOf(
                                    PullExerciseDto(
                                        id = "$sessionP-ex1",
                                        sessionId = sessionP,
                                        name = "Squat",
                                        sets = listOf(
                                            PullSetDto(
                                                id = "$sessionP-set1",
                                                exerciseId = "$sessionP-ex1",
                                                setNumber = 1,
                                                actualReps = 8,
                                                weightKg = 40f,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    },
                    routines = if (!rescoped) {
                        emptyList()
                    } else {
                        listOf(PullRoutineDto(id = routineP, name = "Split", updatedAt = currentTimeMillis()))
                    },
                    personalRecords = if (!rescoped) {
                        emptyList()
                    } else {
                        listOf(
                            PullPersonalRecordDto(
                                id = prUuidP,
                                exerciseId = "squat",
                                exerciseName = "squat",
                                recordType = "MAX_WEIGHT",
                                value = 80.0,
                                reps = 5,
                                workoutPhase = "COMBINED",
                                achievedAt = iso,
                                updatedAt = iso,
                            ),
                        )
                    },
                ),
            )
        }
    }
}
