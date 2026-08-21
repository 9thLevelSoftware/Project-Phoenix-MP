package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseCableIntent
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SqlDelightExerciseRepository(
    db: VitruvianDatabase,
    private val exerciseImporter: ExerciseImporter,
    private val preferencesManager: PreferencesManager,
) : ExerciseRepository {

    private val queries = db.vitruvianDatabaseQueries

    // Mapper function to convert database entity to Domain Model
    // Parameters match the column order in the Exercise table
    private fun mapToExercise(
        id: String,
        name: String,
        displayName: String?,
        description: String?,
        created: Long,
        muscleGroup: String,
        muscleGroups: String,
        muscles: String?,
        equipment: String,
        movement: String?,
        sidedness: String?,
        grip: String?,
        gripWidth: String?,
        minRepRange: Double?,
        popularity: Double,
        archived: Long,
        isFavorite: Long,
        isCustom: Long,
        timesPerformed: Long,
        lastPerformed: Long?,
        aliases: String?,
        defaultCableConfig: String,
        one_rep_max_kg: Double?,
        // Sync fields (migration 11)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?,
        // Per-exercise MVT override (migration 37)
        mvtOverrideMs: Double?,
        // Explicit bodyweight classification (migration 39, #635); null = derive from equipment
        isBodyweight: Long?,
    ): Exercise = Exercise(
        id = id,
        name = name,
        muscleGroup = muscleGroup,
        muscleGroups = muscleGroups,
        equipment = equipment,
        isFavorite = isFavorite == 1L,
        isCustom = isCustom == 1L,
        timesPerformed = timesPerformed.toInt(),
        oneRepMaxKg = one_rep_max_kg?.toFloat(),
        cableIntent = resolveCableIntent(
            sidedness = sidedness,
            defaultCableConfig = defaultCableConfig,
            isCustom = isCustom == 1L,
        ),
        displayName = displayName ?: name,
        mvtOverrideMs = mvtOverrideMs?.toFloat(),
        isBodyweightOverride = isBodyweight?.let { it != 0L },
    )

    private fun resolveCableIntent(
        sidedness: String?,
        defaultCableConfig: String,
        isCustom: Boolean,
    ): ExerciseCableIntent? {
        if (isCustom) return null

        return when (sidedness?.trim()?.lowercase()) {
            "single", "unilateral" -> ExerciseCableIntent.SINGLE

            "double", "bilateral" -> ExerciseCableIntent.DUAL

            "alternating" -> ExerciseCableIntent.EITHER

            else -> when (defaultCableConfig.trim().uppercase()) {
                "SINGLE" -> ExerciseCableIntent.SINGLE
                "EITHER" -> ExerciseCableIntent.EITHER
                else -> null
            }
        }
    }

    override fun getAllExercises(): Flow<List<Exercise>> = queries.selectAllExercises(::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun searchExercises(query: String): Flow<List<Exercise>> = queries.searchExercises(query, ::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun filterByMuscleGroup(muscleGroup: String): Flow<List<Exercise>> = queries.filterExercisesByMuscle(muscleGroup, ::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun filterByEquipment(equipment: String): Flow<List<Exercise>> = queries.filterExercisesByEquipment(equipment, ::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun getFavorites(): Flow<List<Exercise>> = queries.selectFavorites(::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun toggleFavorite(id: String) {
        withContext(Dispatchers.IO) {
            val exercise = queries.selectExerciseById(id).executeAsOneOrNull()
            if (exercise != null) {
                val newStatus = if (exercise.isFavorite == 1L) 0L else 1L
                queries.updateFavorite(newStatus, id)
            }
        }
    }

    override suspend fun getExerciseById(id: String): Exercise? = withContext(Dispatchers.IO) {
        queries.selectExerciseById(id, ::mapToExercise).executeAsOneOrNull()
    }

    override suspend fun getImages(exerciseId: String): List<ExerciseImageEntity> = withContext(Dispatchers.IO) {
        queries.selectImagesByExercise(exerciseId).executeAsList().map {
            ExerciseImageEntity(
                id = it.id,
                exerciseId = it.exerciseId,
                url = it.url,
                sortOrder = it.sortOrder.toInt(),
            )
        }
    }

    override suspend fun importExercises(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentSource = preferencesManager.getExerciseCatalogSource()
            if (currentSource == ExerciseImporter.BUNDLED_CATALOG_SOURCE) {
                Logger.d { "Exercise catalogue already imported ($currentSource)" }
                return@withContext Result.success(Unit)
            }

            Logger.d { "Importing bundled free-exercise-db catalogue..." }
            val result = exerciseImporter.importExercises()
            val importedCount = result.getOrNull() ?: 0
            if (result.isSuccess && importedCount > 0) {
                preferencesManager.setExerciseCatalogSource(ExerciseImporter.BUNDLED_CATALOG_SOURCE)
                Logger.d { "Successfully imported $importedCount exercises" }
                Result.success(Unit)
            } else {
                result.exceptionOrNull()?.let { Result.failure(it) }
                    ?: Result.failure(Exception("Import produced no exercises"))
            }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to import exercises" }
            Result.failure(e)
        }
    }

    override suspend fun isExerciseLibraryEmpty(): Boolean = withContext(Dispatchers.IO) {
        val count = queries.countExercises().executeAsOne()
        count == 0L
    }

    override suspend fun updateFromWger(): Result<Int> = exerciseImporter.updateFromWger()

    // ========== Custom Exercise Management ==========

    override fun getCustomExercises(): Flow<List<Exercise>> = queries.selectCustomExercises(::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun createCustomExercise(exercise: Exercise): Result<Exercise> = withContext(Dispatchers.IO) {
        try {
            // Generate a unique ID for custom exercises
            val customId = "custom_${currentTimeMillis()}"

            queries.insertExercise(
                id = customId,
                name = exercise.name,
                displayName = null, // Custom exercises use name directly
                description = null, // Custom exercises start without description
                created = currentTimeMillis(),
                muscleGroup = exercise.muscleGroup,
                muscleGroups = exercise.muscleGroups,
                muscles = null,
                equipment = exercise.equipment,
                movement = null,
                sidedness = null,
                grip = null,
                gripWidth = null,
                minRepRange = null,
                popularity = 0.0,
                archived = 0L,
                isFavorite = if (exercise.isFavorite) 1L else 0L,
                isCustom = 1L, // Always mark as custom
                timesPerformed = 0L,
                lastPerformed = null,
                aliases = null,
                defaultCableConfig = "DOUBLE", // Legacy field - no longer used
                one_rep_max_kg = exercise.oneRepMaxKg?.toDouble(),
                mvtOverrideMs = exercise.mvtOverrideMs?.toDouble(),
                // Custom exercises carry no explicit flag; classification derives from
                // their equipment token (HANDLES/BODYWEIGHT set by CreateExerciseDialog).
                isBodyweight = null,
            )

            Logger.d { "Created custom exercise: ${exercise.name} with ID: $customId" }

            // Return the created exercise with the generated ID
            Result.success(exercise.copy(id = customId, isCustom = true, cableIntent = null))
        } catch (e: Exception) {
            Logger.e(e) { "Failed to create custom exercise: ${exercise.name}" }
            Result.failure(e)
        }
    }

    override suspend fun updateCustomExercise(exercise: Exercise): Result<Exercise> {
        return withContext(Dispatchers.IO) {
            try {
                val exerciseId = exercise.id
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Exercise ID is required for update"),
                    )

                // Verify it's a custom exercise
                val existing = queries.selectExerciseById(exerciseId).executeAsOneOrNull()
                if (existing == null) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Exercise not found: $exerciseId"),
                    )
                }
                if (existing.isCustom != 1L) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Cannot update non-custom exercise"),
                    )
                }

                queries.updateCustomExercise(
                    name = exercise.name,
                    description = null, // Custom exercise description (not in domain model yet)
                    muscleGroup = exercise.muscleGroup,
                    muscleGroups = exercise.muscleGroups,
                    muscles = null,
                    equipment = exercise.equipment,
                    movement = null,
                    sidedness = null,
                    grip = null,
                    gripWidth = null,
                    minRepRange = null,
                    aliases = null,
                    defaultCableConfig = "DOUBLE", // Legacy field - no longer used
                    one_rep_max_kg = exercise.oneRepMaxKg?.toDouble(),
                    id = exerciseId,
                )

                Logger.d { "Updated custom exercise: ${exercise.name}" }
                Result.success(exercise.copy(cableIntent = null))
            } catch (e: Exception) {
                Logger.e(e) { "Failed to update custom exercise: ${exercise.name}" }
                Result.failure(e)
            }
        }
    }

    override suspend fun deleteCustomExercise(exerciseId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Verify it's a custom exercise
                val existing = queries.selectExerciseById(exerciseId).executeAsOneOrNull()
                if (existing == null) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Exercise not found: $exerciseId"),
                    )
                }
                if (existing.isCustom != 1L) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Cannot delete non-custom exercise"),
                    )
                }

                queries.deleteCustomExercise(exerciseId)

                Logger.d { "Deleted custom exercise: $exerciseId" }
                Result.success(Unit)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to delete custom exercise: $exerciseId" }
                Result.failure(e)
            }
        }
    }

    // ========== One Rep Max Management ==========

    override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
        withContext(Dispatchers.IO) {
            queries.updateOneRepMax(oneRepMaxKg?.toDouble(), exerciseId)
        }
    }

    override fun getExercisesWithOneRepMax(): Flow<List<Exercise>> = queries.getExercisesWithOneRepMax(::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun findByName(name: String): Exercise? = withContext(Dispatchers.IO) {
        queries.findExerciseByName(name, ::mapToExercise).executeAsOneOrNull()
    }

    override suspend fun findByIdOrName(id: String?, name: String): Exercise? {
        return withContext(Dispatchers.IO) {
            // Strategy 1: Direct ID lookup (fastest, most reliable)
            if (id != null) {
                val byId = queries.selectExerciseById(id, ::mapToExercise).executeAsOneOrNull()
                if (byId != null) return@withContext byId
            }

            // Strategy 2: Exact name match (uses TRIM for trailing space tolerance)
            val byName = queries.findExerciseByName(name, ::mapToExercise).executeAsOneOrNull()
            if (byName != null) return@withContext byName

            // Strategy 3: Fuzzy search - take first result
            val searchResults = queries.searchExercises(name, ::mapToExercise).executeAsList()
            searchResults.firstOrNull()
        }
    }
}
