package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.IntegrationProvider
import kotlin.math.abs

internal data class ProfileMeasurementKey(
    val profileId: String,
    val coreLocalGeneration: Long,
    val bodyWeightKg: Float,
)

internal fun normalizedLedSchemeIndex(
    storedIndex: Int,
    schemeCount: Int,
): Int = storedIndex.takeIf { it in 0 until schemeCount } ?: 0

internal fun latestImportedBodyWeightMeasuredAt(
    profileId: String,
    bodyWeightKg: Float,
    measurements: List<ExternalBodyMeasurement>,
): Long? {
    if (!bodyWeightKg.isFinite() || bodyWeightKg <= 0f) return null
    return measurements.asSequence()
        .filter { it.profileId == profileId }
        .filter { it.measurementType == HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT }
        .filter { it.unit == HealthBodyWeightSyncManager.UNIT_KG }
        .filter {
            it.provider == IntegrationProvider.APPLE_HEALTH ||
                it.provider == IntegrationProvider.GOOGLE_HEALTH
        }
        .filter { abs(it.value - bodyWeightKg.toDouble()) < 0.05 }
        .maxOfOrNull(ExternalBodyMeasurement::measuredAt)
}
