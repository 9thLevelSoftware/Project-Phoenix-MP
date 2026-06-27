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
    UNKNOWN("unknown", "Unknown"),
    ;

    companion object {
        // F364: normalize the incoming key (trim + lowercase) before matching so
        // "HEVY" / " hevy " resolve instead of falling through to null/UNKNOWN.
        // Enum keys are already lowercase; null-on-unknown contract preserved.
        fun fromKey(key: String): IntegrationProvider? = key.trim().lowercase().let { k -> entries.find { it.key == k } }
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
    val deletedAt: Long? = null,
)
