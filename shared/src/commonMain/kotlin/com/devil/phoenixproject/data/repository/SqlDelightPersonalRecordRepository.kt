package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightPersonalRecordRepository(private val db: VitruvianDatabase) : PersonalRecordRepository {
    private val queries = db.vitruvianDatabaseQueries

    // SQLDelight mapper - parameters must match query columns even if not all are used
    private fun mapToPR(
        id: Long,
        exerciseId: String,
        exerciseName: String,
        weight: Double,
        reps: Long,
        oneRepMax: Double,
        achievedAt: Long,
        workoutMode: String,
        prType: String,
        volume: Double,
        phase: String,
        // Sync fields (migration 6)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?,
        // Multi-profile support (migration 21)
        profileId: String,
    ): PersonalRecord = PersonalRecord(
        id = id,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        weightPerCableKg = weight.toFloat(),
        reps = reps.toInt(),
        oneRepMax = oneRepMax.toFloat(),
        timestamp = achievedAt,
        workoutMode = workoutMode,
        prType = when (prType) {
            "MAX_VOLUME" -> PRType.MAX_VOLUME
            else -> PRType.MAX_WEIGHT
        },
        volume = volume.toFloat(),
        phase = when (phase) {
            "CONCENTRIC" -> WorkoutPhase.CONCENTRIC
            "ECCENTRIC" -> WorkoutPhase.ECCENTRIC
            else -> WorkoutPhase.COMBINED
        },
        profileId = profileId,
    )

    override suspend fun getLatestPR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForExercise(exerciseId, profileId)
            .filter {
                normalizeWorkoutModeKey(it.workoutMode) ==
                    normalizeWorkoutModeKey(workoutMode)
            }
            .maxByOrNull { it.timestamp }
    }

    override fun getPRsForExercise(exerciseId: String, profileId: String): Flow<List<PersonalRecord>> = queries.selectRecordsByExercise(exerciseId, profileId = profileId, mapper = ::mapToPR)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun getBestPR(exerciseId: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForExercise(exerciseId, profileId)
            .maxByOrNull { it.weightPerCableKg } // Sort by weight (parity with parent repo)
    }

    override fun getAllPRs(profileId: String): Flow<List<PersonalRecord>> = queries.selectAllRecords(profileId = profileId, mapper = ::mapToPR)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun getAllPRsGrouped(profileId: String): Flow<List<PersonalRecord>> = getAllPRs(profileId).map { records ->
        records.groupBy { it.exerciseId }
            .mapNotNull { (_, prs) ->
                // Return the best PR for each exercise (by weight, parity with parent repo)
                prs.maxByOrNull { it.weightPerCableKg }
            }
    }

    override suspend fun updatePRIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long,
        profileId: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val brokenPRs = updatePRsIfBetterInternal(
                exerciseId = exerciseId,
                weightPRWeightPerCableKg = weightPerCableKg,
                volumePRWeightPerCableKg = weightPerCableKg,
                reps = reps,
                workoutMode = workoutMode,
                timestamp = timestamp,
                phase = WorkoutPhase.COMBINED,
                profileId = profileId,
            )
            Result.success(brokenPRs.isNotEmpty())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== Volume/Weight PR Methods ==========

    override suspend fun getWeightPR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForMode(exerciseId, workoutMode, profileId)
            .filter { it.prType == PRType.MAX_WEIGHT && it.phase == WorkoutPhase.COMBINED }
            .maxByOrNull { it.weightPerCableKg }
    }

    override suspend fun getVolumePR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForMode(exerciseId, workoutMode, profileId)
            .filter { it.prType == PRType.MAX_VOLUME && it.phase == WorkoutPhase.COMBINED }
            .maxByOrNull { it.volume }
    }

    override suspend fun getBestWeightPR(exerciseId: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForExercise(exerciseId, profileId)
            .filter { it.prType == PRType.MAX_WEIGHT }
            .maxByOrNull { it.weightPerCableKg }
    }

    override suspend fun getBestVolumePR(exerciseId: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForExercise(exerciseId, profileId)
            .filter { it.prType == PRType.MAX_VOLUME }
            .maxByOrNull { it.volume }
    }

    override suspend fun getBestWeightPR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForMode(exerciseId, workoutMode, profileId)
            .filter { it.prType == PRType.MAX_WEIGHT }
            .maxByOrNull { it.weightPerCableKg }
    }

    override suspend fun getBestVolumePR(exerciseId: String, workoutMode: String, profileId: String): PersonalRecord? = withContext(Dispatchers.IO) {
        recordsForMode(exerciseId, workoutMode, profileId)
            .filter { it.prType == PRType.MAX_VOLUME }
            .maxByOrNull { it.volume }
    }

    override suspend fun getAllPRsForExercise(exerciseId: String, profileId: String): List<PersonalRecord> = withContext(Dispatchers.IO) {
        queries.selectAllPRsForExercise(
            exerciseId = exerciseId,
            profileId = profileId,
            mapper = ::mapToPR,
        )
            .executeAsList()
    }

    override suspend fun updatePRsIfBetter(
        exerciseId: String,
        weightPRWeightPerCableKg: Float,
        volumePRWeightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long,
        profileId: String,
    ): Result<List<PRType>> = withContext(Dispatchers.IO) {
        try {
            val brokenPRs = updatePRsIfBetterInternal(
                exerciseId = exerciseId,
                weightPRWeightPerCableKg = weightPRWeightPerCableKg,
                volumePRWeightPerCableKg = volumePRWeightPerCableKg,
                reps = reps,
                workoutMode = workoutMode,
                timestamp = timestamp,
                phase = WorkoutPhase.COMBINED,
                profileId = profileId,
            )
            Result.success(brokenPRs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePhaseSpecificPRs(
        exerciseId: String,
        workoutMode: String,
        timestamp: Long,
        reps: Int,
        peakConcentricForceKg: Float,
        peakEccentricForceKg: Float,
        profileId: String,
    ): Result<List<WorkoutPhase>> = withContext(Dispatchers.IO) {
        try {
            val brokenPhases = mutableListOf<WorkoutPhase>()

            // Check concentric PR (peak force during lifting)
            if (peakConcentricForceKg > 0f) {
                val broken = updatePRsIfBetterInternal(
                    exerciseId = exerciseId,
                    weightPRWeightPerCableKg = peakConcentricForceKg,
                    volumePRWeightPerCableKg = peakConcentricForceKg,
                    reps = reps,
                    workoutMode = workoutMode,
                    timestamp = timestamp,
                    phase = WorkoutPhase.CONCENTRIC,
                    profileId = profileId,
                )
                if (broken.isNotEmpty()) brokenPhases.add(WorkoutPhase.CONCENTRIC)
            }

            // Check eccentric PR (peak force during lowering)
            if (peakEccentricForceKg > 0f) {
                val broken = updatePRsIfBetterInternal(
                    exerciseId = exerciseId,
                    weightPRWeightPerCableKg = peakEccentricForceKg,
                    volumePRWeightPerCableKg = peakEccentricForceKg,
                    reps = reps,
                    workoutMode = workoutMode,
                    timestamp = timestamp,
                    phase = WorkoutPhase.ECCENTRIC,
                    profileId = profileId,
                )
                if (broken.isNotEmpty()) brokenPhases.add(WorkoutPhase.ECCENTRIC)
            }

            Result.success(brokenPhases)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Internal implementation that checks and updates both weight and volume PRs
     * for a specific phase (COMBINED, CONCENTRIC, or ECCENTRIC).
     */
    private fun updatePRsIfBetterInternal(
        exerciseId: String,
        weightPRWeightPerCableKg: Float,
        volumePRWeightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long,
        phase: WorkoutPhase,
        profileId: String,
    ): List<PRType> {
        // Issue #319: Defensive validation for profileId
        if (profileId.isBlank()) {
            Logger.e { "PR_SAVE: CRITICAL - profileId is blank for exercise=$exerciseId, using 'default' as fallback. Stack trace: ${Throwable().stackTrace.take(5).joinToString()}" }
        }
        val effectiveProfileId = profileId.ifBlank { "default" }

        val brokenPRs = mutableListOf<PRType>()
        val canonicalWorkoutMode = normalizeWorkoutModeKey(workoutMode)
        val volumeForWeightPR = weightPRWeightPerCableKg * reps
        val volumeForVolumePR = volumePRWeightPerCableKg * reps
        val estimatedOneRepMax = OneRepMaxCalculator.epley(weightPRWeightPerCableKg, reps)
        val phaseName = phase.name

        // Issue #319: Log the profile context being used
        Logger.d { "PR_SAVE: Checking for exercise=$exerciseId, mode=$canonicalWorkoutMode, phase=$phaseName, profile=$effectiveProfileId" }

        // Check weight PR for this phase
        val currentWeightPR = queries.selectPR(
            exerciseId,
            canonicalWorkoutMode,
            PRType.MAX_WEIGHT.name,
            phaseName,
            profileId = effectiveProfileId,
            mapper = ::mapToPR,
        )
            .executeAsOneOrNull()

        // Issue #319: Detect profile mismatch if a PR exists with a different profile_id
        if (currentWeightPR != null && currentWeightPR.profileId != effectiveProfileId) {
            Logger.w { "PR_SAVE: Profile mismatch detected for exercise=$exerciseId — existing PR has profile=${currentWeightPR.profileId}, but saving with profile=$effectiveProfileId" }
        }

        val isNewWeightPR =
            currentWeightPR == null || weightPRWeightPerCableKg > currentWeightPR.weightPerCableKg

        // Issue #319: Diagnostic logging for PR comparison
        Logger.d {
            "PR_CHECK[$phaseName/WEIGHT]: exercise=$exerciseId, mode=$canonicalWorkoutMode, profile=$effectiveProfileId — " +
                "new=${weightPRWeightPerCableKg}kg vs current=${currentWeightPR?.weightPerCableKg ?: "NONE"} → ${if (isNewWeightPR) "NEW PR" else "no change"}"
        }

        // Check volume PR for this phase
        val currentVolumePR = queries.selectPR(
            exerciseId,
            canonicalWorkoutMode,
            PRType.MAX_VOLUME.name,
            phaseName,
            profileId = effectiveProfileId,
            mapper = ::mapToPR,
        )
            .executeAsOneOrNull()

        // Issue #319: Detect profile mismatch for volume PR too
        if (currentVolumePR != null && currentVolumePR.profileId != effectiveProfileId) {
            Logger.w { "PR_SAVE: Profile mismatch detected for volume PR — exercise=$exerciseId, existing=${currentVolumePR.profileId}, saving=$effectiveProfileId" }
        }

        val currentVolume = currentVolumePR?.volume ?: 0f
        val isNewVolumePR = volumeForVolumePR > currentVolume

        Logger.d {
            "PR_CHECK[$phaseName/VOLUME]: exercise=$exerciseId, mode=$canonicalWorkoutMode, profile=$effectiveProfileId — " +
                "new=${volumeForVolumePR} vs current=${currentVolume} → ${if (isNewVolumePR) "NEW PR" else "no change"}"
        }

        // Issue #319/Codex review: Wrap all writes in a transaction so a partial failure
        // (e.g., weight PR upsert succeeds but volume PR or 1RM sync throws) doesn't
        // leave the DB in an inconsistent state while reporting Result.failure to the caller.
        db.transaction {
            if (isNewWeightPR) {
                Logger.i { "PR_SAVE: Writing WEIGHT PR for exercise=$exerciseId, weight=${weightPRWeightPerCableKg}kg, profile=$effectiveProfileId" }
                queries.upsertPR(
                    exerciseId = exerciseId,
                    exerciseName = "",
                    weight = weightPRWeightPerCableKg.toDouble(),
                    reps = reps.toLong(),
                    oneRepMax = estimatedOneRepMax.toDouble(),
                    achievedAt = timestamp,
                    workoutMode = canonicalWorkoutMode,
                    prType = PRType.MAX_WEIGHT.name,
                    volume = volumeForWeightPR.toDouble(),
                    phase = phaseName,
                    profile_id = effectiveProfileId,
                )
                brokenPRs.add(PRType.MAX_WEIGHT)
            }

            if (isNewVolumePR) {
                Logger.i { "PR_SAVE: Writing VOLUME PR for exercise=$exerciseId, volume=${volumeForVolumePR}, profile=$effectiveProfileId" }
                queries.upsertPR(
                    exerciseId = exerciseId,
                    exerciseName = "",
                    weight = volumePRWeightPerCableKg.toDouble(),
                    reps = reps.toLong(),
                    oneRepMax = estimatedOneRepMax.toDouble(),
                    achievedAt = timestamp,
                    workoutMode = canonicalWorkoutMode,
                    prType = PRType.MAX_VOLUME.name,
                    volume = volumeForVolumePR.toDouble(),
                    phase = phaseName,
                    profile_id = effectiveProfileId,
                )
                brokenPRs.add(PRType.MAX_VOLUME)
            }

            // Sync estimated 1RM to Exercise table for %-based training features.
            // Only update from COMBINED phase PRs to keep the canonical 1RM stable.
            if (phase == WorkoutPhase.COMBINED && brokenPRs.isNotEmpty()) {
                val currentExercise1RM = queries.selectExerciseById(exerciseId)
                    .executeAsOneOrNull()?.one_rep_max_kg?.toFloat() ?: 0f
                if (estimatedOneRepMax > currentExercise1RM) {
                    Logger.d { "PR_SAVE: Updating 1RM for exercise=$exerciseId from $currentExercise1RM to $estimatedOneRepMax" }
                    queries.updateOneRepMax(
                        one_rep_max_kg = estimatedOneRepMax.toDouble(),
                        id = exerciseId,
                    )
                }
            }
        }

        if (brokenPRs.isNotEmpty()) {
            Logger.i { "PR_SAVE: SUCCESS — Broken ${brokenPRs.size} PR(s) for exercise=$exerciseId: $brokenPRs (profile=$effectiveProfileId)" }
        }

        return brokenPRs
    }

    private fun recordsForExercise(exerciseId: String, profileId: String): List<PersonalRecord> = queries.selectRecordsByExercise(
        exerciseId,
        profileId = profileId,
        mapper = ::mapToPR,
    ).executeAsList()

    private fun recordsForMode(exerciseId: String, workoutMode: String, profileId: String): List<PersonalRecord> {
        val canonicalWorkoutMode = normalizeWorkoutModeKey(workoutMode)
        return recordsForExercise(exerciseId, profileId)
            .filter { normalizeWorkoutModeKey(it.workoutMode) == canonicalWorkoutMode }
    }
}
