package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.ExternalActivitySyncKey
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeExternalActivityRepository : ExternalActivityRepository {

    val activities = mutableListOf<ExternalActivity>()
    val markedSyncedIds = mutableListOf<String>()
    val markedSyncedKeys = mutableListOf<ExternalActivitySyncKey>()
    var upsertCallCount = 0

    /** Captures every updateIntegrationStatus call for test assertions. */
    data class StatusUpdate(
        val provider: IntegrationProvider,
        val status: ConnectionStatus,
        val profileId: String,
        val lastSyncAt: Long?,
        val errorMessage: String?
    )
    val statusUpdates = mutableListOf<StatusUpdate>()

    override fun getAll(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalActivity>> {
        return MutableStateFlow(
            activities.filter { it.profileId == profileId && (provider == null || it.provider == provider) }
        )
    }

    override suspend fun getUnsyncedActivities(profileId: String): List<ExternalActivity> {
        return activities.filter { it.profileId == profileId && it.needsSync }
    }

    override suspend fun upsertActivities(activities: List<ExternalActivity>) {
        upsertCallCount++
        // Deduplicate on (externalId, provider) to match real INSERT OR IGNORE + UPDATE behavior.
        // Existing rows are updated in-place (preserving id and needsSync); new rows are appended.
        for (activity in activities) {
            val existingIndex = this.activities.indexOfFirst {
                it.externalId == activity.externalId && it.provider == activity.provider
            }
            if (existingIndex >= 0) {
                // Update data fields but preserve id and needsSync from existing row
                val existing = this.activities[existingIndex]
                this.activities[existingIndex] = activity.copy(
                    id = existing.id,
                    needsSync = existing.needsSync
                )
            } else {
                this.activities.add(activity)
            }
        }
    }

    override suspend fun markSynced(ids: List<String>) {
        markedSyncedIds.addAll(ids)
    }

    override suspend fun markSyncedBySyncKeys(syncKeys: List<ExternalActivitySyncKey>, profileId: String) {
        markedSyncedKeys.addAll(syncKeys)
    }

    override suspend fun deleteActivities(provider: IntegrationProvider, profileId: String) {
        activities.removeAll { it.provider == provider && it.profileId == profileId }
    }

    override fun getIntegrationStatus(provider: IntegrationProvider, profileId: String): Flow<IntegrationStatus?> {
        return MutableStateFlow(null)
    }

    override fun getAllIntegrationStatuses(profileId: String): Flow<List<IntegrationStatus>> {
        return MutableStateFlow(emptyList())
    }

    override suspend fun updateIntegrationStatus(
        provider: IntegrationProvider,
        status: ConnectionStatus,
        profileId: String,
        lastSyncAt: Long?,
        errorMessage: String?
    ) {
        statusUpdates += StatusUpdate(provider, status, profileId, lastSyncAt, errorMessage)
    }
}
