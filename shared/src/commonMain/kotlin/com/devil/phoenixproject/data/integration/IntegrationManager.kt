package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.IntegrationActivityDto
import com.devil.phoenixproject.data.sync.IntegrationSyncRequest
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.datetime.Instant

/**
 * Manages third-party integration lifecycle: connect, sync, and disconnect.
 *
 * Delegates network calls to [PortalApiClient] and persists results via
 * [ExternalActivityRepository]. Status updates are written transactionally
 * so local state always reflects the last known portal outcome.
 */
class IntegrationManager(private val apiClient: PortalApiClient, private val repository: ExternalActivityRepository) {

    /**
     * Connect a provider by submitting an API key to the portal Edge Function.
     * On success, the returned activities are upserted locally and the
     * integration status is set to CONNECTED.
     *
     * @param provider  The integration provider to connect.
     * @param apiKey    The user-supplied API key for the provider.
     * @param profileId The local profile ID scoping the data.
     * @param isPaidUser Whether the user has an active paid subscription.
     *                   Activities get [ExternalActivity.needsSync] = true only for paid users.
     */
    suspend fun connectProvider(
        provider: IntegrationProvider,
        apiKey: String,
        profileId: String,
        isPaidUser: Boolean,
    ): Result<List<ExternalActivity>> {
        Logger.d("IntegrationManager") { "Connecting provider ${provider.key}" }

        val request = IntegrationSyncRequest(
            provider = provider.key,
            action = "connect",
            apiKey = apiKey,
        )

        return apiClient.callIntegrationSync(request).fold(
            onSuccess = { response ->
                if (response.status == "error") {
                    Logger.w("IntegrationManager") {
                        "Connect responded with error for ${provider.key}: ${response.error}"
                    }
                    repository.updateIntegrationStatus(
                        provider = provider,
                        status = ConnectionStatus.ERROR,
                        profileId = profileId,
                        errorMessage = response.error,
                    )
                    Result.failure(Exception(response.error ?: "Unknown error from portal"))
                } else {
                    val activities = response.activities.map { dto ->
                        dto.toDomain(provider, profileId, isPaidUser)
                    }
                    if (activities.isNotEmpty()) {
                        repository.upsertActivities(activities)
                    }
                    repository.updateIntegrationStatus(
                        provider = provider,
                        status = ConnectionStatus.CONNECTED,
                        profileId = profileId,
                        lastSyncAt = currentTimeMillis(),
                    )
                    Logger.i("IntegrationManager") {
                        "Connected ${provider.key}: ${activities.size} activities"
                    }
                    Result.success(activities)
                }
            },
            onFailure = { error ->
                Logger.e("IntegrationManager") {
                    "Connect failed for ${provider.key}: ${error.message}"
                }
                repository.updateIntegrationStatus(
                    provider = provider,
                    status = ConnectionStatus.ERROR,
                    profileId = profileId,
                    errorMessage = error.message,
                )
                Result.failure(error)
            },
        )
    }

    /**
     * Sync a previously connected provider. No API key is needed — the portal
     * uses the stored token for the provider.
     *
     * @param provider  The integration provider to sync.
     * @param profileId The local profile ID scoping the data.
     * @param isPaidUser Whether the user has an active paid subscription.
     */
    suspend fun syncProvider(provider: IntegrationProvider, profileId: String, isPaidUser: Boolean): Result<List<ExternalActivity>> {
        Logger.d("IntegrationManager") { "Syncing provider ${provider.key}" }

        val request = IntegrationSyncRequest(
            provider = provider.key,
            action = "sync",
        )

        return apiClient.callIntegrationSync(request).fold(
            onSuccess = { response ->
                if (response.status == "error") {
                    Logger.w("IntegrationManager") {
                        "Sync responded with error for ${provider.key}: ${response.error}"
                    }
                    repository.updateIntegrationStatus(
                        provider = provider,
                        status = ConnectionStatus.ERROR,
                        profileId = profileId,
                        errorMessage = response.error,
                    )
                    Result.failure(Exception(response.error ?: "Unknown error from portal"))
                } else {
                    val activities = response.activities.map { dto ->
                        dto.toDomain(provider, profileId, isPaidUser)
                    }
                    if (activities.isNotEmpty()) {
                        repository.upsertActivities(activities)
                    }
                    repository.updateIntegrationStatus(
                        provider = provider,
                        status = ConnectionStatus.CONNECTED,
                        profileId = profileId,
                        lastSyncAt = currentTimeMillis(),
                    )
                    Logger.i("IntegrationManager") {
                        "Synced ${provider.key}: ${activities.size} activities"
                    }
                    Result.success(activities)
                }
            },
            onFailure = { error ->
                Logger.e("IntegrationManager") {
                    "Sync failed for ${provider.key}: ${error.message}"
                }
                repository.updateIntegrationStatus(
                    provider = provider,
                    status = ConnectionStatus.ERROR,
                    profileId = profileId,
                    errorMessage = error.message,
                )
                Result.failure(error)
            },
        )
    }

    /**
     * Disconnect a provider: notifies the portal, deletes local activities,
     * and updates status to DISCONNECTED.
     *
     * Portal-side disconnection failure is treated as non-critical — local
     * data is still cleared regardless (mirrors the sign-out pattern).
     *
     * @param provider  The integration provider to disconnect.
     * @param profileId The local profile ID scoping the data.
     */
    suspend fun disconnectProvider(provider: IntegrationProvider, profileId: String): Result<Unit> {
        Logger.d("IntegrationManager") { "Disconnecting provider ${provider.key}" }

        val request = IntegrationSyncRequest(
            provider = provider.key,
            action = "disconnect",
        )

        // Notify portal (best-effort; we proceed regardless of outcome)
        val portalResult = apiClient.callIntegrationSync(request)
        portalResult.onFailure { error ->
            Logger.w("IntegrationManager") {
                "Portal disconnect call failed for ${provider.key} (non-fatal): ${error.message}"
            }
        }

        // Always clean up local state
        repository.deleteActivities(provider, profileId)
        repository.updateIntegrationStatus(
            provider = provider,
            status = ConnectionStatus.DISCONNECTED,
            profileId = profileId,
        )

        Logger.i("IntegrationManager") { "Disconnected ${provider.key}" }
        return Result.success(Unit)
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────

    private fun IntegrationActivityDto.toDomain(provider: IntegrationProvider, profileId: String, isPaidUser: Boolean): ExternalActivity {
        val startEpochMillis = try {
            Instant.parse(startedAt).toEpochMilliseconds()
        } catch (_: Exception) {
            Logger.w("IntegrationManager") {
                "Could not parse startedAt '$startedAt' for $externalId — using current time"
            }
            currentTimeMillis()
        }

        return ExternalActivity(
            id = generateUUID(),
            externalId = externalId,
            provider = provider,
            name = name,
            activityType = activityType,
            startedAt = startEpochMillis,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            calories = calories,
            avgHeartRate = avgHeartRate,
            maxHeartRate = maxHeartRate,
            elevationGainMeters = elevationGainMeters,
            rawData = rawData,
            syncedAt = currentTimeMillis(),
            profileId = profileId,
            needsSync = isPaidUser,
        )
    }
}
