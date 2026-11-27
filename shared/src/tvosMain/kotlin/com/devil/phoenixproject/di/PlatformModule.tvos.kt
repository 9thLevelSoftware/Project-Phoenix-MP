package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.garmin.GarminCompanionTransport
import com.devil.phoenixproject.data.garmin.NoOpGarminCompanionTransport
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.tv.offlineSyncTriggerManager
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.TvosUnavailableDataBackupManager
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    clearKeychainOnFreshInstall()

    single {
        val bundle = NSBundle.mainBundle
        val url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String
        val anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String
        if (url.isNullOrBlank() || anonKey.isNullOrBlank()) {
            Logger.w { "tvOS Cloud Sync is unavailable because Supabase configuration is not present in this build." }
            SupabaseConfig(url = url.orEmpty(), anonKey = anonKey.orEmpty())
        } else {
            SupabaseConfig(url = url, anonKey = anonKey)
        }
    }
    single { DriverFactory() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    @OptIn(ExperimentalSettingsImplementation::class)
    single<Settings>(SecureSettingsQualifier) {
        val secure = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
        migrateTokensToKeychain(get(), secure)
        secure
    }
    single { OAuthLauncher() }
    single<BleRepository> { createTvosBleRepository(get()) }
    single(createdAtStart = true) { ConnectivityChecker() }
    single { HealthIntegration() }
    single<HealthWorkoutWriter> { get<HealthIntegration>() }
    single<DataBackupManager> { TvosUnavailableDataBackupManager() }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    // Garmin watch-button companion transport (issue #1391): tvOS is a clean no-op;
    // Garmin controls are phone-only and tvOS product functionality stays out.
    single<GarminCompanionTransport> { NoOpGarminCompanionTransport() }
    single {
        val cloud = get<SupabaseConfig>()
        val cloudConfigured = cloud.url.isNotBlank() && cloud.anonKey.isNotBlank()
        MainViewModel(
            bleRepository = get(),
            workoutRepository = get(),
            exerciseRepository = get(),
            personalRecordRepository = get(),
            profileExerciseBaselineRepository = get(),
            repCounter = get(),
            preferencesManager = get(),
            gamificationRepository = get(),
            trainingCycleRepository = get(),
            completedSetRepository = get(),
            activeWorkoutRuntimeRepository = get(),
            dropSetEligibilityPolicy = get(),
            syncTriggerManager = if (cloudConfigured) {
                get<SyncTriggerManager>()
            } else {
                offlineSyncTriggerManager()
            },
            resolveWeightsUseCase = get(),
            applyRoutineModifierUseCase = get(),
            recommendWeightAdjustmentUseCase = get(),
            equipmentRackRepository = get(),
            applyEquipmentRackLoadUseCase = get(),
            dataBackupManager = get(),
            userProfileRepository = get(),
            healthIntegration = get(),
            externalActivityRepository = get(),
            workoutServiceController = get(),
            healthExportCursorRepository = get(),
            computeVelocityOneRepMaxUseCase = get(),
            recordPersonalMvtSampleUseCase = get(),
            velocityOneRepMaxRepository = get(),
            countVelocityOneRepMaxImprovementsUseCase = get(),
            backfillVelocityOneRepMaxUseCase = get(),
            machineSafetyCoordinator = get(),
            profileRecoveryActivityTracker = get(),
            recentJustLiftExerciseStore = get(),
        )
    }
}
