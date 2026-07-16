package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AssessmentRepository {

    private val queries = db.vitruvianDatabaseQueries
    private val assessmentWriteMutex = Mutex()

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
    ): Long {
        require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
        return assessmentWriteMutex.withLock {
            withContext(ioDispatcher) {
                queries.insertAssessmentResult(
                    exerciseId = exerciseId,
                    estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
                    loadVelocityData = loadVelocityDataJson,
                    assessmentSessionId = sessionId,
                    userOverrideKg = userOverrideKg?.toDouble(),
                    createdAt = currentTimeMillis(),
                    profile_id = profileId,
                )
                queries.lastInsertRowId().executeAsOne()
            }
        }
    }

    override fun getAssessmentsByExercise(exerciseId: String, profileId: String): Flow<List<AssessmentResultEntity>> = queries.selectAssessmentsByExercise(
        exerciseId,
        profileId = profileId,
        mapper = ::mapToEntity,
    )
        .asFlow()
        .mapToList(ioDispatcher)

    override suspend fun getLatestAssessment(exerciseId: String, profileId: String): AssessmentResultEntity? = withContext(ioDispatcher) {
        queries.selectLatestAssessment(
            exerciseId,
            profileId = profileId,
            mapper = ::mapToEntity,
        ).executeAsOneOrNull()
    }

    override suspend fun deleteAssessment(id: Long) {
        withContext(ioDispatcher) {
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
    ): String {
        require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
        return assessmentWriteMutex.withLock {
            withContext(ioDispatcher) {
                val finalOneRepMaxTotalKg = userOverrideKg ?: estimatedOneRepMaxKg
                val attemptedOneRepMaxPerCableKg = finalOneRepMaxTotalKg / 2f
                val sessionId = generateUUID()
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

                var insertedResultId: Long? = null
                var previousOneRepMaxPerCableKg: Float? = null
                var exerciseWriteAttempted = false
                try {
                    workoutRepository.saveSession(session)
                    queries.insertAssessmentResult(
                        exerciseId = exerciseId,
                        estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
                        loadVelocityData = loadVelocityDataJson,
                        assessmentSessionId = sessionId,
                        userOverrideKg = userOverrideKg?.toDouble(),
                        createdAt = currentTimeMillis(),
                        profile_id = profileId,
                    )
                    insertedResultId = queries.lastInsertRowId().executeAsOne()

                    previousOneRepMaxPerCableKg =
                        exerciseRepository.getExerciseById(exerciseId)?.oneRepMaxKg
                    exerciseWriteAttempted = true
                    exerciseRepository.updateOneRepMax(
                        exerciseId,
                        attemptedOneRepMaxPerCableKg,
                    )
                    Logger.d {
                        "Assessment saved for $exerciseName: " +
                            "$attemptedOneRepMaxPerCableKg kg per cable"
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        if (exerciseWriteAttempted) {
                            runCatching {
                                queries.restoreOneRepMaxIfCurrent(
                                    previousOneRepMaxKg =
                                        previousOneRepMaxPerCableKg?.toDouble(),
                                    exerciseId = exerciseId,
                                    attemptedOneRepMaxKg =
                                        attemptedOneRepMaxPerCableKg.toDouble(),
                                )
                            }
                        }
                        insertedResultId?.let { id ->
                            runCatching { queries.deleteAssessmentResult(id) }
                        }
                        runCatching { workoutRepository.deleteSession(sessionId) }
                    }
                    if (failure is CancellationException) throw failure
                    Logger.w(failure) {
                        "Assessment save failed; compensated session, result, and exercise 1RM"
                    }
                    throw failure
                }

                sessionId
            }
        }
    }
}
