package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.Settings
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
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
}
