package com.devil.phoenixproject.data.sync

import android.content.ContextWrapper
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.util.ConnectivityChecker
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * PR 12 / F-074: a confirmed-free account with a prior sync must see
 * "Sync paused — subscription required", not a stale "Last synced", and nothing is sent.
 * Drives the real [SyncTriggerManager] and [SyncManager]; the entitlement skip runs
 * before the connectivity check, so the Context is never consulted.
 */
class SyncPausedNotPremiumTest {

    @Test
    fun appForegroundForAFreeAccountWithAPriorSyncPublishesNotPremiumAndSendsNothing() = runTest {
        val database = createTestDatabase()
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = "user-free")
        }
        val tokenStorage = PortalTokenStorage(MapSettings())
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token-free",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh-free",
                user = GoTrueUser(id = "user-free", email = "free@example.com"),
            ),
        )
        tokenStorage.updatePremiumStatus(false)
        tokenStorage.publishMinPullCursor(1_000L) // a sync completed before
        // The fake's own token store is empty, so the entitlement refresh fails fast
        // and the stored "free" status is kept, exactly as on a network error.
        val api = FakePortalApiClient()
        val manager = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = SqlDelightSyncRepository(database, profiles),
            gamificationRepository = SqlDelightGamificationRepository(database),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = profiles,
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
            completedSetRepository = FakeCompletedSetRepository(),
        )
        val trigger = SyncTriggerManager(manager, ConnectivityChecker(ContextWrapper(null)))

        trigger.onAppForeground()

        assertIs<SyncState.NotPremium>(manager.syncState.value)
        assertEquals(0, api.pushCallCount)
        assertEquals(0, api.pullCallCount)
        assertEquals(1_000L, manager.lastSyncTime.value) // still available as secondary text
    }

    @Test
    fun aPersistedAccountSwitchHoldTakesPrecedenceOverThePausedStateAfterARestart() = runTest {
        val (manager, api, tokenStorage) = freeAccountWithPriorSync()
        // A different account already received this device's rows; the in-memory state is
        // back to Idle after a restart, so only durable state records the pending choice.
        tokenStorage.setLastSyncedPortalUserId("user-previous")

        SyncTriggerManager(manager, ConnectivityChecker(ContextWrapper(null))).onAppForeground()

        assertIs<SyncState.AccountMismatch>(manager.syncState.value)
        assertEquals(0, api.pushCallCount)
    }

    @Test
    fun aPersistedOwnershipConflictTakesPrecedenceOverThePausedStateAfterARestart() = runTest {
        val (manager, api, tokenStorage) = freeAccountWithPriorSync()
        tokenStorage.setOwnershipConflict("user-free", "belongs to another user")

        SyncTriggerManager(manager, ConnectivityChecker(ContextWrapper(null))).onAppForeground()

        assertIs<SyncState.OwnershipConflict>(manager.syncState.value)
        assertEquals(0, api.pushCallCount)
    }

    private fun freeAccountWithPriorSync(): Triple<SyncManager, FakePortalApiClient, PortalTokenStorage> {
        val database = createTestDatabase()
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = null)
        }
        val tokenStorage = PortalTokenStorage(MapSettings())
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token-free",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh-free",
                user = GoTrueUser(id = "user-free", email = "free@example.com"),
            ),
        )
        tokenStorage.updatePremiumStatus(false)
        tokenStorage.publishMinPullCursor(1_000L)
        val api = FakePortalApiClient()
        val manager = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = SqlDelightSyncRepository(database, profiles),
            gamificationRepository = SqlDelightGamificationRepository(database),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = profiles,
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
            completedSetRepository = FakeCompletedSetRepository(),
        )
        return Triple(manager, api, tokenStorage)
    }
}
