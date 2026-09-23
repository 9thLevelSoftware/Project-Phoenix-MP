package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.devil.phoenixproject.data.repository.PortalAuthRepository
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
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
import com.devil.phoenixproject.testutil.seedExercise
import com.russhwolf.settings.MapSettings
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

/**
 * PR 11 acceptance: detect a different portal account at sign-in, honour the
 * account-switch choice for every pushed entity type, and treat both of the
 * portal's ownership-400 bodies as terminal — against a fake portal that
 * enforces ownership on every entity type.
 */
class AccountSwitchSyncTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var syncRepository: SqlDelightSyncRepository
    private lateinit var tokenStorage: PortalTokenStorage
    private lateinit var settings: MapSettings
    private lateinit var api: OwnershipEnforcingPortalApi
    private lateinit var manager: SyncManager
    private val pendingAccountMismatch = PendingAccountMismatch()

    private val userA = "user-a"
    private val userB = "user-b"
    private val emailA = "a@example.com"
    private val emailB = "b@example.com"
    private val profileId = "default"

    // Canonical UUIDs: SyncManager strips non-UUID routine ids from payloads.
    private val routinePre = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val routineNew = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val cyclePre = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private val baseTime = currentTimeMillis()

    @Before
    fun setup() {
        database = createTestDatabase()
        userProfileRepository = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = profileId, supabaseUserId = null)
        }
        syncRepository = SqlDelightSyncRepository(database, userProfileRepository)
        settings = MapSettings()
        tokenStorage = PortalTokenStorage(settings)
        tokenStorage.saveGoTrueAuth(authResponse(userA, emailA, "token-a"))
        // Suppress both one-time repair pushes; they gather from 0 and would re-send
        // pre-switch rows regardless of the exclusion filter.
        tokenStorage.setRoutineGroupRepairCursor(profileId, 0L)
        tokenStorage.markRoutineCyclePrRepairPushDone(userA)
        tokenStorage.markRoutineCyclePrRepairPushDone(userB)
        api = OwnershipEnforcingPortalApi().also { it.currentPushUser = userA }
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
            pendingAccountMismatch = pendingAccountMismatch,
        )
        database.seedExercise("bench", name = "Bench Press")
    }

    // ===== 1. A different portal account is detected at sign-in =====

    @Test
    fun loginAsDifferentAccountAfterSyncMakesNoPushAndSetsAccountMismatch() = runTest {
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        insertRoutine(routinePre, profileId = profileId, createdAt = baseTime)
        assertTrue(manager.sync().isSuccess)
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == userA)
        val pushesAfterA = api.pushCallCount
        assertTrue(pushesAfterA > 0, "the A sync must have pushed")

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)

        val state = manager.syncState.value
        assertIs<SyncState.AccountMismatch>(state)
        assertTrue(state.previousUserId == userA)
        assertTrue(state.newUserId == userB)
        assertTrue(
            api.pushCallCount == pushesAfterA,
            "login must not push (was $pushesAfterA, now ${api.pushCallCount})",
        )

        assertTrue(manager.sync().isFailure)
        assertTrue(
            api.pushCallCount == pushesAfterA,
            "sync must not push while AccountMismatch is open",
        )
    }

    @Test
    fun lastSyncedOnlyMismatchIsDetectedWhenProfilesWereNeverLinked() = runTest {
        // No prior sync, profiles never linked: only lastSyncedPortalUserId names A.
        tokenStorage.setLastSyncedPortalUserId(userA)
        tokenStorage.setLastSyncedPortalUserLabel(emailA)

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)

        val state = manager.syncState.value
        assertIs<SyncState.AccountMismatch>(state)
        assertTrue(state.previousUserId == userA)
        assertTrue(state.previousUserLabel == emailA)
        // A sync attempt is what could push; login alone never does.
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        assertTrue(manager.sync().isFailure)
        assertTrue(api.pushCallCount == 0, "sync must not push while AccountMismatch is open")

        // The pull-only retry is gated too, and must not overwrite the pause.
        val pulls = api.pullCallCount
        assertTrue(manager.retryPull().isFailure)
        assertIs<SyncState.AccountMismatch>(manager.syncState.value)
        assertTrue(api.pullCallCount == pulls, "retryPull must not pull while AccountMismatch is open")
    }

    // ===== 1b. The pause is durable: a process restart before the choice keeps it =====

    @Test
    fun accountMismatchSurvivesAProcessRestartBeforeTheUserChooses() = runTest {
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        insertRoutine(routinePre, profileId = profileId, createdAt = baseTime)
        assertTrue(manager.sync().isSuccess)

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertIs<SyncState.AccountMismatch>(manager.syncState.value)
        val pushes = api.pushCallCount

        // The process dies before the dialog is answered. A fresh SyncManager and hand-off
        // holder start over the same token storage and database, with an Idle state.
        val restarted = newManager(PendingAccountMismatch())
        assertFalse(restarted.syncState.value is SyncState.AccountMismatch)

        assertTrue(restarted.sync().isFailure)
        val state = assertIs<SyncState.AccountMismatch>(restarted.syncState.value)
        assertTrue(state.previousUserId == userA && state.newUserId == userB)
        assertTrue(api.pushCallCount == pushes, "a restarted sync must not push before the choice")
        assertTrue(
            tokenStorage.getLastSyncedPortalUserId() == userA,
            "the evidence of the switch must not be overwritten",
        )

        // The choice still resolves from the restarted manager, and sync resumes.
        assertTrue(
            restarted.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess,
        )
        api.pushPayloads.clear()
        assertTrue(restarted.sync().isSuccess)
        assertTrue(api.pushPayloads.flatMap { it.sessions }.none { it.id == "pre-1" })
    }

    // ===== 1c. Signing back into the same account is not a switch =====

    @Test
    fun signingBackIntoTheSameAccountDoesNotPauseSync() = runTest {
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        assertTrue(manager.sync().isSuccess)
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == userA)

        manager.logout()
        api.signInResult = Result.success(authResponse(userA, emailA, "token-a2"))
        assertTrue(manager.login(emailA, "pw").isSuccess)
        assertFalse(manager.syncState.value is SyncState.AccountMismatch)
        assertFalse(pendingAccountMismatch.peek() != null, "no mismatch may be published")

        insertSession("after-relogin", groupId = null, timestamp = currentTimeMillis(), profileId = profileId)
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        assertTrue(
            "after-relogin" in api.pushPayloads.flatMap { it.sessions }.map { it.id },
            "the same account must keep uploading",
        )
        assertTrue(syncRepository.getSyncExcludedEntityIds(userA, SyncExcludedEntityTypes.WORKOUT).isEmpty())
    }

    // ===== 2. "Upload workouts not yet synced" =====

    @Test
    fun uploadNeverSyncedPushesOnlyTheRowsThatNeverReachedTheOldAccount() = runTest {
        val pre = seedPreSwitchRows()
        val boundary = prepareSwitchWithNewRows(pre)
        val lateCustomId = "custom_${boundary + 70_000}"

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertIs<SyncState.AccountMismatch>(manager.syncState.value)
        assertTrue(
            manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess,
        )

        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)

        val pushed = api.pushPayloads
        val sessionIds = pushed.flatMap { it.sessions }.map { it.id }
        val routineIds = pushed.flatMap { it.routines }.map { it.id }
        val cycleIds = pushed.flatMap { it.cycles }.map { it.id }
        val customIds = pushed.flatMap { it.customExercises }.map { it.clientId }
        val assessmentIds = pushed.flatMap { it.assessments }.map { it.id }

        assertTrue(pre.sessionIds.none { it in sessionIds }, "pre-switch sessions leaked: $sessionIds")
        assertTrue("new-session" in sessionIds, "new session missing: $sessionIds")
        assertTrue(pre.routineId !in routineIds, "pre-switch routine leaked: $routineIds")
        assertTrue(routineNew in routineIds, "new routine missing: $routineIds")
        assertTrue(pre.cycleId !in cycleIds, "pre-switch cycle leaked: $cycleIds")
        assertTrue(pre.customId !in customIds, "pre-switch custom exercise leaked: $customIds")
        assertTrue(lateCustomId in customIds, "new custom exercise missing: $customIds")
        assertTrue(pre.assessmentId !in assessmentIds, "pre-switch assessment leaked: $assessmentIds")
    }

    @Test
    fun editingAPreSwitchRoutineAfterUploadNeverSyncedDoesNotWedgeSync() = runTest {
        val pre = seedPreSwitchRows()
        prepareSwitchWithNewRows(pre)

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertTrue(
            manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess,
        )
        assertTrue(manager.sync().isSuccess)

        // Edit the excluded pre-switch routine; it must not re-enter the payload and
        // must not wedge the upload loop.
        val q = database.phoenixDatabaseQueries
        q.updateRoutineFields(
            name = "Edited split",
            description = "",
            createdAt = baseTime,
            lastUsed = null,
            useCount = 0L,
            updatedAt = currentTimeMillis(),
            profile_id = profileId,
            groupId = null,
            id = pre.routineId,
        )
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        val routineIds = api.pushPayloads.flatMap { it.routines }.map { it.id }
        assertTrue(
            pre.routineId !in routineIds,
            "an edited pre-switch routine must stay excluded (saw $routineIds)",
        )
        assertFalse(manager.syncState.value is SyncState.OwnershipConflict)
    }

    @Test
    fun eachChoiceRelinksEveryProfileAndALaterSignInShowsNoDialog() = runTest {
        for (choice in AccountSwitchChoice.entries) {
            setup()
            val second = userProfileRepository.createProfile("Second", 1)
            insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
            assertTrue(manager.sync().isSuccess)

            api.currentPushUser = userB
            api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
            assertTrue(manager.login(emailB, "pw").isSuccess)
            assertIs<SyncState.AccountMismatch>(manager.syncState.value)
            assertTrue(manager.resolveAccountMismatch(choice).isSuccess)

            val owners = userProfileRepository.allProfiles.value.associate { it.id to it.supabaseUserId }
            assertTrue(owners.keys.containsAll(listOf(profileId, second.id)), "profiles: $owners")
            assertTrue(owners.values.all { it == userB }, "$choice must relink every profile: $owners")

            manager.logout()
            api.signInResult = Result.success(authResponse(userB, emailB, "token-b2"))
            assertTrue(manager.login(emailB, "pw").isSuccess)
            assertFalse(
                manager.syncState.value is SyncState.AccountMismatch,
                "$choice: signing in again as the new account must not reopen the dialog",
            )
            assertTrue(manager.sync().isSuccess)
        }
    }

    @Test
    fun aChoiceAnsweredAfterTheSignedInAccountChangedIsRefused() = runTest {
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        assertTrue(manager.sync().isSuccess)
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertIs<SyncState.AccountMismatch>(manager.syncState.value)

        // Someone else is now signed in underneath the still-open dialog.
        tokenStorage.saveGoTrueAuth(authResponse("user-c", "c@example.com", "token-c"))
        assertTrue(manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isFailure)
        assertTrue(
            userProfileRepository.allProfiles.value.none { it.supabaseUserId == userB },
            "a stale choice must not bind the rows to the account the mismatch named",
        )
        assertTrue(syncRepository.getSyncExcludedEntityIds(userB, SyncExcludedEntityTypes.WORKOUT).isEmpty())
    }

    // ===== 2c. Account-level markers never advance on an aborted loop =====

    @Test
    fun anAuthAbortBeforeAnyPushLandsAdvancesNoAccountMarker() = runTest {
        val second = userProfileRepository.createProfile("Second", 1)
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        insertSession("pre-2", groupId = null, timestamp = baseTime, profileId = second.id)
        api.pushResult = Result.failure(PortalApiException("expired", null, 401))

        assertTrue(manager.sync().isFailure)

        assertTrue(api.pushCallCount == 1, "a 401 must abort the loop (pushes=${api.pushCallCount})")
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == null, "no push landed, so no last-synced account")
        for (id in listOf(profileId, second.id)) {
            assertTrue(tokenStorage.getPushWatermark(userA, id) == 0L, "push watermark advanced for $id")
            assertTrue(tokenStorage.getPullMergeWatermark(userA, id) == 0L, "pull-merge stamp advanced for $id")
        }
    }

    @Test
    fun aFailedPullMergeDoesNotAdvanceThePullMergeStamp() = runTest {
        val routine = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        // The push lands, then the pull fails before merging anything: the stamp records
        // pulled rows landing, so it must stay where it was. (A merge that throws is covered
        // by SyncManagerTest.aRolledBackPullMergeDoesNotAdvanceThePullMergeStamp.)
        api.pullResult = Result.failure(PortalApiException("server", null, 500))
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        insertRoutine(routine, profileId = profileId, createdAt = baseTime)

        manager.sync()

        assertTrue(tokenStorage.getPushWatermark(userA, profileId) > 0L, "the push itself landed")
        assertTrue(
            tokenStorage.getPullMergeWatermark(userA, profileId) == 0L,
            "a pull that merged nothing must not advance the pull-merge stamp",
        )
    }

    // ===== 2d. Devices upgraded from a pre-PR-11 build (legacy global cursor) =====

    private fun plantLegacyCursor(ownerUserId: String?, lastSync: Long) {
        settings.putLong("portal_last_sync_timestamp", lastSync)
        if (ownerUserId != null) settings.putString("portal_delta_pull_key", "$ownerUserId:$profileId")
    }

    private fun legacySeedingRan(): Boolean =
        database.phoenixDatabaseQueries.selectAppliedDataRepair("legacy-sync-generations-v1").executeAsOneOrNull() != null

    @Test
    fun anUpgradedDeviceSignedInAsTheLegacyOwnerSeedsAndSyncsWithoutPausing() = runTest {
        // A workout the old client pushed: stamped at the legacy cursor, generations 1/0.
        insertSession("legacy-1", groupId = null, timestamp = baseTime, profileId = profileId)
        database.phoenixDatabaseQueries.updateSessionTimestampsByIds(timestamp = baseTime, ids = listOf("legacy-1"), gatherStartedAt = baseTime)
        plantLegacyCursor(userA, lastSync = baseTime + 1_000)

        assertTrue(manager.sync().isSuccess)
        assertFalse(manager.syncState.value is SyncState.AccountMismatch)
        assertTrue(legacySeedingRan(), "the legacy generation seeding runs for the legacy owner")
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == userA)
        assertTrue(
            api.pushPayloads.flatMap { it.sessions }.none { it.id == "legacy-1" },
            "a row the old client synced is seeded as synced, not re-pushed",
        )
    }

    @Test
    fun anUpgradedDeviceSigningInAsAnotherAccountIsDetectedFromTheLegacyCursor() = runTest {
        // No PR 11 record yet: only the legacy cursor says which account the rows reached.
        insertSession("legacy-1", groupId = null, timestamp = baseTime, profileId = profileId)
        plantLegacyCursor(userA, lastSync = baseTime + 1_000)
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == null)

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)

        val state = assertIs<SyncState.AccountMismatch>(manager.syncState.value)
        assertTrue(state.previousUserId == userA && state.newUserId == userB)
        // saveGoTrueAuth dropped the legacy key on the switch; the evidence survived it.
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == userA)
        assertTrue(manager.sync().isFailure)
        assertTrue(api.pushCallCount == 0, "nothing may be pushed before the choice")
    }

    @Test
    fun changingAccountInTokenStorageKeepsTheLegacyOwnerAsLastSynced() = runTest {
        // saveGoTrueAuth drops the un-namespaced legacy cursor on an account change; the
        // fact it carried (which account the old client synced into) must survive it.
        plantLegacyCursor(userA, lastSync = baseTime)
        tokenStorage.saveGoTrueAuth(authResponse(userB, emailB, "token-b"))
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == userA)
    }

    @Test
    fun signingOutKeepsTheLegacyOwnerSoALaterSignInAsAnotherAccountIsDetected() = runTest {
        // No delta-pull marker: the legacy cursor belongs to whoever was signed in.
        insertSession("legacy-1", groupId = null, timestamp = baseTime, profileId = profileId)
        plantLegacyCursor(ownerUserId = null, lastSync = baseTime)
        manager.logout()
        assertTrue(tokenStorage.getLastSyncedPortalUserId() == userA, "sign-out must keep the legacy owner")

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        val state = assertIs<SyncState.AccountMismatch>(manager.syncState.value)
        assertTrue(state.previousUserId == userA)
        assertTrue(api.pushCallCount == 0)
    }

    @Test
    fun legacySeedingNeverRunsUnderAMismatchedAccount() = runTest {
        // The token already names B (e.g. a restart after an older sign-in) while the legacy
        // cursor is attributed to A: the pause gate must stop the sync before the one-shot
        // seeding acks rows on B's behalf.
        tokenStorage.saveGoTrueAuth(authResponse(userB, emailB, "token-b"))
        insertSession("legacy-1", groupId = null, timestamp = baseTime, profileId = profileId)
        database.phoenixDatabaseQueries.updateSessionTimestampsByIds(timestamp = baseTime, ids = listOf("legacy-1"), gatherStartedAt = baseTime)
        plantLegacyCursor(userA, lastSync = baseTime + 1_000)
        api.currentPushUser = userB

        val restarted = newManager(PendingAccountMismatch())
        assertTrue(restarted.sync().isFailure)
        val state = assertIs<SyncState.AccountMismatch>(restarted.syncState.value)
        assertTrue(state.previousUserId == userA && state.newUserId == userB)
        assertFalse(legacySeedingRan(), "the one-shot seeding must not run under a mismatched account")
        assertTrue(api.pushCallCount == 0)
    }

    // ===== 3. Rows pulled from the old account are not "never synced" =====

    @Test
    fun rowsPulledFromTheOldAccountAreNotUploadedToTheNewOne() = runTest {
        val pulledRoutine = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val pulledCycle = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        api.claim(userA, pulledRoutine, pulledCycle)
        api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = currentTimeMillis(),
                sessions = emptyList(),
                routines = listOf(PullRoutineDto(id = pulledRoutine, userId = userA, name = "Pulled split")),
                cycles = listOf(PullTrainingCycleDto(id = pulledCycle, userId = userA, name = "Pulled block")),
                rpgAttributes = null,
                badges = emptyList(),
                gamificationStats = null,
            ),
        )
        // A's last sync pushes nothing of these and pulls both in (a fresh-install first sync).
        assertTrue(manager.sync().isSuccess)
        val q = database.phoenixDatabaseQueries
        assertTrue(q.selectRoutineById(pulledRoutine).executeAsOneOrNull() != null, "routine was not pulled")
        assertTrue(q.selectTrainingCycleById(pulledCycle).executeAsOneOrNull() != null, "cycle was not pulled")
        api.pullResult = Result.success(
            PortalSyncPullResponse(
                syncTime = currentTimeMillis(),
                sessions = emptyList(),
                routines = emptyList(),
                rpgAttributes = null,
                badges = emptyList(),
                gamificationStats = null,
            ),
        )

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertTrue(
            manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess,
        )
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess, "the new account's first sync must not hit an ownership 400")
        assertFalse(manager.syncState.value is SyncState.OwnershipConflict)
        assertTrue(api.pushPayloads.flatMap { it.routines }.none { it.id == pulledRoutine })
        assertTrue(api.pushPayloads.flatMap { it.cycles }.none { it.id == pulledCycle })
    }

    // ===== 3b. Workouts are classified by origin and acknowledged generation =====

    @Test
    fun taggedJustLiftSessionUploadsAndAReArmedSyncedSessionStaysExcluded() = runTest {
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        assertTrue(manager.sync().isSuccess)
        val q = database.phoenixDatabaseQueries
        // PR 8's repair re-arm nulls updatedAt on a row the old account already holds.
        q.clearSessionTimestamps(listOf("pre-1"))

        // A Just Lift set recorded after A's last sync and tagged with an exercise:
        // never pushed, but the tag write gives it a non-null updatedAt.
        val later = tokenStorage.getAccountSyncBoundary(userA, profileId) + 60_000
        insertSession("just-lift", groupId = null, timestamp = later, profileId = profileId)
        q.updateSessionExerciseTag("bench", "Bench Press", later, "just-lift")

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertTrue(
            manager.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED).isSuccess,
        )
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        val sessionIds = api.pushPayloads.flatMap { it.sessions }.map { it.id }
        assertTrue("just-lift" in sessionIds, "a tagged, never-synced Just Lift set must upload: $sessionIds")
        assertTrue("pre-1" !in sessionIds, "a re-armed row the old account holds must stay excluded: $sessionIds")
        assertFalse(manager.syncState.value is SyncState.OwnershipConflict)
    }

    // ===== 4. "Don't upload existing data" =====

    @Test
    fun excludeAllExistingSkipsEverythingOnDiskAndStillUploadsLaterSessions() = runTest {
        val pre = seedPreSwitchRows()
        prepareSwitchWithNewRows(pre)

        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        assertTrue(manager.login(emailB, "pw").isSuccess)
        assertTrue(
            manager.resolveAccountMismatch(AccountSwitchChoice.EXCLUDE_ALL_EXISTING).isSuccess,
        )

        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        var pushed = api.pushPayloads
        assertTrue(pushed.flatMap { it.sessions }.none { it.id in pre.sessionIds })
        assertTrue(pushed.flatMap { it.sessions }.none { it.id == "new-session" })
        assertTrue(pushed.flatMap { it.routines }.none { it.id == pre.routineId })
        assertTrue(pushed.flatMap { it.routines }.none { it.id == routineNew })
        assertTrue(pushed.flatMap { it.customExercises }.isEmpty())
        assertTrue(pushed.flatMap { it.cycles }.none { it.id == pre.cycleId })

        insertSession("after-switch", groupId = null, timestamp = currentTimeMillis(), profileId = profileId)
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        pushed = api.pushPayloads
        assertTrue(
            "after-switch" in pushed.flatMap { it.sessions }.map { it.id },
            "a session recorded after the switch must still upload",
        )
    }

    // ===== 5. Both ownership-400 bodies are terminal =====

    @Test
    fun belongsToAnotherUserRefusalIsTerminalAndDoesNotRetry() = runTest {
        api.forcedRejectBody = "Entity pre-1 belongs to another user"
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)

        assertTrue(manager.sync().isFailure)
        assertIs<SyncState.OwnershipConflict>(manager.syncState.value)
        val pushes = api.pushCallCount
        assertTrue(pushes > 0)

        assertTrue(manager.sync().isFailure)
        assertTrue(
            api.pushCallCount == pushes,
            "sync must not push again while OwnershipConflict is open",
        )
    }

    @Test
    fun ownershipConflictRecoveryLeavesTheTerminalStateAndUploadsLaterRows() = runTest {
        api.forcedRejectBody = "Entity pre-1 belongs to another user"
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        assertTrue(manager.sync().isFailure)
        assertIs<SyncState.OwnershipConflict>(manager.syncState.value)

        assertTrue(manager.applyOwnershipConflictRecovery().isSuccess)
        assertFalse(manager.syncState.value is SyncState.OwnershipConflict)
        assertTrue(
            "pre-1" in syncRepository.getSyncExcludedEntityIds(userA, SyncExcludedEntityTypes.WORKOUT),
            "recovery must record the pre-existing rows as excluded",
        )

        api.forcedRejectBody = null
        insertSession("after-recovery", groupId = null, timestamp = currentTimeMillis(), profileId = profileId)
        api.pushPayloads.clear()
        assertTrue(manager.sync().isSuccess)
        val sessionIds = api.pushPayloads.flatMap { it.sessions }.map { it.id }
        assertTrue("after-recovery" in sessionIds, "a row created after recovery must upload: $sessionIds")
        assertTrue("pre-1" !in sessionIds, "the refused row must stay excluded: $sessionIds")
    }

    @Test
    fun customExerciseCatalogConflictIsTerminalAndDoesNotRetry() = runTest {
        api.forcedRejectBody = "Custom exercise id conflicts with an existing catalog exercise"
        database.seedExercise("custom_${baseTime}", name = "My Fly", isCustom = true)

        assertTrue(manager.sync().isFailure)
        assertIs<SyncState.OwnershipConflict>(manager.syncState.value)
        val pushes = api.pushCallCount

        assertTrue(manager.sync().isFailure)
        assertTrue(api.pushCallCount == pushes)
    }

    // ===== 6. Login/signup logs never carry the email (F-076) =====

    @Test
    fun loggerCaptureContainsNoEmail() = runTest {
        val recording = RecordingLogWriter()
        val previous = Logger.config.logWriterList
        Logger.setLogWriters(recording)
        try {
            tokenStorage.setLastSyncedPortalUserId(userA)
            api.currentPushUser = userB
            api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
            assertTrue(manager.login(emailB, "pw").isSuccess)
            // The mismatch path is the one this PR adds; cover signup's log line too.
            api.signUpResult = Result.success(authResponse(userB, emailB, "token-b2"))
            assertTrue(manager.signup(emailB, "pw", "B").isSuccess)
            assertIs<SyncState.AccountMismatch>(manager.syncState.value)
            assertTrue(recording.lines.any { it.contains("different portal account") })

            assertTrue(
                recording.lines.none { it.contains(emailB) },
                "login/signup logs leaked the new account email: ${recording.lines}",
            )
            assertTrue(
                recording.lines.none { it.contains(emailA) },
                "login/signup logs leaked the prior account email: ${recording.lines}",
            )
        } finally {
            Logger.setLogWriters(previous)
        }
    }

    // ===== 7. The sign-in -> sync hand-off is scoped to the injected holder =====

    @Test
    fun signInMismatchReachesOnlyTheSyncManagerSharingItsHolder() = runTest {
        tokenStorage.setLastSyncedPortalUserId(userA)
        api.currentPushUser = userB
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        val otherManager = newManager(PendingAccountMismatch())
        val auth = newAuthRepository()
        try {
            assertTrue(auth.signInWithEmail(emailB, "pw").isSuccess)
            val pushes = api.pushCallCount

            // A manager wired to a different holder never sees this sign-in's mismatch.
            assertFalse(otherManager.adoptPendingAccountMismatch())
            // The manager sharing the holder adopts it and refuses to push.
            assertTrue(manager.sync().isFailure)
            val state = assertIs<SyncState.AccountMismatch>(manager.syncState.value)
            assertTrue(state.previousUserId == userA && state.newUserId == userB)
            assertTrue(api.pushCallCount == pushes)
        } finally {
            auth.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun logoutDropsAnUnansweredSignInMismatch() = runTest {
        tokenStorage.setLastSyncedPortalUserId(userA)
        api.signInResult = Result.success(authResponse(userB, emailB, "token-b"))
        val auth = newAuthRepository()
        try {
            assertTrue(auth.signInWithEmail(emailB, "pw").isSuccess)
            manager.logout()
            assertFalse(manager.adoptPendingAccountMismatch())
        } finally {
            auth.close()
            Dispatchers.resetMain()
        }
    }

    // ===== Helpers =====

    private data class PreSwitchRows(
        val sessionIds: List<String>,
        val routineId: String,
        val cycleId: String,
        val customId: String,
        val assessmentId: String,
    )

    private fun newManager(holder: PendingAccountMismatch) = SyncManager(
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
        pendingAccountMismatch = holder,
    )

    /** Sign-in path that cannot reach [manager]; shares [pendingAccountMismatch] like Koin does. */
    private fun newAuthRepository(): PortalAuthRepository {
        Dispatchers.setMain(StandardTestDispatcher())
        return PortalAuthRepository(
            apiClient = api,
            tokenStorage = tokenStorage,
            userProfileRepository = userProfileRepository,
            supabaseConfig = SupabaseConfig("https://fake.supabase.co", "anon"),
            profileMutationBarrier = ProfileMutationBarrier(),
            launchOAuth = { _, _ -> Result.failure(IllegalStateException("OAuth was not expected")) },
            pendingAccountMismatch = pendingAccountMismatch,
        )
    }

    /** Rows created and uploaded as user A, before any account switch. */
    private fun seedPreSwitchRows(): PreSwitchRows {
        val customId = "custom_$baseTime"
        database.seedExercise(customId, name = "Pre Fly", isCustom = true)
        insertSession("pre-1", groupId = null, timestamp = baseTime, profileId = profileId)
        insertSession("pre-2", groupId = "33333333-3333-4333-8333-333333333333", timestamp = baseTime, profileId = profileId)
        insertRoutine(routinePre, profileId = profileId, createdAt = baseTime)
        insertCycle(cyclePre, profileId = profileId, createdAt = baseTime)
        insertAssessment(createdAt = baseTime)
        val assessmentId = database.phoenixDatabaseQueries
            .selectAllAssessments(profileId)
            .executeAsList()
            .last()
            .id
            .toString()
        return PreSwitchRows(
            sessionIds = listOf("pre-1", "pre-2"),
            routineId = routinePre,
            cycleId = cyclePre,
            customId = customId,
            assessmentId = assessmentId,
        )
    }

    /**
     * Syncs the pre-switch rows to A (stamping them and writing A's push watermark),
     * then inserts never-synced rows created after that watermark and returns the
     * watermark plus the id of the late custom exercise.
     */
    private suspend fun prepareSwitchWithNewRows(pre: PreSwitchRows): Long {
        assertTrue(manager.sync().isSuccess)
        val watermark = tokenStorage.getPushWatermark(userA, profileId)
        assertTrue(watermark > 0, "a completed push must persist the profile push watermark")
        val boundary = tokenStorage.getAccountSyncBoundary(userA, profileId)

        // Never-synced: a session that was never pushed; routine createdAt and custom
        // exercise id time after A's sync boundary. All planted AFTER A's sync so they
        // never reached the old account.
        insertSession("new-session", groupId = null, timestamp = boundary + 60_000, profileId = profileId)
        insertRoutine(routineNew, profileId = profileId, createdAt = boundary + 60_000)
        val lateCustomId = "custom_${boundary + 70_000}"
        database.seedExercise(lateCustomId, name = "New Fly", isCustom = true)
        return boundary
    }

    private fun insertRoutine(id: String, profileId: String, createdAt: Long) {
        database.phoenixDatabaseQueries.insertRoutine(
            id = id,
            name = "Split",
            description = "",
            createdAt = createdAt,
            lastUsed = null,
            useCount = 0L,
            profile_id = profileId,
            groupId = null,
            deletedAt = null,
        )
    }

    private fun insertCycle(id: String, profileId: String, createdAt: Long) {
        database.phoenixDatabaseQueries.insertTrainingCycle(
            id = id,
            name = "Block",
            description = "",
            created_at = createdAt,
            is_active = 0L,
            profile_id = profileId,
            template_id = null,
            week_number = 1L,
            updatedAt = createdAt,
        )
    }

    private fun insertAssessment(createdAt: Long) {
        val q = database.phoenixDatabaseQueries
        q.insertAssessmentResult(
            exerciseId = "bench",
            estimatedOneRepMaxKg = 100.0,
            loadVelocityData = "[]",
            assessmentSessionId = null,
            userOverrideKg = null,
            createdAt = createdAt,
            profile_id = profileId,
        )
    }

    private fun insertSession(
        id: String,
        groupId: String?,
        timestamp: Long,
        profileId: String,
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
    }

    private fun authResponse(userId: String, email: String, token: String) = GoTrueAuthResponse(
        accessToken = token,
        tokenType = "bearer",
        expiresIn = 3600,
        expiresAt = currentTimeMillis() / 1000 + 3600,
        refreshToken = "refresh-$userId",
        user = GoTrueUser(id = userId, email = email),
    )

    private class RecordingLogWriter : LogWriter() {
        val lines: MutableList<String> = mutableListOf()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += "$tag:$message"
        }
    }
}

/**
 * Fake portal that enforces ownership on every pushed entity type (PR 11).
 * A row already claimed by another portal user is refused with one of the two
 * real `mobile-sync-push` ownership-400 bodies.
 */
class OwnershipEnforcingPortalApi : FakePortalApiClient() {
    var currentPushUser: String = "user-a"
    /** When set, every push is refused with this exact ownership-400 body. */
    var forcedRejectBody: String? = null

    private val ownedIds = mutableMapOf<String, String>()

    fun claim(userId: String, vararg ids: String?) {
        ids.filterNotNull().forEach { ownedIds[it] = userId }
    }

    override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
        forcedRejectBody?.let { body ->
            pushCallCount++
            lastPushPayload = payload
            pushPayloads += payload
            return Result.failure(PortalApiException(body, null, 400))
        }

        val customConflict = payload.customExercises.firstOrNull { exercise ->
            ownedIds[exercise.clientId]?.let { it != currentPushUser } == true
        }
        if (customConflict != null) {
            pushCallCount++
            lastPushPayload = payload
            pushPayloads += payload
            return Result.failure(
                PortalApiException(
                    "Custom exercise id conflicts with an existing catalog exercise",
                    null,
                    400,
                ),
            )
        }

        val foreign = firstForeignId(payload)
        if (foreign != null) {
            pushCallCount++
            lastPushPayload = payload
            pushPayloads += payload
            return Result.failure(
                PortalApiException("Entity $foreign belongs to another user", null, 400),
            )
        }

        val result = super.pushPortalPayload(payload)
        if (result.isSuccess) {
            claim(currentPushUser, *sentIds(payload).toTypedArray())
            val acked = payload.sessions.map { it.id }.distinct()
            return result.map { it.copy(acknowledgedWorkoutSessionIds = acked) }
        }
        return result
    }

    private fun sentIds(payload: PortalSyncPayload): List<String> = buildList {
        payload.sessions.forEach {
            add(it.id)
            it.routineSessionId?.let { r -> add(r) }
        }
        payload.routines.forEach { add(it.id) }
        payload.deletedRoutineIds.forEach { add(it) }
        payload.cycles.forEach { add(it.id) }
        payload.customExercises.forEach { add(it.clientId) }
        payload.assessments.forEach { add(it.id) }
        payload.personalRecords.forEach { it.id?.let { id -> add(id) } }
    }

    private fun firstForeignId(payload: PortalSyncPayload): String? =
        sentIds(payload).firstOrNull { id ->
            ownedIds[id]?.let { it != currentPushUser } == true
        }
}
