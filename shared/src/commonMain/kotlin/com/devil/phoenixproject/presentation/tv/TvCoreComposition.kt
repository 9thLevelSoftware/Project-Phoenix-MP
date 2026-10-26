package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin

/**
 * Same shared core as the phone. Only this workout consumer receives the manual-start
 * profile view; auth/sync repositories retain their original, unmodified profile data.
 * Completion uses the shared post-save trigger. TV account hooks gate automatic sync
 * until migrations and identity resolution are ready and first sync was explicit.
 */
internal fun createTvMainViewModel(
    koin: Koin,
    scope: CoroutineScope,
    syncTrigger: com.devil.phoenixproject.data.sync.SyncTriggerManager = koin.get(),
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
