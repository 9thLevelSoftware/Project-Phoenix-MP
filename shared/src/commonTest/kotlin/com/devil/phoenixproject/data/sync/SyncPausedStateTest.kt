package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** PR 12: the "Sync paused — subscription required" state (F-074). */
class SyncPausedStateTest {

    @Test
    fun pausingLeavesAnInFlightSyncAndOpenDecisionsAlone() {
        val kept = listOf(
            SyncState.Syncing,
            SyncState.SyncingWithProgress(pagesProcessed = 1, entitiesFetched = 2),
            SyncState.AccountMismatch(
                previousUserId = "a",
                previousUserLabel = "a",
                newUserId = "b",
                newUserLabel = "b",
            ),
            SyncState.OwnershipConflict("refused"),
        )
        kept.forEach { assertEquals(it, pausedNotPremiumState(it)) }
    }

    @Test
    fun pausingReplacesIdleSuccessAndErrorStates() {
        listOf(
            SyncState.Idle,
            SyncState.Success(1L),
            SyncState.Error("x"),
            SyncState.PartialSuccess(pushSucceeded = true, pullSucceeded = false, lastSyncTime = 1L),
            SyncState.NotAuthenticated,
        ).forEach { assertEquals(SyncState.NotPremium, pausedNotPremiumState(it)) }
    }

    @Test
    fun aRenewedSubscriptionClearsThePausedStateOnTheEntitlementRefresh() = runTest {
        val tokenStorage = PortalTokenStorage(MapSettings())
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "u@example.com"),
            ),
        )
        tokenStorage.updatePremiumStatus(false)
        // Both entitlement calls read the subscriptions table: one active EMBER row.
        val engine = MockEngine {
            respond(
                content = """[{"tier":"EMBER","status":"active"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = PortalApiClient(SupabaseConfig("https://fake.supabase.co", "anon"), tokenStorage, engine)
        val manager = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = FakeSyncRepository(),
            gamificationRepository = FakeGamificationRepository(),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = FakeUserProfileRepository(),
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
        )
        manager.markPausedNotPremium()
        assertIs<SyncState.NotPremium>(manager.syncState.value)

        // Real dispatcher: Ktor's HttpTimeout would fire instantly on runTest's virtual clock.
        withContext(Dispatchers.Default) { manager.refreshPremiumStatusFromServer() }

        assertEquals(true, tokenStorage.currentUser.value?.isPremium)
        assertIs<SyncState.Idle>(manager.syncState.value)
    }

    @Test
    fun aFailedEntitlementCheckKeepsThePausedStateEvenWhenTheCachedFlagSaysPremium() = runTest {
        val tokenStorage = PortalTokenStorage(MapSettings())
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "u@example.com"),
            ),
        )
        // A 402/403 sync paused this account while the cached entitlement is still premium.
        tokenStorage.updatePremiumStatus(true)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.ServiceUnavailable) }
        val api = PortalApiClient(SupabaseConfig("https://fake.supabase.co", "anon"), tokenStorage, engine)
        val manager = SyncManager(
            apiClient = api,
            tokenStorage = tokenStorage,
            syncRepository = FakeSyncRepository(),
            gamificationRepository = FakeGamificationRepository(),
            repMetricRepository = FakeRepMetricRepository(),
            userProfileRepository = FakeUserProfileRepository(),
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
        )
        manager.markPausedNotPremium()

        withContext(Dispatchers.Default) { manager.refreshPremiumStatusFromServer() }

        assertEquals(true, tokenStorage.currentUser.value?.isPremium) // cached flag kept on failure
        assertIs<SyncState.NotPremium>(manager.syncState.value)
    }
}
