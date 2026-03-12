package com.devil.phoenixproject.di

import android.content.Context
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.util.AndroidCsvExporter
import com.devil.phoenixproject.util.AndroidDataBackupManager
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
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
    // Plugin-based BleRepository resolution
    factory<BleRepository> {
        val pluginContext: VendorPluginContext = get()
        VendorPluginRegistry.createBleRepository(pluginContext)
    }
    single<CsvExporter> { AndroidCsvExporter(androidContext()) }
    single<DataBackupManager> { AndroidDataBackupManager(androidContext(), get()) }
    single { ConnectivityChecker(androidContext()) }
}
