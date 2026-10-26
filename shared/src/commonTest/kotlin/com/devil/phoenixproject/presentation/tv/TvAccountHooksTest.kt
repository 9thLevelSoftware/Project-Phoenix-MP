package com.devil.phoenixproject.presentation.tv

import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.migration.*
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.*
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.testutil.*
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvAccountHooksTest {
    @Test
    fun realTvCompletionPersistsLocallyButCannotSyncAcrossPendingAccountIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture()
        val fixture = TvCoreFixture(backgroundScope, h.tv.syncTrigger, h.profiles)
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope, h.tv.syncTrigger)
        try {
            assertTrue(h.tv.login("b@example.com", "test-password"))
            advanceUntilIdle()
            assertIs<SyncState.AccountMismatch>(h.manager.syncState.value)
            val tv = TvSessionController(main.workoutSessionManager, main.bleConnectionManager, backgroundScope) {
                main.autoplayEnabled.first { !it }
            }
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
            runCurrent()
            assertTrue(tv.startRoutineSet())
            advanceTimeBy(35_000); runCurrent()
            assertTrue(tv.confirmBodyweightReps(5, 0))
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, fixture.workouts.allSessions().size)
            assertEquals(0, h.api.pushCallCount)
            assertEquals(0, h.api.pullCallCount)
            assertEquals("owner-a", h.profiles.activeProfile.value?.supabaseUserId)
            assertIs<SyncState.AccountMismatch>(h.manager.syncState.value)
        } finally { main.viewModelScope.cancel(); fixture.close(); h.close(); Dispatchers.resetMain() }
    }

    @Test
    fun migrationGatePrecedesLoginIdentityChoiceAndExplicitSync() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture()
        try {
            h.migrationState.value = RequiredMigrationState.Applying
            assertFalse(h.tv.login("b@example.com", "test-password"))
            assertFalse(h.tv.syncNow())
            assertFalse(h.tv.resolveAccountMismatch(AccountSwitchChoice.EXCLUDE_ALL_EXISTING))
            runCurrent()
            assertEquals(0, h.api.signInCallCount)
            assertEquals(0, h.api.pullCallCount)
            h.migrationState.value = RequiredMigrationState.Ready
            assertTrue(h.tv.login("b@example.com", "test-password"))
            runCurrent()
            assertIs<SyncState.AccountMismatch>(h.manager.syncState.value)
            assertEquals("owner-a", h.profiles.activeProfile.value?.supabaseUserId)
            assertFalse(h.tv.syncNow())
            h.tv.onForeground()
            h.tv.syncTrigger.onWorkoutCompleted()
            assertEquals(0, h.api.pushCallCount)
            assertEquals(0, h.api.pullCallCount)
            assertTrue(h.tv.resolveAccountMismatch(AccountSwitchChoice.EXCLUDE_ALL_EXISTING))
            runCurrent()
            assertEquals("owner-b", h.profiles.activeProfile.value?.supabaseUserId)
            assertEquals(0, h.api.pullCallCount)
            h.tv.onForeground()
            h.tv.syncTrigger.onWorkoutCompleted()
            assertEquals(0, h.api.pullCallCount, "Identity resolution is not consent to first sync")
            assertTrue(h.tv.syncNow())
            advanceUntilIdle()
            assertTrue(h.api.pullCallCount > 0)
            assertIs<SyncState.Success>(h.manager.syncState.value)
        } finally { h.close(); Dispatchers.resetMain() }
    }

    @Test
    fun reloginRequiresExplicitFirstSyncEvenWhenSameAccountHasPriorSyncTimestamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture(owner = "owner-b")
        try {
            assertTrue(h.tv.login("b@example.com", "test-password"))
            advanceUntilIdle()
            assertTrue(h.tv.syncNow())
            advanceUntilIdle()
            assertTrue(h.manager.lastSyncTime.value > 0)
            assertTrue(h.tv.login("b@example.com", "test-password"))
            advanceUntilIdle()
            val pulls = h.api.pullCallCount
            h.tv.onForeground()
            h.tv.syncTrigger.onWorkoutCompleted()
            assertEquals(pulls, h.api.pullCallCount)
            assertTrue(h.tv.syncNow())
            advanceUntilIdle()
            assertTrue(h.api.pullCallCount > pulls)
            h.training = true
            assertFalse(h.tv.login("other@example.com", "test-password"))
            assertFalse(h.tv.syncNow())
            assertFalse(h.tv.signOut())
        } finally { h.close(); Dispatchers.resetMain() }
    }

    @Test
    fun tvCompletionUsesAuthoritativeMissingAndFailedAckHandling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture(owner = "owner-b")
        val fixture = TvCoreFixture(backgroundScope, h.tv.syncTrigger, h.profiles)
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope, h.tv.syncTrigger)
        try {
            assertTrue(h.tv.login("b@example.com", "test-password"))
            advanceUntilIdle()
            assertTrue(h.tv.syncNow())
            advanceUntilIdle()
            h.pending = true
            val tv = TvSessionController(main.workoutSessionManager, main.bleConnectionManager, backgroundScope) {
                main.autoplayEnabled.first { !it }
            }
            runCurrent()
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
            runCurrent()
            assertTrue(tv.startRoutineSet())
            advanceTimeBy(35_000); runCurrent()
            assertIs<WorkoutState.BodyweightRepEntry>(main.workoutState.value)
            assertTrue(tv.confirmBodyweightReps(5, 0))
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, fixture.workouts.allSessions().size)
            assertTrue(h.api.pushPayloads.any { it.workoutDeletions.isNotEmpty() })
            assertTrue(h.pending, "Missing ACK cannot retire the pending operation")
            val pulls = h.api.pullCallCount
            h.api.pushResult = Result.failure(PortalApiException("offline"))
            h.tv.syncTrigger.onWorkoutCompleted()
            assertTrue(h.pending)
            assertEquals(pulls, h.api.pullCallCount)
            h.api.pushResult = Result.success(PortalSyncPushResponse(syncTime = "2026-09-20T12:00:00Z", acknowledgedWorkoutDeletionIds = listOf("delete-a")))
            h.tv.syncTrigger.onWorkoutCompleted()
            assertFalse(h.pending)
            assertEquals("owner-b", h.acknowledgedOwner)
        } finally { main.viewModelScope.cancel(); fixture.close(); h.close(); Dispatchers.resetMain() }
    }
}

private class AccountFixture(owner: String = "owner-a") {
    val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest(supabaseUserId = owner) }
    val storage = PortalTokenStorage(MapSettings())
    val api = object : FakePortalApiClient() {
        override suspend fun fetchSubscriptionEntitlement() = Result.success(SubscriptionEntitlement(true, "premium"))
    }.apply {
        signInResult = Result.success(GoTrueAuthResponse(accessToken = "test-access", tokenType = "bearer", expiresIn = 3600,
            expiresAt = currentTimeMillis() / 1000 + 3600, refreshToken = "test-refresh", user = GoTrueUser("owner-b", "b@example.com")))
    }
    val migrationState = MutableStateFlow<RequiredMigrationState>(RequiredMigrationState.Ready)
    val migrations = object : RequiredMigrationGate {
        override val requiredMigrationState = migrationState
        override suspend fun awaitRequiredMigrations() { requiredMigrationState.first { it is RequiredMigrationState.Ready } }
    }
    var pending = false
    var acknowledgedOwner: String? = null
    var training = false
    val manager = SyncManager(api, storage, FakeSyncRepository(), FakeGamificationRepository(), FakeRepMetricRepository(), profiles,
        FakeProfilePreferenceSyncRepository(), FakeExternalActivityRepository(), FakeVelocityOneRepMaxRepository(),
        isProfilePreferenceMigrationReady = { migrations.requiredMigrationState.value is RequiredMigrationState.Ready },
        workoutDeletionRepository = object : WorkoutDeletionRepository {
            override suspend fun pendingForOwner(ownerUserId: String) = if (!pending) emptyList() else listOf(WorkoutDeletionMutation(
                mutationId = "delete-a", ownerUserId = "owner-b", profileId = "default", scope = WorkoutDeletionScope.WORKOUT,
                portalSessionId = "00000000-0000-4000-8000-000000000001", componentSessionId = null,
                deletedAt = 1L, acknowledgedAt = null, source = WorkoutDeletionSource.LOCAL,
            ))
            override suspend fun acknowledge(ownerUserId: String, mutationIds: Set<String>, acknowledgedAt: Long) {
                if ("delete-a" in mutationIds) { pending = false; acknowledgedOwner = ownerUserId }
            }
        },
    )
    private val auth = object : AuthRepository {
        override val authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
        override val currentUser: AuthUser? = null
        override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> = error("TV must use shared password login")
        override suspend fun signInWithGoogle(): Result<AuthUser> = error("Not a TV action")
        override suspend fun signInWithApple(): Result<AuthUser> = error("Not a TV action")
        override suspend fun signOut() = Result.success(Unit)
        override suspend fun refreshSession() = Result.success(Unit)
    }
    val link = LinkAccountViewModel(manager, auth)
    val tv = TvAccountHooks(link, manager, { true }, migrations, { training })
    fun close() { link.clear() }
}
