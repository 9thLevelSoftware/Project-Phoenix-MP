package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.WorkoutSession

data class SyncInvariantViolation(
    val code: String,
    val entityId: String?,
    val message: String,
)

object SyncInvariantChecker {

    fun checkPullPage(
        pullResponse: PortalSyncPullResponse,
        mobileSessions: List<WorkoutSession>,
        sessionNoteKeys: Set<String>,
    ): List<SyncInvariantViolation> {
        val violations = mutableListOf<SyncInvariantViolation>()

        addDuplicateIdViolations("DUPLICATE_PULL_SESSION_ID", pullResponse.sessions.map { it.id }, violations)
        addDuplicateIdViolations("DUPLICATE_PULL_ROUTINE_ID", pullResponse.routines.map { it.id }, violations)
        addDuplicateIdViolations("DUPLICATE_PULL_CYCLE_ID", pullResponse.cycles.map { it.id }, violations)
        addDuplicateIdViolations("DUPLICATE_PULL_PR_ID", pullResponse.personalRecords.map { it.id }, violations)
        addDuplicateIdViolations("DUPLICATE_MOBILE_SESSION_ID", mobileSessions.map { it.id }, violations)

        checkSessionTree(pullResponse.sessions, violations)
        checkRoutineTree(pullResponse.routines, violations)
        checkCycleTree(pullResponse.cycles, violations)
        checkPersonalRecords(pullResponse.personalRecords, violations)
        checkSessionNotes(pullResponse.sessions, sessionNoteKeys, violations)
        checkLogicalSessionDuplicates(mobileSessions, violations)

        return violations
    }

    private fun checkSessionTree(
        sessions: List<PullWorkoutSessionDto>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        for (session in sessions) {
            addDuplicateIdViolations("DUPLICATE_PULL_EXERCISE_ID", session.exercises.map { it.id }, violations)
            for (exercise in session.exercises) {
                if (exercise.sessionId.isNotBlank() && exercise.sessionId != session.id) {
                    violations += SyncInvariantViolation(
                        code = "ORPHAN_PULL_EXERCISE",
                        entityId = exercise.id,
                        message = "Exercise ${exercise.id} references session ${exercise.sessionId}, expected ${session.id}",
                    )
                }
                addDuplicateIdViolations("DUPLICATE_PULL_SET_ID", exercise.sets.map { it.id }, violations)
                for (set in exercise.sets) {
                    if (set.exerciseId.isNotBlank() && set.exerciseId != exercise.id) {
                        violations += SyncInvariantViolation(
                            code = "ORPHAN_PULL_SET",
                            entityId = set.id,
                            message = "Set ${set.id} references exercise ${set.exerciseId}, expected ${exercise.id}",
                        )
                    }
                    addDuplicateIdViolations("DUPLICATE_PULL_REP_SUMMARY_ID", set.repSummaries.map { it.id }, violations)
                    for (rep in set.repSummaries) {
                        if (rep.setId.isNotBlank() && rep.setId != set.id) {
                            violations += SyncInvariantViolation(
                                code = "ORPHAN_PULL_REP_SUMMARY",
                                entityId = rep.id,
                                message = "Rep summary ${rep.id} references set ${rep.setId}, expected ${set.id}",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkRoutineTree(
        routines: List<PullRoutineDto>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        for (routine in routines) {
            addDuplicateIdViolations("DUPLICATE_PULL_ROUTINE_EXERCISE_ID", routine.exercises.map { it.id }, violations)
            for (exercise in routine.exercises) {
                if (exercise.routineId.isNotBlank() && exercise.routineId != routine.id) {
                    violations += SyncInvariantViolation(
                        code = "ORPHAN_PULL_ROUTINE_EXERCISE",
                        entityId = exercise.id,
                        message = "Routine exercise ${exercise.id} references routine ${exercise.routineId}, expected ${routine.id}",
                    )
                }
                if (exercise.exerciseId.isNullOrBlank() && exercise.name.isBlank()) {
                    violations += SyncInvariantViolation(
                        code = "MISSING_ROUTINE_EXERCISE_IDENTITY",
                        entityId = exercise.id,
                        message = "Routine exercise ${exercise.id} has neither exerciseId nor name",
                    )
                }
            }
        }
    }

    private fun checkCycleTree(
        cycles: List<PullTrainingCycleDto>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        for (cycle in cycles) {
            addDuplicateIdViolations("DUPLICATE_PULL_CYCLE_DAY_ID", cycle.days.map { it.id }, violations)
            for (day in cycle.days) {
                if (day.cycleId.isNotBlank() && day.cycleId != cycle.id) {
                    violations += SyncInvariantViolation(
                        code = "ORPHAN_PULL_CYCLE_DAY",
                        entityId = day.id,
                        message = "Cycle day ${day.id} references cycle ${day.cycleId}, expected ${cycle.id}",
                    )
                }
            }
        }
    }

    private fun checkPersonalRecords(
        personalRecords: List<PullPersonalRecordDto>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        for (record in personalRecords) {
            if (record.exerciseName.isBlank()) {
                violations += SyncInvariantViolation(
                    code = "MISSING_PR_EXERCISE_IDENTITY",
                    entityId = record.id,
                    message = "Personal record ${record.id} has no exerciseName",
                )
            }
            if (record.achievedAt.isNullOrBlank()) {
                violations += SyncInvariantViolation(
                    code = "MISSING_PR_ACHIEVED_AT",
                    entityId = record.id,
                    message = "Personal record ${record.id} has no achievedAt timestamp",
                )
            }
        }

        personalRecords
            .filter { it.exerciseName.isNotBlank() && !it.achievedAt.isNullOrBlank() }
            .groupBy {
                listOf(
                    it.exerciseName.trim().lowercase(),
                    it.recordType.trim().lowercase(),
                    it.workoutPhase.orEmpty().trim().lowercase(),
                    it.achievedAt.orEmpty().trim(),
                    it.sessionId.orEmpty().trim(),
                ).joinToString("|")
            }
            .filterValues { it.size > 1 }
            .forEach { (_, records) ->
                violations += SyncInvariantViolation(
                    code = "DUPLICATE_LOGICAL_PR",
                    entityId = records.joinToString(",") { it.id },
                    message = "Multiple personal records represent the same logical PR: ${records.map { it.id }}",
                )
            }
    }

    private fun checkSessionNotes(
        sessions: List<PullWorkoutSessionDto>,
        sessionNoteKeys: Set<String>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        val sessionIds = sessions.map { it.id }.toSet()
        for (key in sessionNoteKeys) {
            if (key !in sessionIds) {
                violations += SyncInvariantViolation(
                    code = "ORPHAN_SESSION_NOTE",
                    entityId = key,
                    message = "Session note key $key does not match a pulled session id",
                )
            }
        }
    }

    private fun checkLogicalSessionDuplicates(
        mobileSessions: List<WorkoutSession>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        mobileSessions
            .groupBy {
                listOf(
                    it.routineSessionId.orEmpty().trim(),
                    it.exerciseId.orEmpty().trim(),
                    it.exerciseName.orEmpty().trim().lowercase(),
                    it.timestamp.toString(),
                    it.mode.trim().lowercase(),
                ).joinToString("|")
            }
            .filterValues { sessions -> sessions.map { it.id }.distinct().size > 1 }
            .forEach { (_, sessions) ->
                violations += SyncInvariantViolation(
                    code = "DUPLICATE_LOGICAL_SESSION",
                    entityId = sessions.joinToString(",") { it.id },
                    message = "Multiple local sessions represent the same logical session: ${sessions.map { it.id }}",
                )
            }
    }

    private fun addDuplicateIdViolations(
        code: String,
        rawIds: List<String>,
        violations: MutableList<SyncInvariantViolation>,
    ) {
        rawIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupBy { it.lowercase() }
            .filterValues { it.size > 1 }
            .forEach { (_, ids) ->
                violations += SyncInvariantViolation(
                    code = code,
                    entityId = ids.joinToString(","),
                    message = "Duplicate ids found: $ids",
                )
            }
    }
}
