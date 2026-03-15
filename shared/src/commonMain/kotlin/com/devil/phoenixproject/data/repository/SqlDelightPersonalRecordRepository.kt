package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightPersonalRecordRepository(
    db: VitruvianDatabase
) : PersonalRecordRepository {
    private val queries = db.vitruvianDatabaseQueries

    // SQLDelight mapper - parameters must match query columns even if not all are used
    private fun mapToPR(
        id: Long,
        exerciseId: String,
        exerciseName: String,
        weight: Double,
        reps: Long,
        oneRepMax: Double,
        achievedAt: Long,
        workoutMode: String,
        prType: String,
        volume: Double,
        phase: String,
        // Sync fields (migration 6)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?
    ): PersonalRecord {
        return PersonalRecord(
            id = id,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            weightPerCableKg = weight.toFloat(),
            reps = reps.toInt(),
            oneRepMax = oneRepMax.toFloat(),
            timestamp = achievedAt,
            workoutMode = workoutMode,
            prType = when (prType) {
                "MAX_VOLUME" -> PRType.MAX_VOLUME
                else -> PRType.MAX_WEIGHT
            },
            volume = volume.toFloat(),
            phase = when (phase) {
                "CONCENTRIC" -> WorkoutPhase.CONCENTRIC
                "ECCENTRIC" -> WorkoutPhase.ECCENTRIC
                else -> WorkoutPhase.COMBINED
            }
        )
    }

    override suspend fun getLatestPR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .filter { it.workoutMode == workoutMode }
                .maxByOrNull { it.timestamp }
        }
    }

    override fun getPRsForExercise(exerciseId: String): Flow<List<PersonalRecord>> {
        return queries.selectRecordsByExercise(exerciseId, ::mapToPR)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun getBestPR(exerciseId: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .maxByOrNull { it.weightPerCableKg } // Sort by weight (parity with parent repo)
        }
    }

    override fun getAllPRs(): Flow<List<PersonalRecord>> {
        return queries.selectAllRecords(::mapToPR)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override fun getAllPRsGrouped(): Flow<List<PersonalRecord>> {
        return getAllPRs().map { records ->
            records.groupBy { it.exerciseId }
                .mapNotNull { (_, prs) ->
                    // Return the best PR for each exercise (by weight, parity with parent repo)
                    prs.maxByOrNull { it.weightPerCableKg }
                }
        }
    }

    override suspend fun updatePRIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val brokenPRs = updatePRsIfBetterInternal(
                    exerciseId, weightPerCableKg, reps, workoutMode, timestamp,
                    WorkoutPhase.COMBINED
                )
                Result.success(brokenPRs.isNotEmpty())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ========== Volume/Weight PR Methods ==========

    override suspend fun getWeightPR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .filter { it.workoutMode == workoutMode && it.phase == WorkoutPhase.COMBINED }
                .maxByOrNull { it.weightPerCableKg }
        }
    }

    override suspend fun getVolumePR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .filter { it.workoutMode == workoutMode && it.phase == WorkoutPhase.COMBINED }
                .maxByOrNull { it.weightPerCableKg * it.reps }
        }
    }

    override suspend fun getBestWeightPR(exerciseId: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .maxByOrNull { it.weightPerCableKg }
        }
    }

    override suspend fun getBestVolumePR(exerciseId: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .maxByOrNull { it.weightPerCableKg * it.reps }
        }
    }

    override suspend fun getBestWeightPR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectBestWeightPRByMode(exerciseId, workoutMode, ::mapToPR)
                .executeAsOneOrNull()
        }
    }

    override suspend fun getBestVolumePR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectBestVolumePRByMode(exerciseId, workoutMode, ::mapToPR)
                .executeAsOneOrNull()
        }
    }

    override suspend fun getAllPRsForExercise(exerciseId: String): List<PersonalRecord> {
        return withContext(Dispatchers.IO) {
            queries.selectAllPRsForExercise(exerciseId, ::mapToPR)
                .executeAsList()
        }
    }

    override suspend fun updatePRsIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<List<PRType>> {
        return withContext(Dispatchers.IO) {
            try {
                val brokenPRs = updatePRsIfBetterInternal(
                    exerciseId, weightPerCableKg, reps, workoutMode, timestamp,
                    WorkoutPhase.COMBINED
                )
                Result.success(brokenPRs)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun updatePhaseSpecificPRs(
        exerciseId: String,
        workoutMode: String,
        timestamp: Long,
        reps: Int,
        peakConcentricForceKg: Float,
        peakEccentricForceKg: Float
    ): Result<List<WorkoutPhase>> {
        return withContext(Dispatchers.IO) {
            try {
                val brokenPhases = mutableListOf<WorkoutPhase>()

                // Check concentric PR (peak force during lifting)
                if (peakConcentricForceKg > 0f) {
                    val broken = updatePRsIfBetterInternal(
                        exerciseId = exerciseId,
                        weightPerCableKg = peakConcentricForceKg,
                        reps = reps,
                        workoutMode = workoutMode,
                        timestamp = timestamp,
                        phase = WorkoutPhase.CONCENTRIC
                    )
                    if (broken.isNotEmpty()) brokenPhases.add(WorkoutPhase.CONCENTRIC)
                }

                // Check eccentric PR (peak force during lowering)
                if (peakEccentricForceKg > 0f) {
                    val broken = updatePRsIfBetterInternal(
                        exerciseId = exerciseId,
                        weightPerCableKg = peakEccentricForceKg,
                        reps = reps,
                        workoutMode = workoutMode,
                        timestamp = timestamp,
                        phase = WorkoutPhase.ECCENTRIC
                    )
                    if (broken.isNotEmpty()) brokenPhases.add(WorkoutPhase.ECCENTRIC)
                }

                Result.success(brokenPhases)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Internal implementation that checks and updates both weight and volume PRs
     * for a specific phase (COMBINED, CONCENTRIC, or ECCENTRIC).
     */
    private fun updatePRsIfBetterInternal(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long,
        phase: WorkoutPhase
    ): List<PRType> {
        val brokenPRs = mutableListOf<PRType>()
        val newVolume = weightPerCableKg * reps
        val estimatedOneRepMax = OneRepMaxCalculator.epley(weightPerCableKg, reps)
        val phaseName = phase.name

        // Check weight PR for this phase
        val currentWeightPR = queries.selectPR(exerciseId, workoutMode, PRType.MAX_WEIGHT.name, phaseName, ::mapToPR)
            .executeAsOneOrNull()
        val isNewWeightPR = currentWeightPR == null || weightPerCableKg > currentWeightPR.weightPerCableKg

        // Check volume PR for this phase
        val currentVolumePR = queries.selectPR(exerciseId, workoutMode, PRType.MAX_VOLUME.name, phaseName, ::mapToPR)
            .executeAsOneOrNull()
        val currentVolume = (currentVolumePR?.weightPerCableKg ?: 0f) * (currentVolumePR?.reps ?: 0)
        val isNewVolumePR = newVolume > currentVolume

        if (isNewWeightPR) {
            queries.upsertPR(
                exerciseId = exerciseId,
                exerciseName = "",
                weight = weightPerCableKg.toDouble(),
                reps = reps.toLong(),
                oneRepMax = estimatedOneRepMax.toDouble(),
                achievedAt = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_WEIGHT.name,
                volume = newVolume.toDouble(),
                phase = phaseName
            )
            brokenPRs.add(PRType.MAX_WEIGHT)
        }

        if (isNewVolumePR && !isNewWeightPR) {
            queries.upsertPR(
                exerciseId = exerciseId,
                exerciseName = "",
                weight = weightPerCableKg.toDouble(),
                reps = reps.toLong(),
                oneRepMax = estimatedOneRepMax.toDouble(),
                achievedAt = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_VOLUME.name,
                volume = newVolume.toDouble(),
                phase = phaseName
            )
            brokenPRs.add(PRType.MAX_VOLUME)
        } else if (isNewVolumePR) {
            brokenPRs.add(PRType.MAX_VOLUME)
        }

        // Sync estimated 1RM to Exercise table for %-based training features.
        // Only update from COMBINED phase PRs to keep the canonical 1RM stable.
        if (phase == WorkoutPhase.COMBINED && brokenPRs.isNotEmpty()) {
            val currentExercise1RM = queries.selectExerciseById(exerciseId)
                .executeAsOneOrNull()?.one_rep_max_kg?.toFloat() ?: 0f
            if (estimatedOneRepMax > currentExercise1RM) {
                queries.updateOneRepMax(
                    one_rep_max_kg = estimatedOneRepMax.toDouble(),
                    id = exerciseId
                )
            }
        }

        return brokenPRs
    }
}
