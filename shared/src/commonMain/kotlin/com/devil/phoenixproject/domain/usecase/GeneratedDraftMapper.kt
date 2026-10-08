package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.sync.AI_ROUTINE_DRAFT_DISCLAIMER
import com.devil.phoenixproject.data.sync.GeneratedRoutineDraft
import com.devil.phoenixproject.data.sync.GeneratedRoutineExercise
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.generateSupersetId
import com.devil.phoenixproject.domain.model.generateUUID

/**
 * Result of mapping a generated draft to an unsaved routine.
 */
sealed class GeneratedDraftResult {
    /**
     * The draft resolved to a complete unsaved [routine]. [warnings] lists the draft
     * entries dropped because the local exercise library does not know their id —
     * shown on the preview, never substituted with a different movement.
     */
    data class Mapped(val routine: Routine, val warnings: List<String>) : GeneratedDraftResult()

    /**
     * No draft exercise survived the local re-check. The screen turns this into the
     * 422-style "could not build a valid workout" message and does NOT open the editor.
     */
    data object EmptyDraft : GeneratedDraftResult()
}

/**
 * Maps one server-generated routine draft into an unsaved [Routine] + [Superset] rows.
 *
 * This is the issue #1223 client seam ("draft in, editor out"): the mapper performs
 * NO writes. It resolves every draft `exerciseId` against the local library, produces
 * the routine purely in memory, and the user's existing editor Save is the first and
 * only write. It deliberately does NOT call [RoutineTimeEstimator] (the preview screen
 * does) and never calls [ResolveRoutineWeightsUseCase] (workout start resolves the
 * percent-of-1RM chain fresh; the mapper must not freeze a resolved kilogram).
 */
class GeneratedDraftMapper(
    private val exerciseRepository: ExerciseRepository,
    private val baselineRepository: ProfileExerciseBaselineRepository,
) {
    /**
     * @param draft the validated server draft (ids only; no substitution).
     * @param activeProfileId the active profile — the routine is scoped to it so the
     *   editor's Save writes it into the right profile (Save still owns the final value).
     */
    suspend fun map(
        draft: GeneratedRoutineDraft,
        activeProfileId: String,
    ): GeneratedDraftResult {
        val warnings = mutableListOf<String>()
        // Re-check every id against the local library. Misses are dropped with a
        // warning entry; unknown ids are NEVER substituted (acceptance criterion 9).
        val resolved = draft.exercises.mapNotNull { entry ->
            val exercise = exerciseRepository.getExerciseById(entry.exerciseId)
            if (exercise == null) {
                Logger.w {
                    "Generated draft exercise not in local library, dropped: " +
                        "id=${entry.exerciseId} name=${entry.name}"
                }
                warnings += entry.name?.takeIf { it.isNotBlank() } ?: entry.exerciseId
                null
            } else {
                entry to exercise
            }
        }
        if (resolved.isEmpty()) return GeneratedDraftResult.EmptyDraft

        // Groups of one are flattened server-side; flatten again defensively (also for
        // groups that lost members to the drop above) so a singleton never becomes a
        // Superset container.
        val groupSizes = LinkedHashMap<String, Int>()
        resolved.forEach { (entry, _) ->
            entry.supersetGroup?.let { key -> groupSizes[key] = (groupSizes[key] ?: 0) + 1 }
        }

        val routineId = generateUUID()
        val usedColors = mutableSetOf<Int>()
        val supersetIdByKey = mutableMapOf<String, String>()
        val slotOrderByKey = mutableMapOf<String, Int>()
        val memberCountByKey = mutableMapOf<String, Int>()
        val routineExercises = mutableListOf<RoutineExercise>()
        val supersets = mutableListOf<Superset>()
        // One shared orderIndex sequence across standalone exercises and superset
        // containers, matching draft order (the editor sorts items by it). Superset
        // members share their container's slot and are ordered by orderInSuperset.
        var nextOrderIndex = 0

        for ((entry, exercise) in resolved) {
            val key = entry.supersetGroup?.takeIf { groupSizes.getValue(it) >= 2 }
            val orderIndex: Int
            val supersetId: String?
            val orderInSuperset: Int
            if (key == null) {
                orderIndex = nextOrderIndex++
                supersetId = null
                orderInSuperset = 0
            } else {
                val existingSlot = slotOrderByKey[key]
                if (existingSlot == null) {
                    orderIndex = nextOrderIndex++
                    slotOrderByKey[key] = orderIndex
                    val colorIndex = SupersetColors.next(usedColors).also { usedColors += it }
                    val newSupersetId = generateSupersetId()
                    supersetIdByKey[key] = newSupersetId
                    supersets += Superset(
                        id = newSupersetId,
                        routineId = routineId,
                        name = "Superset",
                        colorIndex = colorIndex,
                        restBetweenSeconds = 10,
                        orderIndex = orderIndex,
                    )
                } else {
                    orderIndex = existingSlot
                }
                supersetId = supersetIdByKey.getValue(key)
                orderInSuperset = memberCountByKey[key] ?: 0
                memberCountByKey[key] = orderInSuperset + 1
            }

            routineExercises += buildRoutineExercise(
                entry = entry,
                exercise = exercise,
                activeProfileId = activeProfileId,
                orderIndex = orderIndex,
                supersetId = supersetId,
                orderInSuperset = orderInSuperset,
            )
        }

        return GeneratedDraftResult.Mapped(
            routine = Routine(
                id = routineId,
                name = draft.name.trim().takeIf { it.isNotEmpty() && it.length <= MAX_DRAFT_NAME_LENGTH }
                    ?: FALLBACK_DRAFT_NAME,
                // Disclaimer one-liner only. NEVER the prompt (acceptance criterion 10).
                description = AI_ROUTINE_DRAFT_DISCLAIMER,
                exercises = routineExercises,
                supersets = supersets,
                profileId = activeProfileId,
            ),
            warnings = warnings,
        )
    }

    private suspend fun buildRoutineExercise(
        entry: GeneratedRoutineExercise,
        exercise: Exercise,
        activeProfileId: String,
        orderIndex: Int,
        supersetId: String?,
        orderInSuperset: Int,
    ): RoutineExercise {
        val draftMode = ProgramMode.fromSyncString(entry.mode) ?: ProgramMode.OldSchool
        val isBodyweight = exercise.isBodyweight
        // Bodyweight: percent loading is meaningless and Echo is stripped — the
        // draft's Echo request is forced to Old School with cleared Echo settings.
        val programMode = if (isBodyweight) ProgramMode.OldSchool else draftMode
        val echoLevel: EchoLevel
        val eccentricLoad: EccentricLoad
        if (programMode == ProgramMode.Echo) {
            echoLevel = parseEchoLevel(entry.echoLevel)
            eccentricLoad = parseEccentricLoad(entry.eccentricLoad)
        } else {
            echoLevel = EchoLevel.HARDER
            eccentricLoad = EccentricLoad.LOAD_100
        }

        val weightPerCableKg = if (isBodyweight) {
            TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG
        } else {
            // Snapshot of the profile baseline (never 0, never a kilogram resolved by
            // ResolveRoutineWeightsUseCase). Workout start re-resolves from the
            // percent-of-1RM chain; this is only the absolute fallback.
            baselineRepository.get(activeProfileId, entry.exerciseId)
                ?.oneRepMaxPerCableKg
                ?.takeIf { it.isFinite() && it > 0f }
                ?: TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG
        }

        return RoutineExercise(
            id = generateUUID(),
            exercise = exercise,
            orderIndex = orderIndex,
            setReps = List(entry.sets) { entry.reps },
            weightPerCableKg = weightPerCableKg,
            programMode = programMode,
            eccentricLoad = eccentricLoad,
            echoLevel = echoLevel,
            setRestSeconds = List(entry.sets) { entry.restSeconds },
            dropSetEnabled = false,
            supersetId = supersetId,
            orderInSuperset = orderInSuperset,
            // Cable: live percent-of-estimated-1RM scaling. Bodyweight: fixed fallback.
            usePercentOfPR = !isBodyweight,
            weightPercentOfPR = entry.percentOfOneRm,
            scalingBasis = if (isBodyweight) null else ScalingBasis.ESTIMATED_1RM,
        )
    }

    /** Wire enum name ("HARD", "HARDER", "HARDEST", "EPIC"); nulled/unparsed defaults HARDER. */
    private fun parseEchoLevel(value: String?): EchoLevel =
        value?.trim()?.let { raw ->
            EchoLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: EchoLevel.HARDER

    /** Wire enum name ("LOAD_100", ...); nulled/unparsed defaults LOAD_100. */
    private fun parseEccentricLoad(value: String?): EccentricLoad {
        val raw = value?.trim() ?: return EccentricLoad.LOAD_100
        EccentricLoad.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.let { return it }
        val percent = raw.removePrefix("LOAD_").removePrefix("load_").toIntOrNull()
            ?: return EccentricLoad.LOAD_100
        return EccentricLoad.entries.firstOrNull { it.percentage == percent }
            ?: EccentricLoad.LOAD_100
    }

    private companion object {
        const val MAX_DRAFT_NAME_LENGTH = 80
        const val FALLBACK_DRAFT_NAME = "AI workout"
    }
}
