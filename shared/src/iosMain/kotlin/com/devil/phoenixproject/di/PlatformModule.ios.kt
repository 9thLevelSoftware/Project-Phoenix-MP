package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.ble.BleScannerCoordinator
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.domain.voice.IosSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.BackupDestinationResolver
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.IosBackupDestinationResolver
import com.devil.phoenixproject.util.IosCsvExporter
import com.devil.phoenixproject.util.IosCsvImporter
import com.devil.phoenixproject.util.IosDataBackupManager
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    // Runs when the module is built (inside startKoin), before any single can
    // create the database; a lazily-resolved check would see the DB that this
    // launch just created and mistake a fresh install for an upgrade.
    clearKeychainOnFreshInstall()

    single {
        val bundle = NSBundle.mainBundle
        val url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String
        val anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String

        // Fail fast with clear error if Supabase config is missing
        // This usually means Supabase.xcconfig wasn't created or linked in Xcode
        require(!url.isNullOrBlank()) {
            "SUPABASE_URL not found in Info.plist. " +
                "Ensure Supabase.xcconfig exists and is linked in the Xcode project."
        }
        require(!anonKey.isNullOrBlank()) {
            "SUPABASE_ANON_KEY not found in Info.plist. " +
                "Ensure Supabase.xcconfig exists and is linked in the Xcode project."
        }

        SupabaseConfig(url = url, anonKey = anonKey)
    }
    single { DriverFactory() }
    single<Settings> {
        val defaults = NSUserDefaults.standardUserDefaults
        NSUserDefaultsSettings(defaults)
    }
    // Secure storage using iOS Keychain for auth tokens (JWT, refresh token, user identity).
    // Keychain data persists across app reinstalls and is protected by iOS Data Protection;
    // clearKeychainOnFreshInstall() above wipes it when the app itself was reinstalled.
    @OptIn(ExperimentalSettingsImplementation::class)
    single<Settings>(SecureSettingsQualifier) {
        val keychainSettings = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
        val legacySettings: Settings = get() // NSUserDefaults
        migrateTokensToKeychain(legacySettings, keychainSettings)
        purgeRetiredDirectExportSecrets(keychainSettings)
        keychainSettings
    }
    single { OAuthLauncher() }
    single<BleRepository> { KableBleRepository(get<BleScannerCoordinator>()) }
    single<CsvExporter> { IosCsvExporter() }
    single<CsvImporter> { IosCsvImporter(get()) }
    single<BackupDestinationResolver> { IosBackupDestinationResolver(get()) }
    single<DataBackupManager> { IosDataBackupManager(get(), get(), get(), get(), get(), get(), get()) }
    single(createdAtStart = true) { ConnectivityChecker() }
    single<SafeWordListenerFactory> { IosSafeWordListenerFactory() }
    single { HealthIntegration() }
    single<HealthWorkoutWriter> { get<HealthIntegration>() }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    // Issue #1358 (SD5/P3): phone-only exercise media file store (never in dataModule —
    // commonMain cannot reference a phoneMain type, F1).
    single<com.devil.phoenixproject.data.media.ExerciseMediaStore> {
        com.devil.phoenixproject.data.media.IosExerciseMediaStore()
    }
    single {
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
            syncTriggerManager = get(),
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
            hrmBleManager = getOrNull(),
        )
    }
}
