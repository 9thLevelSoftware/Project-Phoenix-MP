package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.FiveThreeOneWeeks
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.computeFiveThreeOneSetWeightsForWeek
import com.devil.phoenixproject.domain.model.generateUUID

/**
 * Convert eccentric load percentage to EccentricLoad enum.
 * Rounds to nearest supported value.
 */
private fun Int.toEccentricLoad(): EccentricLoad = when {
    this <= 25 -> EccentricLoad.LOAD_0
    this <= 62 -> EccentricLoad.LOAD_50
    this <= 87 -> EccentricLoad.LOAD_75
    this <= 105 -> EccentricLoad.LOAD_100
    this <= 115 -> EccentricLoad.LOAD_110
    this <= 125 -> EccentricLoad.LOAD_120
    this <= 135 -> EccentricLoad.LOAD_130
    this <= 145 -> EccentricLoad.LOAD_140
    else -> EccentricLoad.LOAD_150
}

/**
 * Result of converting a CycleTemplate to concrete training cycle with routines.
 *
 * @param cycle The created TrainingCycle ready to save
 * @param routines List of Routine objects with resolved exercises
 * @param warnings List of exercise names that couldn't be found in the library
 */
data class ConversionResult(val cycle: TrainingCycle, val routines: List<Routine>, val warnings: List<String>)

/**
 * Converts CycleTemplate objects into concrete TrainingCycle and Routine instances.
 *
 * This utility takes template definitions (e.g., from CycleTemplates) and:
 * - Generates UUIDs for all entities
 * - Resolves exercise names to actual Exercise objects via the repository
 * - Creates Routine and RoutineExercise instances with proper IDs
 * - Tracks any exercises that couldn't be found as warnings
 * - Maps template configuration to actual domain models
 *
 * Example usage:
 * ```kotlin
 * val converter = TemplateConverter(exerciseRepository)
 * val result = converter.convert(CycleTemplates.threeDay())
 * if (result.warnings.isEmpty()) {
 *     // All exercises found - save cycle and routines
 *     trainingCycleRepository.save(result.cycle, result.routines)
 * } else {
 *     // Some exercises not found - show warnings to user
 *     println("Warning: Exercises not found: ${result.warnings}")
 * }
 * ```
 *
 * @param exerciseRepository Repository for looking up exercises by name
 */
class TemplateConverter(private val exerciseRepository: ExerciseRepository) {
    companion object {
        /** Default percentage of 1RM used for starting weights (70%) */
        const val DEFAULT_STARTING_WEIGHT_PERCENT = 0.70f

        /**
         * Conservative per-cable fallback weight (kg) used when no 1RM/PR data exists
         * anywhere for an exercise. Never 0 — a 0kg command confuses the machine and
         * reads as a broken workout to the user. Clearly editable in Set Ready.
         */
        const val DEFAULT_FALLBACK_WEIGHT_KG = 10f

    }

    /**
     * Convert a CycleTemplate to a TrainingCycle with concrete routines.
     *
     * This method:
     * 1. Creates a TrainingCycle from the template
     * 2. For each CycleDayTemplate with a routine:
     *    - Generates a UUID for the routine
     *    - Resolves all TemplateExercise names to actual Exercise entities
     *    - Creates RoutineExercise instances with proper configuration
     *    - Uses ExerciseConfig for mode, weight, echoLevel, and eccentricLoad settings
     *    - Creates PlannedSet instances for percentage-based sets (5/3/1)
     *    - Tracks any exercises that couldn't be found
     * 3. Returns ConversionResult with cycle, routines, and warnings
     *
     * @param template The cycle template to convert
     * @param exerciseConfigs User-configured settings for exercises (mode, weight, echoLevel, eccentricLoad)
     * @param profileId Profile ID for multi-profile support (Issue #364 fix)
     * @param weekNumber Active 5/3/1 week (1-4) selecting which percentage scheme applies to
     *   percentage-based exercises. Week 1 uses the template's embedded percentageSets.
     * @return ConversionResult containing the cycle, routines, and any warnings
     */
    suspend fun convert(
        template: CycleTemplate,
        exerciseConfigs: Map<String, ExerciseConfig> = emptyMap(),
        profileId: String = "default",
        weekNumber: Int = 1,
    ): ConversionResult {
        val cycleId = generateUUID()
        val warnings = mutableListOf<String>()
        val routines = mutableListOf<Routine>()
        val cycleDays = mutableListOf<CycleDay>()

        // Process each day in the template
        for (dayTemplate in template.days) {
            if (dayTemplate.isRestDay) {
                // Create a rest day with no routine
                cycleDays.add(
                    CycleDay.restDay(
                        cycleId = cycleId,
                        dayNumber = dayTemplate.dayNumber,
                        name = dayTemplate.name,
                    ),
                )
            } else {
                // Create a training day with a routine
                // Use cycle_routine_ prefix so these don't show in Daily Routines list
                val routineId = "cycle_routine_${generateUUID()}"
                val routineTemplate = dayTemplate.routine
                    ?: error("Training day ${dayTemplate.dayNumber} has no routine")

                // Convert TemplateExercises to RoutineExercises
                val routineExercises = mutableListOf<RoutineExercise>()
                for ((index, templateExercise) in routineTemplate.exercises.withIndex()) {
                    // Multi-strategy exercise resolution: ID → name → fuzzy search
                    val exercise = exerciseRepository.findByIdOrName(
                        templateExercise.exerciseId,
                        templateExercise.exerciseName,
                    )

                    if (exercise == null) {
                        // All resolution strategies failed
                        Logger.w {
                            "Template exercise not found: '${templateExercise.exerciseName}' (id=${templateExercise.exerciseId})"
                        }
                        warnings.add(templateExercise.exerciseName)
                        continue
                    }

                    // Determine the workout mode:
                    // 1. User config (from ExerciseConfigModal) takes priority
                    // 2. Fall back to template's suggested mode
                    // 3. Default to OldSchool for bodyweight exercises (null suggested mode)
                    // Config lookup: try exercise name first (how UI stores configs), then ID
                    val config = exerciseConfigs[templateExercise.exerciseName]
                        ?: templateExercise.exerciseId?.let { exerciseConfigs[it] }
                    val selectedMode = config?.mode
                        ?: templateExercise.suggestedMode
                        ?: ProgramMode.OldSchool

                    // Live %-of-1RM/PR resolution: template exercises opt into
                    // ResolveRoutineWeightsUseCase (usePercentOfPR + scalingBasis), so working
                    // weights resolve fresh at every workout start (VBT 1RM → stored 1RM →
                    // mode PR → cross-mode PR) and grow with the user. weightPerCableKg is
                    // only the absolute FALLBACK used when that whole chain misses.
                    //
                    // Exception: a weight the user EXPLICITLY edited (ExerciseConfigModal)
                    // pins the exercise to that absolute value. ModeConfirmationScreen
                    // auto-fills config weights from 1RM (ExerciseConfig.fromTemplate), so
                    // the userEditedWeight flag — not mere presence of a weight — is the
                    // pin signal. Without it, entering 1RMs would silently disable live
                    // scaling for every exercise (#633 review, P1).
                    val configuredWeight = config?.weightPerCableKg
                        ?.takeIf { it > 0f && config.userEditedWeight }

                    // Bodyweight/core exercises (suggestedMode == null: Plank, Crunch) never
                    // scale from 1RM — they run at a light fixed weight the user can adjust.
                    val isBodyweight = templateExercise.suggestedMode == null

                    // Fallback weight when no PR/1RM data exists anywhere: snapshot from the
                    // exercise's stored 1RM if present, else a conservative non-zero default.
                    // F381: use round(), not toInt() — toInt() truncates (70.9 → 70.5 not 71.0).
                    // Floor at 0.5kg (machine increment): a tiny 1RM must never round to 0kg.
                    val oneRepMax = exercise.oneRepMaxKg ?: 0f
                    val fallbackWeight = if (isBodyweight || oneRepMax <= 0f) {
                        DEFAULT_FALLBACK_WEIGHT_KG
                    } else {
                        (
                            kotlin.math.round(
                                (oneRepMax * (templateExercise.percentOfOneRm / 100f)) * 2,
                            ).toInt() / 2f
                            ).coerceAtLeast(0.5f)
                    }

                    val routineExercise = if (templateExercise.isPercentageBased) {
                        // Percentage-based main lifts (5/3/1): per-set percentages of the
                        // training max (90% of 1RM), folded into %-of-1RM ints so the
                        // existing per-set resolution applies them directly.
                        // Week 1 uses the template's embedded sets; later weeks come from
                        // the canonical FiveThreeOneWeeks table.
                        val activeSets = templateExercise.percentageSets
                            ?.takeIf { weekNumber == 1 }
                            ?: FiveThreeOneWeeks.forWeek(weekNumber)
                        RoutineExercise(
                            id = generateUUID(),
                            exercise = exercise,
                            orderIndex = index,
                            setReps = activeSets.map { it.targetReps },
                            weightPerCableKg = fallbackWeight,
                            programMode = selectedMode,
                            echoLevel = config?.echoLevel ?: EchoLevel.HARDER,
                            eccentricLoad = config?.eccentricLoadPercent?.toEccentricLoad()
                                ?: EccentricLoad.LOAD_100,
                            isAMRAP = activeSets.any { it.isAmrap },
                            usePercentOfPR = true,
                            scalingBasis = ScalingBasis.ESTIMATED_1RM,
                            setWeightsPercentOfPR = computeFiveThreeOneSetWeightsForWeek(weekNumber),
                        )
                    } else {
                        RoutineExercise(
                            id = generateUUID(),
                            exercise = exercise,
                            orderIndex = index,
                            setReps = List(templateExercise.sets) { templateExercise.reps },
                            weightPerCableKg = configuredWeight ?: fallbackWeight,
                            programMode = selectedMode,
                            echoLevel = config?.echoLevel ?: EchoLevel.HARDER,
                            eccentricLoad = config?.eccentricLoadPercent?.toEccentricLoad()
                                ?: EccentricLoad.LOAD_100,
                            // User-pinned absolute weight disables live scaling; bodyweight
                            // exercises never scale; otherwise resolve from the template's
                            // explicit %-of-1RM prescription.
                            usePercentOfPR = configuredWeight == null && !isBodyweight,
                            weightPercentOfPR = templateExercise.percentOfOneRm,
                            scalingBasis = ScalingBasis.ESTIMATED_1RM,
                        )
                    }

                    routineExercises.add(routineExercise)
                }

                // Always keep the cycle day — silently dropping it shifted the cycle's day
                // numbering with no explanation (issue #620 audit, BUG 2). But when NO
                // exercise resolved, save the day UNASSIGNED (routineId = null) instead of
                // pointing it at an empty routine: the cycle card treats any non-null
                // routineId as startable, and starting an empty routine dead-ends, whereas
                // an unassigned day gets the proper "No routine assigned" state with an
                // "Assign Routine" repair affordance. (#633 review)
                if (routineExercises.isEmpty()) {
                    Logger.e {
                        "Day ${dayTemplate.dayNumber} ('${dayTemplate.name}'): no exercises " +
                            "resolved from the library — day kept unassigned for manual repair"
                    }
                    warnings.add("${dayTemplate.name}: no exercises found in library")
                    cycleDays.add(
                        CycleDay.create(
                            cycleId = cycleId,
                            dayNumber = dayTemplate.dayNumber,
                            name = dayTemplate.name,
                            routineId = null,
                        ),
                    )
                } else {
                    val routine = Routine(
                        id = routineId,
                        name = routineTemplate.name,
                        exercises = routineExercises,
                    )
                    routines.add(routine)

                    // Create cycle day referencing this routine
                    cycleDays.add(
                        CycleDay.create(
                            cycleId = cycleId,
                            dayNumber = dayTemplate.dayNumber,
                            name = dayTemplate.name,
                            routineId = routineId,
                        ),
                    )
                }
            }
        }

        // Create the training cycle with profile ownership (Issue #364 fix)
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = template.name,
            description = template.description,
            days = cycleDays,
            progressionRule = template.progressionRule,
            weekNumber = weekNumber,
            profileId = profileId,
            templateId = template.id,
        )

        return ConversionResult(
            cycle = cycle,
            routines = routines,
            warnings = warnings.distinct(), // Remove duplicate warnings
        )
    }
}
