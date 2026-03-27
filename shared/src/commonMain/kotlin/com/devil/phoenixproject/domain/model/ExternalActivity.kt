package com.devil.phoenixproject.domain.model

/**
 * Provider for third-party integrations.
 * The [key] matches the portal's `user_integrations.provider` column.
 */
enum class IntegrationProvider(val key: String, val displayName: String) {
    HEVY("hevy", "Hevy"),
    LIFTOSAUR("liftosaur", "Liftosaur"),
    STRONG("strong", "Strong"),
    APPLE_HEALTH("apple_health", "Apple Health"),
    GOOGLE_HEALTH("google_health", "Google Health Connect"),
    ;

    companion object {
        fun fromKey(key: String): IntegrationProvider? = entries.find { it.key == key }
    }
}

/**
 * Connection status for an integration provider.
 */
enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    ERROR,
    TOKEN_EXPIRED,
}

/**
 * Tracks connection state per provider.
 */
data class IntegrationStatus(
    val provider: IntegrationProvider,
    val status: ConnectionStatus,
    val lastSyncAt: Long? = null,
    val errorMessage: String? = null,
    val profileId: String = "default",
)

/**
 * Normalized activity from an external source (Hevy, Liftosaur, Strong CSV, etc.).
 * Weights stored as-is from the source app (total weight, NOT per-cable).
 * This is distinct from WorkoutSession which uses per-cable convention.
 */
data class ExternalActivity(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val name: String,
    val activityType: String = "strength",
    val startedAt: Long,
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null,
    val syncedAt: Long = currentTimeMillis(),
    val profileId: String = "default",
    val needsSync: Boolean = false,
)
