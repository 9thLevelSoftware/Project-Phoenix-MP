package com.devil.phoenixproject.di

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class QualifiersTest {

    @Test
    fun `purgeRetiredDirectExportSecrets deletes retired keys and preserves portal tokens`() {
        val settings = MapSettings(
            "portal_auth_token" to "jwt-token",
            "portal_refresh_token" to "refresh-token",
            "portal_user_id" to "user-123",
            "intervals.installationId" to "inst-1",
            "intervals.apiKey.default.1" to "key-1",
            "intervals.apiKey.profile-2.4" to "key-2",
        )

        purgeRetiredDirectExportSecrets(settings)

        assertNull(settings.getStringOrNull("intervals.installationId"))
        assertNull(settings.getStringOrNull("intervals.apiKey.default.1"))
        assertNull(settings.getStringOrNull("intervals.apiKey.profile-2.4"))
        assertFalse(settings.keys.any { it.startsWith("intervals.") })
        assertEquals("jwt-token", settings.getStringOrNull("portal_auth_token"))
        assertEquals("refresh-token", settings.getStringOrNull("portal_refresh_token"))
        assertEquals("user-123", settings.getStringOrNull("portal_user_id"))
    }
}
