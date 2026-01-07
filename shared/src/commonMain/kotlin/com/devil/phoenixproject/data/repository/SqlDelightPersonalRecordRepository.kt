package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
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
        volume: Double
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
            volume = volume.toFloat()
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
                val brokenPRs = updatePRsIfBetterInternal(exerciseId, weightPerCableKg, reps, workoutMode, timestamp)
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
                .filter { it.workoutMode == workoutMode }
                .maxByOrNull { it.weightPerCableKg }
        }
    }

    override suspend fun getVolumePR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return withContext(Dispatchers.IO) {
            queries.selectRecordsByExercise(exerciseId, ::mapToPR)
                .executeAsList()
                .filter { it.workoutMode == workoutMode }
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
                val brokenPRs = updatePRsIfBetterInternal(exerciseId, weightPerCableKg, reps, workoutMode, timestamp)
                Result.success(brokenPRs)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Internal implementation that checks and updates both weight and volume PRs
     */
    private suspend fun updatePRsIfBetterInternal(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): List<PRType> {
        val brokenPRs = mutableListOf<PRType>()
        val newVolume = weightPerCableKg * reps

        // Check weight PR
        val currentWeightPR = getWeightPR(exerciseId, workoutMode)
        val isNewWeightPR = currentWeightPR == null || weightPerCableKg > currentWeightPR.weightPerCableKg

        // Check volume PR
        val currentVolumePR = getVolumePR(exerciseId, workoutMode)
        val currentVolume = (currentVolumePR?.weightPerCableKg ?: 0f) * (currentVolumePR?.reps ?: 0)
        val isNewVolumePR = newVolume > currentVolume

        if (isNewWeightPR) {
            // Calculate Epley 1RM
            val oneRepMax = if (reps == 1) weightPerCableKg else weightPerCableKg * (1 + reps / 30f)

            // Use upsertPR to handle both insert and update (fixes unique constraint crash)
            queries.upsertPR(
                exerciseId = exerciseId,
                exerciseName = "", // Will be resolved by join if needed
                weight = weightPerCableKg.toDouble(),
                reps = reps.toLong(),
                oneRepMax = oneRepMax.toDouble(),
                achievedAt = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_WEIGHT.name,
                volume = newVolume.toDouble()
            )

            // Sync 1RM to Exercise table for %-based training features
            queries.updateOneRepMax(
                one_rep_max_kg = oneRepMax.toDouble(),
                id = exerciseId
            )

            brokenPRs.add(PRType.MAX_WEIGHT)
        }

        if (isNewVolumePR && !isNewWeightPR) {
            // Only insert volume PR if it's not also a weight PR (avoid duplicates)
            val oneRepMax = if (reps == 1) weightPerCableKg else weightPerCableKg * (1 + reps / 30f)

            // Use upsertPR to handle both insert and update (fixes unique constraint crash)
            queries.upsertPR(
                exerciseId = exerciseId,
                exerciseName = "",
                weight = weightPerCableKg.toDouble(),
                reps = reps.toLong(),
                oneRepMax = oneRepMax.toDouble(),
                achievedAt = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_VOLUME.name,
                volume = newVolume.toDouble()
            )
            brokenPRs.add(PRType.MAX_VOLUME)
        } else if (isNewVolumePR) {
            brokenPRs.add(PRType.MAX_VOLUME)
        }

        return brokenPRs
    }
}
