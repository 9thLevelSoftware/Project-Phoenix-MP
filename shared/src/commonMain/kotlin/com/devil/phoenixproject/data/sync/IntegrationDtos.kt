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
    val apiKey: String? = null,
    val entityTypes: List<String>? = null,
    val cursor: String? = null,
    val forceFullRefresh: Boolean? = null,
    val includeRawData: Boolean? = null,
)

@Serializable
data class IntegrationSyncResponse(
    val status: String,
    val activities: List<IntegrationActivityDto> = emptyList(),
    val routines: List<IntegrationRoutineDto> = emptyList(),
    val routineFolders: List<IntegrationRoutineFolderDto> = emptyList(),
    val exerciseTemplates: List<IntegrationExerciseTemplateDto> = emptyList(),
    val bodyMeasurements: List<IntegrationBodyMeasurementDto> = emptyList(),
    val programs: List<IntegrationProgramDto> = emptyList(),
    val programStats: List<IntegrationProgramStatsDto> = emptyList(),
    val playground: IntegrationPlaygroundPreviewDto? = null,
    val requiresUpgrade: Boolean = false,
    val upgradeReason: String? = null,
    val providerPlanName: String? = null,
    val entitlementStatus: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val partial: Boolean = false,
    val deletedExternalIds: List<String> = emptyList(),
    val updatedExternalIds: List<String> = emptyList(),
    val providerSyncCursor: String? = null,
    val errors: List<IntegrationEntityErrorDto> = emptyList(),
    val error: String? = null,
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
    val rawData: String? = null,
)

@Serializable
data class IntegrationRoutineDto(
    val externalId: String,
    val provider: String,
    val title: String,
    val folderExternalId: String? = null,
    val folderName: String? = null,
    val updatedAt: String? = null,
    val exercises: List<IntegrationRoutineExerciseDto> = emptyList(),
    val rawData: String? = null,
)

@Serializable
data class IntegrationRoutineExerciseDto(
    val externalExerciseTemplateId: String? = null,
    val title: String,
    val exerciseType: String? = null,
    val primaryMuscleGroups: List<String> = emptyList(),
    val secondaryMuscleGroups: List<String> = emptyList(),
    val orderIndex: Int = 0,
    val sets: List<IntegrationRoutineSetDto> = emptyList(),
    val rawData: String? = null,
)

@Serializable
data class IntegrationRoutineSetDto(
    val index: Int = 0,
    val setType: String? = null,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val minReps: Int? = null,
    val maxReps: Int? = null,
    val restSeconds: Int? = null,
    val rpe: Double? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val rawData: String? = null,
)

@Serializable
data class IntegrationRoutineFolderDto(
    val externalId: String,
    val provider: String,
    val title: String,
    val index: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val rawData: String? = null,
)

@Serializable
data class IntegrationExerciseTemplateDto(
    val externalId: String,
    val provider: String,
    val title: String,
    val exerciseType: String? = null,
    val primaryMuscleGroups: List<String> = emptyList(),
    val secondaryMuscleGroups: List<String> = emptyList(),
    val isCustom: Boolean = false,
    val rawData: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class IntegrationBodyMeasurementDto(
    val externalId: String,
    val provider: String,
    val measurementType: String,
    val value: Double,
    val unit: String,
    val measuredAt: String,
    val rawData: String? = null,
)

@Serializable
data class IntegrationProgramDto(
    val externalId: String,
    val provider: String,
    val name: String,
    val isCurrent: Boolean = false,
    val scriptText: String? = null,
    val rawData: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class IntegrationProgramStatsDto(
    val externalProgramId: String,
    val days: Int? = null,
    val approximateMinutes: Int? = null,
    val setCount: Int? = null,
    val muscleGroupBreakdownJson: String? = null,
    val rawData: String? = null,
    val computedAt: String? = null,
)

@Serializable
data class IntegrationPlaygroundPreviewDto(
    val programExternalId: String,
    val currentWorkoutText: String? = null,
    val nextWorkoutText: String? = null,
    val updatedProgramText: String? = null,
    val commandsApplied: List<String> = emptyList(),
    val rawData: String? = null,
    val generatedAt: String? = null,
)

@Serializable
data class IntegrationEntityErrorDto(
    val entityType: String,
    val code: String? = null,
    val message: String,
    val retryAfterSeconds: Int? = null,
)

@Serializable
data class IntegrationPlaygroundSimulationRequest(
    val provider: String,
    val externalProgramId: String,
    val scriptText: String,
    val commands: List<String> = emptyList(),
    val profileId: String,
)

@Serializable
data class IntegrationPlaygroundSimulationResponse(
    val status: String,
    val preview: IntegrationPlaygroundPreviewDto? = null,
    val updatedProgramText: String? = null,
    val error: String? = null,
    val requiresUpgrade: Boolean = false,
)
