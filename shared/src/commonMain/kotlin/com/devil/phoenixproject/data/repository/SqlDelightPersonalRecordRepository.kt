package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.max

class SqlDelightPersonalRecordRepository(
    db: VitruvianDatabase
) : PersonalRecordRepository {
    private val queries = db.vitruvianDatabaseQueries

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
            weightPerCableKg = weight.toFloat(),
            reps = reps.toInt(),
            timestamp = achievedAt,
            workoutMode = workoutMode
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
                .maxByOrNull { it.weightPerCableKg * it.reps } // Sort by volume or 1RM
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
                    // Return the best PR for each exercise
                    prs.maxByOrNull { it.weightPerCableKg * it.reps }
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

            queries.insertRecord(
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
            brokenPRs.add(PRType.MAX_WEIGHT)
        }

        if (isNewVolumePR && !isNewWeightPR) {
            // Only insert volume PR if it's not also a weight PR (avoid duplicates)
            val oneRepMax = if (reps == 1) weightPerCableKg else weightPerCableKg * (1 + reps / 30f)

            queries.insertRecord(
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
