package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.generateSupersetId
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.model.routineCopyName

/** What the user chose for routines that match one they already have. */
enum class RoutineCsvImportMode {
    /** Only valid when nothing in the file matches an existing routine. */
    CREATE_NEW,

    /** Replace each matched routine in place, keeping its id, usage and history. */
    OVERWRITE_MATCHING,

    /** Import each matched routine as a new "(Copy)" routine; nothing existing changes. */
    CREATE_COPIES,
}

enum class RoutineCsvImportAction { CREATE, OVERWRITE, COPY }

/** One routine of the preview. */
data class RoutineCsvPlannedRoutine(
    val name: String,
    val action: RoutineCsvImportAction,
    val exerciseCount: Int,
    val setCount: Int,
    val modes: List<String>,
    val groupName: String?,
)

/**
 * Result of planning an import. Nothing has been written: [writes], [newGroups] and
 * [overwriteRoutineIds] are what [com.devil.phoenixproject.data.repository.WorkoutRepository.commitRoutineCsvImport]
 * would store, and only when [canCommit].
 */
data class RoutineCsvImportPlan(
    val mode: RoutineCsvImportMode,
    val routines: List<RoutineCsvPlannedRoutine>,
    val issues: List<RoutineCsvIssue>,
    /** True when at least one routine in the file matches an existing one. */
    val hasMatches: Boolean,
    val newGroups: List<RoutineGroup>,
    val writes: List<Routine>,
    val overwriteRoutineIds: Set<String>,
) {
    val canCommit: Boolean get() = issues.isEmpty() && writes.isNotEmpty()
}

/**
 * Turns parsed CSV routines into routines for [profileId], matching routines and exercises
 * (#772). Pure: the caller supplies the profile's routines, groups and the exercise library.
 *
 * - Routines match by `routine_id` among the profile's own live routines (training-cycle
 *   routines excluded), then by exact name ignoring case. A name shared by several routines
 *   cannot be overwritten.
 * - Exercises match by `exercise_id` in the library, then by unique name. An id that is not in
 *   the library is an error; it never falls back to the name, which could pick a different
 *   exercise.
 */
class RoutineCsvImportPlanner(
    private val newId: () -> String = ::generateUUID,
    private val newSupersetId: () -> String = ::generateSupersetId,
) {
    fun plan(
        drafts: List<RoutineCsvRoutineDraft>,
        mode: RoutineCsvImportMode,
        profileId: String,
        existingRoutines: List<Routine>,
        existingGroups: List<RoutineGroup>,
        exerciseLibrary: List<Exercise>,
        nowMs: Long,
    ): RoutineCsvImportPlan {
        val issues = mutableListOf<RoutineCsvIssue>()
        val liveRoutines = existingRoutines.filter { it.profileId == profileId && !it.id.startsWith(CYCLE_ROUTINE_PREFIX) }
        val exercisesById = exerciseLibrary.filter { !it.id.isNullOrBlank() }.associateBy { it.id!! }
        val exercisesByName = exerciseLibrary.groupBy { normalizedName(it.name) }

        val matches = drafts.map { draft -> match(draft, liveRoutines) }
        val hasMatches = matches.any { it !is Match.None }

        val profileGroups = existingGroups.filter { it.profileId == profileId }
        val newGroups = mutableListOf<RoutineGroup>()
        fun groupIdFor(name: String, order: Int?): String {
            val key = normalizedName(name)
            profileGroups.firstOrNull { normalizedName(it.name) == key }?.let { return it.id }
            newGroups.firstOrNull { normalizedName(it.name) == key }?.let { return it.id }
            val group = RoutineGroup(
                id = newId(),
                name = name,
                profileId = profileId,
                orderIndex = order ?: (profileGroups.size + newGroups.size),
                createdAt = nowMs,
            )
            newGroups += group
            return group.id
        }

        val takenNames = liveRoutines.map { it.name }.toMutableList()
        val overwriteTargets = mutableMapOf<String, Int>()
        val planned = mutableListOf<RoutineCsvPlannedRoutine>()
        val writes = mutableListOf<Routine>()

        drafts.forEachIndexed { index, draft ->
            val issueCount = issues.size
            val exercises = draft.exercises.map { resolveExercise(it, exercisesById, exercisesByName, issues) }

            val target: Routine?
            val action: RoutineCsvImportAction
            when (val match = matches[index]) {
                Match.None -> {
                    target = null
                    action = RoutineCsvImportAction.CREATE
                }

                is Match.Found -> when (mode) {
                    RoutineCsvImportMode.CREATE_NEW -> {
                        issues += RoutineCsvIssue(draft.line, "'${draft.name}' matches an existing routine. Choose Overwrite or Create copies.")
                        target = null
                        action = RoutineCsvImportAction.CREATE
                    }

                    RoutineCsvImportMode.OVERWRITE_MATCHING -> {
                        overwriteTargets[match.routine.id]?.let { otherLine ->
                            issues += RoutineCsvIssue(draft.line, "'${draft.name}' and line $otherLine both match the same existing routine.")
                        }
                        overwriteTargets[match.routine.id] = draft.line
                        target = match.routine
                        action = RoutineCsvImportAction.OVERWRITE
                    }

                    RoutineCsvImportMode.CREATE_COPIES -> {
                        target = null
                        action = RoutineCsvImportAction.COPY
                    }
                }

                is Match.Ambiguous -> when (mode) {
                    RoutineCsvImportMode.CREATE_COPIES -> {
                        target = null
                        action = RoutineCsvImportAction.COPY
                    }

                    else -> {
                        issues += RoutineCsvIssue(
                            draft.line,
                            "${match.count} routines are named '${draft.name}'. Choose Create copies, or add the routine_id of the one to replace.",
                        )
                        target = null
                        action = RoutineCsvImportAction.CREATE
                    }
                }
            }

            val name = if (action == RoutineCsvImportAction.COPY) routineCopyName(draft.name, takenNames) else draft.name
            takenNames += name
            val groupId = when {
                draft.groupName != null -> groupIdFor(draft.groupName, draft.groupOrder)
                action == RoutineCsvImportAction.OVERWRITE -> target?.groupId
                else -> null
            }

            planned += RoutineCsvPlannedRoutine(
                name = name,
                action = action,
                exerciseCount = draft.exercises.size,
                setCount = draft.exercises.sumOf { it.setReps.size },
                modes = draft.exercises.map { RoutineCsvFormat.modeName(it.mode) }.distinct(),
                groupName = draft.groupName ?: groupId?.let { id -> profileGroups.firstOrNull { it.id == id }?.name },
            )

            if (issues.size == issueCount) {
                writes += buildRoutine(
                    draft = draft,
                    exercises = exercises.requireNoNulls(),
                    routineId = target?.id ?: newId(),
                    name = name,
                    target = target,
                    profileId = profileId,
                    groupId = groupId,
                    nowMs = nowMs,
                )
            }
        }

        return RoutineCsvImportPlan(
            mode = mode,
            routines = planned,
            issues = issues,
            hasMatches = hasMatches,
            newGroups = newGroups,
            writes = if (issues.isEmpty()) writes else emptyList(),
            overwriteRoutineIds = if (issues.isEmpty()) overwriteTargets.keys else emptySet(),
        )
    }

    private sealed interface Match {
        data object None : Match

        data class Found(val routine: Routine) : Match

        data class Ambiguous(val count: Int) : Match
    }

    private fun match(draft: RoutineCsvRoutineDraft, liveRoutines: List<Routine>): Match {
        draft.routineId?.let { id -> liveRoutines.firstOrNull { it.id == id } }?.let { return Match.Found(it) }
        val byName = liveRoutines.filter { normalizedName(it.name) == normalizedName(draft.name) }
        return when (byName.size) {
            0 -> Match.None
            1 -> Match.Found(byName.single())
            else -> Match.Ambiguous(byName.size)
        }
    }

    private fun resolveExercise(
        draft: RoutineCsvExerciseDraft,
        byId: Map<String, Exercise>,
        byName: Map<String, List<Exercise>>,
        issues: MutableList<RoutineCsvIssue>,
    ): Exercise? {
        if (draft.exerciseId != null) {
            return byId[draft.exerciseId] ?: run {
                issues += RoutineCsvIssue(
                    draft.line,
                    "exercise_id '${draft.exerciseId}' is not in the exercise library. Remove it to match '${draft.exerciseName}' by name.",
                )
                null
            }
        }
        val candidates = byName[normalizedName(draft.exerciseName)].orEmpty()
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> {
                issues += RoutineCsvIssue(draft.line, "No exercise named '${draft.exerciseName}' in the exercise library.")
                null
            }
            else -> {
                issues += RoutineCsvIssue(
                    draft.line,
                    "${candidates.size} exercises are named '${draft.exerciseName}'. Add the exercise_id: " +
                        candidates.mapNotNull { it.id }.joinToString(", ") + ".",
                )
                null
            }
        }
    }

    private fun buildRoutine(
        draft: RoutineCsvRoutineDraft,
        exercises: List<Exercise>,
        routineId: String,
        name: String,
        target: Routine?,
        profileId: String,
        groupId: String?,
        nowMs: Long,
    ): Routine {
        // Supersets in order of first appearance; each sits at its first exercise's position.
        val supersetKeys = draft.exercises.mapNotNull { it.supersetKey }.distinct()
        val supersetIds = supersetKeys.associateWith { newSupersetId() }
        val usedColors = mutableSetOf<Int>()
        val supersets = supersetKeys.mapIndexed { number, key ->
            val members = draft.exercises.withIndex().filter { it.value.supersetKey == key }
            val first = members.first().value
            val color = SupersetColors.next(usedColors).also { usedColors += it }
            Superset(
                id = supersetIds.getValue(key),
                routineId = routineId,
                name = first.supersetName ?: "Superset ${number + 1}",
                colorIndex = color,
                restBetweenSeconds = first.supersetRestSeconds ?: RoutineCsvFormat.DEFAULT_SUPERSET_REST_SECONDS,
                orderIndex = members.minOf { it.index },
            )
        }

        val routineExercises = draft.exercises.mapIndexed { flatIndex, row ->
            val supersetId = row.supersetKey?.let(supersetIds::getValue)
            RoutineExercise(
                id = newId(),
                exercise = exercises[flatIndex],
                orderIndex = flatIndex,
                setReps = row.setReps,
                weightPerCableKg = row.setWeightsKg.first(),
                setWeightsPerCableKg = row.setWeightsKg,
                programMode = row.mode,
                setRestSeconds = List(row.setReps.size) { row.restSeconds },
                perSetRestTime = false,
                isAMRAP = row.setReps.all { it == null },
                supersetId = supersetId,
                orderInSuperset = if (supersetId == null) {
                    0
                } else {
                    draft.exercises.take(flatIndex).count { it.supersetKey == row.supersetKey }
                },
            )
        }

        return Routine(
            id = routineId,
            name = name,
            description = draft.description,
            exercises = routineExercises,
            supersets = supersets,
            createdAt = target?.createdAt ?: nowMs,
            lastUsed = target?.lastUsed,
            useCount = target?.useCount ?: 0,
            profileId = profileId,
            groupId = groupId,
            updatedAt = nowMs,
        )
    }

    companion object {
        /** Routines generated for training-cycle templates; never a CSV import target. */
        const val CYCLE_ROUTINE_PREFIX = "cycle_routine_"

        /** Trimmed, lower-case, single-spaced: how routine, group and exercise names are matched. */
        fun normalizedName(name: String): String = name.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
    }
}
