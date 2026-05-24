package com.devil.phoenixproject.data.integration

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplate
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplateMapping
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.ExternalRoutineExercise
import com.devil.phoenixproject.domain.model.ExternalRoutineFolder
import com.devil.phoenixproject.domain.model.ExternalRoutineSet
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val LIST_SEPARATOR = "|"

private fun List<String>.encodeList(): String = joinToString(LIST_SEPARATOR)
private fun String.decodeList(): List<String> = split(LIST_SEPARATOR).filter { it.isNotBlank() }
private fun providerFromKey(key: String): IntegrationProvider = IntegrationProvider.fromKey(key) ?: IntegrationProvider.UNKNOWN

class SqlDelightExternalRoutineRepository(db: VitruvianDatabase) : ExternalRoutineRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun com.devil.phoenixproject.database.ExternalRoutine.toDomain(
        exercises: List<ExternalRoutineExercise>,
    ): ExternalRoutine = ExternalRoutine(
        id = id,
        externalId = externalId,
        provider = providerFromKey(provider),
        title = title,
        folderExternalId = folderExternalId,
        folderName = folderName,
        updatedAt = updatedAt,
        exercises = exercises,
        rawData = rawData,
        profileId = profileId,
        syncedAt = syncedAt,
        needsSync = needsSync != 0L,
        deletedAt = deletedAt,
    )

    private fun com.devil.phoenixproject.database.ExternalRoutineExercise.toDomain(
        sets: List<ExternalRoutineSet>,
    ): ExternalRoutineExercise = ExternalRoutineExercise(
        id = id,
        externalRoutineId = externalRoutineId,
        externalExerciseTemplateId = externalExerciseTemplateId,
        title = title,
        exerciseType = exerciseType,
        primaryMuscleGroups = primaryMuscleGroups.decodeList(),
        secondaryMuscleGroups = secondaryMuscleGroups.decodeList(),
        orderIndex = orderIndex.toInt(),
        sets = sets,
        rawData = rawData,
    )

    private fun com.devil.phoenixproject.database.ExternalRoutineSet.toDomain(): ExternalRoutineSet = ExternalRoutineSet(
        id = id,
        externalRoutineExerciseId = externalRoutineExerciseId,
        index = setIndex.toInt(),
        setType = setType,
        weightKg = weightKg,
        reps = reps?.toInt(),
        minReps = minReps?.toInt(),
        maxReps = maxReps?.toInt(),
        restSeconds = restSeconds?.toInt(),
        rpe = rpe,
        durationSeconds = durationSeconds?.toInt(),
        distanceMeters = distanceMeters,
        rawData = rawData,
    )

    private fun com.devil.phoenixproject.database.ExternalRoutineFolder.toDomain(): ExternalRoutineFolder = ExternalRoutineFolder(
        id = id,
        externalId = externalId,
        provider = providerFromKey(provider),
        title = title,
        index = folderIndex?.toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        profileId = profileId,
        rawData = rawData,
    )

    override fun observeRoutines(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalRoutine>> {
        val query = if (provider == null) {
            queries.getExternalRoutines(profileId)
        } else {
            queries.getExternalRoutinesByProvider(profileId, provider.key)
        }
        return query.asFlow().mapToList(Dispatchers.IO).map { rows ->
            if (rows.isEmpty()) {
                emptyList()
            } else {
                val exerciseRows = queries.getExternalRoutineExercisesForRoutines(rows.map { it.id }).executeAsList()
                val setRows = if (exerciseRows.isEmpty()) {
                    emptyList()
                } else {
                    queries.getExternalRoutineSetsForExercises(exerciseRows.map { it.id }).executeAsList()
                }
                val setsByExerciseId = setRows
                    .groupBy { it.externalRoutineExerciseId }
                    .mapValues { (_, sets) -> sets.map { it.toDomain() } }
                val exercisesByRoutineId = exerciseRows
                    .map { exercise -> exercise.toDomain(setsByExerciseId[exercise.id].orEmpty()) }
                    .groupBy { it.externalRoutineId }
                rows.map { routine -> routine.toDomain(exercisesByRoutineId[routine.id].orEmpty()) }
            }
        }
    }

    override fun observeFolders(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalRoutineFolder>> {
        val query = if (provider == null) {
            queries.getExternalRoutineFolders(profileId)
        } else {
            queries.getExternalRoutineFoldersByProvider(profileId, provider.key)
        }
        return query.asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun upsertRoutines(routines: List<ExternalRoutine>) {
        if (routines.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (routine in routines) {
                    queries.insertExternalRoutineIfNew(
                        id = routine.id,
                        externalId = routine.externalId,
                        provider = routine.provider.key,
                        title = routine.title,
                        folderExternalId = routine.folderExternalId,
                        folderName = routine.folderName,
                        updatedAt = routine.updatedAt,
                        syncedAt = routine.syncedAt,
                        rawData = routine.rawData,
                        profileId = routine.profileId,
                        needsSync = if (routine.needsSync) 1L else 0L,
                        deletedAt = routine.deletedAt,
                    )
                    queries.updateExternalRoutineOnConflict(
                        title = routine.title,
                        folderExternalId = routine.folderExternalId,
                        folderName = routine.folderName,
                        updatedAt = routine.updatedAt,
                        syncedAt = routine.syncedAt,
                        rawData = routine.rawData,
                        deletedAt = routine.deletedAt,
                        provider = routine.provider.key,
                        externalId = routine.externalId,
                        profileId = routine.profileId,
                    )
                    val localRoutine = queries.getExternalRoutineBySyncKey(
                        provider = routine.provider.key,
                        externalId = routine.externalId,
                        profileId = routine.profileId,
                    ).executeAsOneOrNull()

                    if (localRoutine != null) {
                        queries.deleteExternalRoutineSetsByRoutine(localRoutine.id)
                        queries.deleteExternalRoutineExercisesByRoutine(localRoutine.id)
                        for (exercise in routine.exercises.sortedBy { it.orderIndex }) {
                            queries.insertExternalRoutineExercise(
                                id = exercise.id,
                                externalRoutineId = localRoutine.id,
                                externalExerciseTemplateId = exercise.externalExerciseTemplateId,
                                title = exercise.title,
                                exerciseType = exercise.exerciseType,
                                primaryMuscleGroups = exercise.primaryMuscleGroups.encodeList(),
                                secondaryMuscleGroups = exercise.secondaryMuscleGroups.encodeList(),
                                orderIndex = exercise.orderIndex.toLong(),
                                rawData = exercise.rawData,
                            )
                            for (set in exercise.sets.sortedBy { it.index }) {
                                queries.insertExternalRoutineSet(
                                    id = set.id,
                                    externalRoutineExerciseId = exercise.id,
                                    setIndex = set.index.toLong(),
                                    setType = set.setType,
                                    weightKg = set.weightKg,
                                    reps = set.reps?.toLong(),
                                    minReps = set.minReps?.toLong(),
                                    maxReps = set.maxReps?.toLong(),
                                    restSeconds = set.restSeconds?.toLong(),
                                    rpe = set.rpe,
                                    durationSeconds = set.durationSeconds?.toLong(),
                                    distanceMeters = set.distanceMeters,
                                    rawData = set.rawData,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun upsertFolders(folders: List<ExternalRoutineFolder>) {
        if (folders.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (folder in folders) {
                    queries.upsertExternalRoutineFolder(
                        id = folder.id,
                        externalId = folder.externalId,
                        provider = folder.provider.key,
                        title = folder.title,
                        folderIndex = folder.index?.toLong(),
                        createdAt = folder.createdAt,
                        updatedAt = folder.updatedAt,
                        profileId = folder.profileId,
                        rawData = folder.rawData,
                    )
                }
            }
        }
    }

    override suspend fun deleteProviderRoutines(provider: IntegrationProvider, profileId: String) = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteExternalRoutineSetsByProvider(provider.key, profileId)
            queries.deleteExternalRoutineExercisesByProvider(provider.key, profileId)
            queries.deleteExternalRoutinesByProvider(provider.key, profileId)
            queries.deleteExternalRoutineFoldersByProvider(provider.key, profileId)
        }
    }
}

class SqlDelightExternalProgramRepository(db: VitruvianDatabase) : ExternalProgramRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun com.devil.phoenixproject.database.ExternalProgram.toDomain(): ExternalProgram = ExternalProgram(
        id = id,
        externalId = externalId,
        provider = providerFromKey(provider),
        name = name,
        isCurrent = isCurrent != 0L,
        scriptText = scriptText,
        rawData = rawData,
        updatedAt = updatedAt,
        syncedAt = syncedAt,
        profileId = profileId,
        needsSync = needsSync != 0L,
        deletedAt = deletedAt,
    )

    private fun com.devil.phoenixproject.database.ExternalProgramStats.toDomain(): ExternalProgramStats = ExternalProgramStats(
        id = id,
        externalProgramId = externalProgramId,
        days = days?.toInt(),
        approximateMinutes = approximateMinutes?.toInt(),
        setCount = setCount?.toInt(),
        muscleGroupBreakdownJson = muscleGroupBreakdownJson,
        rawData = rawData,
        computedAt = computedAt,
    )

    override fun observePrograms(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalProgram>> {
        val query = if (provider == null) {
            queries.getExternalPrograms(profileId)
        } else {
            queries.getExternalProgramsByProvider(profileId, provider.key)
        }
        return query.asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeCurrentProgram(profileId: String, provider: IntegrationProvider): Flow<ExternalProgram?> =
        queries.getCurrentExternalProgram(profileId, provider.key)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it?.toDomain() }

    override fun observeProgramStats(profileId: String, provider: IntegrationProvider?): Flow<Map<String, ExternalProgramStats>> {
        val query = if (provider == null) {
            queries.getExternalProgramStatsByProfile(profileId)
        } else {
            queries.getExternalProgramStatsByProvider(profileId, provider.key)
        }
        return query.asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.associate { it.externalProgramId to it.toDomain() }
        }
    }

    override suspend fun findProgram(provider: IntegrationProvider, externalId: String, profileId: String): ExternalProgram? = withContext(Dispatchers.IO) {
        queries.getExternalProgramBySyncKey(provider.key, externalId, profileId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun findPrograms(provider: IntegrationProvider, externalIds: List<String>, profileId: String): List<ExternalProgram> = withContext(Dispatchers.IO) {
        if (externalIds.isEmpty()) {
            emptyList()
        } else {
            queries.getExternalProgramsBySyncKeys(provider.key, profileId, externalIds).executeAsList().map { it.toDomain() }
        }
    }

    override suspend fun upsertPrograms(programs: List<ExternalProgram>) {
        if (programs.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (program in programs) {
                    queries.insertExternalProgramIfNew(
                        id = program.id,
                        externalId = program.externalId,
                        provider = program.provider.key,
                        name = program.name,
                        isCurrent = if (program.isCurrent) 1L else 0L,
                        scriptText = program.scriptText,
                        rawData = program.rawData,
                        updatedAt = program.updatedAt,
                        syncedAt = program.syncedAt,
                        profileId = program.profileId,
                        needsSync = if (program.needsSync) 1L else 0L,
                        deletedAt = program.deletedAt,
                    )
                    queries.updateExternalProgramOnConflict(
                        name = program.name,
                        isCurrent = if (program.isCurrent) 1L else 0L,
                        scriptText = program.scriptText,
                        rawData = program.rawData,
                        updatedAt = program.updatedAt,
                        syncedAt = program.syncedAt,
                        deletedAt = program.deletedAt,
                        provider = program.provider.key,
                        externalId = program.externalId,
                        profileId = program.profileId,
                    )
                }
            }
        }
    }

    override suspend fun upsertStats(stats: List<ExternalProgramStats>) {
        if (stats.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (stat in stats) {
                    queries.insertExternalProgramStats(
                        id = stat.id,
                        externalProgramId = stat.externalProgramId,
                        days = stat.days?.toLong(),
                        approximateMinutes = stat.approximateMinutes?.toLong(),
                        setCount = stat.setCount?.toLong(),
                        muscleGroupBreakdownJson = stat.muscleGroupBreakdownJson,
                        rawData = stat.rawData,
                        computedAt = stat.computedAt,
                    )
                }
            }
        }
    }

    override suspend fun updateProgramText(programId: String, scriptText: String, markNeedsSync: Boolean) = withContext(Dispatchers.IO) {
        queries.updateExternalProgramText(
            scriptText = scriptText,
            updatedAt = currentTimeMillis(),
            syncedAt = currentTimeMillis(),
            needsSync = if (markNeedsSync) 1L else 0L,
            id = programId,
        )
        Unit
    }

    override suspend fun deleteProviderPrograms(provider: IntegrationProvider, profileId: String) = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteExternalProgramStatsByProvider(provider.key, profileId)
            queries.deleteExternalProgramsByProvider(provider.key, profileId)
        }
    }
}

class SqlDelightExternalMeasurementRepository(db: VitruvianDatabase) : ExternalMeasurementRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun com.devil.phoenixproject.database.ExternalBodyMeasurement.toDomain(): ExternalBodyMeasurement = ExternalBodyMeasurement(
        id = id,
        externalId = externalId,
        provider = providerFromKey(provider),
        measurementType = measurementType,
        value = value_,
        unit = unit,
        measuredAt = measuredAt,
        rawData = rawData,
        profileId = profileId,
        syncedAt = syncedAt,
    )

    override fun observeMeasurements(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalBodyMeasurement>> {
        val query = if (provider == null) {
            queries.getExternalBodyMeasurements(profileId)
        } else {
            queries.getExternalBodyMeasurementsByProvider(profileId, provider.key)
        }
        return query.asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeMeasurementsByType(profileId: String, measurementType: String): Flow<List<ExternalBodyMeasurement>> =
        queries.getExternalBodyMeasurementsByType(profileId, measurementType)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertMeasurements(measurements: List<ExternalBodyMeasurement>) {
        if (measurements.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (measurement in measurements) {
                    queries.upsertExternalBodyMeasurement(
                        id = measurement.id,
                        externalId = measurement.externalId,
                        provider = measurement.provider.key,
                        measurementType = measurement.measurementType,
                        value_ = measurement.value,
                        unit = measurement.unit,
                        measuredAt = measurement.measuredAt,
                        syncedAt = measurement.syncedAt,
                        rawData = measurement.rawData,
                        profileId = measurement.profileId,
                    )
                }
            }
        }
    }

    override suspend fun deleteProviderMeasurements(provider: IntegrationProvider, profileId: String) = withContext(Dispatchers.IO) {
        queries.deleteExternalBodyMeasurementsByProvider(provider.key, profileId)
        Unit
    }
}

class SqlDelightExternalExerciseTemplateRepository(db: VitruvianDatabase) : ExternalExerciseTemplateRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun com.devil.phoenixproject.database.ExternalExerciseTemplate.toDomain(): ExternalExerciseTemplate = ExternalExerciseTemplate(
        id = id,
        externalId = externalId,
        provider = providerFromKey(provider),
        title = title,
        exerciseType = exerciseType,
        primaryMuscleGroups = primaryMuscleGroups.decodeList(),
        secondaryMuscleGroups = secondaryMuscleGroups.decodeList(),
        isCustom = isCustom != 0L,
        rawData = rawData,
        updatedAt = updatedAt,
        profileId = profileId,
    )

    private fun com.devil.phoenixproject.database.ExternalExerciseTemplateMapping.toDomain(): ExternalExerciseTemplateMapping = ExternalExerciseTemplateMapping(
        id = id,
        provider = providerFromKey(provider),
        externalTemplateId = externalTemplateId,
        localExerciseId = localExerciseId,
        profileId = profileId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        rawData = rawData,
    )

    override fun observeTemplates(profileId: String, provider: IntegrationProvider?): Flow<List<ExternalExerciseTemplate>> {
        val query = if (provider == null) {
            queries.getExternalExerciseTemplates(profileId)
        } else {
            queries.getExternalExerciseTemplatesByProvider(profileId, provider.key)
        }
        return query.asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeTemplateCounts(profileId: String): Flow<Map<IntegrationProvider, Int>> =
        queries.countExternalExerciseTemplatesByProvider(profileId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.mapNotNull { row ->
                    IntegrationProvider.fromKey(row.provider)?.let { provider ->
                        provider to row.templateCount.toInt()
                    }
                }.toMap()
            }

    override suspend fun upsertTemplates(templates: List<ExternalExerciseTemplate>) {
        if (templates.isEmpty()) return
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (template in templates) {
                    queries.upsertExternalExerciseTemplate(
                        id = template.id,
                        externalId = template.externalId,
                        provider = template.provider.key,
                        title = template.title,
                        exerciseType = template.exerciseType,
                        primaryMuscleGroups = template.primaryMuscleGroups.encodeList(),
                        secondaryMuscleGroups = template.secondaryMuscleGroups.encodeList(),
                        isCustom = if (template.isCustom) 1L else 0L,
                        rawData = template.rawData,
                        updatedAt = template.updatedAt,
                        profileId = template.profileId,
                    )
                }
            }
        }
    }

    override suspend fun findTemplate(provider: IntegrationProvider, externalId: String, profileId: String): ExternalExerciseTemplate? = withContext(Dispatchers.IO) {
        queries.getExternalExerciseTemplateBySyncKey(provider.key, externalId, profileId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsertMapping(mapping: ExternalExerciseTemplateMapping) = withContext(Dispatchers.IO) {
        queries.upsertExternalExerciseTemplateMapping(
            id = mapping.id,
            provider = mapping.provider.key,
            externalTemplateId = mapping.externalTemplateId,
            localExerciseId = mapping.localExerciseId,
            profileId = mapping.profileId,
            createdAt = mapping.createdAt,
            updatedAt = mapping.updatedAt,
            rawData = mapping.rawData,
        )
        Unit
    }

    override suspend fun findMapping(
        provider: IntegrationProvider,
        externalTemplateId: String,
        profileId: String,
    ): ExternalExerciseTemplateMapping? = withContext(Dispatchers.IO) {
        queries.getExternalExerciseTemplateMapping(provider.key, externalTemplateId, profileId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun deleteProviderTemplates(provider: IntegrationProvider, profileId: String) = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteExternalExerciseTemplateMappingsByProvider(provider.key, profileId)
            queries.deleteExternalExerciseTemplatesByProvider(provider.key, profileId)
        }
    }
}

class SqlDelightIntegrationSyncCursorRepository(db: VitruvianDatabase) : IntegrationSyncCursorRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun com.devil.phoenixproject.database.IntegrationSyncCursor.toDomain(): IntegrationSyncCursor = IntegrationSyncCursor(
        provider = providerFromKey(provider),
        profileId = profileId,
        cursorType = cursorType,
        cursorValue = cursorValue,
        updatedAt = updatedAt,
    )

    override suspend fun getCursor(provider: IntegrationProvider, profileId: String, cursorType: String): IntegrationSyncCursor? = withContext(Dispatchers.IO) {
        queries.getIntegrationSyncCursor(provider.key, profileId, cursorType).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsertCursor(cursor: IntegrationSyncCursor) = withContext(Dispatchers.IO) {
        queries.upsertIntegrationSyncCursor(
            provider = cursor.provider.key,
            profileId = cursor.profileId,
            cursorType = cursor.cursorType,
            cursorValue = cursor.cursorValue,
            updatedAt = cursor.updatedAt,
        )
        Unit
    }

    override suspend fun deleteProviderCursors(provider: IntegrationProvider, profileId: String) = withContext(Dispatchers.IO) {
        queries.deleteIntegrationSyncCursorsByProvider(provider.key, profileId)
        Unit
    }
}
