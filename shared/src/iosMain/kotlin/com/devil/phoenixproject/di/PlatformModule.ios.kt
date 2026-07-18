package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
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
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
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
    single<Settings>(SecureSettingsQualifier) {
        IosRuntimeBindings.createSecureSettings(get())
    }
    single { OAuthLauncher() }
    single<BleRepository> { IosRuntimeBindings.createBleRepository() }
    single<CsvExporter> { IosCsvExporter() }
    single<CsvImporter> { IosCsvImporter(get()) }
    single<BackupDestinationResolver> { IosBackupDestinationResolver() }
    single<DataBackupManager> { IosDataBackupManager(get(), get(), get(), get(), get()) }
    single { ConnectivityChecker() }
    single<SafeWordListenerFactory> { IosSafeWordListenerFactory() }
    single { HealthIntegration() }
    single<HealthWorkoutWriter> { get<HealthIntegration>() }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    single {
        MainViewModel(
            bleRepository = get(),
            workoutRepository = get(),
            exerciseRepository = get(),
            personalRecordRepository = get(),
            repCounter = get(),
            preferencesManager = get(),
            gamificationRepository = get(),
            trainingCycleRepository = get(),
            completedSetRepository = get(),
            syncTriggerManager = get(),
            repMetricRepository = get(),
            biomechanicsRepository = get(),
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
        )
    }
}
