package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.PhantomBleRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication

class IosRuntimeBindingsTest {
    @Test
    fun `simulator bindings select phantom repository and ephemeral secure settings`() {
        val repository = IosRuntimeBindings.createBleRepository()
        val settings = IosRuntimeBindings.createSecureSettings(MapSettings())

        assertTrue(repository is PhantomBleRepository)
        settings.putString("portal_auth_token", "simulator-token")
        assertEquals("simulator-token", settings.getStringOrNull("portal_auth_token"))

        val nextProcessSettings = IosRuntimeBindings.createSecureSettings(MapSettings())
        assertNull(nextProcessSettings.getStringOrNull("portal_auth_token"))
    }

    @Test
    fun `simulator koin binding resolves portal token storage without keychain`() {
        val first = koinApplication {
            modules(platformModule, syncModule)
        }
        try {
            val repository = first.koin.get<BleRepository>()
            val settings = first.koin.get<Settings>(SecureSettingsQualifier)
            val tokenStorage = first.koin.get<PortalTokenStorage>()

            assertTrue(repository is PhantomBleRepository)
            assertTrue(tokenStorage.getToken() == null)
            settings.putString("portal_auth_token", "ephemeral-token")
            assertEquals("ephemeral-token", tokenStorage.getToken())
        } finally {
            first.close()
        }

        val second = koinApplication {
            modules(platformModule, syncModule)
        }
        try {
            assertNull(second.koin.get<PortalTokenStorage>().getToken())
        } finally {
            second.close()
        }
    }
}
