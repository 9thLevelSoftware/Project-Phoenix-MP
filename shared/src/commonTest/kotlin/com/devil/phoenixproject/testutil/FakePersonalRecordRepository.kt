package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.PhasePRBreak
import com.devil.phoenixproject.data.repository.normalizeWorkoutModeKey
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake PersonalRecordRepository for testing.
 * Provides in-memory storage for personal records.
 */
class FakePersonalRecordRepository : PersonalRecordRepository {

    private val records = mutableMapOf<String, PersonalRecord>()
    private val _recordsFlow = MutableStateFlow<List<PersonalRecord>>(emptyList())

    // Track calls for verification
    val updateCalls = mutableListOf<UpdateCall>()

    data class UpdateCall(
        val exerciseId: String,
        val weightPRWeightPerCableKg: Float,
        val volumePRWeightPerCableKg: Float,
        val reps: Int,
        val workoutMode: String,
        val timestamp: Long,
    )

    // Test control methods
    fun addRecord(record: PersonalRecord) {
        val key = recordKey(record.exerciseId, record.workoutMode, record.prType, record.phase, record.profileId)
        records[key] = record
        updateRecordsFlow()
    }

    fun reset() {
        records.clear()
        updateCalls.clear()
        updateRecordsFlow()
    }

    private fun updateRecordsFlow() {
        _recordsFlow.value = records.values.toList()
    }

    private fun calculateOneRepMax(weightKg: Float, reps: Int): Float {
        // Brzycki formula: 1RM = weight * (36 / (37 - reps))
        return if (reps >= 37) weightKg else weightKg * (36f / (37 - reps))
    }

    // ========== PersonalRecordRepository interface implementation ==========

    override suspend fun getLatestPR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = records.values
        .filter {
            it.exerciseId == exerciseId &&
                it.profileId == profileId &&
                normalizeWorkoutModeKey(it.workoutMode) == normalizeWorkoutModeKey(workoutMode)
        }
        .maxByOrNull { it.timestamp }

    override fun getPRsForExercise(exerciseId: String, profileId: String): Flow<List<PersonalRecord>> = _recordsFlow.map { list ->
        list.filter { it.exerciseId == exerciseId && it.profileId == profileId }
    }

    override suspend fun getBestPR(exerciseId: String, profileId: String): PersonalRecord? = records.values
        .filter { it.exerciseId == exerciseId && it.profileId == profileId }
        .maxByOrNull { it.volume }

    override fun getAllPRs(profileId: String): Flow<List<PersonalRecord>> = _recordsFlow.map { list ->
        list.filter { it.profileId == profileId }
    }

    override fun getAllPRsGrouped(profileId: String): Flow<List<PersonalRecord>> = _recordsFlow.map { list ->
        list.filter { it.profileId == profileId }
            .groupBy { it.exerciseId }
            .mapNotNull { (_, records) -> records.maxByOrNull { it.volume } }
    }

    override suspend fun updatePRIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long,
        profileId: String,
        cableCount: Int?,
    ): Result<Boolean> {
        updateCalls.add(UpdateCall(exerciseId, weightPerCableKg, weightPerCableKg, reps, workoutMode, timestamp))

        val normalizedMode = normalizeWorkoutModeKey(workoutMode)
        val key = recordKey(exerciseId, normalizedMode, PRType.MAX_VOLUME, WorkoutPhase.COMBINED, profileId)
        val existing = records[key]
        val newVolume = weightPerCableKg * reps

        return if (existing == null || newVolume > existing.volume) {
            records[key] = PersonalRecord(
                id = existing?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existing?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPerCableKg,
                reps = reps,
                oneRepMax = calculateOneRepMax(weightPerCableKg, reps),
                timestamp = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_VOLUME,
                volume = newVolume,
                phase = WorkoutPhase.COMBINED,
                profileId = profileId,
                cableCount = cableCount,
            )
            updateRecordsFlow()
            Result.success(true)
        } else {
            Result.success(false)
        }
    }

    override suspend fun getWeightPR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = records.values
        .filter {
            it.exerciseId == exerciseId &&
                it.profileId == profileId &&
                normalizeWorkoutModeKey(it.workoutMode) == normalizeWorkoutModeKey(workoutMode) &&
                it.prType == PRType.MAX_WEIGHT &&
                it.phase == WorkoutPhase.COMBINED
        }
        .maxByOrNull { it.weightPerCableKg }

    override suspend fun getVolumePR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = records.values
        .filter {
            it.exerciseId == exerciseId &&
                it.profileId == profileId &&
                normalizeWorkoutModeKey(it.workoutMode) == normalizeWorkoutModeKey(workoutMode) &&
                it.prType == PRType.MAX_VOLUME &&
                it.phase == WorkoutPhase.COMBINED
        }
        .maxByOrNull { it.volume }

    override suspend fun getBestWeightPR(exerciseId: String, profileId: String, phase: WorkoutPhase): PersonalRecord? = records.values
        .filter { it.exerciseId == exerciseId && it.profileId == profileId && it.prType == PRType.MAX_WEIGHT && it.phase == phase }
        .maxByOrNull { it.weightPerCableKg }

    override suspend fun getBestVolumePR(exerciseId: String, profileId: String, phase: WorkoutPhase): PersonalRecord? = records.values
        .filter { it.exerciseId == exerciseId && it.profileId == profileId && it.prType == PRType.MAX_VOLUME && it.phase == phase }
        .maxByOrNull { it.volume }

    override suspend fun getBestWeightPR(exerciseId: String, workoutMode: String, profileId: String, phase: WorkoutPhase): PersonalRecord? = records.values
        .filter {
            it.exerciseId == exerciseId &&
                it.profileId == profileId &&
                normalizeWorkoutModeKey(it.workoutMode) == normalizeWorkoutModeKey(workoutMode) &&
                it.prType == PRType.MAX_WEIGHT &&
                it.phase == phase
        }
        .maxByOrNull { it.weightPerCableKg }

    override suspend fun getBestVolumePR(exerciseId: String, workoutMode: String, profileId: String, phase: WorkoutPhase): PersonalRecord? = records.values
        .filter {
            it.exerciseId == exerciseId &&
                it.profileId == profileId &&
                normalizeWorkoutModeKey(it.workoutMode) == normalizeWorkoutModeKey(workoutMode) &&
                it.prType == PRType.MAX_VOLUME &&
                it.phase == phase
        }
        .maxByOrNull { it.volume }

    override suspend fun getAllPRsForExercise(exerciseId: String, profileId: String): List<PersonalRecord> = records.values
        .filter { it.exerciseId == exerciseId && it.profileId == profileId }
        .sortedByDescending { it.timestamp }

    override suspend fun updatePRsIfBetter(
        exerciseId: String,
        weightPRWeightPerCableKg: Float,
        volumePRWeightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long,
        profileId: String,
        cableCount: Int?,
    ): Result<List<PRType>> {
        updateCalls.add(
            UpdateCall(
                exerciseId = exerciseId,
                weightPRWeightPerCableKg = weightPRWeightPerCableKg,
                volumePRWeightPerCableKg = volumePRWeightPerCableKg,
                reps = reps,
                workoutMode = workoutMode,
                timestamp = timestamp,
            ),
        )

        val brokenPRs = mutableListOf<PRType>()
        val normalizedMode = normalizeWorkoutModeKey(workoutMode)
        val weightPRVolume = weightPRWeightPerCableKg * reps
        val newVolumePRVolume = volumePRWeightPerCableKg * reps
        val newOneRepMax = calculateOneRepMax(weightPRWeightPerCableKg, reps)

        // Check weight PR
        val weightKey = recordKey(exerciseId, normalizedMode, PRType.MAX_WEIGHT, WorkoutPhase.COMBINED, profileId)
        val existingWeightPR = records[weightKey]
        if (existingWeightPR == null || weightPRWeightPerCableKg > existingWeightPR.weightPerCableKg) {
            records[weightKey] = PersonalRecord(
                id = existingWeightPR?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existingWeightPR?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPRWeightPerCableKg,
                reps = reps,
                oneRepMax = newOneRepMax,
                timestamp = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_WEIGHT,
                volume = weightPRVolume,
                phase = WorkoutPhase.COMBINED,
                profileId = profileId,
                cableCount = cableCount,
            )
            brokenPRs.add(PRType.MAX_WEIGHT)
        }

        // Check volume PR
        val volumeKey = recordKey(exerciseId, normalizedMode, PRType.MAX_VOLUME, WorkoutPhase.COMBINED, profileId)
        val existingVolumePR = records[volumeKey]
        if (existingVolumePR == null || newVolumePRVolume > existingVolumePR.volume) {
            records[volumeKey] = PersonalRecord(
                id = existingVolumePR?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existingVolumePR?.exerciseName ?: exerciseId,
                weightPerCableKg = volumePRWeightPerCableKg,
                reps = reps,
                oneRepMax = newOneRepMax,
                timestamp = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_VOLUME,
                volume = newVolumePRVolume,
                phase = WorkoutPhase.COMBINED,
                profileId = profileId,
                cableCount = cableCount,
            )
            brokenPRs.add(PRType.MAX_VOLUME)
        }

        updateRecordsFlow()
        return Result.success(brokenPRs)
    }

    override suspend fun updatePhaseSpecificPRs(
        exerciseId: String,
        workoutMode: String,
        timestamp: Long,
        reps: Int,
        peakConcentricForceKg: Float,
        peakEccentricForceKg: Float,
        profileId: String,
        cableCount: Int?,
    ): Result<List<PhasePRBreak>> {
        val brokenPRs = mutableListOf<PhasePRBreak>()

        if (peakConcentricForceKg > 0f) {
            brokenPRs.addAll(
                updatePhasePRs(
                    exerciseId = exerciseId,
                    workoutMode = workoutMode,
                    timestamp = timestamp,
                    reps = reps,
                    weightPerCableKg = peakConcentricForceKg,
                    phase = WorkoutPhase.CONCENTRIC,
                    profileId = profileId,
                    cableCount = cableCount,
                ),
            )
        }

        if (peakEccentricForceKg > 0f) {
            brokenPRs.addAll(
                updatePhasePRs(
                    exerciseId = exerciseId,
                    workoutMode = workoutMode,
                    timestamp = timestamp,
                    reps = reps,
                    weightPerCableKg = peakEccentricForceKg,
                    phase = WorkoutPhase.ECCENTRIC,
                    profileId = profileId,
                    cableCount = cableCount,
                ),
            )
        }

        updateRecordsFlow()
        return Result.success(brokenPRs)
    }

    private fun updatePhasePRs(
        exerciseId: String,
        workoutMode: String,
        timestamp: Long,
        reps: Int,
        weightPerCableKg: Float,
        phase: WorkoutPhase,
        profileId: String,
        cableCount: Int?,
    ): List<PhasePRBreak> {
        val broken = mutableListOf<PhasePRBreak>()
        val normalizedMode = normalizeWorkoutModeKey(workoutMode)
        val volume = weightPerCableKg * reps
        val oneRepMax = calculateOneRepMax(weightPerCableKg, reps)

        val weightKey = recordKey(exerciseId, normalizedMode, PRType.MAX_WEIGHT, phase, profileId)
        val existingWeight = records[weightKey]
        if (existingWeight == null || weightPerCableKg > existingWeight.weightPerCableKg) {
            records[weightKey] = PersonalRecord(
                id = existingWeight?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existingWeight?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPerCableKg,
                reps = reps,
                oneRepMax = oneRepMax,
                timestamp = timestamp,
                workoutMode = normalizedMode,
                prType = PRType.MAX_WEIGHT,
                volume = volume,
                phase = phase,
                profileId = profileId,
                cableCount = cableCount,
            )
            broken.add(PhasePRBreak(phase, PRType.MAX_WEIGHT))
        }

        val volumeKey = recordKey(exerciseId, normalizedMode, PRType.MAX_VOLUME, phase, profileId)
        val existingVolume = records[volumeKey]
        if (existingVolume == null || volume > existingVolume.volume) {
            records[volumeKey] = PersonalRecord(
                id = existingVolume?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existingVolume?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPerCableKg,
                reps = reps,
                oneRepMax = oneRepMax,
                timestamp = timestamp,
                workoutMode = normalizedMode,
                prType = PRType.MAX_VOLUME,
                volume = volume,
                phase = phase,
                profileId = profileId,
                cableCount = cableCount,
            )
            broken.add(PhasePRBreak(phase, PRType.MAX_VOLUME))
        }

        return broken
    }

    private fun recordKey(
        exerciseId: String,
        workoutMode: String,
        prType: PRType,
        phase: WorkoutPhase,
        profileId: String,
    ): String = "$exerciseId-${normalizeWorkoutModeKey(workoutMode)}-$prType-$phase-$profileId"
}
