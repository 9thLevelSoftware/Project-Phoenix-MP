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
    fun deferredSignInBlocksTrainingUntilActualSharedLoginCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture(owner = "owner-b")
        val response = CompletableDeferred<Result<GoTrueAuthResponse>>()
        h.signInResponse = response
        val fixture = TvCoreFixture(backgroundScope, h.tv.syncTrigger, h.profiles)
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope, h.tv.syncTrigger)
        val tv = h.controller(main, backgroundScope)
        try {
            runCurrent()
            fixture.ble.simulateConnect("Vee_Test")
            main.updateWorkoutParameters(WorkoutParameters(programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool, reps = 0, weightPerCableKg = 20f, selectedExerciseId = "cable", isJustLift = true))
            runCurrent()
            assertIs<ConnectionState.Connected>(main.connectionState.value)
            assertTrue(h.tv.login("b@example.com", "test-password"))
            // Admission must happen before the VM's launch gets a scheduler turn.
            assertFalse(tv.beginRoutine(tvBodyweightRoutine()))
            assertFalse(tv.startJustLift())
            runCurrent()
            assertEquals(1, h.api.signInCallCount)
            assertFalse(tv.beginRoutine(tvBodyweightRoutine()))
            assertFalse(tv.startRoutineSet())
            assertFalse(tv.startJustLift())
            assertFalse(h.tv.login("duplicate@example.com", "test-password"))
            assertFalse(h.tv.signOut())
            assertFalse(h.tv.resolveAccountMismatch(AccountSwitchChoice.EXCLUDE_ALL_EXISTING))
            assertNull(main.loadedRoutine.value)
            assertTrue(fixture.ble.programCommands.isEmpty())
            h.entitlementRelease = CompletableDeferred()
            response.complete(h.api.signInResult!!)
            runCurrent()
            assertTrue(h.link.isAuthenticated.value)
            assertFalse(tv.beginRoutine(tvBodyweightRoutine()), "Identity publication is not Job completion")
            assertFalse(tv.startJustLift())
            h.entitlementRelease!!.complete(Unit)
            runCurrent()
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
            runCurrent()
            assertTrue(tv.startRoutineSet(), "The training owner may start its next set")
        } finally { main.viewModelScope.cancel(); fixture.close(); h.close(); Dispatchers.resetMain() }
    }

    @Test
    fun failedAndCancelledSharedLoginReleaseTrainingAdmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (cancel in listOf(false, true)) {
                assertTrue(backgroundScope.isActive, "Previous case must not cancel the test's background scope; cancel=$cancel")
                // The rack repository owns and cancels the scope supplied to TvCoreFixture.
                // Give each composition its own child, not the shared runTest background scope.
                val caseJob = Job(backgroundScope.coroutineContext[Job])
                val caseScope = CoroutineScope(backgroundScope.coroutineContext + caseJob)
                val h = AccountFixture(owner = "owner-b")
                h.signInResponse = CompletableDeferred()
                val fixture = TvCoreFixture(caseScope, h.tv.syncTrigger, h.profiles)
                val main = createTvMainViewModel(fixture.koin.koin, caseScope, h.tv.syncTrigger)
                val tv = h.controller(main, caseScope)
                try {
                    runCurrent()
                    assertTrue(h.tv.login("b@example.com", "test-password"))
                    runCurrent()
                    assertFalse(tv.beginRoutine(tvBodyweightRoutine()))
                    if (cancel) h.link.clear()
                    else h.signInResponse!!.complete(Result.failure(PortalApiException("rejected")))
                    runCurrent()
                    assertFalse(h.link.isAuthenticated.value)
                    assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
                    runCurrent()
                    assertNotNull(main.loadedRoutine.value, "cancel=$cancel: accepted training must actually load; preparing=${tv.preparing}, error=${tv.error}")
                    assertIs<RoutineFlowState.SetReady>(main.routineFlowState.value)
                    assertFalse(tv.preparing)
                    tv.requestExit()
                    runCurrent()
                    assertTrue(tv.confirmExit(), "cancel=$cancel: state=${main.workoutState.value}, error=${tv.error}, preparing=${tv.preparing}, loadedRoutine=${main.loadedRoutine.value}, teardown=${main.workoutSessionManager.machineTeardownState.value}, pending=${tv.exitConfirmationPending}, scope=$caseJob")
                    assertFalse(tv.sessionOpen)
                    assertTrue(h.admission.tryAccountMutation(), "Confirmed exit must release training")
                    h.admission.releaseAccountMutation()
                } finally {
                    main.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                    fixture.close()
                    caseJob.join()
                    h.close()
                }
                assertTrue(backgroundScope.isActive, "Closing cancel=$cancel composition must not cancel its parent")
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun cancelledLoginKeepsAdmissionUntilItsActualJobFinishesCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture(owner = "owner-b")
        h.signInResponse = CompletableDeferred()
        val cleanup = CompletableDeferred<Unit>()
        h.signInCleanup = cleanup
        val fixture = TvCoreFixture(backgroundScope, h.tv.syncTrigger, h.profiles)
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope, h.tv.syncTrigger)
        val tv = h.controller(main, backgroundScope)
        try {
            assertTrue(h.tv.login("b@example.com", "test-password"))
            runCurrent()
            assertEquals(1, h.api.signInCallCount)
            h.link.clear()
            runCurrent()
            assertFalse(tv.beginRoutine(tvBodyweightRoutine()), "Cancellation request is not Job completion")
            assertFalse(h.tv.signOut())
            cleanup.complete(Unit)
            runCurrent()
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
        } finally { cleanup.complete(Unit); main.viewModelScope.cancel(); fixture.close(); h.close(); Dispatchers.resetMain() }
    }

    @Test
    fun logoutAndMismatchRemainExclusiveUntilSharedMutationBarrierCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (logout in listOf(false, true)) {
                assertTrue(backgroundScope.isActive, "Previous case must not cancel the test's background scope; logout=$logout")
                val caseJob = Job(backgroundScope.coroutineContext[Job])
                val caseScope = CoroutineScope(backgroundScope.coroutineContext + caseJob)
                val h = AccountFixture()
                val fixture = TvCoreFixture(caseScope, h.tv.syncTrigger, h.profiles)
                val main = createTvMainViewModel(fixture.koin.koin, caseScope, h.tv.syncTrigger)
                val tv = h.controller(main, caseScope)
                val release = CompletableDeferred<Unit>()
                try {
                    assertTrue(h.tv.login("b@example.com", "test-password"))
                    runCurrent()
                    assertIs<SyncState.AccountMismatch>(h.link.syncState.value)
                    val locked = CompletableDeferred<Unit>()
                    val blocker = caseScope.launch {
                        h.barrier.withExclusive { locked.complete(Unit); release.await() }
                    }
                    runCurrent()
                    assertTrue(locked.isCompleted, "logout=$logout: blocker=$blocker, scope=$caseJob, account=${h.link.uiState.value}, sync=${h.link.syncState.value}, profile=${h.profiles.activeProfile.value}, state=${main.workoutState.value}, error=${tv.error}, preparing=${tv.preparing}, loadedRoutine=${main.loadedRoutine.value}")
                    assertTrue(blocker.isActive, "The shared mutation barrier must still be held")
                    assertTrue(if (logout) h.tv.signOut() else h.tv.resolveAccountMismatch(AccountSwitchChoice.EXCLUDE_ALL_EXISTING))
                    assertFalse(h.tv.signOut())
                    assertFalse(h.tv.resolveAccountMismatch(AccountSwitchChoice.UPLOAD_NEVER_SYNCED))
                    runCurrent()
                    assertTrue(h.link.isAuthenticated.value, "Shared operation is still waiting on the barrier")
                    assertFalse(tv.beginRoutine(tvBodyweightRoutine()))
                    assertFalse(h.tv.login("b@example.com", "test-password"))
                    release.complete(Unit)
                    blocker.join()
                    assertTrue(blocker.isCompleted && !blocker.isCancelled, "Barrier holder must finish normally: $blocker")
                    runCurrent()
                    if (logout) assertFalse(h.link.isAuthenticated.value)
                    else assertEquals("owner-b", h.profiles.activeProfile.value?.supabaseUserId)
                    assertTrue(tv.beginRoutine(tvBodyweightRoutine()))
                    // Reverse guard applies synchronously even before load starts.
                    assertFalse(h.tv.signOut())
                    assertFalse(h.tv.login("b@example.com", "test-password"))
                    assertFalse(h.tv.resolveAccountMismatch(AccountSwitchChoice.EXCLUDE_ALL_EXISTING))
                    runCurrent()
                    assertNotNull(main.loadedRoutine.value, "logout=$logout: accepted training must actually load; preparing=${tv.preparing}, error=${tv.error}")
                    assertIs<RoutineFlowState.SetReady>(main.routineFlowState.value)
                    assertFalse(tv.preparing)
                    tv.requestExit(); runCurrent()
                    assertTrue(tv.confirmExit(), "logout=$logout: state=${main.workoutState.value}, error=${tv.error}, preparing=${tv.preparing}, loadedRoutine=${main.loadedRoutine.value}, teardown=${main.workoutSessionManager.machineTeardownState.value}, pending=${tv.exitConfirmationPending}, scope=$caseJob")
                    assertTrue(h.tv.signOut(), "Actual controller exit permits the next account operation")
                    runCurrent()
                } finally {
                    release.complete(Unit)
                    main.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                    fixture.close()
                    caseJob.join()
                    h.close()
                }
                assertTrue(backgroundScope.isActive, "Closing logout=$logout composition must not cancel its parent")
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun manualAndForegroundSyncReserveUntilAwaitedOperationCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = AccountFixture(owner = "owner-b")
        val fixture = TvCoreFixture(backgroundScope, h.tv.syncTrigger, h.profiles)
        val main = createTvMainViewModel(fixture.koin.koin, backgroundScope, h.tv.syncTrigger)
        val tv = h.controller(main, backgroundScope)
        try {
            assertTrue(h.tv.login("b@example.com", "test-password")); runCurrent()
            val release = CompletableDeferred<Unit>()
            val blocker = backgroundScope.launch { h.barrier.withExclusive { release.await() } }
            runCurrent()
            assertTrue(h.tv.syncNow())
            assertFalse(h.tv.syncNow())
            runCurrent()
            assertFalse(tv.beginRoutine(tvBodyweightRoutine()))
            release.complete(Unit); blocker.join(); runCurrent()
            assertTrue(h.link.lastSyncTime.value > 0)
            h.entitlementRelease = CompletableDeferred()
            val foreground = backgroundScope.launch { h.tv.onForeground() }
            runCurrent()
            assertFalse(tv.beginRoutine(tvBodyweightRoutine()))
            assertFalse(h.tv.signOut())
            foreground.cancelAndJoin()
            assertTrue(tv.beginRoutine(tvBodyweightRoutine()), "Foreground cancellation must release admission")
        } finally { main.viewModelScope.cancel(); fixture.close(); h.close(); Dispatchers.resetMain() }
    }

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
            val tv = h.controller(main, backgroundScope)
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
            val tv = h.controller(main, backgroundScope)
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
            assertTrue(tv.sessionOpen, "Post-save sync must continue while the routine stays open")
            assertFalse(h.admission.tryAccountMutation())
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
    var signInResponse: CompletableDeferred<Result<GoTrueAuthResponse>>? = null
    var signInCleanup: CompletableDeferred<Unit>? = null
    var entitlementRelease: CompletableDeferred<Unit>? = null
    val barrier = ProfileMutationBarrier()
    val admission = TvActivityAdmission()
    private var controller: TvSessionController? = null
    val api = object : FakePortalApiClient() {
        override suspend fun signIn(email: String, password: String): Result<GoTrueAuthResponse> {
            val result = super.signIn(email, password)
            return try {
                signInResponse?.await() ?: result
            } finally {
                signInCleanup?.let { cleanup -> withContext(NonCancellable) { cleanup.await() } }
            }
        }
        override suspend fun fetchSubscriptionEntitlement(): Result<SubscriptionEntitlement> {
            entitlementRelease?.await()
            return Result.success(SubscriptionEntitlement(true, "premium"))
        }
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
        profileMutationBarrier = barrier,
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
    val tv = TvAccountHooks(link, manager, { true }, migrations, { training || controller?.sessionOpen == true }, admission)
    fun controller(main: com.devil.phoenixproject.presentation.viewmodel.MainViewModel, scope: CoroutineScope): TvSessionController =
        TvSessionController(main.workoutSessionManager, main.bleConnectionManager, scope,
            admitTraining = admission::tryTraining, releaseTraining = admission::releaseTraining,
        ) { main.autoplayEnabled.first { !it } }.also { controller = it }
    fun close() { link.clear() }
}
