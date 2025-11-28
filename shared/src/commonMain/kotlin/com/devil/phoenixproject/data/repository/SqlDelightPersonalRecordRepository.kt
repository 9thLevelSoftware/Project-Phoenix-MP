package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
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
        workoutMode: String
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
                val currentBest = getBestPR(exerciseId)
                val currentVolume = (currentBest?.weightPerCableKg ?: 0f) * (currentBest?.reps ?: 0)
                val newVolume = weightPerCableKg * reps

                if (newVolume > currentVolume) {
                    // Calculate Epley 1RM
                    val oneRepMax = weightPerCableKg * (1 + reps / 30f)
                    
                    queries.insertRecord(
                        exerciseId = exerciseId,
                        exerciseName = "Unknown", // TODO: Fetch name
                        weight = weightPerCableKg.toDouble(),
                        reps = reps.toLong(),
                        oneRepMax = oneRepMax.toDouble(),
                        achievedAt = timestamp,
                        workoutMode = workoutMode
                    )
                    Result.success(true)
                } else {
                    Result.success(false)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
