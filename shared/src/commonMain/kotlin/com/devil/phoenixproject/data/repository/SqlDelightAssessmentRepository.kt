package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PhoenixDatabase
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
 * Uses existing AssessmentResult queries from PhoenixDatabase.sq,
 * delegates session creation to [WorkoutRepository], and updates
 * the profile-scoped training baseline via [ProfileExerciseBaselineRepository].
 */
class SqlDelightAssessmentRepository(
    private val db: PhoenixDatabase,
    private val workoutRepository: WorkoutRepository,
    private val baselineRepository: ProfileExerciseBaselineRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AssessmentRepository {

    private val queries = db.phoenixDatabaseQueries
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
                var resultId: Long? = null
                db.transaction {
                    queries.insertAssessmentResult(
                        exerciseId = exerciseId,
                        estimatedOneRepMaxKg = estimatedOneRepMaxKg.toDouble(),
                        loadVelocityData = loadVelocityDataJson,
                        assessmentSessionId = sessionId,
                        userOverrideKg = userOverrideKg?.toDouble(),
                        createdAt = currentTimeMillis(),
                        profile_id = profileId,
                    )
                    resultId = queries.lastInsertRowId().executeAsOne()
                }
                requireNotNull(resultId)
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
            val finalOneRepMaxTotalKg = userOverrideKg ?: estimatedOneRepMaxKg
            val attemptedOneRepMaxPerCableKg = finalOneRepMaxTotalKg / 2f
            require(attemptedOneRepMaxPerCableKg.isFinite() && attemptedOneRepMaxPerCableKg > 0f) {
                "Assessment one-rep max must be finite and positive"
            }
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
            var baselineReceipt: AssessmentBaselineWriteReceipt? = null
            try {
                withContext(ioDispatcher) {
                    workoutRepository.saveSession(session)
                    db.transaction {
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
                    }

                    withContext(NonCancellable) {
                        baselineReceipt = baselineRepository.writeForAssessment(
                            profileId = profileId,
                            exerciseId = exerciseId,
                            oneRepMaxPerCableKg = attemptedOneRepMaxPerCableKg,
                            updatedAt = currentTimeMillis(),
                        )
                    }
                    Logger.d {
                        "Assessment saved for $exerciseName: " +
                            "$attemptedOneRepMaxPerCableKg kg per cable"
                    }
                    sessionId
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + ioDispatcher) {
                    baselineReceipt?.let { receipt ->
                        runCatching {
                            baselineRepository.compensateAssessmentWrite(
                                profileId = profileId,
                                exerciseId = exerciseId,
                                expectedWrittenRevision = receipt.written.revision,
                                previous = receipt.previous,
                            )
                        }
                    }
                    insertedResultId?.let { id ->
                        runCatching { queries.deleteAssessmentResult(id) }
                    }
                    runCatching { workoutRepository.discardSessionInternal(sessionId) }
                }
                if (failure is CancellationException) throw failure
                Logger.w(failure) {
                    "Assessment save failed; compensated session, result, and scoped baseline"
                }
                throw failure
            }
        }
    }
}
