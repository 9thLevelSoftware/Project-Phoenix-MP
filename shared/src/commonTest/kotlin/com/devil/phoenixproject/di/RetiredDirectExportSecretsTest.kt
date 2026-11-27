package com.devil.phoenixproject.di

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetiredDirectExportSecretsTest {
    @Test
    fun directUpgradeFrom111RemovesAllProfileGenerationsAndPreservesOtherSettings() {
        val settings = MapSettings().apply {
            putString("intervals.installationId", "installation")
            putString("intervals.apiKey.profile-a.1", "retired-a")
            putString("intervals.apiKey.profile-a.2", "retired-rotated")
            putString("intervals.apiKey.profile-b.1", "retired-b")
            putString("portal_auth_token", "current-auth")
            putString("portal_refresh_token", "current-refresh")
            putLong("portal_token_expires_at", 42L)
            putBoolean("portal_user_is_premium", true)
            putString("units", "kg")
            putString("intervals.apiKeyUnrelated", "keep")
        }

        purgeRetiredDirectExportSecrets(settings)
        purgeRetiredDirectExportSecrets(settings)

        assertFalse(settings.hasKey("intervals.installationId"))
        assertFalse(settings.keys.any { it.startsWith("intervals.apiKey.") })
        assertEquals("current-auth", settings.getStringOrNull("portal_auth_token"))
        assertEquals("current-refresh", settings.getStringOrNull("portal_refresh_token"))
        assertEquals(42L, settings.getLongOrNull("portal_token_expires_at"))
        assertTrue(settings.getBoolean("portal_user_is_premium", false))
        assertEquals("kg", settings.getStringOrNull("units"))
        assertEquals("keep", settings.getStringOrNull("intervals.apiKeyUnrelated"))
    }

    @Test
    fun failedDeletionCanBeRetriedWithoutLosingPortalAuth() {
        val backing = MapSettings().apply {
            putString("intervals.apiKey.profile.1", "retired")
            putString("portal_auth_token", "current-auth")
        }
        var failRemoval = true
        val settings = object : Settings by backing {
            override fun remove(key: String) {
                if (failRemoval) error("secure storage unavailable")
                backing.remove(key)
            }
        }

        purgeRetiredDirectExportSecrets(settings)
        assertTrue(backing.hasKey("intervals.apiKey.profile.1"))
        assertEquals("current-auth", backing.getStringOrNull("portal_auth_token"))

        failRemoval = false
        purgeRetiredDirectExportSecrets(settings)
        assertFalse(backing.hasKey("intervals.apiKey.profile.1"))
        assertEquals("current-auth", backing.getStringOrNull("portal_auth_token"))
    }
}
