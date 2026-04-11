package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of [AssessmentRepository].
 *
 * Uses existing AssessmentResult queries from VitruvianDatabase.sq,
 * delegates session creation to [WorkoutRepository], and updates
 * exercise 1RM via [ExerciseRepository].
 */
class SqlDelightAssessmentRepository(
    db: VitruvianDatabase,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
) : AssessmentRepository {

    private val queries = db.vitruvianDatabaseQueries

    companion object {
        /** Marker used in WorkoutSession.routineName to identify assessment sessions. */
        const val ASSESSMENT_ROUTINE_NAME = "__ASSESSMENT__"
    }

    private fun mapToEntity(
        id: Long,
        exerciseId: String,
        estimatedOneRepMaxKg: Double,
        loadVelocityData: String,
        assessmentSessionId: String?,
        userOverrideKg: Double?,
        createdAt: Long,
        // Multi-profile support (migration 21)
        profileId: String,
    ): AssessmentResultEntity = AssessmentResultEntity(
        id = id,
        exerciseId = exerciseId,
        estimatedOneRepMaxKg = estimatedOneRepMaxKg.toFloat(),
        loadVelocityData = loadVelocityData,
        assessmentSessionId = assessmentSessionId,
        userOverrideKg = userOverrideKg?.toFloat(),
        createdAt = createdAt,
        profileId = profileId,
    )

    override suspend fun saveAssessment(
        exerciseId: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        sessionId: String?,
        userOverrideKg: Float?,
        profileId: String,
    ): Long = withContext(Dispatchers.IO) {
        queries.insertAssessmentResult(
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
            loadVelocityData = loadVelocityDataJson,
            assessmentSessionId = sessionId,
            userOverrideKg = userOverrideKg?.toDouble(),
            createdAt = currentTimeMillis(),
            profile_id = profileId,
        )
        // Return the last inserted row ID
        queries.lastInsertRowId().executeAsOne()
    }

    override fun getAssessmentsByExercise(exerciseId: String, profileId: String): Flow<List<AssessmentResultEntity>> = queries.selectAssessmentsByExercise(
        exerciseId,
        profileId = profileId,
        mapper = ::mapToEntity,
    )
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun getLatestAssessment(exerciseId: String, profileId: String): AssessmentResultEntity? = withContext(Dispatchers.IO) {
        queries.selectLatestAssessment(
            exerciseId,
            profileId = profileId,
            mapper = ::mapToEntity,
        ).executeAsOneOrNull()
    }

    override suspend fun deleteAssessment(id: Long) {
        withContext(Dispatchers.IO) {
            queries.deleteAssessmentResult(id)
        }
    }

    override suspend fun saveAssessmentSession(
        exerciseId: String,
        exerciseName: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        userOverrideKg: Float?,
        totalReps: Int,
        durationMs: Long,
        weightPerCableKg: Float,
        profileId: String,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = generateUUID()

        // 1. Create WorkoutSession with __ASSESSMENT__ marker
        val session = WorkoutSession(
            id = sessionId,
            timestamp = currentTimeMillis(),
            mode = "OldSchool",
            reps = totalReps,
            weightPerCableKg = weightPerCableKg,
            duration = durationMs,
            totalReps = totalReps,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            routineName = ASSESSMENT_ROUTINE_NAME,
            profileId = profileId,
        )
        workoutRepository.saveSession(session)
        Logger.d { "Assessment session created: $sessionId for exercise $exerciseName" }

        // 2. Insert AssessmentResult linked to the session
        queries.insertAssessmentResult(
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
            loadVelocityData = loadVelocityDataJson,
            assessmentSessionId = sessionId,
            userOverrideKg = userOverrideKg?.toDouble(),
            createdAt = currentTimeMillis(),
            profile_id = profileId,
        )
        Logger.d {
            "Assessment result saved for exercise $exerciseName (1RM: ${estimatedOneRepMaxKg}kg)"
        }

        // 3. Update exercise 1RM (prefer user override if provided)
        val finalOneRepMax = userOverrideKg ?: estimatedOneRepMaxKg
        exerciseRepository.updateOneRepMax(exerciseId, finalOneRepMax)
        Logger.d { "Exercise 1RM updated: $exerciseName -> ${finalOneRepMax}kg" }

        sessionId
    }
}
