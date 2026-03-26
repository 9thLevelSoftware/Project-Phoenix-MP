package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalActivityTest {

    @Test
    fun integrationProvider_fromKey_validKey_returnsProvider() {
        assertEquals(IntegrationProvider.HEVY, IntegrationProvider.fromKey("hevy"))
        assertEquals(IntegrationProvider.LIFTOSAUR, IntegrationProvider.fromKey("liftosaur"))
        assertEquals(IntegrationProvider.STRONG, IntegrationProvider.fromKey("strong"))
        assertEquals(IntegrationProvider.APPLE_HEALTH, IntegrationProvider.fromKey("apple_health"))
        assertEquals(IntegrationProvider.GOOGLE_HEALTH, IntegrationProvider.fromKey("google_health"))
    }

    @Test
    fun integrationProvider_fromKey_unknownKey_returnsNull() {
        assertNull(IntegrationProvider.fromKey("fitbit"))
        assertNull(IntegrationProvider.fromKey(""))
    }

    @Test
    fun externalActivity_defaults() {
        val activity = ExternalActivity(
            externalId = "hevy-chest-12345",
            provider = IntegrationProvider.HEVY,
            name = "Chest Day",
            startedAt = 1000L
        )
        assertEquals("strength", activity.activityType)
        assertEquals(0, activity.durationSeconds)
        assertNull(activity.calories)
        assertEquals("default", activity.profileId)
        assertEquals(true, activity.needsSync)
    }
}
