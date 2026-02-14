package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Repository for fetching session data formatted for the SmartSuggestionsEngine.
 *
 * Bridges the gap between raw SQLDelight queries and the pure domain computation engine.
 * All methods return [SessionSummary] objects that can be passed directly to engine functions.
 */
interface SmartSuggestionsRepository {
    /**
     * Get session summaries since the given timestamp, joined with exercise muscle group data.
     * Used for volume analysis, balance analysis, and time-of-day analysis.
     */
    suspend fun getSessionSummariesSince(sinceTimestamp: Long): List<SessionSummary>

    /**
     * Get last-performed dates for all active exercises.
     * Used for neglect detection. Only exerciseId, exerciseName, muscleGroup, and timestamp
     * are populated; weight/rep fields default to 0.
     */
    suspend fun getExerciseLastPerformed(): List<SessionSummary>

    /**
     * Get per-exercise weight history ordered by timestamp.
     * Used for plateau detection. Only exerciseId, exerciseName, weightPerCableKg, and timestamp
     * are populated; muscleGroup defaults to exercise name, reps default to 0.
     */
    suspend fun getExerciseWeightHistory(): List<SessionSummary>
}

/**
 * SQLDelight implementation of [SmartSuggestionsRepository].
 *
 * Maps generated query result types to [SessionSummary] domain models,
 * handling NULL values from LEFT JOINs gracefully.
 */
class SqlDelightSmartSuggestionsRepository(
    private val database: VitruvianDatabase
) : SmartSuggestionsRepository {

    private val queries = database.vitruvianDatabaseQueries

    override suspend fun getSessionSummariesSince(sinceTimestamp: Long): List<SessionSummary> =
        withContext(Dispatchers.IO) {
            queries.selectSessionSummariesSince(sinceTimestamp).executeAsList().map { row ->
                SessionSummary(
                    exerciseId = row.exerciseId ?: "",
                    exerciseName = row.exerciseName ?: "Unknown",
                    muscleGroup = row.muscleGroup ?: row.exerciseName ?: "Unknown",
                    timestamp = row.timestamp,
                    weightPerCableKg = row.weightPerCableKg.toFloat(),
                    totalReps = row.totalReps.toInt(),
                    workingReps = row.workingReps.toInt()
                )
            }
        }

    override suspend fun getExerciseLastPerformed(): List<SessionSummary> =
        withContext(Dispatchers.IO) {
            queries.selectExerciseLastPerformed().executeAsList().map { row ->
                SessionSummary(
                    exerciseId = row.exerciseId,
                    exerciseName = row.exerciseName,
                    muscleGroup = row.muscleGroup,
                    timestamp = row.lastPerformed ?: 0L,
                    weightPerCableKg = 0f,
                    totalReps = 0,
                    workingReps = 0
                )
            }
        }

    override suspend fun getExerciseWeightHistory(): List<SessionSummary> =
        withContext(Dispatchers.IO) {
            queries.selectExerciseWeightHistory().executeAsList().map { row ->
                SessionSummary(
                    exerciseId = row.exerciseId ?: "",
                    exerciseName = row.exerciseName ?: "Unknown",
                    muscleGroup = row.exerciseName ?: "Unknown",
                    timestamp = row.timestamp,
                    weightPerCableKg = row.weightPerCableKg.toFloat(),
                    totalReps = 0,
                    workingReps = 0
                )
            }
        }
}
