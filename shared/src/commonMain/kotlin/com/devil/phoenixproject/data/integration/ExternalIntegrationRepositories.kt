package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplate
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplateMapping
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.ExternalRoutineFolder
import com.devil.phoenixproject.domain.model.IntegrationProvider
import kotlinx.coroutines.flow.Flow

data class IntegrationSyncCursor(
    val provider: IntegrationProvider,
    val profileId: String,
    val cursorType: String,
    val cursorValue: String?,
    val updatedAt: Long,
)

interface ExternalRoutineRepository {
    fun observeRoutines(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalRoutine>>
    fun observeFolders(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalRoutineFolder>>
    suspend fun upsertRoutines(routines: List<ExternalRoutine>)
    suspend fun upsertFolders(folders: List<ExternalRoutineFolder>)
    suspend fun deleteProviderRoutines(provider: IntegrationProvider, profileId: String)
}

interface ExternalProgramRepository {
    fun observePrograms(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalProgram>>
    fun observeCurrentProgram(profileId: String, provider: IntegrationProvider): Flow<ExternalProgram?>
    fun observeProgramStats(profileId: String, provider: IntegrationProvider? = null): Flow<Map<String, ExternalProgramStats>>
    suspend fun findProgram(provider: IntegrationProvider, externalId: String, profileId: String): ExternalProgram?
    suspend fun findPrograms(provider: IntegrationProvider, externalIds: List<String>, profileId: String): List<ExternalProgram> =
        externalIds.mapNotNull { externalId -> findProgram(provider, externalId, profileId) }
    suspend fun upsertPrograms(programs: List<ExternalProgram>)
    suspend fun upsertStats(stats: List<ExternalProgramStats>)
    suspend fun updateProgramText(programId: String, scriptText: String, markNeedsSync: Boolean)
    suspend fun deleteProviderPrograms(provider: IntegrationProvider, profileId: String)
}

interface ExternalMeasurementRepository {
    fun observeMeasurements(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalBodyMeasurement>>
    fun observeMeasurementsByType(profileId: String, measurementType: String): Flow<List<ExternalBodyMeasurement>>
    suspend fun upsertMeasurements(measurements: List<ExternalBodyMeasurement>)
    suspend fun deleteProviderMeasurements(provider: IntegrationProvider, profileId: String)
}

interface ExternalExerciseTemplateRepository {
    fun observeTemplates(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalExerciseTemplate>>
    fun observeTemplateCounts(profileId: String): Flow<Map<IntegrationProvider, Int>>
    suspend fun upsertTemplates(templates: List<ExternalExerciseTemplate>)
    suspend fun findTemplate(provider: IntegrationProvider, externalId: String, profileId: String): ExternalExerciseTemplate?
    suspend fun upsertMapping(mapping: ExternalExerciseTemplateMapping)
    suspend fun findMapping(provider: IntegrationProvider, externalTemplateId: String, profileId: String): ExternalExerciseTemplateMapping?
    suspend fun deleteProviderTemplates(provider: IntegrationProvider, profileId: String)
}

interface IntegrationSyncCursorRepository {
    suspend fun getCursor(provider: IntegrationProvider, profileId: String, cursorType: String): IntegrationSyncCursor?
    suspend fun upsertCursor(cursor: IntegrationSyncCursor)
    suspend fun deleteProviderCursors(provider: IntegrationProvider, profileId: String)
}
