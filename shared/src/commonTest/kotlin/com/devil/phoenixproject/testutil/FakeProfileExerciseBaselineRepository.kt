package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.LegacyExerciseBaseline
import com.devil.phoenixproject.data.repository.AssessmentBaselineWriteReceipt
import com.devil.phoenixproject.data.repository.ProfileExerciseBaseline
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository

class FakeProfileExerciseBaselineRepository : ProfileExerciseBaselineRepository {
    private val rows = mutableMapOf<Pair<String, String>, ProfileExerciseBaseline>()

    fun seed(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float?,
        updatedAt: Long = 1L,
    ) {
        rows[profileId to exerciseId] = ProfileExerciseBaseline(
            profileId,
            exerciseId,
            oneRepMaxPerCableKg,
            updatedAt,
            revision = 1L,
        )
    }

    override suspend fun get(profileId: String, exerciseId: String): ProfileExerciseBaseline? =
        rows[profileId to exerciseId]

    override suspend fun getAllForProfile(profileId: String): List<ProfileExerciseBaseline> =
        rows.values.filter { it.profileId == profileId }

    override suspend fun set(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float?,
        updatedAt: Long,
    ): ProfileExerciseBaseline {
        val key = profileId to exerciseId
        val row = ProfileExerciseBaseline(
            profileId = profileId,
            exerciseId = exerciseId,
            oneRepMaxPerCableKg = oneRepMaxPerCableKg,
            updatedAt = updatedAt,
            revision = (rows[key]?.revision ?: 0L) + 1L,
        )
        rows[key] = row
        return row
    }

    override suspend fun writeForAssessment(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float,
        updatedAt: Long,
    ): AssessmentBaselineWriteReceipt {
        val previous = rows[profileId to exerciseId]
        val written = set(profileId, exerciseId, oneRepMaxPerCableKg, updatedAt)
        return AssessmentBaselineWriteReceipt(previous = previous, written = written)
    }

    override suspend fun increment(
        profileId: String,
        exerciseId: String,
        incrementKg: Float,
        updatedAt: Long,
    ): ProfileExerciseBaseline? {
        val current = rows[profileId to exerciseId]
        val value = current?.oneRepMaxPerCableKg ?: return null
        return set(
            profileId = profileId,
            exerciseId = exerciseId,
            oneRepMaxPerCableKg = value + incrementKg,
            updatedAt = maxOf(current.updatedAt, updatedAt),
        )
    }

    override suspend fun compensateAssessmentWrite(
        profileId: String,
        exerciseId: String,
        expectedWrittenRevision: Long,
        previous: ProfileExerciseBaseline?,
    ): Boolean {
        val key = profileId to exerciseId
        if (rows[key]?.revision != expectedWrittenRevision) return false
        if (previous == null) rows.remove(key) else rows[key] = previous.copy(revision = expectedWrittenRevision + 1L)
        return true
    }

    override suspend fun getLegacyBaselines(): List<LegacyExerciseBaseline> = emptyList()

    override suspend fun copyAndConsumeLegacyForSoleProfile(profileId: String, updatedAt: Long): Int = 0

    override suspend fun assignAndConsumeLegacy(
        profileId: String,
        legacy: LegacyExerciseBaseline,
        updatedAt: Long,
    ): ProfileExerciseBaseline = set(
        profileId,
        legacy.exerciseId,
        legacy.oneRepMaxPerCableKg,
        updatedAt,
    )

    override suspend fun copyForProfileDeletion(sourceProfileId: String, targetProfileId: String) {
        rows.values.filter { it.profileId == sourceProfileId }.forEach { source ->
            rows.getOrPut(targetProfileId to source.exerciseId) {
                source.copy(profileId = targetProfileId)
            }
        }
    }

    override fun mergeForProfileRecoveryInCurrentTransaction(
        sourceProfileId: String,
        targetProfileId: String,
    ) {
        rows.values.filter { it.profileId == sourceProfileId }.toList().forEach { source ->
            rows.getOrPut(targetProfileId to source.exerciseId) { source.copy(profileId = targetProfileId) }
            rows.remove(sourceProfileId to source.exerciseId)
        }
    }

    override suspend fun remapExercise(oldExerciseId: String, newExerciseId: String) {
        rows.values.filter { it.exerciseId == oldExerciseId }.forEach { source ->
            val targetKey = source.profileId to newExerciseId
            val target = rows[targetKey]
            if (target == null || source.updatedAt > target.updatedAt) {
                rows[targetKey] = source.copy(exerciseId = newExerciseId)
            }
            rows.remove(source.profileId to oldExerciseId)
        }
    }
}
