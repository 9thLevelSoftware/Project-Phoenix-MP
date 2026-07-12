package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.AssessmentResultEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeAssessmentRepository : AssessmentRepository {
    data class SavedSession(
        val exerciseId: String,
        val estimatedOneRepMaxTotalKg: Float,
        val userOverrideTotalKg: Float?,
        val weightPerCableKg: Float,
        val profileId: String,
    )

    val attemptedSessions = mutableListOf<SavedSession>()
    val savedSessions = mutableListOf<SavedSession>()
    var saveSessionFailure: Throwable? = null
    private val assessments = mutableListOf<AssessmentResultEntity>()

    override suspend fun saveAssessment(
        exerciseId: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        sessionId: String?,
        userOverrideKg: Float?,
        profileId: String,
    ): Long {
        require(profileId.isNotBlank())
        val id = assessments.size.toLong() + 1L
        assessments += AssessmentResultEntity(
            id = id,
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = estimatedOneRepMaxKg,
            loadVelocityData = loadVelocityDataJson,
            assessmentSessionId = sessionId,
            userOverrideKg = userOverrideKg,
            createdAt = id,
            profileId = profileId,
        )
        return id
    }

    override fun getAssessmentsByExercise(
        exerciseId: String,
        profileId: String,
    ): Flow<List<AssessmentResultEntity>> = flowOf(
        assessments.filter { it.exerciseId == exerciseId && it.profileId == profileId }
            .sortedByDescending { it.createdAt },
    )

    override suspend fun getLatestAssessment(
        exerciseId: String,
        profileId: String,
    ): AssessmentResultEntity? = assessments
        .filter { it.exerciseId == exerciseId && it.profileId == profileId }
        .maxByOrNull { it.createdAt }

    override suspend fun deleteAssessment(id: Long) {
        assessments.removeAll { it.id == id }
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
        require(profileId.isNotBlank())
        val captured = SavedSession(
            exerciseId = exerciseId,
            estimatedOneRepMaxTotalKg = estimatedOneRepMaxKg,
            userOverrideTotalKg = userOverrideKg,
            weightPerCableKg = weightPerCableKg,
            profileId = profileId,
        )
        attemptedSessions += captured
        saveSessionFailure?.let { throw it }
        savedSessions += captured
        val sessionId = "assessment-session-${savedSessions.size}"
        saveAssessment(
            exerciseId = exerciseId,
            estimatedOneRepMaxKg = estimatedOneRepMaxKg,
            loadVelocityDataJson = loadVelocityDataJson,
            sessionId = sessionId,
            userOverrideKg = userOverrideKg,
            profileId = profileId,
        )
        return sessionId
    }
}
