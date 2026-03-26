package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.integration.ExternalActivityRepository
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
    var upsertCallCount = 0

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
        this.activities.addAll(activities)
    }

    override suspend fun markSynced(ids: List<String>) {
        markedSyncedIds.addAll(ids)
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
        // no-op for tests
    }
}
