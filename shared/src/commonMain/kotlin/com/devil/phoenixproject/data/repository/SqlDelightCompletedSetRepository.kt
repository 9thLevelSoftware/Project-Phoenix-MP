package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of CompletedSetRepository.
 * Handles both planned sets (templates) and completed sets (actual performance).
 */
class SqlDelightCompletedSetRepository(db: VitruvianDatabase) : CompletedSetRepository {

    private val queries = db.vitruvianDatabaseQueries

    // ==================== Mapping Functions ====================

    private fun mapToPlannedSet(
        id: String,
        routineExerciseId: String,
        setNumber: Long,
        setType: String,
        targetReps: Long?,
        targetWeightKg: Double?,
        targetRpe: Long?,
        restSeconds: Long?,
    ): PlannedSet = PlannedSet(
        id = id,
        routineExerciseId = routineExerciseId,
        setNumber = setNumber.toInt(),
        setType = SetType.valueOf(setType),
        targetReps = targetReps?.toInt(),
        targetWeightKg = targetWeightKg?.toFloat(),
        targetRpe = targetRpe?.toInt(),
        restSeconds = restSeconds?.toInt(),
    )

    private fun mapToCompletedSet(
        id: String,
        sessionId: String,
        plannedSetId: String?,
        setNumber: Long,
        setType: String,
        actualReps: Long,
        actualWeightKg: Double,
        loggedRpe: Long?,
        isPr: Long,
        completedAt: Long,
    ): CompletedSet = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = plannedSetId,
        setNumber = setNumber.toInt(),
        setType = SetType.valueOf(setType),
        actualReps = actualReps.toInt(),
        actualWeightKg = actualWeightKg.toFloat(),
        loggedRpe = loggedRpe?.toInt(),
        isPr = isPr == 1L,
        completedAt = completedAt,
    )

    // ==================== Planned Sets ====================

    override suspend fun getPlannedSets(routineExerciseId: String): List<PlannedSet> = withContext(Dispatchers.IO) {
        queries.selectPlannedSetsByRoutineExercise(
            routineExerciseId,
            ::mapToPlannedSet,
        ).executeAsList()
    }

    override suspend fun savePlannedSet(set: PlannedSet) {
        withContext(Dispatchers.IO) {
            queries.insertPlannedSet(
                id = set.id,
                routine_exercise_id = set.routineExerciseId,
                set_number = set.setNumber.toLong(),
                set_type = set.setType.name,
                target_reps = set.targetReps?.toLong(),
                target_weight_kg = set.targetWeightKg?.toDouble(),
                target_rpe = set.targetRpe?.toLong(),
                rest_seconds = set.restSeconds?.toLong(),
            )
        }
    }

    override suspend fun savePlannedSets(sets: List<PlannedSet>) {
        withContext(Dispatchers.IO) {
            sets.forEach { set ->
                queries.insertPlannedSet(
                    id = set.id,
                    routine_exercise_id = set.routineExerciseId,
                    set_number = set.setNumber.toLong(),
                    set_type = set.setType.name,
                    target_reps = set.targetReps?.toLong(),
                    target_weight_kg = set.targetWeightKg?.toDouble(),
                    target_rpe = set.targetRpe?.toLong(),
                    rest_seconds = set.restSeconds?.toLong(),
                )
            }
        }
    }

    override suspend fun updatePlannedSet(set: PlannedSet) {
        withContext(Dispatchers.IO) {
            queries.updatePlannedSet(
                set_number = set.setNumber.toLong(),
                set_type = set.setType.name,
                target_reps = set.targetReps?.toLong(),
                target_weight_kg = set.targetWeightKg?.toDouble(),
                target_rpe = set.targetRpe?.toLong(),
                rest_seconds = set.restSeconds?.toLong(),
                id = set.id,
            )
        }
    }

    override suspend fun deletePlannedSet(setId: String) {
        withContext(Dispatchers.IO) {
            queries.deletePlannedSet(id = setId)
        }
    }

    override suspend fun deletePlannedSetsForExercise(routineExerciseId: String) {
        withContext(Dispatchers.IO) {
            queries.deletePlannedSetsByRoutineExercise(routine_exercise_id = routineExerciseId)
        }
    }

    // ==================== Completed Sets ====================

    override suspend fun getCompletedSets(sessionId: String): List<CompletedSet> = withContext(Dispatchers.IO) {
        queries.selectCompletedSetsBySession(sessionId, ::mapToCompletedSet).executeAsList()
    }

    override fun getCompletedSetsFlow(sessionId: String): Flow<List<CompletedSet>> = queries.selectCompletedSetsBySession(sessionId, ::mapToCompletedSet)
        .asFlow().mapToList(Dispatchers.IO)

    override suspend fun getCompletedSetsForExercise(exerciseId: String): List<CompletedSet> = withContext(Dispatchers.IO) {
        queries.selectCompletedSetsForExercise(exerciseId, ::mapToCompletedSet).executeAsList()
    }

    override suspend fun getRecentCompletedSetsForExercise(exerciseId: String, limit: Int): List<CompletedSet> = withContext(Dispatchers.IO) {
        queries.selectRecentCompletedSetsForExercise(
            exerciseId,
            limit.toLong(),
            ::mapToCompletedSet,
        )
            .executeAsList()
    }

    override suspend fun saveCompletedSet(set: CompletedSet) {
        withContext(Dispatchers.IO) {
            queries.insertCompletedSet(
                id = set.id,
                session_id = set.sessionId,
                planned_set_id = set.plannedSetId,
                set_number = set.setNumber.toLong(),
                set_type = set.setType.name,
                actual_reps = set.actualReps.toLong(),
                actual_weight_kg = set.actualWeightKg.toDouble(),
                logged_rpe = set.loggedRpe?.toLong(),
                is_pr = if (set.isPr) 1L else 0L,
                completed_at = set.completedAt,
            )
        }
    }

    override suspend fun ensureCompletedSetForTaggedJustLift(session: WorkoutSession, isAmrap: Boolean): CompletedSet? = withContext(Dispatchers.IO) {
        val actualReps = (if (session.workingReps > 0) session.workingReps else session.totalReps)
            .coerceAtLeast(0)
        if (actualReps <= 0) return@withContext null

        val setType = if (isAmrap) SetType.AMRAP else SetType.STANDARD
        val completedAt = session.timestamp + session.duration
        val existing = queries.selectCompletedSetsBySession(session.id, ::mapToCompletedSet).executeAsList()
            .firstOrNull()
        if (existing != null) {
            queries.updateCompletedSetForTaggedJustLift(
                set_type = setType.name,
                actual_reps = actualReps.toLong(),
                actual_weight_kg = session.weightPerCableKg.toDouble(),
                logged_rpe = session.rpe?.toLong(),
                completed_at = completedAt,
                id = existing.id,
            )
            return@withContext existing.copy(
                setType = setType,
                actualReps = actualReps,
                actualWeightKg = session.weightPerCableKg,
                loggedRpe = session.rpe,
                completedAt = completedAt,
            )
        }

        val completedSet = CompletedSet(
            id = generateUUID(),
            sessionId = session.id,
            plannedSetId = null,
            setNumber = 0,
            setType = setType,
            actualReps = actualReps,
            actualWeightKg = session.weightPerCableKg,
            loggedRpe = session.rpe,
            isPr = false,
            completedAt = completedAt,
        )

        queries.insertCompletedSet(
            id = completedSet.id,
            session_id = completedSet.sessionId,
            planned_set_id = completedSet.plannedSetId,
            set_number = completedSet.setNumber.toLong(),
            set_type = completedSet.setType.name,
            actual_reps = completedSet.actualReps.toLong(),
            actual_weight_kg = completedSet.actualWeightKg.toDouble(),
            logged_rpe = completedSet.loggedRpe?.toLong(),
            is_pr = if (completedSet.isPr) 1L else 0L,
            completed_at = completedSet.completedAt,
        )

        completedSet
    }

    override suspend fun saveCompletedSets(sets: List<CompletedSet>) {
        withContext(Dispatchers.IO) {
            sets.forEach { set ->
                queries.insertCompletedSet(
                    id = set.id,
                    session_id = set.sessionId,
                    planned_set_id = set.plannedSetId,
                    set_number = set.setNumber.toLong(),
                    set_type = set.setType.name,
                    actual_reps = set.actualReps.toLong(),
                    actual_weight_kg = set.actualWeightKg.toDouble(),
                    logged_rpe = set.loggedRpe?.toLong(),
                    is_pr = if (set.isPr) 1L else 0L,
                    completed_at = set.completedAt,
                )
            }
        }
    }

    override suspend fun updateRpe(setId: String, rpe: Int) {
        withContext(Dispatchers.IO) {
            queries.updateCompletedSetRpe(
                logged_rpe = rpe.toLong(),
                id = setId,
            )
        }
    }

    override suspend fun markAsPr(setId: String) {
        withContext(Dispatchers.IO) {
            queries.markCompletedSetAsPr(id = setId)
        }
    }

    override suspend fun deleteCompletedSet(setId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteCompletedSet(id = setId)
        }
    }

    override suspend fun deleteCompletedSetsForSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteCompletedSetsBySession(session_id = sessionId)
        }
    }
}
