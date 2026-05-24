package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.integration.ExternalExerciseTemplateRepository
import com.devil.phoenixproject.data.integration.ExternalMeasurementRepository
import com.devil.phoenixproject.data.integration.ExternalProgramRepository
import com.devil.phoenixproject.data.integration.ExternalRoutineRepository
import com.devil.phoenixproject.data.integration.IntegrationSyncCursor
import com.devil.phoenixproject.data.integration.IntegrationSyncCursorRepository
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplate
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplateMapping
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.ExternalRoutineFolder
import com.devil.phoenixproject.domain.model.IntegrationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeExternalRoutineRepository : ExternalRoutineRepository {
    val routines = mutableListOf<ExternalRoutine>()
    val folders = mutableListOf<ExternalRoutineFolder>()

    override fun observeRoutines(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalRoutine>> = MutableStateFlow(
        routines.filter { it.profileId == profileId && (provider == null || it.provider == provider) },
    )

    override fun observeFolders(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalRoutineFolder>> = MutableStateFlow(
        folders.filter { it.profileId == profileId && (provider == null || it.provider == provider) },
    )

    override suspend fun upsertRoutines(routines: List<ExternalRoutine>) {
        for (routine in routines) {
            val existing = this.routines.indexOfFirst {
                it.provider == routine.provider &&
                    it.externalId == routine.externalId &&
                    it.profileId == routine.profileId
            }
            if (existing >= 0) {
                this.routines[existing] = routine.copy(id = this.routines[existing].id)
            } else {
                this.routines += routine
            }
        }
    }

    override suspend fun upsertFolders(folders: List<ExternalRoutineFolder>) {
        for (folder in folders) {
            val existing = this.folders.indexOfFirst {
                it.provider == folder.provider &&
                    it.externalId == folder.externalId &&
                    it.profileId == folder.profileId
            }
            if (existing >= 0) {
                this.folders[existing] = folder.copy(id = this.folders[existing].id)
            } else {
                this.folders += folder
            }
        }
    }

    override suspend fun deleteProviderRoutines(provider: IntegrationProvider, profileId: String) {
        routines.removeAll { it.provider == provider && it.profileId == profileId }
        folders.removeAll { it.provider == provider && it.profileId == profileId }
    }
}

class FakeExternalProgramRepository : ExternalProgramRepository {
    val programs = mutableListOf<ExternalProgram>()
    val stats = mutableListOf<ExternalProgramStats>()

    override fun observePrograms(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalProgram>> = MutableStateFlow(
        programs.filter { it.profileId == profileId && (provider == null || it.provider == provider) },
    )

    override fun observeCurrentProgram(profileId: String, provider: IntegrationProvider): Flow<ExternalProgram?> = MutableStateFlow(
        programs.firstOrNull { it.profileId == profileId && it.provider == provider && it.isCurrent },
    )

    override fun observeProgramStats(profileId: String, provider: IntegrationProvider?): Flow<Map<String, ExternalProgramStats>> = MutableStateFlow(
        stats.associateBy { it.externalProgramId },
    )

    override suspend fun findProgram(provider: IntegrationProvider, externalId: String, profileId: String): ExternalProgram? =
        programs.firstOrNull { it.provider == provider && it.externalId == externalId && it.profileId == profileId }

    override suspend fun upsertPrograms(programs: List<ExternalProgram>) {
        for (program in programs) {
            val existing = this.programs.indexOfFirst {
                it.provider == program.provider &&
                    it.externalId == program.externalId &&
                    it.profileId == program.profileId
            }
            if (existing >= 0) {
                this.programs[existing] = program.copy(id = this.programs[existing].id)
            } else {
                this.programs += program
            }
        }
    }

    override suspend fun upsertStats(stats: List<ExternalProgramStats>) {
        for (stat in stats) {
            this.stats.removeAll { it.externalProgramId == stat.externalProgramId }
            this.stats += stat
        }
    }

    override suspend fun updateProgramText(programId: String, scriptText: String, markNeedsSync: Boolean) {
        val index = programs.indexOfFirst { it.id == programId }
        if (index >= 0) {
            programs[index] = programs[index].copy(scriptText = scriptText, needsSync = markNeedsSync)
        }
    }

    override suspend fun deleteProviderPrograms(provider: IntegrationProvider, profileId: String) {
        val removedIds = programs.filter { it.provider == provider && it.profileId == profileId }.map { it.id }.toSet()
        programs.removeAll { it.id in removedIds }
        stats.removeAll { it.externalProgramId in removedIds }
    }
}

class FakeExternalMeasurementRepository : ExternalMeasurementRepository {
    val measurements = mutableListOf<ExternalBodyMeasurement>()

    override fun observeMeasurements(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalBodyMeasurement>> = MutableStateFlow(
        measurements.filter { it.profileId == profileId && (provider == null || it.provider == provider) },
    )

    override fun observeMeasurementsByType(profileId: String, measurementType: String): Flow<List<ExternalBodyMeasurement>> = MutableStateFlow(
        measurements.filter { it.profileId == profileId && it.measurementType == measurementType },
    )

    override suspend fun upsertMeasurements(measurements: List<ExternalBodyMeasurement>) {
        for (measurement in measurements) {
            this.measurements.removeAll {
                it.provider == measurement.provider &&
                    it.externalId == measurement.externalId &&
                    it.profileId == measurement.profileId
            }
            this.measurements += measurement
        }
    }

    override suspend fun deleteProviderMeasurements(provider: IntegrationProvider, profileId: String) {
        measurements.removeAll { it.provider == provider && it.profileId == profileId }
    }
}

class FakeExternalExerciseTemplateRepository : ExternalExerciseTemplateRepository {
    val templates = mutableListOf<ExternalExerciseTemplate>()
    val mappings = mutableListOf<ExternalExerciseTemplateMapping>()

    override fun observeTemplates(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalExerciseTemplate>> = MutableStateFlow(
        templates.filter { it.profileId == profileId && (provider == null || it.provider == provider) },
    )

    override fun observeTemplateCounts(profileId: String): Flow<Map<IntegrationProvider, Int>> = MutableStateFlow(
        templates.filter { it.profileId == profileId }.groupingBy { it.provider }.eachCount(),
    )

    override suspend fun upsertTemplates(templates: List<ExternalExerciseTemplate>) {
        for (template in templates) {
            this.templates.removeAll {
                it.provider == template.provider &&
                    it.externalId == template.externalId &&
                    it.profileId == template.profileId
            }
            this.templates += template
        }
    }

    override suspend fun findTemplate(provider: IntegrationProvider, externalId: String, profileId: String): ExternalExerciseTemplate? =
        templates.firstOrNull { it.provider == provider && it.externalId == externalId && it.profileId == profileId }

    override suspend fun upsertMapping(mapping: ExternalExerciseTemplateMapping) {
        mappings.removeAll {
            it.provider == mapping.provider &&
                it.externalTemplateId == mapping.externalTemplateId &&
                it.profileId == mapping.profileId
        }
        mappings += mapping
    }

    override suspend fun findMapping(
        provider: IntegrationProvider,
        externalTemplateId: String,
        profileId: String,
    ): ExternalExerciseTemplateMapping? = mappings.firstOrNull {
        it.provider == provider &&
            it.externalTemplateId == externalTemplateId &&
            it.profileId == profileId
    }

    override suspend fun deleteProviderTemplates(provider: IntegrationProvider, profileId: String) {
        templates.removeAll { it.provider == provider && it.profileId == profileId }
        mappings.removeAll { it.provider == provider && it.profileId == profileId }
    }
}

class FakeIntegrationSyncCursorRepository : IntegrationSyncCursorRepository {
    val cursors = mutableListOf<IntegrationSyncCursor>()

    override suspend fun getCursor(provider: IntegrationProvider, profileId: String, cursorType: String): IntegrationSyncCursor? =
        cursors.firstOrNull {
            it.provider == provider && it.profileId == profileId && it.cursorType == cursorType
        }

    override suspend fun upsertCursor(cursor: IntegrationSyncCursor) {
        cursors.removeAll {
            it.provider == cursor.provider &&
                it.profileId == cursor.profileId &&
                it.cursorType == cursor.cursorType
        }
        cursors += cursor
    }

    override suspend fun deleteProviderCursors(provider: IntegrationProvider, profileId: String) {
        cursors.removeAll { it.provider == provider && it.profileId == profileId }
    }
}
