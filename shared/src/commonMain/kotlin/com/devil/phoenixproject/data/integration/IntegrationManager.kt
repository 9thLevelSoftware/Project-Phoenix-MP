package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.IntegrationActivityDto
import com.devil.phoenixproject.data.sync.IntegrationBodyMeasurementDto
import com.devil.phoenixproject.data.sync.IntegrationEntityErrorDto
import com.devil.phoenixproject.data.sync.IntegrationExerciseTemplateDto
import com.devil.phoenixproject.data.sync.IntegrationPlaygroundPreviewDto
import com.devil.phoenixproject.data.sync.IntegrationPlaygroundSimulationRequest
import com.devil.phoenixproject.data.sync.IntegrationProgramDto
import com.devil.phoenixproject.data.sync.IntegrationProgramStatsDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineExerciseDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineFolderDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineSetDto
import com.devil.phoenixproject.data.sync.IntegrationSyncRequest
import com.devil.phoenixproject.data.sync.IntegrationSyncResponse
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplate
import com.devil.phoenixproject.domain.model.ExternalPlaygroundPreview
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.ExternalRoutineExercise
import com.devil.phoenixproject.domain.model.ExternalRoutineFolder
import com.devil.phoenixproject.domain.model.ExternalRoutineSet
import com.devil.phoenixproject.domain.model.IntegrationEntitlementState
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationSyncProgress
import com.devil.phoenixproject.domain.model.IntegrationSyncResult
import com.devil.phoenixproject.domain.model.IntegrationSyncWarning
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.datetime.Instant

private const val MAX_SYNC_PAGES_PER_ENTITY = 50

/**
 * Multi-entity coordinator for third-party integrations.
 *
 * Mobile still talks only to the portal integration endpoint. The manager maps
 * the expanded payload into focused local repositories and keeps the previous
 * activity-only response shape backward compatible.
 */
class IntegrationManager(
    private val apiClient: PortalApiClient,
    private val activityRepository: ExternalActivityRepository,
    private val routineRepository: ExternalRoutineRepository,
    private val programRepository: ExternalProgramRepository,
    private val measurementRepository: ExternalMeasurementRepository,
    private val templateRepository: ExternalExerciseTemplateRepository,
    private val cursorRepository: IntegrationSyncCursorRepository,
) {
    suspend fun connectProvider(
        provider: IntegrationProvider,
        apiKey: String,
        profileId: String,
        isPaidUser: Boolean,
    ): Result<IntegrationSyncResult> {
        Logger.d("IntegrationManager") { "Connecting provider ${provider.key}" }
        return syncProviderInternal(
            provider = provider,
            action = "connect",
            apiKey = apiKey,
            profileId = profileId,
            isPaidUser = isPaidUser,
            startCursor = null,
        )
    }

    suspend fun syncProvider(
        provider: IntegrationProvider,
        profileId: String,
        isPaidUser: Boolean,
    ): Result<IntegrationSyncResult> {
        Logger.d("IntegrationManager") { "Syncing provider ${provider.key}" }
        val startCursor = cursorRepository.getCursor(provider, profileId, "integration_sync")?.cursorValue
        return syncProviderInternal(
            provider = provider,
            action = "sync",
            apiKey = null,
            profileId = profileId,
            isPaidUser = isPaidUser,
            startCursor = startCursor,
        )
    }

    suspend fun disconnectProvider(provider: IntegrationProvider, profileId: String): Result<Unit> {
        Logger.d("IntegrationManager") { "Disconnecting provider ${provider.key}" }

        val portalResult = apiClient.callIntegrationSync(
            IntegrationSyncRequest(provider = provider.key, action = "disconnect"),
        )
        portalResult.onFailure { error ->
            Logger.w("IntegrationManager") {
                "Portal disconnect call failed for ${provider.key} (non-fatal): ${error.message}"
            }
        }

        activityRepository.deleteActivities(provider, profileId)
        routineRepository.deleteProviderRoutines(provider, profileId)
        programRepository.deleteProviderPrograms(provider, profileId)
        measurementRepository.deleteProviderMeasurements(provider, profileId)
        templateRepository.deleteProviderTemplates(provider, profileId)
        cursorRepository.deleteProviderCursors(provider, profileId)
        activityRepository.updateIntegrationStatus(
            provider = provider,
            status = ConnectionStatus.DISCONNECTED,
            profileId = profileId,
        )

        Logger.i("IntegrationManager") { "Disconnected ${provider.key}" }
        return Result.success(Unit)
    }

    suspend fun simulatePlayground(
        provider: IntegrationProvider,
        externalProgramId: String,
        scriptText: String,
        commands: List<String>,
        profileId: String,
    ): Result<ExternalPlaygroundPreview> = apiClient.callIntegrationPlaygroundSimulation(
        IntegrationPlaygroundSimulationRequest(
            provider = provider.key,
            externalProgramId = externalProgramId,
            scriptText = scriptText,
            commands = commands,
            profileId = profileId,
        ),
    ).mapCatching { response ->
        if (response.status == "error") {
            throw IllegalStateException(response.error ?: "Playground simulation failed")
        }
        val preview = response.preview ?: IntegrationPlaygroundPreviewDto(
            programExternalId = externalProgramId,
            updatedProgramText = response.updatedProgramText,
        )
        preview.toDomain()
    }

    suspend fun commitProgramText(programId: String, updatedProgramText: String, markNeedsSync: Boolean = true) {
        programRepository.updateProgramText(programId, updatedProgramText, markNeedsSync)
    }

    private suspend fun syncProviderInternal(
        provider: IntegrationProvider,
        action: String,
        apiKey: String?,
        profileId: String,
        isPaidUser: Boolean,
        startCursor: String?,
    ): Result<IntegrationSyncResult> {
        var page = 0
        var cursor = startCursor
        var aggregate = IntegrationSyncResult(provider = provider)
        var latestProviderSyncCursor: String? = null

        while (page < MAX_SYNC_PAGES_PER_ENTITY) {
            val request = IntegrationSyncRequest(
                provider = provider.key,
                action = action,
                apiKey = if (page == 0) apiKey else null,
                entityTypes = ALL_ENTITY_TYPES,
                cursor = cursor,
                includeRawData = true,
            )

            val response = apiClient.callIntegrationSync(request).getOrElse { error ->
                Logger.e("IntegrationManager") { "$action failed for ${provider.key}: ${error.message}" }
                activityRepository.updateIntegrationStatus(
                    provider = provider,
                    status = ConnectionStatus.ERROR,
                    profileId = profileId,
                    errorMessage = error.message,
                )
                return Result.failure(error)
            }

            if (response.status == "error" && !response.requiresUpgrade) {
                val message = response.error ?: "Unknown error from portal"
                Logger.w("IntegrationManager") { "$action responded with error for ${provider.key}: $message" }
                activityRepository.updateIntegrationStatus(
                    provider = provider,
                    status = ConnectionStatus.ERROR,
                    profileId = profileId,
                    errorMessage = message,
                )
                return Result.failure(Exception(message))
            }

            val pageResult = persistResponse(provider, profileId, isPaidUser, response)
            aggregate = aggregate.merge(pageResult)
            response.providerSyncCursor?.let { latestProviderSyncCursor = it }

            if (!response.hasMore || response.nextCursor == null) break
            cursor = response.nextCursor
            page++
        }

        if (page >= MAX_SYNC_PAGES_PER_ENTITY) {
            aggregate = aggregate.copy(
                partial = true,
                warnings = aggregate.warnings + IntegrationSyncWarning(
                    entityType = "sync",
                    code = "page_cap_reached",
                    message = "Stopped after $MAX_SYNC_PAGES_PER_ENTITY pages to avoid an infinite sync loop.",
                ),
            )
        }

        if (latestProviderSyncCursor != null) {
            cursorRepository.upsertCursor(
                IntegrationSyncCursor(
                    provider = provider,
                    profileId = profileId,
                    cursorType = "integration_sync",
                    cursorValue = latestProviderSyncCursor,
                    updatedAt = currentTimeMillis(),
                ),
            )
        }

        activityRepository.updateIntegrationStatus(
            provider = provider,
            status = ConnectionStatus.CONNECTED,
            profileId = profileId,
            lastSyncAt = currentTimeMillis(),
            errorMessage = aggregate.warnings.firstOrNull()?.message,
        )

        Logger.i("IntegrationManager") {
            "${action.replaceFirstChar { it.uppercase() }} ${provider.key}: ${aggregate.progress}"
        }
        return Result.success(aggregate)
    }

    private suspend fun persistResponse(
        provider: IntegrationProvider,
        profileId: String,
        isPaidUser: Boolean,
        response: IntegrationSyncResponse,
    ): IntegrationSyncResult {
        val activities = response.activities.map { it.toDomain(provider, profileId, isPaidUser) }
        val folders = response.routineFolders.map { it.toDomain(provider, profileId) }
        val routines = response.routines.map { it.toDomain(provider, profileId) }
        val templates = response.exerciseTemplates.map { it.toDomain(provider, profileId) }
        val measurements = response.bodyMeasurements.map { it.toDomain(provider, profileId) }
        val programs = response.programs.map { it.toDomain(provider, profileId) }

        if (activities.isNotEmpty()) activityRepository.upsertActivities(activities)
        if (response.deletedExternalIds.isNotEmpty()) {
            activityRepository.markDeletedByExternalIds(
                provider = provider,
                profileId = profileId,
                externalIds = response.deletedExternalIds,
                deletedAt = currentTimeMillis(),
                needsSync = isPaidUser,
            )
        }
        if (folders.isNotEmpty()) routineRepository.upsertFolders(folders)
        if (routines.isNotEmpty()) routineRepository.upsertRoutines(routines)
        if (templates.isNotEmpty()) templateRepository.upsertTemplates(templates)
        if (measurements.isNotEmpty()) measurementRepository.upsertMeasurements(measurements)
        if (programs.isNotEmpty()) programRepository.upsertPrograms(programs)

        val programsByExternalId = programRepository.findPrograms(
            provider = provider,
            externalIds = response.programStats.map { it.externalProgramId }.distinct(),
            profileId = profileId,
        ).associateBy { it.externalId }
        val stats = response.programStats.mapNotNull { dto ->
            val localProgram = programsByExternalId[dto.externalProgramId]
            if (localProgram == null) {
                Logger.w("IntegrationManager") {
                    "Skipping stats for missing ${provider.key} program ${dto.externalProgramId}"
                }
                null
            } else {
                dto.toDomain(localProgram.id)
            }
        }
        if (stats.isNotEmpty()) programRepository.upsertStats(stats)

        val warnings = response.errors.map { it.toWarning() }
        val entitlementState = response.toEntitlementState(provider)

        return IntegrationSyncResult(
            provider = provider,
            progress = IntegrationSyncProgress(
                activitiesImported = activities.size,
                routinesImported = routines.size,
                routineFoldersImported = folders.size,
                exerciseTemplatesImported = templates.size,
                measurementsImported = measurements.size,
                programsImported = programs.size,
                programStatsImported = stats.size,
            ),
            entitlementState = entitlementState,
            partial = response.partial || response.errors.isNotEmpty(),
            warnings = warnings,
            nextCursor = response.nextCursor,
            hasMore = response.hasMore,
        )
    }

    private fun IntegrationActivityDto.toDomain(
        fallbackProvider: IntegrationProvider,
        profileId: String,
        isPaidUser: Boolean,
    ): ExternalActivity {
        val parsedProvider = IntegrationProvider.fromKey(provider) ?: fallbackProvider
        return ExternalActivity(
            id = generateUUID(),
            externalId = externalId,
            provider = parsedProvider,
            name = name,
            activityType = activityType,
            startedAt = startedAt.parseEpochMillisOrNow("startedAt", externalId),
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

    private fun IntegrationRoutineDto.toDomain(fallbackProvider: IntegrationProvider, profileId: String): ExternalRoutine {
        val routineId = generateUUID()
        return ExternalRoutine(
            id = routineId,
            externalId = externalId,
            provider = IntegrationProvider.fromKey(provider) ?: fallbackProvider,
            title = title,
            folderExternalId = folderExternalId,
            folderName = folderName,
            updatedAt = updatedAt?.parseEpochMillisOrNull("routine.updatedAt", externalId),
            exercises = exercises.map { it.toDomain(routineId) },
            rawData = rawData,
            profileId = profileId,
            syncedAt = currentTimeMillis(),
        )
    }

    private fun IntegrationRoutineExerciseDto.toDomain(routineId: String): ExternalRoutineExercise {
        val exerciseId = generateUUID()
        return ExternalRoutineExercise(
            id = exerciseId,
            externalRoutineId = routineId,
            externalExerciseTemplateId = externalExerciseTemplateId,
            title = title,
            exerciseType = exerciseType,
            primaryMuscleGroups = primaryMuscleGroups,
            secondaryMuscleGroups = secondaryMuscleGroups,
            orderIndex = orderIndex,
            sets = sets.map { it.toDomain(exerciseId) },
            rawData = rawData,
        )
    }

    private fun IntegrationRoutineSetDto.toDomain(exerciseId: String): ExternalRoutineSet = ExternalRoutineSet(
        id = generateUUID(),
        externalRoutineExerciseId = exerciseId,
        index = index,
        setType = setType,
        weightKg = weightKg,
        reps = reps,
        minReps = minReps,
        maxReps = maxReps,
        restSeconds = restSeconds,
        rpe = rpe,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        rawData = rawData,
    )

    private fun IntegrationRoutineFolderDto.toDomain(
        fallbackProvider: IntegrationProvider,
        profileId: String,
    ): ExternalRoutineFolder = ExternalRoutineFolder(
        id = generateUUID(),
        externalId = externalId,
        provider = IntegrationProvider.fromKey(provider) ?: fallbackProvider,
        title = title,
        index = index,
        createdAt = createdAt?.parseEpochMillisOrNull("folder.createdAt", externalId),
        updatedAt = updatedAt?.parseEpochMillisOrNull("folder.updatedAt", externalId),
        profileId = profileId,
        rawData = rawData,
    )

    private fun IntegrationExerciseTemplateDto.toDomain(
        fallbackProvider: IntegrationProvider,
        profileId: String,
    ): ExternalExerciseTemplate = ExternalExerciseTemplate(
        id = generateUUID(),
        externalId = externalId,
        provider = IntegrationProvider.fromKey(provider) ?: fallbackProvider,
        title = title,
        exerciseType = exerciseType,
        primaryMuscleGroups = primaryMuscleGroups,
        secondaryMuscleGroups = secondaryMuscleGroups,
        isCustom = isCustom,
        rawData = rawData,
        updatedAt = updatedAt?.parseEpochMillisOrNull("template.updatedAt", externalId),
        profileId = profileId,
    )

    private fun IntegrationBodyMeasurementDto.toDomain(
        fallbackProvider: IntegrationProvider,
        profileId: String,
    ): ExternalBodyMeasurement = ExternalBodyMeasurement(
        id = generateUUID(),
        externalId = externalId,
        provider = IntegrationProvider.fromKey(provider) ?: fallbackProvider,
        measurementType = measurementType,
        value = value,
        unit = unit,
        measuredAt = measuredAt.parseEpochMillisOrNow("measurement.measuredAt", externalId),
        rawData = rawData,
        profileId = profileId,
        syncedAt = currentTimeMillis(),
    )

    private fun IntegrationProgramDto.toDomain(
        fallbackProvider: IntegrationProvider,
        profileId: String,
    ): ExternalProgram = ExternalProgram(
        id = generateUUID(),
        externalId = externalId,
        provider = IntegrationProvider.fromKey(provider) ?: fallbackProvider,
        name = name,
        isCurrent = isCurrent,
        scriptText = scriptText,
        rawData = rawData,
        updatedAt = updatedAt?.parseEpochMillisOrNull("program.updatedAt", externalId),
        syncedAt = currentTimeMillis(),
        profileId = profileId,
    )

    private fun IntegrationProgramStatsDto.toDomain(localProgramId: String): ExternalProgramStats = ExternalProgramStats(
        id = generateUUID(),
        externalProgramId = localProgramId,
        days = days,
        approximateMinutes = approximateMinutes,
        setCount = setCount,
        muscleGroupBreakdownJson = muscleGroupBreakdownJson,
        rawData = rawData,
        computedAt = computedAt?.parseEpochMillisOrNull("programStats.computedAt", externalProgramId),
    )

    private fun IntegrationPlaygroundPreviewDto.toDomain(): ExternalPlaygroundPreview = ExternalPlaygroundPreview(
        programExternalId = programExternalId,
        currentWorkoutText = currentWorkoutText,
        nextWorkoutText = nextWorkoutText,
        updatedProgramText = updatedProgramText,
        commandsApplied = commandsApplied,
        rawData = rawData,
        generatedAt = generatedAt?.parseEpochMillisOrNull("playground.generatedAt", programExternalId) ?: currentTimeMillis(),
    )

    private fun IntegrationEntityErrorDto.toWarning(): IntegrationSyncWarning = IntegrationSyncWarning(
        entityType = entityType,
        code = code,
        message = message,
        retryAfterSeconds = retryAfterSeconds,
    )

    private fun IntegrationSyncResponse.toEntitlementState(provider: IntegrationProvider): IntegrationEntitlementState? {
        if (!requiresUpgrade && entitlementStatus == null && upgradeReason == null && providerPlanName == null) return null
        return IntegrationEntitlementState(
            provider = provider,
            status = entitlementStatus,
            requiresUpgrade = requiresUpgrade,
            upgradeReason = upgradeReason,
            providerPlanName = providerPlanName,
        )
    }

    private fun IntegrationSyncResult.merge(other: IntegrationSyncResult): IntegrationSyncResult = copy(
        progress = IntegrationSyncProgress(
            activitiesImported = progress.activitiesImported + other.progress.activitiesImported,
            routinesImported = progress.routinesImported + other.progress.routinesImported,
            routineFoldersImported = progress.routineFoldersImported + other.progress.routineFoldersImported,
            exerciseTemplatesImported = progress.exerciseTemplatesImported + other.progress.exerciseTemplatesImported,
            measurementsImported = progress.measurementsImported + other.progress.measurementsImported,
            programsImported = progress.programsImported + other.progress.programsImported,
            programStatsImported = progress.programStatsImported + other.progress.programStatsImported,
        ),
        entitlementState = other.entitlementState ?: entitlementState,
        partial = partial || other.partial,
        warnings = warnings + other.warnings,
        nextCursor = other.nextCursor ?: nextCursor,
        hasMore = other.hasMore,
    )

    private fun String.parseEpochMillisOrNow(field: String, externalId: String): Long =
        parseEpochMillisOrNull(field, externalId) ?: currentTimeMillis()

    private fun String.parseEpochMillisOrNull(field: String, externalId: String): Long? = try {
        Instant.parse(this).toEpochMilliseconds()
    } catch (_: Exception) {
        Logger.w("IntegrationManager") {
            "Could not parse $field '$this' for $externalId"
        }
        null
    }

    private companion object {
        val ALL_ENTITY_TYPES = listOf(
            "activities",
            "routines",
            "routineFolders",
            "templates",
            "measurements",
            "programs",
            "programStats",
        )
    }
}
