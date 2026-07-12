package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.migration.RequiredMigrationFailedException
import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfileContextUnavailableException
import com.devil.phoenixproject.data.repository.StaleProfileContextException
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.isIosPlatform
import kotlinx.coroutines.flow.first

private val bodyWeightSyncLog = Logger.withTag("HealthBodyWeightSync")

sealed class HealthBodyWeightSyncResult {
    object NotConnected : HealthBodyWeightSyncResult()
    object Unavailable : HealthBodyWeightSyncResult()
    object PermissionMissing : HealthBodyWeightSyncResult()
    object NoEligibleSample : HealthBodyWeightSyncResult()
    data class RejectedOutOfRange(val sample: HealthBodyWeightSample) : HealthBodyWeightSyncResult()
    data class Synced(
        val sample: HealthBodyWeightSample,
        val measurement: ExternalBodyMeasurement,
    ) : HealthBodyWeightSyncResult()
    data class Failed(val error: Throwable) : HealthBodyWeightSyncResult()
}

class HealthBodyWeightSyncManager(
    private val bodyWeightReader: HealthBodyWeightReader,
    private val externalActivityRepository: ExternalActivityRepository,
    private val externalMeasurementRepository: ExternalMeasurementRepository,
    private val requiredMigrationGate: RequiredMigrationGate,
    private val userProfileRepository: UserProfileRepository,
    private val providerResolver: () -> IntegrationProvider = {
        if (isIosPlatform) IntegrationProvider.APPLE_HEALTH else IntegrationProvider.GOOGLE_HEALTH
    },
    private val nowProvider: () -> Long = { currentTimeMillis() },
) {
    companion object {
        const val MEASUREMENT_TYPE_WEIGHT = "weight"
        const val UNIT_KG = "kg"
        const val MIN_BODY_WEIGHT_KG = 20f
        const val MAX_BODY_WEIGHT_KG = 300f
    }

    suspend fun syncLatestFromConnectedPlatform(): HealthBodyWeightSyncResult {
        val ready = try {
            awaitReadyProfile()
        } catch (error: RequiredMigrationFailedException) {
            bodyWeightSyncLog.w(error) { "Body-weight sync blocked by required migration failure" }
            return HealthBodyWeightSyncResult.Failed(error)
        } catch (error: ProfileContextUnavailableException) {
            bodyWeightSyncLog.w(error) { "Body-weight sync blocked while active profile was switching" }
            return HealthBodyWeightSyncResult.Failed(error)
        }
        val provider = providerResolver()
        val profileId = ready.profile.id
        val status = externalActivityRepository.getIntegrationStatus(provider, profileId).first()

        if (status?.status != ConnectionStatus.CONNECTED) {
            bodyWeightSyncLog.d { "Skipping body-weight sync: ${provider.key} is not connected for profile=$profileId" }
            return HealthBodyWeightSyncResult.NotConnected
        }

        if (!bodyWeightReader.isAvailable()) {
            updateConnectedStatus(
                provider = provider,
                profileId = profileId,
                errorMessage = "Health body weight sync unavailable on this device",
            )
            return HealthBodyWeightSyncResult.Unavailable
        }

        if (!bodyWeightReader.hasBodyWeightReadPermission()) {
            updateConnectedStatus(
                provider = provider,
                profileId = profileId,
                errorMessage = "Body weight read permission is missing",
            )
            return HealthBodyWeightSyncResult.PermissionMissing
        }

        val readResult = bodyWeightReader.readLatestScaleBodyWeight()
        val sample = readResult.getOrElse { error ->
            bodyWeightSyncLog.w(error) { "Health body-weight read failed for ${provider.key}" }
            updateConnectedStatus(
                provider = provider,
                profileId = profileId,
                errorMessage = "Body weight sync failed: ${error.message ?: "unknown error"}",
            )
            return HealthBodyWeightSyncResult.Failed(error)
        }

        val now = nowProvider()
        if (sample == null) {
            updateConnectedStatus(
                provider = provider,
                profileId = profileId,
                lastSyncAt = now,
                errorMessage = "No eligible scale body weight found",
            )
            return HealthBodyWeightSyncResult.NoEligibleSample
        }

        if (sample.weightKg !in MIN_BODY_WEIGHT_KG..MAX_BODY_WEIGHT_KG) {
            updateConnectedStatus(
                provider = provider,
                profileId = profileId,
                lastSyncAt = now,
                errorMessage = "Latest Health body weight is outside Phoenix's 20-300 kg range",
            )
            return HealthBodyWeightSyncResult.RejectedOutOfRange(sample)
        }

        val measurement = ExternalBodyMeasurement(
            externalId = sample.externalId,
            provider = provider,
            measurementType = MEASUREMENT_TYPE_WEIGHT,
            value = sample.weightKg.toDouble(),
            unit = UNIT_KG,
            measuredAt = sample.measuredAtMs,
            rawData = sample.rawMetadataJson ?: sample.toRawDataJson(provider),
            profileId = profileId,
            syncedAt = now,
        )
        try {
            userProfileRepository.mutateCore(ready.profile.id) { latest ->
                latest.copy(bodyWeightKg = sample.weightKg)
            }
        } catch (error: StaleProfileContextException) {
            bodyWeightSyncLog.w(error) { "Body-weight sync stopped after active profile changed" }
            return HealthBodyWeightSyncResult.Failed(error)
        } catch (error: ProfileContextUnavailableException) {
            bodyWeightSyncLog.w(error) { "Body-weight sync stopped while active profile was switching" }
            return HealthBodyWeightSyncResult.Failed(error)
        }
        externalMeasurementRepository.upsertMeasurements(listOf(measurement))
        updateConnectedStatus(
            provider = provider,
            profileId = profileId,
            lastSyncAt = now,
            errorMessage = null,
        )
        return HealthBodyWeightSyncResult.Synced(sample = sample, measurement = measurement)
    }

    private suspend fun awaitReadyProfile(): ActiveProfileContext.Ready {
        requiredMigrationGate.awaitRequiredMigrations()
        return userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
    }

    private suspend fun updateConnectedStatus(
        provider: IntegrationProvider,
        profileId: String,
        lastSyncAt: Long? = null,
        errorMessage: String? = null,
    ) {
        externalActivityRepository.updateIntegrationStatus(
            provider = provider,
            status = ConnectionStatus.CONNECTED,
            profileId = profileId,
            lastSyncAt = lastSyncAt,
            errorMessage = errorMessage,
        )
    }

    private fun HealthBodyWeightSample.toRawDataJson(provider: IntegrationProvider): String {
        val metadata = deviceMetadata.entries.joinToString(separator = ",") { (key, value) ->
            "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
        }
        return buildString {
            append("{")
            append("\"provider\":\"${provider.key.escapeJson()}\",")
            append("\"externalId\":\"${externalId.escapeJson()}\",")
            append("\"weightKg\":$weightKg,")
            append("\"measuredAtMs\":$measuredAtMs,")
            append("\"sourceName\":")
            append(sourceName?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"deviceMetadata\":{")
            append(metadata)
            append("}")
            append("}")
        }
    }

    private fun String.escapeJson(): String = buildString {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
