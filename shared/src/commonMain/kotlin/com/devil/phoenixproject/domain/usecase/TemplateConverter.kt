package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*

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
    /**
     * Convert a CycleTemplate to a TrainingCycle with concrete routines.
     *
     * This method:
     * 1. Creates a TrainingCycle from the template
     * 2. For each CycleDayTemplate with a routine:
     *    - Generates a UUID for the routine
     *    - Resolves all TemplateExercise names to actual Exercise entities
     *    - Creates RoutineExercise instances with proper configuration
     *    - Creates PlannedSet instances for percentage-based sets (5/3/1)
     *    - Tracks any exercises that couldn't be found
     * 3. Returns ConversionResult with cycle, routines, and warnings
     *
     * @param template The cycle template to convert
     * @return ConversionResult containing the cycle, routines, and any warnings
     */
    suspend fun convert(template: CycleTemplate): ConversionResult {
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
                val routineId = generateUUID()
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
                        weightPerCableKg = 0f, // Will be set by user or auto-calculated from 1RM
                        // For bodyweight exercises (null mode), default to OldSchool as placeholder
                        // The UI should detect bodyweight via empty equipment and skip mode selection
                        workoutType = templateExercise.suggestedMode?.let { WorkoutType.Program(it) }
                            ?: WorkoutType.Program(ProgramMode.OldSchool),
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
