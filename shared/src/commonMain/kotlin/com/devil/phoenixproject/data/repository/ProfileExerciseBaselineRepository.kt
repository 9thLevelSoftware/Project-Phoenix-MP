package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ProfileExerciseBaseline(
    val profileId: String,
    val exerciseId: String,
    val oneRepMaxPerCableKg: Float?,
    val updatedAt: Long,
    val revision: Long,
)

data class LegacyExerciseBaseline(
    val exerciseId: String,
    val oneRepMaxPerCableKg: Float,
    /** Exact persisted value used as the compare-and-consume token. */
    val sourceOneRepMaxPerCableKg: Double = oneRepMaxPerCableKg.toDouble(),
)

data class AssessmentBaselineWriteReceipt(
    val previous: ProfileExerciseBaseline?,
    val written: ProfileExerciseBaseline,
)

data class LegacyBaselineRepairResult(
    val copiedCount: Int,
    val ambiguous: List<LegacyExerciseBaseline>,
)

interface ProfileExerciseBaselineRepository {
    suspend fun get(profileId: String, exerciseId: String): ProfileExerciseBaseline?

    suspend fun getAllForProfile(profileId: String): List<ProfileExerciseBaseline>

    suspend fun set(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float?,
        updatedAt: Long,
    ): ProfileExerciseBaseline

    /** Atomically snapshots and replaces the row, returning proof of the committed write. */
    suspend fun writeForAssessment(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float,
        updatedAt: Long,
    ): AssessmentBaselineWriteReceipt

    /** Atomically increments an existing non-null baseline; returns null when none exists. */
    suspend fun increment(
        profileId: String,
        exerciseId: String,
        incrementKg: Float,
        updatedAt: Long,
    ): ProfileExerciseBaseline?

    suspend fun compensateAssessmentWrite(
        profileId: String,
        exerciseId: String,
        expectedWrittenRevision: Long,
        previous: ProfileExerciseBaseline?,
    ): Boolean

    suspend fun getLegacyBaselines(): List<LegacyExerciseBaseline>

    suspend fun copyAndConsumeLegacyForSoleProfile(
        profileId: String,
        updatedAt: Long,
    ): Int

    suspend fun assignAndConsumeLegacy(
        profileId: String,
        legacy: LegacyExerciseBaseline,
        updatedAt: Long,
    ): ProfileExerciseBaseline

    suspend fun copyForProfileDeletion(sourceProfileId: String, targetProfileId: String)

    /** Caller owns the surrounding database transaction. Target rows win collisions. */
    fun mergeForProfileRecoveryInCurrentTransaction(sourceProfileId: String, targetProfileId: String)

    suspend fun remapExercise(oldExerciseId: String, newExerciseId: String)
}

class LegacyBaselineRepair(
    private val baselines: ProfileExerciseBaselineRepository,
) {
    suspend fun reconcileAfterProfileBootstrap(
        profiles: List<UserProfile>,
    ): LegacyBaselineRepairResult {
        val legacy = baselines.getLegacyBaselines()
        if (profiles.size != 1) {
            return LegacyBaselineRepairResult(copiedCount = 0, ambiguous = legacy)
        }
        val copied = baselines.copyAndConsumeLegacyForSoleProfile(
            profileId = profiles.single().id,
            updatedAt = currentTimeMillis(),
        )
        return LegacyBaselineRepairResult(copiedCount = copied, ambiguous = emptyList())
    }

    suspend fun assignAndConsume(
        profileId: String,
        legacy: LegacyExerciseBaseline,
        updatedAt: Long = currentTimeMillis(),
    ): ProfileExerciseBaseline = baselines.assignAndConsumeLegacy(
        profileId = profileId,
        legacy = legacy,
        updatedAt = updatedAt,
    )
}

class SqlDelightProfileExerciseBaselineRepository(
    private val database: PhoenixDatabase,
) : ProfileExerciseBaselineRepository {
    private val queries = database.phoenixDatabaseQueries

    override suspend fun get(profileId: String, exerciseId: String): ProfileExerciseBaseline? =
        withContext(Dispatchers.IO) {
            validateIds(profileId, exerciseId)
            select(profileId, exerciseId)
        }

    override suspend fun getAllForProfile(profileId: String): List<ProfileExerciseBaseline> =
        withContext(Dispatchers.IO) {
            require(profileId.isNotBlank()) { "Baseline profileId must not be blank" }
            queries.selectProfileExerciseBaselinesByProfile(profileId, ::mapBaseline).executeAsList()
        }

    override suspend fun set(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float?,
        updatedAt: Long,
    ): ProfileExerciseBaseline = withContext(Dispatchers.IO) {
        validateIds(profileId, exerciseId)
        validateValue(oneRepMaxPerCableKg)
        setInTransaction(profileId, exerciseId, oneRepMaxPerCableKg, updatedAt)
    }

    override suspend fun writeForAssessment(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float,
        updatedAt: Long,
    ): AssessmentBaselineWriteReceipt = withContext(NonCancellable + Dispatchers.IO) {
        validateIds(profileId, exerciseId)
        validateValue(oneRepMaxPerCableKg)
        var receipt: AssessmentBaselineWriteReceipt? = null
        database.transaction {
            val previous = select(profileId, exerciseId)
            val written = setInCurrentTransaction(
                profileId = profileId,
                exerciseId = exerciseId,
                oneRepMaxPerCableKg = oneRepMaxPerCableKg,
                updatedAt = updatedAt,
            )
            receipt = AssessmentBaselineWriteReceipt(previous = previous, written = written)
        }
        requireNotNull(receipt)
    }

    override suspend fun increment(
        profileId: String,
        exerciseId: String,
        incrementKg: Float,
        updatedAt: Long,
    ): ProfileExerciseBaseline? = withContext(Dispatchers.IO) {
        validateIds(profileId, exerciseId)
        require(incrementKg.isFinite() && incrementKg > 0f) {
            "Baseline increment must be finite and positive"
        }
        var incremented: ProfileExerciseBaseline? = null
        database.transaction {
            queries.incrementProfileExerciseBaseline(
                profileId = profileId,
                exerciseId = exerciseId,
                incrementKg = incrementKg.toDouble(),
                updatedAt = updatedAt,
            )
            if (queries.selectChangedRowCount().executeAsOne() == 1L) {
                incremented = select(profileId, exerciseId)
            }
        }
        incremented
    }

    override suspend fun compensateAssessmentWrite(
        profileId: String,
        exerciseId: String,
        expectedWrittenRevision: Long,
        previous: ProfileExerciseBaseline?,
    ): Boolean = withContext(Dispatchers.IO) {
        validateIds(profileId, exerciseId)
        require(expectedWrittenRevision > 0L) { "Expected baseline revision must be positive" }
        if (previous != null) {
            require(previous.profileId == profileId && previous.exerciseId == exerciseId) {
                "Previous baseline must match the compensated profile and exercise"
            }
            validateValue(previous.oneRepMaxPerCableKg)
        }

        var restored = false
        database.transaction {
            val current = select(profileId, exerciseId)
            if (current?.revision != expectedWrittenRevision) return@transaction

            if (previous == null) {
                queries.deleteProfileExerciseBaseline(profileId, exerciseId)
                restored = queries.selectChangedRowCount().executeAsOne() == 1L
            } else {
                queries.updateProfileExerciseBaselineAtRevision(
                    oneRepMaxPerCableKg = previous.oneRepMaxPerCableKg?.toDouble(),
                    updatedAt = previous.updatedAt,
                    profileId = profileId,
                    exerciseId = exerciseId,
                    expectedRevision = expectedWrittenRevision,
                )
                restored = queries.selectChangedRowCount().executeAsOne() == 1L
            }
        }
        restored
    }

    override suspend fun getLegacyBaselines(): List<LegacyExerciseBaseline> = withContext(Dispatchers.IO) {
        legacyBaselines()
    }

    override suspend fun copyAndConsumeLegacyForSoleProfile(profileId: String, updatedAt: Long): Int =
        withContext(Dispatchers.IO) {
            require(profileId.isNotBlank()) { "Baseline profileId must not be blank" }
            var consumed = 0
            database.transaction {
                check(queries.countProfiles().executeAsOne() == 1L) {
                    "Legacy baselines require explicit assignment when multiple profiles exist"
                }
                check(queries.getProfileById(profileId).executeAsOneOrNull() != null) {
                    "Sole baseline profile does not exist: $profileId"
                }
                legacyBaselines().forEach { legacy ->
                    val existing = select(profileId, legacy.exerciseId)
                    if (existing == null) {
                        queries.insertProfileExerciseBaselineIfAbsent(
                            profileId = profileId,
                            exerciseId = legacy.exerciseId,
                            oneRepMaxPerCableKg = legacy.sourceOneRepMaxPerCableKg,
                            updatedAt = updatedAt,
                            revision = 1L,
                        )
                        check(queries.selectChangedRowCount().executeAsOne() == 1L) {
                            "Failed to copy legacy baseline for exercise=${legacy.exerciseId}"
                        }
                    }
                    queries.consumeLegacyOneRepMaxIfCurrent(
                        exerciseId = legacy.exerciseId,
                        expectedOneRepMaxKg = legacy.sourceOneRepMaxPerCableKg,
                    )
                    consumed += queries.selectChangedRowCount().executeAsOne().toInt()
                }
            }
            consumed
        }

    override suspend fun assignAndConsumeLegacy(
        profileId: String,
        legacy: LegacyExerciseBaseline,
        updatedAt: Long,
    ): ProfileExerciseBaseline = withContext(Dispatchers.IO) {
        val exerciseId = legacy.exerciseId
        validateIds(profileId, exerciseId)
        validateValue(legacy.oneRepMaxPerCableKg)
        var assigned: ProfileExerciseBaseline? = null
        database.transaction {
            val currentLegacy = queries.selectExerciseById(exerciseId).executeAsOneOrNull()?.one_rep_max_kg
            check(currentLegacy == legacy.sourceOneRepMaxPerCableKg) {
                "Legacy baseline changed before assignment for exercise=$exerciseId"
            }
            assigned = setInCurrentTransaction(
                profileId = profileId,
                exerciseId = exerciseId,
                oneRepMaxPerCableKg = legacy.oneRepMaxPerCableKg,
                updatedAt = updatedAt,
            )
            queries.consumeLegacyOneRepMaxIfCurrent(
                exerciseId = exerciseId,
                expectedOneRepMaxKg = legacy.sourceOneRepMaxPerCableKg,
            )
            check(queries.selectChangedRowCount().executeAsOne() == 1L) {
                "Legacy baseline was not consumed for exercise=$exerciseId"
            }
        }
        requireNotNull(assigned)
    }

    override suspend fun copyForProfileDeletion(sourceProfileId: String, targetProfileId: String) {
        withContext(Dispatchers.IO) {
            require(sourceProfileId.isNotBlank()) { "Source profileId must not be blank" }
            require(targetProfileId.isNotBlank()) { "Target profileId must not be blank" }
            require(sourceProfileId != targetProfileId) { "Source and target profiles must differ" }
            database.transaction {
                check(queries.getProfileById(sourceProfileId).executeAsOneOrNull() != null) {
                    "Source profile does not exist: $sourceProfileId"
                }
                check(queries.getProfileById(targetProfileId).executeAsOneOrNull() != null) {
                    "Target profile does not exist: $targetProfileId"
                }
                queries.copyProfileExerciseBaselinesForProfileDelete(
                    sourceProfileId = sourceProfileId,
                    targetProfileId = targetProfileId,
                )
            }
        }
    }

    override fun mergeForProfileRecoveryInCurrentTransaction(
        sourceProfileId: String,
        targetProfileId: String,
    ) {
        validateProfileMove(sourceProfileId, targetProfileId)
        queries.copyProfileExerciseBaselinesForProfileDelete(
            sourceProfileId = sourceProfileId,
            targetProfileId = targetProfileId,
        )
        queries.selectProfileExerciseBaselinesByProfile(sourceProfileId)
            .executeAsList()
            .forEach { source ->
                queries.deleteProfileExerciseBaseline(source.profile_id, source.exercise_id)
            }
    }

    override suspend fun remapExercise(oldExerciseId: String, newExerciseId: String) {
        withContext(Dispatchers.IO) {
            require(oldExerciseId.isNotBlank()) { "Old exerciseId must not be blank" }
            require(newExerciseId.isNotBlank()) { "New exerciseId must not be blank" }
            if (oldExerciseId == newExerciseId) return@withContext
            database.transaction {
                check(queries.selectExerciseById(newExerciseId).executeAsOneOrNull() != null) {
                    "Catalog remap target does not exist: $newExerciseId"
                }
                queries.copyProfileExerciseBaselinesForCatalogRemap(oldExerciseId, newExerciseId)
                queries.mergeProfileExerciseBaselinesForCatalogRemap(oldExerciseId, newExerciseId)
                queries.deleteProfileExerciseBaselinesByExercise(oldExerciseId)
            }
        }
    }

    private fun setInTransaction(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float?,
        updatedAt: Long,
    ): ProfileExerciseBaseline {
        var result: ProfileExerciseBaseline? = null
        database.transaction {
            result = setInCurrentTransaction(profileId, exerciseId, oneRepMaxPerCableKg, updatedAt)
        }
        return requireNotNull(result)
    }

    private fun setInCurrentTransaction(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Float?,
        updatedAt: Long,
    ): ProfileExerciseBaseline {
        val existing = select(profileId, exerciseId)
        if (existing == null) {
            queries.insertProfileExerciseBaselineIfAbsent(
                profileId = profileId,
                exerciseId = exerciseId,
                oneRepMaxPerCableKg = oneRepMaxPerCableKg?.toDouble(),
                updatedAt = updatedAt,
                revision = 1L,
            )
        } else {
            queries.updateProfileExerciseBaselineAtRevision(
                oneRepMaxPerCableKg = oneRepMaxPerCableKg?.toDouble(),
                updatedAt = updatedAt,
                profileId = profileId,
                exerciseId = exerciseId,
                expectedRevision = existing.revision,
            )
        }
        check(queries.selectChangedRowCount().executeAsOne() == 1L) {
            "Baseline write did not change exactly one row for profile=$profileId exercise=$exerciseId"
        }
        return requireNotNull(select(profileId, exerciseId))
    }

    private fun select(profileId: String, exerciseId: String): ProfileExerciseBaseline? =
        queries.selectProfileExerciseBaseline(profileId, exerciseId, ::mapBaseline).executeAsOneOrNull()

    private fun legacyBaselines(): List<LegacyExerciseBaseline> =
        queries.getExercisesWithOneRepMax().executeAsList().mapNotNull { exercise ->
            exercise.one_rep_max_kg
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { exact ->
                    exact.toFloat()
                        .takeIf { it.isFinite() && it > 0f }
                        ?.let { value ->
                            LegacyExerciseBaseline(
                                exerciseId = exercise.id,
                                oneRepMaxPerCableKg = value,
                                sourceOneRepMaxPerCableKg = exact,
                            )
                        }
                }
        }

    private fun mapBaseline(
        profileId: String,
        exerciseId: String,
        oneRepMaxPerCableKg: Double?,
        updatedAt: Long,
        revision: Long,
    ) = ProfileExerciseBaseline(
        profileId = profileId,
        exerciseId = exerciseId,
        oneRepMaxPerCableKg = oneRepMaxPerCableKg?.toFloat(),
        updatedAt = updatedAt,
        revision = revision,
    )

    private fun validateIds(profileId: String, exerciseId: String) {
        require(profileId.isNotBlank()) { "Baseline profileId must not be blank" }
        require(exerciseId.isNotBlank()) { "Baseline exerciseId must not be blank" }
    }

    private fun validateProfileMove(sourceProfileId: String, targetProfileId: String) {
        require(sourceProfileId.isNotBlank()) { "Source profileId must not be blank" }
        require(targetProfileId.isNotBlank()) { "Target profileId must not be blank" }
        require(sourceProfileId != targetProfileId) { "Source and target profiles must differ" }
    }

    private fun validateValue(value: Float?) {
        require(value == null || value.isFinite() && value > 0f) {
            "Baseline one-rep max must be null or finite and positive"
        }
    }
}
