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
import com.devil.phoenixproject.testutil.createTestDriver
import com.devil.phoenixproject.testutil.seedExercise
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
private val PROFILE_OWNER_COLUMNS = setOf("profile_id", "profileId", "original_profile_id")

/** Columns that key a row to a workout: a mobile session id or the portal workout id. */
private val SESSION_KEY_COLUMNS = setOf("sessionId", "session_id", "routineSessionId", "assessmentSessionId")

/**
 * Tables that legitimately keep rows under a permanently deleted profile: the pending
 * tombstones (WorkoutDeletion, CycleSyncState), the local-cleanup queue, and the ownership /
 * recovery bookkeeping, none of which is profile content.
 */
private val KEPT_AFTER_PERMANENT_DELETE = setOf(
    "WorkoutDeletion",
    "CycleSyncState",
    "PendingProfileLocalCleanup",
    "OwnershipTransferOutbox",
    "LocalOwnershipClaim",
    "PendingProfileRecovery",
    "PendingProfileContextRecovery",
)

class ProfileDeletionPropagationTest {

    private lateinit var driver: app.cash.sqldelight.db.SqlDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var settings: MapSettings
    private lateinit var tokenStorage: PortalTokenStorage
    private lateinit var profiles: SqlDelightUserProfileRepository
    private lateinit var syncRepository: SqlDelightSyncRepository
    private lateinit var api: DeletionAwarePortalApi
    private lateinit var externalActivities: FakeExternalActivityRepository
    private lateinit var preferenceSync: FakeProfilePreferenceSyncRepository
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
        driver = createTestDriver()
        database = PhoenixDatabase(driver)
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
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository().also { preferenceSync = it },
            externalActivityRepository = FakeExternalActivityRepository().also { externalActivities = it },
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
    fun aPendingProfileOfAnotherAccountWaitsForThatAccountAndDoesNotLoopTheSwitchDialog() = runTest {
        val p = createProfileWithData()
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", baseTime, p)
        profiles.refreshProfiles()
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertTrue(manager.sync().isFailure)
        assertTrue(manager.syncState.value is SyncState.AccountMismatch)

        assertTrue(manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess)
        assertTrue(manager.sync().isSuccess, "the choice must end the pause: ${manager.syncState.value}")
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id }, "waits for its own account")
        assertEquals("owner-a", database.phoenixDatabaseQueries.getProfileById(p).executeAsOne().supabase_user_id)
        assertTrue(api.pushPayloads.none { it.profileId == p }, "another account must not push or finalize it")

        // Owner A signs in again: A's own sync pushes the tombstones and finalizes.
        signIn("owner-a")
        manager.sync()
        assertTrue(manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess)
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")
        assertTrue(
            api.pushPayloads.filter { it.profileId == p }.flatMap { it.workoutDeletions }.any { it.portalSessionId == sessionP },
            "A's workout tombstone goes out under A",
        )
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
    }

    @Test
    fun aSignedOutDeleteIsNotFinalizedByADifferentAccountThatSignsInFirst() = runTest {
        val p = createProfileWithData()
        assertTrue(manager.sync().isSuccess, "seed sync failed: ${manager.syncState.value}")
        tokenStorage.clearAuth()
        Thread.sleep(5)
        assertTrue(profiles.deleteActiveProfilePermanently(p))

        signIn("owner-b")
        manager.sync()
        assertTrue(manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess)
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        assertTrue(api.pushPayloads.none { it.profileId == p }, "owner-b must not push or finalize user-123's deletion")
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id })
    }

    @Test
    fun anIdLessLegacyPrTombstoneThePortalDroppedKeepsTheProfilePending() = runTest {
        val p = createProfileWithData()
        insertRecord(profileId = p, exerciseId = "deadlift", uuid = null, weight = 120.0)
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        api.dropIdLessPrTombstones = true

        manager.sync()
        assertTrue(
            api.pushPayloads.filter { it.profileId == p }.flatMap { it.personalRecords }.any { it.id == null && it.deletedAt != null },
            "the legacy tombstone goes out without an id",
        )
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id }, "kept while it was dropped")

        api.dropIdLessPrTombstones = false
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "finalized once written")
    }

    @Test
    fun aCycleDeletedWhileTheProfileWasUnboundIsStillPushedBeforeFinalizing() = runTest {
        val p = createProfileWithData()
        assertTrue(manager.sync().isSuccess, "seed sync failed: ${manager.syncState.value}")
        tokenStorage.clearAuth()
        // Deleted on its own while signed out: the unbound profile gives it no account.
        com.devil.phoenixproject.data.repository.SqlDelightTrainingCycleRepository(database).deleteCycle(cycleP)
        Thread.sleep(5)
        assertTrue(profiles.deleteActiveProfilePermanently(p))

        signIn()
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        assertTrue(
            api.pushPayloads.filter { it.profileId == p }.flatMap { it.deletedCycles }.any { it.id == cycleP },
            "the earlier cycle deletion must ride P's final push",
        )
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
    }

    @Test
    fun aPendingProfilesTombstonesAreSentEvenWhenTheClockIsBehindItsWatermark() = runTest {
        val p = createProfileWithData()
        // A device clock that moved back: the stored watermark is ahead of every new stamp.
        tokenStorage.setPushWatermark(userId, p, currentTimeMillis() + 3_600_000L)
        assertTrue(profiles.deleteActiveProfilePermanently(p))

        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        val fromP = api.pushPayloads.filter { it.profileId == p }
        assertTrue(fromP.flatMap { it.deletedRoutineIds }.contains(routineP), "routine tombstone")
        assertTrue(fromP.flatMap { it.personalRecords }.any { it.id == prUuidP && it.deletedAt != null }, "PR tombstone")
    }

    @Test
    fun aPermanentDeleteRemovesAssessmentsAndItsFinalPushUploadsNoLiveProfileData() = runTest {
        val p = createProfileWithData()
        val q = database.phoenixDatabaseQueries
        database.seedExercise("squat")
        q.insertAssessmentResult("squat", 90.0, "[]", null, null, baseTime, p)
        // Paid, with a not-yet-uploaded external activity: both would normally ride P's push.
        q.updateSubscriptionStatus("active", null, p)
        profiles.refreshProfiles()
        externalActivities.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "hevy-activity-p",
            provider = com.devil.phoenixproject.domain.model.IntegrationProvider.HEVY,
            name = "Push Day",
            startedAt = baseTime,
            profileId = p,
            needsSync = true,
        )

        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertTrue(q.selectAllAssessments(p).executeAsList().isEmpty(), "assessments are deleted locally")
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        val fromP = api.pushPayloads.filter { it.profileId == p }
        assertTrue(fromP.isNotEmpty())
        assertTrue(fromP.all { it.assessments.isEmpty() }, "P's final push must not upload assessments")
        assertTrue(fromP.all { it.externalActivities.isEmpty() }, "P's final push must not upload live activities")
    }

    @Test
    fun aLegacyPrTombstoneIsMatchedByItsPortalServerId() = runTest {
        val q = database.phoenixDatabaseQueries
        insertRecord(profileId = "gone", exerciseId = "deadlift", uuid = null, weight = 90.0)
        val legacy = q.selectAllRecords("gone").executeAsList().single()
        val portalId = "abababab-1234-4abc-8def-1234567890ab"
        q.updatePRServerId(portalId, legacy.id)
        q.softDeletePRById(deletedAt = baseTime, updatedAt = baseTime, id = legacy.id, profileId = "gone")

        syncRepository.mergePersonalRecords(
            listOf(
                PersonalRecordSyncDto(
                    clientId = portalId,
                    serverId = portalId,
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

    /**
     * Closes the class of "a table the delete forgot" (codex #861 round 3): every table with a
     * profile column is seeded for P, then P is permanently deleted and finalized. No live row
     * may remain under P in any table, except the tombstone and bookkeeping tables listed in
     * [KEPT_AFTER_PERMANENT_DELETE], and recovery discovery must not resurface P.
     */
    @Test
    fun noProfileOwnedRowSurvivesAPermanentDelete() = runTest {
        val p = createProfileWithData()
        database.seedExercise("squat")
        val seeded = profileOwnedColumns().keys.filter { table -> seedRow(table, p) }
        assertTrue(
            seeded.containsAll(
                listOf(
                    "AssessmentResult", "EarnedBadge", "StreakHistory", "VelocityOneRepMaxEstimate",
                    "ExerciseMvt", "ProgressionEvent", "RoutineGroup", "ExternalActivity",
                    "IntegrationStatus", "ProfileExerciseBaseline",
                ),
            ),
            "fixture must cover the tables the review named (seeded=$seeded)",
        )

        // Children keyed by the workout rather than the profile (codex #861 round 4): every
        // table with a session / portal-workout column, FK or not, gets a row for P's workout.
        database.phoenixDatabaseQueries.upsertSessionNotes(sessionP, "note from the web", baseTime)
        val sessionKeyed = sessionKeyedColumns().filterKeys { it != "WorkoutSession" }
        val seededBySession = sessionKeyed.filter { (table, column) ->
            table == "SessionNotes" || seedRow(table, p, mapOf(column to sessionP))
        }.keys
        assertTrue(
            seededBySession.containsAll(listOf("SessionNotes", "RepMetric", "CompletedSet")),
            "fixture must cover session-keyed children (seeded=$seededBySession)",
        )

        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())

        val sessionSurvivors = sessionKeyed
            .mapValues { (table, column) -> rowCount(table, column, sessionP) }
            .filterValues { it > 0L }
        assertTrue(sessionSurvivors.isEmpty(), "rows of the deleted workout survived: $sessionSurvivors")

        val survivors = profileOwnedColumns()
            .filterKeys { it !in KEPT_AFTER_PERMANENT_DELETE }
            .mapValues { (table, column) -> liveRowCount(table, column, p) }
            .filterValues { it > 0L }
        assertTrue(survivors.isEmpty(), "live rows of the deleted profile survived: $survivors")

        com.devil.phoenixproject.data.repository.ProfileRecoveryDiscovery(database, driver)
            .discoverProfileData(profiles.allProfiles.value)
        assertTrue(
            database.phoenixDatabaseQueries.selectAllPendingProfileRecoveries().executeAsList()
                .none { it.source_profile_id == p },
            "a permanently deleted profile must not reappear in recovery",
        )
    }

    @Test
    fun aPrTombstoneThePortalKeptAgainstKeepsTheProfilePendingUntilItIsWritten() = runTest {
        val p = createProfileWithData()
        assertTrue(profiles.deleteActiveProfilePermanently(p))
        // A web edit stored slightly in the future: this sync's tombstone loses the LWW gate.
        val webEdit = currentTimeMillis() + 150L
        api.storedPrs[prUuidP] = webEdit to false

        manager.sync()
        assertNotNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "kept while a PR tombstone was dropped")
        assertEquals(listOf(p), profiles.pendingDeletionProfiles.value.map { it.id })

        Thread.sleep(300)
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")
        assertEquals(true, api.storedPrs[prUuidP]?.second, "the re-stamped tombstone won on retry")
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull(), "finalized once written")
    }

    @Test
    fun notesOfWorkoutsDeletedBeforeTheProfileAreRemovedToo() = runTest {
        val p = createProfileWithData()
        val q = database.phoenixDatabaseQueries
        // Deleted earlier: the rows are gone, only the WorkoutDeletion record keeps the id.
        val earlier = "abababab-0000-4000-8000-000000000001"
        q.insertWorkoutDeletion(
            mutationId = "m-earlier",
            ownerUserId = userId,
            profileId = p,
            scope = "WORKOUT",
            portalSessionId = earlier,
            componentSessionId = null,
            deletedAt = baseTime,
            source = "LOCAL",
        )
        q.upsertSessionNotes(earlier, "note of a workout deleted earlier", baseTime)
        // Soft-deleted earlier: the row is still there with deletedAt set.
        val softDeleted = "abababab-0000-4000-8000-000000000002"
        insertSession(softDeleted, p)
        q.softDeleteSession(baseTime, baseTime, softDeleted)
        q.upsertSessionNotes(softDeleted, "note of a soft-deleted workout", baseTime)

        assertTrue(profiles.deleteActiveProfilePermanently(p))

        assertNull(q.getSessionNotes(earlier).executeAsOneOrNull(), "note of an earlier-deleted workout survived")
        assertNull(q.getSessionNotes(softDeleted).executeAsOneOrNull(), "note of a soft-deleted workout survived")
    }

    @Test
    fun aPendingProfilesDirtyPreferencesAreNotUploadedOnItsWayOut() = runTest {
        val p = createProfileWithData()
        preferenceSync.dirtySnapshot = ProfilePreferenceDirtySnapshot(
            valid = listOf(
                ProfilePreferenceSectionSyncDto(
                    key = ProfilePreferenceSectionKey(p, com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName.CORE),
                    documentVersion = 1,
                    baseRevision = 0,
                    clientModifiedAtEpochMs = baseTime,
                    localGeneration = 1,
                    payload = kotlinx.serialization.json.buildJsonObject {
                        put("bodyWeightKg", kotlinx.serialization.json.JsonPrimitive(80.0))
                        put("weightUnit", kotlinx.serialization.json.JsonPrimitive("KG"))
                        put("weightIncrement", kotlinx.serialization.json.JsonPrimitive(0.5))
                    },
                ),
            ),
            unsyncable = emptyList(),
        )
        assertTrue(profiles.deleteActiveProfilePermanently(p))

        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        val uploaded = api.pushPayloads.flatMap { it.profilePreferenceSections.orEmpty() }.map { it.localProfileId }
        assertFalse(p in uploaded, "the deleted profile's preferences were uploaded: $uploaded")
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
    fun aWorkoutDeletedWhileTheProfileWasUnboundIsTombstonedBeforeFinalizing() = runTest {
        val p = createProfileWithData()
        // A workout already on the portal under P (synced by an older, unbinding build) and
        // deleted locally while P was unbound: rows gone, tombstone kept with no owner.
        val earlier = "abababab-0000-4000-8000-000000000003"
        api.liveRowsByProfile.getOrPut(p) { mutableSetOf() } += earlier
        database.phoenixDatabaseQueries.insertWorkoutDeletion(
            mutationId = "m-unbound",
            ownerUserId = null,
            profileId = p,
            scope = "WORKOUT",
            portalSessionId = earlier,
            componentSessionId = null,
            deletedAt = baseTime,
            source = "LOCAL",
        )

        assertTrue(profiles.deleteActiveProfilePermanently(p))
        assertTrue(manager.sync().isSuccess, "sync failed: ${manager.syncState.value}")

        assertTrue(
            api.pushPayloads.filter { it.profileId == p }.flatMap { it.workoutDeletions }.any { it.portalSessionId == earlier },
            "the earlier workout tombstone must ride P's push",
        )
        assertTrue(api.reScopedToDefault.isEmpty(), "rows re-scoped to Default: ${api.reScopedToDefault}")
        assertNull(database.phoenixDatabaseQueries.getProfileById(p).executeAsOneOrNull())
    }

    @Test
    fun aPermanentDeleteIsRefusedDuringALiveWorkout() = runTest {
        val p = createProfileWithData()

        kotlin.test.assertFailsWith<com.devil.phoenixproject.data.repository.ProfileSwitchBlockedDuringWorkoutException> {
            profiles.deleteActiveProfilePermanently(p, blockedByLiveSession = { true })
        }

        assertEquals(p, profiles.activeProfile.value?.id, "PR 16 guard: the live profile stays active")
        assertNotNull(database.phoenixDatabaseQueries.selectSessionById(sessionP).executeAsOneOrNull(), "nothing deleted")
        assertTrue(profiles.pendingDeletionProfiles.value.isEmpty())
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

    /** Every table with a profile-owner column, and that column (from the live schema). */
    private fun profileOwnedColumns(): Map<String, String> {
        val tables = mutableListOf<String>()
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", { c ->
            while (c.next().value) c.getString(0)?.let(tables::add)
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0)
        return tables.mapNotNull { table ->
            columnsOf(table).map { it.name }
                .firstOrNull { it in PROFILE_OWNER_COLUMNS }
                ?.let { table to it }
        }.toMap()
    }

    private data class Column(val name: String, val type: String, val notNull: Boolean, val hasDefault: Boolean, val pk: Boolean)

    private fun columnsOf(table: String): List<Column> {
        val columns = mutableListOf<Column>()
        driver.executeQuery(null, "PRAGMA table_info($table)", { c ->
            while (c.next().value) {
                columns += Column(
                    name = c.getString(1)!!,
                    type = c.getString(2).orEmpty().uppercase(),
                    notNull = c.getLong(3) == 1L,
                    hasDefault = c.getString(4) != null,
                    pk = c.getLong(5)!! > 0L,
                )
            }
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0)
        return columns
    }

    /** Every table with a column keyed by a workout (session id or portal workout id). */
    private fun sessionKeyedColumns(): Map<String, String> {
        val tables = mutableListOf<String>()
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", { c ->
            while (c.next().value) c.getString(0)?.let(tables::add)
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0)
        return tables.mapNotNull { table ->
            columnsOf(table).map { it.name }
                .firstOrNull { it in SESSION_KEY_COLUMNS }
                ?.let { table to it }
        }.toMap()
    }

    /**
     * Inserts one minimal row owned by [profileId] (when the table has an owner column),
     * with [fixed] column values; false when the table's constraints refuse it.
     */
    private fun seedRow(table: String, profileId: String, fixed: Map<String, String> = emptyMap()): Boolean {
        val columns = columnsOf(table)
        val owner = columns.firstOrNull { it.name in PROFILE_OWNER_COLUMNS }?.name
        val fill = columns.filter {
            it.name == owner || it.name in fixed || it.name == "exerciseId" || it.name == "exercise_id" ||
                (it.notNull && !it.hasDefault && !(it.pk && it.type == "INTEGER"))
        }
        val values = fill.map { column ->
            when {
                column.name in fixed -> fixed.getValue(column.name)
                column.name == owner -> profileId
                column.name == "exerciseId" || column.name == "exercise_id" -> "squat"
                column.type.contains("INT") -> 1L
                column.type.contains("REAL") -> 1.0
                column.pk -> "seed-$table"
                else -> if (column.name.endsWith("Json", ignoreCase = true) || column.name.endsWith("_json")) "{}" else "seed"
            }
        }
        return runCatching {
            driver.execute(
                null,
                "INSERT INTO $table (${fill.joinToString { it.name }}) VALUES (${fill.joinToString { "?" }})",
                fill.size,
            ) {
                values.forEachIndexed { i, v ->
                    when (v) {
                        is Long -> bindLong(i, v)
                        is Double -> bindDouble(i, v)
                        else -> bindString(i, v as String)
                    }
                }
            }
        }.isSuccess
    }

    private fun rowCount(table: String, column: String, value: String): Long {
        var count = 0L
        driver.executeQuery(null, "SELECT COUNT(*) FROM $table WHERE $column = ?", { c ->
            if (c.next().value) count = c.getLong(0) ?: 0L
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 1) { bindString(0, value) }
        return count
    }

    private fun liveRowCount(table: String, column: String, profileId: String): Long {
        val live = if (columnsOf(table).any { it.name == "deletedAt" }) " AND deletedAt IS NULL" else ""
        var count = 0L
        driver.executeQuery(null, "SELECT COUNT(*) FROM $table WHERE $column = ?$live", { c ->
            if (c.next().value) count = c.getLong(0) ?: 0L
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 1) { bindString(0, profileId) }
        return count
    }

    private fun signIn(asUser: String = userId) {
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = asUser, email = "$asUser@b.c"),
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
        /** Stored dedicated PR rows: id -> (LWW clock, is tombstone). Models the portal's PR gate. */
        val storedPrs = mutableMapOf<String, Pair<Long, Boolean>>()
        /** Drops id-less (legacy, uuid-less) PR tombstones, e.g. to an identity collision. */
        var dropIdLessPrTombstones = false
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
            // The portal's dedicated-PR LWW gate: a stored tombstone is never resurrected, a
            // strictly newer row wins, and an equal-time tombstone beats a live row. Only rows
            // that pass are written and counted in `personalRecordsInserted`.
            var prsWritten = 0
            payload.personalRecords.forEach { pr ->
                val id = pr.id
                if (id == null) {
                    if (!(pr.deletedAt != null && dropIdLessPrTombstones)) prsWritten++
                    return@forEach
                }
                val incoming = kotlin.time.Instant.parse(pr.updatedAt ?: pr.deletedAt ?: pr.achievedAt)
                    .toEpochMilliseconds()
                val deleted = pr.deletedAt != null
                val stored = storedPrs[id]
                val passes = when {
                    stored == null -> true
                    stored.second && !deleted -> false
                    incoming != stored.first -> incoming > stored.first
                    else -> deleted && !stored.second
                }
                if (passes) {
                    storedPrs[id] = incoming to deleted
                    prsWritten++
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
                    personalRecordsInserted = prsWritten,
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
