package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.data.sync.SyncTriggerTarget
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** Avoid constructing Keychain-backed cloud services in a build with cloud sync disabled. */
internal object CloudUnavailableSyncTarget : SyncTriggerTarget {
    override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(false)
    override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.NotAuthenticated)
    override val currentUser: StateFlow<PortalUser?> = MutableStateFlow(null)
    override val lastSyncTime: StateFlow<Long> = MutableStateFlow(0L)

    override suspend fun sync(): Result<Long> = Result.failure(
        IllegalStateException("Cloud Sync is unavailable because this build has no server configuration."),
    )

    override suspend fun refreshPremiumStatusFromServer() = Unit
    override fun markPausedNotPremium() = Unit
}

internal fun offlineSyncTriggerManager(): SyncTriggerManager =
    SyncTriggerManager(syncManager = CloudUnavailableSyncTarget, isOnline = { false })

/**
 * Same shared core as the phone. Only this workout consumer receives the manual-start
 * profile view; auth/sync repositories retain their original, unmodified profile data.
 * Completion uses the shared post-save trigger. TV account hooks gate automatic sync
 * until migrations and identity resolution are ready and first sync was explicit.
 */
internal fun createTvMainViewModel(
    koin: Koin,
    scope: CoroutineScope,
    syncTrigger: SyncTriggerManager = koin.get(),
): MainViewModel = MainViewModel(
    bleRepository = koin.get(),
    workoutRepository = koin.get(),
    exerciseRepository = koin.get(),
    personalRecordRepository = koin.get(),
    profileExerciseBaselineRepository = koin.get(),
    repCounter = koin.get(),
    preferencesManager = koin.get(),
    gamificationRepository = koin.get(),
    trainingCycleRepository = koin.get(),
    completedSetRepository = koin.get(),
    activeWorkoutRuntimeRepository = koin.get(),
    dropSetEligibilityPolicy = koin.get(),
    syncTriggerManager = syncTrigger,
    resolveWeightsUseCase = koin.get(),
    applyRoutineModifierUseCase = koin.get(),
    recommendWeightAdjustmentUseCase = koin.get(),
    equipmentRackRepository = koin.get(),
    applyEquipmentRackLoadUseCase = koin.get(),
    dataBackupManager = koin.get(),
    userProfileRepository = TvManualStartProfileRepository(koin.get(), scope),
    healthIntegration = koin.getOrNull(),
    externalActivityRepository = koin.get(),
    workoutServiceController = koin.get(),
    healthExportCursorRepository = koin.get(),
    computeVelocityOneRepMaxUseCase = koin.get(),
    recordPersonalMvtSampleUseCase = koin.get(),
    velocityOneRepMaxRepository = koin.get(),
    countVelocityOneRepMaxImprovementsUseCase = koin.get(),
    backfillVelocityOneRepMaxUseCase = koin.get(),
    machineSafetyCoordinator = koin.get(),
    profileRecoveryActivityTracker = koin.get(),
    recentJustLiftExerciseStore = koin.get(),
)
