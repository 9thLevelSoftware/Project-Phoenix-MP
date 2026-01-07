package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*

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
data class ConversionResult(
    val cycle: TrainingCycle,
    val routines: List<Routine>,
    val warnings: List<String>
)

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
class TemplateConverter(
    private val exerciseRepository: ExerciseRepository
) {
    companion object {
        /** Default percentage of 1RM used for starting weights (70%) */
        const val DEFAULT_STARTING_WEIGHT_PERCENT = 0.70f
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
     * @return ConversionResult containing the cycle, routines, and any warnings
     */
    suspend fun convert(
        template: CycleTemplate,
        exerciseConfigs: Map<String, ExerciseConfig> = emptyMap()
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
                        name = dayTemplate.name
                    )
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
                    // Look up the exercise in the repository
                    val exercise = exerciseRepository.findByName(templateExercise.exerciseName)

                    if (exercise == null) {
                        // Exercise not found - add to warnings and skip
                        warnings.add(templateExercise.exerciseName)
                        continue
                    }

                    // Determine the workout mode:
                    // 1. User config (from ExerciseConfigModal) takes priority
                    // 2. Fall back to template's suggested mode
                    // 3. Default to OldSchool for bodyweight exercises (null suggested mode)
                    val config = exerciseConfigs[templateExercise.exerciseName]
                    val selectedMode = config?.mode
                        ?: templateExercise.suggestedMode
                        ?: ProgramMode.OldSchool

                    // Calculate starting weight
                    // For percentage-based exercises (5/3/1), weight varies per set so start at 0
                    // For regular exercises, use configured weight or calculate from 1RM
                    val startingWeight = if (templateExercise.isPercentageBased) {
                        // Percentage-based exercises calculate weight per set during workout
                        0f
                    } else {
                        // Use configured weight directly if available, otherwise calculate from 1RM
                        config?.weightPerCableKg?.takeIf { it > 0f }
                            ?: run {
                                val oneRepMax = exercise.oneRepMaxKg ?: 0f
                                if (oneRepMax > 0f) {
                                    // Round to nearest 0.5kg for cleaner weights
                                    ((oneRepMax * DEFAULT_STARTING_WEIGHT_PERCENT) * 2).toInt() / 2f
                                } else {
                                    0f
                                }
                            }
                    }

                    // Create RoutineExercise with proper configuration
                    val routineExercise = RoutineExercise(
                        id = generateUUID(),
                        exercise = exercise,
                        cableConfig = exercise.resolveDefaultCableConfig(),
                        orderIndex = index,
                        setReps = if (templateExercise.isPercentageBased) {
                            // For percentage-based sets (5/3/1), use target reps from percentage sets
                            templateExercise.percentageSets?.map { it.targetReps } ?: listOf(templateExercise.reps)
                        } else {
                            // For regular sets, create list of same reps
                            List(templateExercise.sets) { templateExercise.reps }
                        },
                        weightPerCableKg = startingWeight,
                        programMode = selectedMode,
                        echoLevel = config?.echoLevel ?: EchoLevel.HARDER,
                        eccentricLoad = config?.eccentricLoadPercent?.toEccentricLoad() ?: EccentricLoad.LOAD_100,
                        isAMRAP = templateExercise.percentageSets?.any { it.isAmrap } ?: false
                    )

                    routineExercises.add(routineExercise)
                }

                // Only create routine if we have at least one valid exercise
                if (routineExercises.isNotEmpty()) {
                    val routine = Routine(
                        id = routineId,
                        name = routineTemplate.name,
                        exercises = routineExercises
                    )
                    routines.add(routine)

                    // Create cycle day referencing this routine
                    cycleDays.add(
                        CycleDay.create(
                            cycleId = cycleId,
                            dayNumber = dayTemplate.dayNumber,
                            name = dayTemplate.name,
                            routineId = routineId
                        )
                    )
                }
            }
        }

        // Create the training cycle
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = template.name,
            description = template.description,
            days = cycleDays,
            progressionRule = template.progressionRule,
            weekNumber = 1 // Start at week 1
        )

        return ConversionResult(
            cycle = cycle,
            routines = routines,
            warnings = warnings.distinct() // Remove duplicate warnings
        )
    }
}
