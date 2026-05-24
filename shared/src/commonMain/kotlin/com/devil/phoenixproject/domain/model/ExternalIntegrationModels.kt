package com.devil.phoenixproject.domain.model

data class ExternalRoutine(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val title: String,
    val folderExternalId: String? = null,
    val folderName: String? = null,
    val updatedAt: Long? = null,
    val exercises: List<ExternalRoutineExercise> = emptyList(),
    val rawData: String? = null,
    val profileId: String = "default",
    val syncedAt: Long = currentTimeMillis(),
    val needsSync: Boolean = false,
    val deletedAt: Long? = null,
)

data class ExternalRoutineExercise(
    val id: String = generateUUID(),
    val externalRoutineId: String,
    val externalExerciseTemplateId: String? = null,
    val title: String,
    val exerciseType: String? = null,
    val primaryMuscleGroups: List<String> = emptyList(),
    val secondaryMuscleGroups: List<String> = emptyList(),
    val orderIndex: Int = 0,
    val sets: List<ExternalRoutineSet> = emptyList(),
    val rawData: String? = null,
)

data class ExternalRoutineSet(
    val id: String = generateUUID(),
    val externalRoutineExerciseId: String,
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

data class ExternalRoutineFolder(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val title: String,
    val index: Int? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val profileId: String = "default",
    val rawData: String? = null,
)

data class ExternalProgram(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val name: String,
    val isCurrent: Boolean = false,
    val scriptText: String? = null,
    val rawData: String? = null,
    val updatedAt: Long? = null,
    val syncedAt: Long = currentTimeMillis(),
    val profileId: String = "default",
    val needsSync: Boolean = false,
    val deletedAt: Long? = null,
)

data class ExternalProgramStats(
    val id: String = generateUUID(),
    val externalProgramId: String,
    val days: Int? = null,
    val approximateMinutes: Int? = null,
    val setCount: Int? = null,
    val muscleGroupBreakdownJson: String? = null,
    val rawData: String? = null,
    val computedAt: Long? = null,
)

data class ExternalExerciseTemplate(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val title: String,
    val exerciseType: String? = null,
    val primaryMuscleGroups: List<String> = emptyList(),
    val secondaryMuscleGroups: List<String> = emptyList(),
    val isCustom: Boolean = false,
    val rawData: String? = null,
    val updatedAt: Long? = null,
    val profileId: String = "default",
)

data class ExternalExerciseTemplateMapping(
    val id: String = generateUUID(),
    val provider: IntegrationProvider,
    val externalTemplateId: String,
    val localExerciseId: String,
    val profileId: String = "default",
    val createdAt: Long = currentTimeMillis(),
    val updatedAt: Long = currentTimeMillis(),
    val rawData: String? = null,
)

data class ExternalBodyMeasurement(
    val id: String = generateUUID(),
    val externalId: String,
    val provider: IntegrationProvider,
    val measurementType: String,
    val value: Double,
    val unit: String,
    val measuredAt: Long,
    val rawData: String? = null,
    val profileId: String = "default",
    val syncedAt: Long = currentTimeMillis(),
)

data class ExternalPlaygroundPreview(
    val programExternalId: String,
    val currentWorkoutText: String? = null,
    val nextWorkoutText: String? = null,
    val updatedProgramText: String? = null,
    val commandsApplied: List<String> = emptyList(),
    val rawData: String? = null,
    val generatedAt: Long = currentTimeMillis(),
)

data class IntegrationEntitlementState(
    val provider: IntegrationProvider,
    val status: String? = null,
    val requiresUpgrade: Boolean = false,
    val upgradeReason: String? = null,
    val providerPlanName: String? = null,
    val retryAfterSeconds: Int? = null,
)

data class IntegrationSyncWarning(
    val entityType: String,
    val message: String,
    val code: String? = null,
    val retryAfterSeconds: Int? = null,
)

data class IntegrationSyncProgress(
    val activitiesImported: Int = 0,
    val routinesImported: Int = 0,
    val routineFoldersImported: Int = 0,
    val exerciseTemplatesImported: Int = 0,
    val measurementsImported: Int = 0,
    val programsImported: Int = 0,
    val programStatsImported: Int = 0,
)

data class IntegrationSyncResult(
    val provider: IntegrationProvider,
    val progress: IntegrationSyncProgress = IntegrationSyncProgress(),
    val entitlementState: IntegrationEntitlementState? = null,
    val partial: Boolean = false,
    val warnings: List<IntegrationSyncWarning> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)
