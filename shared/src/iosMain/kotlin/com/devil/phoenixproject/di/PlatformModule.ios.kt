package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.repository.simulator.SimulatorBleRepository
import com.devil.phoenixproject.data.sync.SupabaseConfig
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
            anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String ?: ""
        )
    }
    single { DriverFactory() }
    single<Settings> {
        val defaults = NSUserDefaults.standardUserDefaults
        NSUserDefaultsSettings(defaults)
    }
    // Conditional BleRepository - use simulator when unlocked in preferences
    factory<BleRepository> {
        val prefs: PreferencesManager = get()
        if (prefs.isSimulatorModeUnlocked()) {
            SimulatorBleRepository()
        } else {
            KableBleRepository()
        }
    }
    single<CsvExporter> { IosCsvExporter() }
    single<CsvImporter> { IosCsvImporter() }
    single<DataBackupManager> { IosDataBackupManager(get()) }
    single { ConnectivityChecker() }
}
