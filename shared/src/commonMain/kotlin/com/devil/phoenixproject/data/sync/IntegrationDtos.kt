package com.devil.phoenixproject.data.sync

import kotlinx.serialization.Serializable

/**
 * DTOs for the mobile-integration-sync Edge Function.
 *
 * These represent the wire format for mobile ↔ portal integration sync
 * (connect, sync, disconnect actions for third-party providers like Hevy, Liftosaur, etc.).
 */

@Serializable
data class IntegrationSyncRequest(
    val provider: String,
    val action: String,
    val apiKey: String? = null
)

@Serializable
data class IntegrationSyncResponse(
    val status: String,
    val activities: List<IntegrationActivityDto> = emptyList(),
    val error: String? = null
)

@Serializable
data class IntegrationActivityDto(
    val externalId: String,
    val provider: String,
    val name: String,
    val activityType: String = "strength",
    val startedAt: String,
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null
)
