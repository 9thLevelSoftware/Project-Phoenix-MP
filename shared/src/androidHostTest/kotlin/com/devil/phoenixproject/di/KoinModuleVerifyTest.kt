package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.repository.simulator.SimulatorBleRepository
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.verify.verify

@OptIn(KoinExperimentalAPI::class)
class KoinModuleVerifyTest {

    @Test
    fun verifyAppModule() {
        appModule.verify(
            extraTypes = listOf(
                // Types provided by platformModule (not included in appModule)
                DriverFactory::class,
                Settings::class,
                BleRepository::class,
                CsvExporter::class,
                DataBackupManager::class,
                ConnectivityChecker::class,
                // Lambda types used in constructor injection (e.g. PortalApiClient tokenProvider)
                Function0::class,
            )
        )
    }

    @Test
    fun verifyVendorPluginPermutations() {
        assertBleRepositoryForVendor(
            pluginId = VendorPluginRegistry.defaultPlugin.id,
            expectedRepositoryType = KableBleRepository::class
        )
        assertBleRepositoryForVendor(
            pluginId = VendorPluginRegistry.simulatorPlugin.id,
            expectedRepositoryType = SimulatorBleRepository::class
        )
        assertBleRepositoryForVendor(
            pluginId = "unknown-vendor",
            expectedRepositoryType = KableBleRepository::class,
            expectFallback = true
        )
    }

    private fun assertBleRepositoryForVendor(
        pluginId: String,
        expectedRepositoryType: kotlin.reflect.KClass<out BleRepository>,
        expectFallback: Boolean = false
    ) {
        stopKoin()

        startKoin {
            modules(
                module {
                    single<PreferencesManager> {
                        val mapSettings = MapSettings()
                        val manager = SettingsPreferencesManager(mapSettings)
                        runBlocking { manager.setSelectedVendorId(pluginId) }
                        manager
                    }
                    single {
                        val preferencesManager: PreferencesManager = get()
                        VendorPluginRegistry.resolve(preferencesManager.getSelectedVendorId())
                    }
                    factory<BleRepository> {
                        val pluginContext: VendorPluginContext = get()
                        VendorPluginRegistry.createBleRepository(pluginContext)
                    }
                }
            )
        }

        val koin = org.koin.core.context.GlobalContext.get().koin
        val pluginContext = koin.get<VendorPluginContext>()
        val bleRepository = koin.get<BleRepository>()

        assertEquals(expectFallback, pluginContext.usedFallback)
        assertEquals(expectedRepositoryType, bleRepository::class)

        stopKoin()
    }
}
