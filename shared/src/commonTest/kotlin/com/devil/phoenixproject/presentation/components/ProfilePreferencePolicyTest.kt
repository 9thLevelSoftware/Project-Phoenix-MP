package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.IntegrationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePreferencePolicyTest {
    @Test
    fun `newest matching imported body weight is attributed`() {
        val measurements = listOf(
            measurement("apple-old", IntegrationProvider.APPLE_HEALTH, 80.01, 100L),
            measurement("google-new", IntegrationProvider.GOOGLE_HEALTH, 79.98, 300L),
            measurement("apple-middle", IntegrationProvider.APPLE_HEALTH, 80.0, 200L),
        )

        assertEquals(
            300L,
            latestImportedBodyWeightMeasuredAt(
                profileId = "a",
                bodyWeightKg = 80f,
                measurements = measurements,
            ),
        )
    }

    @Test
    fun `unset and wrong profile provider unit type or tolerance are rejected`() {
        val rejected = listOf(
            measurement("profile", IntegrationProvider.APPLE_HEALTH, 80.0, 1L, profileId = "b"),
            measurement("provider", IntegrationProvider.HEVY, 80.0, 2L),
            measurement("unit", IntegrationProvider.APPLE_HEALTH, 80.0, 3L, unit = "lb"),
            measurement("type", IntegrationProvider.APPLE_HEALTH, 80.0, 4L, type = "height"),
            measurement("tolerance", IntegrationProvider.APPLE_HEALTH, 80.051, 5L),
        )

        assertNull(latestImportedBodyWeightMeasuredAt("a", 0f, rejected))
        assertNull(latestImportedBodyWeightMeasuredAt("a", Float.NaN, rejected))
        assertNull(latestImportedBodyWeightMeasuredAt("a", 80f, rejected))
    }

    @Test
    fun `LED indices zero through seven remain stable`() {
        assertEquals((0..7).toList(), (0..7).map { normalizedLedSchemeIndex(it, 8) })
    }

    @Test
    fun `negative and future LED values display zero without storage writes`() {
        var storedIndex = -1
        assertEquals(0, normalizedLedSchemeIndex(storedIndex, 8))
        assertEquals(-1, storedIndex)

        storedIndex = 8
        assertEquals(0, normalizedLedSchemeIndex(storedIndex, 8))
        assertEquals(8, storedIndex)
    }

    private fun measurement(
        id: String,
        provider: IntegrationProvider,
        value: Double,
        measuredAt: Long,
        profileId: String = "a",
        unit: String = HealthBodyWeightSyncManager.UNIT_KG,
        type: String = HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT,
    ) = ExternalBodyMeasurement(
        externalId = id,
        provider = provider,
        measurementType = type,
        value = value,
        unit = unit,
        measuredAt = measuredAt,
        profileId = profileId,
    )
}
