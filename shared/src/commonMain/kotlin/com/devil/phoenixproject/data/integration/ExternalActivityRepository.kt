package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationStatus
import kotlinx.coroutines.flow.Flow

data class ExternalActivitySyncKey(
    val externalId: String,
    val provider: IntegrationProvider
)

/**
 * Repository for external activity data from third-party integrations (Hevy, Liftosaur, Strong, etc.)
 * and integration connection state tracking.
 *
 * All methods are profile-scoped.
 */
interface ExternalActivityRepository {

    /**
     * Observe all external activities for a profile, optionally filtered by provider.
     * Returns a reactive stream ordered by startedAt DESC.
     */
    fun getAll(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalActivity>>

    /**
     * Return activities that have not yet been pushed to the portal (needsSync = true).
     */
    suspend fun getUnsyncedActivities(profileId: String): List<ExternalActivity>

    /**
     * Insert or replace a batch of external activities.
     * Uses a transaction for atomicity.
     */
    suspend fun upsertActivities(activities: List<ExternalActivity>)

    /**
     * Mark a set of activity IDs as synced (needsSync = false).
     * Uses a transaction for atomicity.
     */
    suspend fun markSynced(ids: List<String>)

    /**
     * Mark activities as synced using the same composite key the database uses for deduplication.
     * This avoids clearing needsSync on the wrong provider when two integrations emit the same
     * external activity ID.
     */
    suspend fun markSyncedBySyncKeys(syncKeys: List<ExternalActivitySyncKey>, profileId: String)

    /**
     * Delete all activities from a specific provider for a given profile.
     * Used when disconnecting an integration.
     */
    suspend fun deleteActivities(provider: IntegrationProvider, profileId: String)

    /**
     * Observe the connection status for a specific provider and profile.
     * Emits null when no status row exists yet.
     */
    fun getIntegrationStatus(provider: IntegrationProvider, profileId: String): Flow<IntegrationStatus?>

    /**
     * Observe all integration statuses for a profile.
     */
    fun getAllIntegrationStatuses(profileId: String): Flow<List<IntegrationStatus>>

    /**
     * Insert or update the integration status for a provider/profile pair.
     */
    suspend fun updateIntegrationStatus(
        provider: IntegrationProvider,
        status: com.devil.phoenixproject.domain.model.ConnectionStatus,
        profileId: String,
        lastSyncAt: Long? = null,
        errorMessage: String? = null
    )
}
