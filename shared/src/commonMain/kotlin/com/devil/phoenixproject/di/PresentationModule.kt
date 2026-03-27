package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.ExerciseDetectionManager
import com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModel
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import com.devil.phoenixproject.util.DataBackupManager
import org.koin.dsl.module

private val log = Logger.withTag("PresentationModule")

val presentationModule = module {
    // Exercise Detection Manager (per-session, not singleton)
    factory { ExerciseDetectionManager(get(), get(), get(), get()) }

    // ViewModels — explicit resolution with diagnostic logging to identify crash source.
    // iOS SIGABRT in InstanceFactory.create wraps the real exception; this unwraps it.
    factory {
        log.i { "MainViewModel: resolving dependencies..." }
        val bleRepo = runCatching { get<BleRepository>() }.onFailure { log.e(it) { "FAILED: BleRepository" } }.getOrThrow()
        val workoutRepo = runCatching { get<WorkoutRepository>() }.onFailure { log.e(it) { "FAILED: WorkoutRepository" } }.getOrThrow()
        val exerciseRepo = runCatching { get<ExerciseRepository>() }.onFailure { log.e(it) { "FAILED: ExerciseRepository" } }.getOrThrow()
        val prRepo = runCatching { get<PersonalRecordRepository>() }.onFailure { log.e(it) { "FAILED: PersonalRecordRepository" } }.getOrThrow()
        val repCounter = runCatching { get<RepCounterFromMachine>() }.onFailure { log.e(it) { "FAILED: RepCounterFromMachine" } }.getOrThrow()
        val prefs = runCatching { get<PreferencesManager>() }.onFailure { log.e(it) { "FAILED: PreferencesManager" } }.getOrThrow()
        val gamificationRepo = runCatching { get<GamificationRepository>() }.onFailure { log.e(it) { "FAILED: GamificationRepository" } }.getOrThrow()
        val cycleRepo = runCatching { get<TrainingCycleRepository>() }.onFailure { log.e(it) { "FAILED: TrainingCycleRepository" } }.getOrThrow()
        val completedSetRepo = runCatching { get<CompletedSetRepository>() }.onFailure { log.e(it) { "FAILED: CompletedSetRepository" } }.getOrThrow()
        val syncTrigger = runCatching { get<SyncTriggerManager>() }.onFailure { log.e(it) { "FAILED: SyncTriggerManager" } }.getOrThrow()
        val repMetricRepo = runCatching { get<RepMetricRepository>() }.onFailure { log.e(it) { "FAILED: RepMetricRepository" } }.getOrThrow()
        val bioRepo = runCatching { get<BiomechanicsRepository>() }.onFailure { log.e(it) { "FAILED: BiomechanicsRepository" } }.getOrThrow()
        val resolveWeights = runCatching { get<ResolveRoutineWeightsUseCase>() }.onFailure { log.e(it) { "FAILED: ResolveRoutineWeightsUseCase" } }.getOrThrow()
        val detectionMgr = runCatching { get<ExerciseDetectionManager>() }.onFailure { log.e(it) { "FAILED: ExerciseDetectionManager" } }.getOrThrow()
        val backupMgr = runCatching { get<DataBackupManager>() }.onFailure { log.e(it) { "FAILED: DataBackupManager" } }.getOrThrow()
        val profileRepo = runCatching { get<UserProfileRepository>() }.onFailure { log.e(it) { "FAILED: UserProfileRepository" } }.getOrThrow()
        val healthInt = runCatching { get<HealthIntegration>() }.onFailure { log.e(it) { "FAILED: HealthIntegration" } }.getOrThrow()
        val extActivityRepo = runCatching { get<ExternalActivityRepository>() }.onFailure { log.e(it) { "FAILED: ExternalActivityRepository" } }.getOrThrow()
        log.i { "MainViewModel: all dependencies resolved, constructing..." }
        MainViewModel(bleRepo, workoutRepo, exerciseRepo, prRepo, repCounter, prefs,
            gamificationRepo, cycleRepo, completedSetRepo, syncTrigger, repMetricRepo,
            bioRepo, resolveWeights, detectionMgr, backupMgr, profileRepo, healthInt, extActivityRepo)
    }
    factory { ConnectionLogsViewModel() }
    factory { CycleEditorViewModel(get()) }
    factory { GamificationViewModel(get(), get()) }
    factory { IntegrationsViewModel(get(), get(), get(), get(), get(), get()) }
    factory { AssessmentViewModel(get(), get(), get()) }
    // ThemeViewModel as singleton - app-wide theme state that must persist
    single { ThemeViewModel(get()) }
    // EulaViewModel as singleton - tracks EULA acceptance across app lifecycle
    single { EulaViewModel(get()) }

    // Sync UI
    factory { LinkAccountViewModel(get()) }
}
