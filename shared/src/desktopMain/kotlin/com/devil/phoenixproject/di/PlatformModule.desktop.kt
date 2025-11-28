package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DriverFactory
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.prefs.Preferences

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.StubBleRepository
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.DesktopCsvExporter

actual val platformModule: Module = module {
    single { DriverFactory() }
    single<BleRepository> { StubBleRepository() }
    single<Settings> {
        val preferences = Preferences.userRoot().node("vitruvian_preferences")
        PreferencesSettings(preferences)
    }
    single<CsvExporter> { DesktopCsvExporter() }
}
