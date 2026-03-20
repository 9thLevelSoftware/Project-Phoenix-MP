package com.devil.phoenixproject.di

import android.content.Context
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.repository.simulator.SimulatorBleRepository
import com.devil.phoenixproject.util.AndroidCsvExporter
import com.devil.phoenixproject.util.AndroidCsvImporter
import com.devil.phoenixproject.util.AndroidDataBackupManager
import com.devil.phoenixproject.domain.voice.AndroidSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single { DriverFactory(androidContext()) }
    single<Settings> {
        val preferences = androidContext().getSharedPreferences("vitruvian_preferences", Context.MODE_PRIVATE)
        SharedPreferencesSettings(preferences)
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
    single<CsvExporter> { AndroidCsvExporter(androidContext()) }
    single<CsvImporter> { AndroidCsvImporter(androidContext(), get()) }
    single<DataBackupManager> { AndroidDataBackupManager(androidContext(), get()) }
    single { ConnectivityChecker(androidContext()) }
    single<SafeWordListenerFactory> { AndroidSafeWordListenerFactory(androidContext()) }
}
