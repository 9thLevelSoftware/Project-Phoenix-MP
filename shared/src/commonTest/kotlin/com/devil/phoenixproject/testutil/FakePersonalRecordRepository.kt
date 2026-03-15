package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.PersonalRecordRepository
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
        val weightPerCableKg: Float,
        val reps: Int,
        val workoutMode: String,
        val timestamp: Long
    )

    // Test control methods
    fun addRecord(record: PersonalRecord) {
        val key = "${record.exerciseId}-${record.workoutMode}-${record.prType}"
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

    override suspend fun getLatestPR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.workoutMode == workoutMode }
            .maxByOrNull { it.timestamp }
    }

    override fun getPRsForExercise(exerciseId: String): Flow<List<PersonalRecord>> {
        return _recordsFlow.map { list -> list.filter { it.exerciseId == exerciseId } }
    }

    override suspend fun getBestPR(exerciseId: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId }
            .maxByOrNull { it.volume }
    }

    override fun getAllPRs(): Flow<List<PersonalRecord>> = _recordsFlow

    override fun getAllPRsGrouped(): Flow<List<PersonalRecord>> {
        return _recordsFlow.map { list ->
            list.groupBy { it.exerciseId }
                .mapNotNull { (_, records) -> records.maxByOrNull { it.volume } }
        }
    }

    override suspend fun updatePRIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<Boolean> {
        updateCalls.add(UpdateCall(exerciseId, weightPerCableKg, reps, workoutMode, timestamp))

        val key = "$exerciseId-$workoutMode-${PRType.MAX_VOLUME}"
        val existing = records[key]
        val newVolume = weightPerCableKg * 2 * reps

        return if (existing == null || newVolume > existing.volume) {
            records[key] = PersonalRecord(
                id = existing?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existing?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPerCableKg,
                reps = reps,
                oneRepMax = calculateOneRepMax(weightPerCableKg * 2, reps),
                timestamp = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_VOLUME,
                volume = newVolume
            )
            updateRecordsFlow()
            Result.success(true)
        } else {
            Result.success(false)
        }
    }

    override suspend fun getWeightPR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.workoutMode == workoutMode && it.prType == PRType.MAX_WEIGHT }
            .maxByOrNull { it.weightPerCableKg }
    }

    override suspend fun getVolumePR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.workoutMode == workoutMode && it.prType == PRType.MAX_VOLUME }
            .maxByOrNull { it.volume }
    }

    override suspend fun getBestWeightPR(exerciseId: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.prType == PRType.MAX_WEIGHT }
            .maxByOrNull { it.weightPerCableKg }
    }

    override suspend fun getBestVolumePR(exerciseId: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.prType == PRType.MAX_VOLUME }
            .maxByOrNull { it.volume }
    }

    override suspend fun getBestWeightPR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.workoutMode == workoutMode }
            .maxByOrNull { it.weightPerCableKg }
    }

    override suspend fun getBestVolumePR(exerciseId: String, workoutMode: String): PersonalRecord? {
        return records.values
            .filter { it.exerciseId == exerciseId && it.workoutMode == workoutMode }
            .maxByOrNull { it.volume }
    }

    override suspend fun getAllPRsForExercise(exerciseId: String): List<PersonalRecord> {
        return records.values
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
    }

    override suspend fun updatePRsIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<List<PRType>> {
        updateCalls.add(UpdateCall(exerciseId, weightPerCableKg, reps, workoutMode, timestamp))

        val brokenPRs = mutableListOf<PRType>()
        val totalWeight = weightPerCableKg * 2
        val newVolume = totalWeight * reps
        val newOneRepMax = calculateOneRepMax(totalWeight, reps)

        // Check weight PR
        val weightKey = "$exerciseId-$workoutMode-${PRType.MAX_WEIGHT}"
        val existingWeightPR = records[weightKey]
        if (existingWeightPR == null || weightPerCableKg > existingWeightPR.weightPerCableKg) {
            records[weightKey] = PersonalRecord(
                id = existingWeightPR?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existingWeightPR?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPerCableKg,
                reps = reps,
                oneRepMax = newOneRepMax,
                timestamp = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_WEIGHT,
                volume = newVolume
            )
            brokenPRs.add(PRType.MAX_WEIGHT)
        }

        // Check volume PR
        val volumeKey = "$exerciseId-$workoutMode-${PRType.MAX_VOLUME}"
        val existingVolumePR = records[volumeKey]
        if (existingVolumePR == null || newVolume > existingVolumePR.volume) {
            records[volumeKey] = PersonalRecord(
                id = existingVolumePR?.id ?: records.size.toLong(),
                exerciseId = exerciseId,
                exerciseName = existingVolumePR?.exerciseName ?: exerciseId,
                weightPerCableKg = weightPerCableKg,
                reps = reps,
                oneRepMax = newOneRepMax,
                timestamp = timestamp,
                workoutMode = workoutMode,
                prType = PRType.MAX_VOLUME,
                volume = newVolume
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
        peakEccentricForceKg: Float
    ): Result<List<WorkoutPhase>> {
        val brokenPhases = mutableListOf<WorkoutPhase>()

        if (peakConcentricForceKg > 0f) {
            val key = "$exerciseId-$workoutMode-${PRType.MAX_WEIGHT}-CONCENTRIC"
            val existing = records[key]
            if (existing == null || peakConcentricForceKg > existing.weightPerCableKg) {
                records[key] = PersonalRecord(
                    id = records.size.toLong(),
                    exerciseId = exerciseId,
                    exerciseName = exerciseId,
                    weightPerCableKg = peakConcentricForceKg,
                    reps = reps,
                    oneRepMax = calculateOneRepMax(peakConcentricForceKg * 2, reps),
                    timestamp = timestamp,
                    workoutMode = workoutMode,
                    prType = PRType.MAX_WEIGHT,
                    volume = peakConcentricForceKg * reps,
                    phase = WorkoutPhase.CONCENTRIC
                )
                brokenPhases.add(WorkoutPhase.CONCENTRIC)
            }
        }

        if (peakEccentricForceKg > 0f) {
            val key = "$exerciseId-$workoutMode-${PRType.MAX_WEIGHT}-ECCENTRIC"
            val existing = records[key]
            if (existing == null || peakEccentricForceKg > existing.weightPerCableKg) {
                records[key] = PersonalRecord(
                    id = records.size.toLong(),
                    exerciseId = exerciseId,
                    exerciseName = exerciseId,
                    weightPerCableKg = peakEccentricForceKg,
                    reps = reps,
                    oneRepMax = calculateOneRepMax(peakEccentricForceKg * 2, reps),
                    timestamp = timestamp,
                    workoutMode = workoutMode,
                    prType = PRType.MAX_WEIGHT,
                    volume = peakEccentricForceKg * reps,
                    phase = WorkoutPhase.ECCENTRIC
                )
                brokenPhases.add(WorkoutPhase.ECCENTRIC)
            }
        }

        updateRecordsFlow()
        return Result.success(brokenPhases)
    }
}
