package com.devil.phoenixproject.data.integration

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of [ExternalActivityRepository].
 * All database operations run on [Dispatchers.IO].
 * INTEGER columns in SQLDelight-generated types are Kotlin Long.
 */
class SqlDelightExternalActivityRepository(db: VitruvianDatabase) : ExternalActivityRepository {

    private val queries = db.vitruvianDatabaseQueries

    // ─── Mapping helpers ──────────────────────────────────────────────

    private fun com.devil.phoenixproject.database.ExternalActivity.toDomain(): ExternalActivity = ExternalActivity(
        id = id,
        externalId = externalId,
        provider = IntegrationProvider.fromKey(provider) ?: IntegrationProvider.HEVY,
        name = name,
        activityType = activityType,
        startedAt = startedAt,
        durationSeconds = durationSeconds.toInt(),
        distanceMeters = distanceMeters,
        calories = calories?.toInt(),
        avgHeartRate = avgHeartRate?.toInt(),
        maxHeartRate = maxHeartRate?.toInt(),
        elevationGainMeters = elevationGainMeters,
        rawData = rawData,
        syncedAt = syncedAt,
        profileId = profileId,
        needsSync = needsSync != 0L,
    )

    private fun com.devil.phoenixproject.database.IntegrationStatus.toDomain(): IntegrationStatus = IntegrationStatus(
        provider = IntegrationProvider.fromKey(provider) ?: IntegrationProvider.HEVY,
        status = runCatching { ConnectionStatus.valueOf(status.uppercase()) }
            .getOrDefault(ConnectionStatus.DISCONNECTED),
        lastSyncAt = lastSyncAt,
        errorMessage = errorMessage,
        profileId = profileId,
    )

    // ─── ExternalActivity queries ─────────────────────────────────────

    override fun getAll(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalActivity>> = if (provider == null) {
        queries.getAllExternalActivities(profileId = profileId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toDomain() } }
    } else {
        queries.getExternalActivitiesByProvider(profileId = profileId, provider = provider.key)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun getUnsyncedActivities(profileId: String): List<ExternalActivity> = withContext(Dispatchers.IO) {
        queries.getUnsyncedExternalActivities(profileId = profileId)
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun upsertActivities(activities: List<ExternalActivity>) {
        if (activities.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (activity in activities) {
                    // INSERT OR IGNORE: inserts the row only if no existing row
                    // matches the (externalId, provider) unique index.
                    // This preserves the existing id and needsSync on re-import.
                    queries.insertExternalActivityIfNew(
                        id = activity.id,
                        externalId = activity.externalId,
                        provider = activity.provider.key,
                        name = activity.name,
                        activityType = activity.activityType,
                        startedAt = activity.startedAt,
                        durationSeconds = activity.durationSeconds.toLong(),
                        distanceMeters = activity.distanceMeters,
                        calories = activity.calories?.toLong(),
                        avgHeartRate = activity.avgHeartRate?.toLong(),
                        maxHeartRate = activity.maxHeartRate?.toLong(),
                        elevationGainMeters = activity.elevationGainMeters,
                        rawData = activity.rawData,
                        syncedAt = activity.syncedAt,
                        profileId = activity.profileId,
                        needsSync = if (activity.needsSync) 1L else 0L,
                    )
                    // UPDATE: updates data fields for any existing row.
                    // Preserves id and needsSync (not touched by this statement).
                    // No-op if the INSERT above succeeded (externalId+provider is new).
                    queries.updateExternalActivityOnConflict(
                        name = activity.name,
                        activityType = activity.activityType,
                        startedAt = activity.startedAt,
                        durationSeconds = activity.durationSeconds.toLong(),
                        distanceMeters = activity.distanceMeters,
                        calories = activity.calories?.toLong(),
                        avgHeartRate = activity.avgHeartRate?.toLong(),
                        maxHeartRate = activity.maxHeartRate?.toLong(),
                        elevationGainMeters = activity.elevationGainMeters,
                        rawData = activity.rawData,
                        syncedAt = activity.syncedAt,
                        profileId = activity.profileId,
                        externalId = activity.externalId,
                        provider = activity.provider.key,
                    )
                }
            }
        }
    }

    override suspend fun markSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (id in ids) {
                    queries.markExternalActivitySynced(id = id)
                }
            }
        }
    }

    override suspend fun markSyncedBySyncKeys(syncKeys: List<ExternalActivitySyncKey>, profileId: String) {
        if (syncKeys.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (syncKey in syncKeys) {
                    queries.markExternalActivitySyncedBySyncKey(
                        externalId = syncKey.externalId,
                        provider = syncKey.provider.key,
                        profileId = profileId,
                    )
                }
            }
        }
    }

    override suspend fun deleteActivities(provider: IntegrationProvider, profileId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteExternalActivitiesByProvider(
                provider = provider.key,
                profileId = profileId,
            )
        }
    }

    // ─── IntegrationStatus queries ────────────────────────────────────

    override fun getIntegrationStatus(provider: IntegrationProvider, profileId: String): Flow<IntegrationStatus?> = queries.getIntegrationStatus(provider = provider.key, profileId = profileId)
        .asFlow()
        .mapToOneOrNull(Dispatchers.IO)
        .map { row -> row?.toDomain() }

    override fun getAllIntegrationStatuses(profileId: String): Flow<List<IntegrationStatus>> = queries.getAllIntegrationStatuses(profileId = profileId)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { rows -> rows.map { it.toDomain() } }

    override suspend fun updateIntegrationStatus(
        provider: IntegrationProvider,
        status: ConnectionStatus,
        profileId: String,
        lastSyncAt: Long?,
        errorMessage: String?,
    ) {
        withContext(Dispatchers.IO) {
            queries.upsertIntegrationStatus(
                provider = provider.key,
                status = status.name.lowercase(),
                lastSyncAt = lastSyncAt,
                errorMessage = errorMessage,
                profileId = profileId,
            )
        }
    }
}
