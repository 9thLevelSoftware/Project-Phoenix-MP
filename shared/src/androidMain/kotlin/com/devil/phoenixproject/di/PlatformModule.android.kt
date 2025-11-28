package com.devil.phoenixproject.di

import android.content.Context
import com.devil.phoenixproject.data.local.DriverFactory
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.util.AndroidCsvExporter
import com.devil.phoenixproject.util.CsvExporter

actual val platformModule: Module = module {
    single { DriverFactory(androidContext()) }
    single<BleRepository> { KableBleRepository() }
    single<Settings> {
        val preferences = androidContext().getSharedPreferences("vitruvian_preferences", Context.MODE_PRIVATE)
        SharedPreferencesSettings(preferences)
    }
    single<CsvExporter> { AndroidCsvExporter(androidContext()) }
}
