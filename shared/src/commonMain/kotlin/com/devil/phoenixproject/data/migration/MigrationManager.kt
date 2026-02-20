package com.devil.phoenixproject.data.migration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.Routine
import com.devil.phoenixproject.database.RoutineExercise
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.database.WorkoutSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages data migrations on app startup.
 * Call [checkAndRunMigrations] after Koin is initialized.
 * Call [close] when done to prevent memory leaks.
 */
class MigrationManager(
    private val database: VitruvianDatabase
) {
    private val log = Logger.withTag("MigrationManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queries get() = database.vitruvianDatabaseQueries

    private data class RoutineNameResolutionContext(
        val uniqueRoutineNameByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseName: Map<String, String>
    )

    /**
     * Check for and run any pending migrations.
     * This should be called once on app startup.
     */
    fun checkAndRunMigrations() {
        scope.launch {
            try {
                runMigrations()
            } catch (e: Exception) {
                log.e(e) { "Migration failed" }
            }
        }
    }

    private suspend fun runMigrations() {
        backfillLegacyWorkoutRoutineNames()
    }

    private fun backfillLegacyWorkoutRoutineNames() {
        val sessions = runCatching { queries.selectAllSessionsSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load workout sessions for legacy routine-name backfill" }
                return
            }
        if (sessions.isEmpty()) return

        val routines = runCatching { queries.selectAllRoutinesSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load routines for legacy routine-name backfill" }
                emptyList()
            }
        val routineExercises = runCatching { queries.selectAllRoutineExercisesSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load routine exercises for legacy routine-name backfill" }
                emptyList()
            }
        val resolutionContext = buildRoutineNameResolutionContext(routines, routineExercises)

        var updatedRows = 0
        database.transaction {
            sessions.forEach { session ->
                val updatedRoutineName = resolveRoutineNameForSession(session, resolutionContext) ?: return@forEach
                if (updatedRoutineName == session.routineName) return@forEach
                queries.updateSessionRoutineName(
                    routineName = updatedRoutineName,
                    id = session.id
                )
                updatedRows++
            }
        }

        if (updatedRows > 0) {
            log.i { "Legacy routine-name backfill updated $updatedRows workout sessions" }
        }
    }

    private fun resolveRoutineNameForSession(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext
    ): String? {
        val existingRoutineName = sanitizeLegacyLabel(session.routineName)
        val inferredRoutineName = inferRoutineName(session, routineNameResolutionContext)
        val existingLooksLikeExercisePlaceholder =
            normalizeExerciseToken(existingRoutineName) == normalizeExerciseToken(session.exerciseName)

        return when {
            session.isJustLift != 0L -> "Just Lift"
            inferredRoutineName != null && (existingRoutineName == null || existingLooksLikeExercisePlaceholder) -> inferredRoutineName
            existingRoutineName != null -> existingRoutineName
            else -> sanitizeLegacyLabel(session.exerciseName)
        }
    }

    private fun inferRoutineName(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext
    ): String? {
        val byExerciseId = sanitizeLegacyLabel(session.exerciseId)?.let { exerciseId ->
            routineNameResolutionContext.uniqueRoutineNameByExerciseId[exerciseId]
        }
        if (byExerciseId != null) return byExerciseId

        val normalizedExerciseName = normalizeExerciseToken(session.exerciseName) ?: return null
        return routineNameResolutionContext.uniqueRoutineNameByExerciseName[normalizedExerciseName]
    }

    private fun buildRoutineNameResolutionContext(
        routines: List<Routine>,
        routineExercises: List<RoutineExercise>
    ): RoutineNameResolutionContext {
        val routineNameById = routines.associate { routine ->
            routine.id to sanitizeEntityName(routine.name, "Unnamed Routine")
        }
        val nonTemplateRoutineIds = routines
            .asSequence()
            .filterNot { it.id.startsWith("cycle_routine_") }
            .map { it.id }
            .toSet()

        fun collectUniqueRoutineNamesByExerciseId(
            allowedRoutineIds: Set<String>? = null
        ): Map<String, String> {
            val routineIdsByExerciseId = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val exerciseId = sanitizeLegacyLabel(exercise.exerciseId) ?: return@forEach
                routineIdsByExerciseId.getOrPut(exerciseId) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseId.forEach { (exerciseId, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[exerciseId] = routineName
            }
            return uniqueRoutineNames
        }

        fun collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds: Set<String>? = null
        ): Map<String, String> {
            val routineIdsByExerciseName = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val normalizedExerciseName = normalizeExerciseToken(exercise.exerciseName) ?: return@forEach
                routineIdsByExerciseName.getOrPut(normalizedExerciseName) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseName.forEach { (exerciseName, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[exerciseName] = routineName
            }
            return uniqueRoutineNames
        }

        val uniqueFromNonTemplateById = collectUniqueRoutineNamesByExerciseId(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() }
        )
        val uniqueFromAllById = collectUniqueRoutineNamesByExerciseId()
        val uniqueRoutineNameByExerciseId = uniqueFromAllById.toMutableMap().apply {
            putAll(uniqueFromNonTemplateById)
        }
        val uniqueFromNonTemplateByName = collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() }
        )
        val uniqueFromAllByName = collectUniqueRoutineNamesByExerciseName()
        val uniqueRoutineNameByExerciseName = uniqueFromAllByName.toMutableMap().apply {
            putAll(uniqueFromNonTemplateByName)
        }

        return RoutineNameResolutionContext(
            uniqueRoutineNameByExerciseId = uniqueRoutineNameByExerciseId,
            uniqueRoutineNameByExerciseName = uniqueRoutineNameByExerciseName
        )
    }

    private fun sanitizeEntityName(raw: String?, fallback: String): String {
        return sanitizeLegacyLabel(raw) ?: fallback
    }

    private fun sanitizeLegacyLabel(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        if (!trimmed.any { it.isLetterOrDigit() }) return null
        return trimmed
    }

    private fun normalizeExerciseToken(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        val collapsedWhitespace = sanitized
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        return collapsedWhitespace.ifEmpty { null }
    }

    /**
     * Cancels the coroutine scope to prevent memory leaks.
     * Should be called when the MigrationManager is no longer needed.
     */
    fun close() {
        scope.cancel()
        log.d { "MigrationManager scope cancelled" }
    }
}
