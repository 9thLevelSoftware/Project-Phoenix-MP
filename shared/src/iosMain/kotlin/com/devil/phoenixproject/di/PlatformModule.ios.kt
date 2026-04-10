package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.domain.voice.IosSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
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
        SupabaseConfig(
            url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String ?: "",
            anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String ?: "",
        )
    }
    single { DriverFactory() }
    single<Settings> {
        val defaults = NSUserDefaults.standardUserDefaults
        NSUserDefaultsSettings(defaults)
    }
    // iOS apps are sandboxed; use the same Settings for secure storage.
    // Keychain-backed storage is a future enhancement.
    single<Settings>(SecureSettingsQualifier) { get() }
    single<BleRepository> { KableBleRepository() }
    single<CsvExporter> { IosCsvExporter() }
    single<CsvImporter> { IosCsvImporter(get()) }
    single<DataBackupManager> { IosDataBackupManager(get()) }
    single { ConnectivityChecker() }
    single<SafeWordListenerFactory> { IosSafeWordListenerFactory() }
    single { HealthIntegration() }
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
            detectionManager = get(),
            dataBackupManager = get(),
            userProfileRepository = get(),
            healthIntegration = get(),
            externalActivityRepository = get(),
            workoutServiceController = get(),
        )
    }
}
