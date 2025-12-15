package com.devil.phoenixproject.data.migration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.ProgramDayEntity
import com.devil.phoenixproject.data.local.WeeklyProgramWithDays
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.CycleDayTemplate
import com.devil.phoenixproject.domain.model.RoutineTemplate
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.domain.model.ProgressionRule
import com.devil.phoenixproject.domain.model.ProgramMode
import kotlinx.coroutines.flow.first

/**
 * Handles migration from WeeklyProgram (calendar-bound) to TrainingCycle (rolling schedule).
 */
class TrainingCycleMigration(
    private val workoutRepository: WorkoutRepository,
    private val trainingCycleRepository: TrainingCycleRepository
) {
    private val log = Logger.withTag("TrainingCycleMigration")

    /**
     * Check if migration is needed (WeeklyPrograms exist but TrainingCycles don't).
     */
    suspend fun needsMigration(): Boolean {
        val programs = workoutRepository.getAllPrograms().first()
        val cycles = trainingCycleRepository.getAllCycles().first()

        return programs.isNotEmpty() && cycles.isEmpty()
    }

    /**
     * Migrate all WeeklyPrograms to TrainingCycles.
     * @return Number of programs migrated
     */
    suspend fun migrateAll(): Int {
        val programs = workoutRepository.getAllPrograms().first()

        if (programs.isEmpty()) {
            log.d { "No programs to migrate" }
            return 0
        }

        log.i { "Starting migration of ${programs.size} programs" }

        var migratedCount = 0
        for (program in programs) {
            try {
                val cycle = migrateProgram(program)
                trainingCycleRepository.saveCycle(cycle)

                // Initialize progress
                trainingCycleRepository.initializeProgress(cycle.id)

                // If program was active, make cycle active
                if (program.program.isActive) {
                    trainingCycleRepository.setActiveCycle(cycle.id)
                }

                migratedCount++
                log.d { "Migrated program '${program.program.name}' to cycle '${cycle.name}'" }
            } catch (e: Exception) {
                log.e(e) { "Failed to migrate program '${program.program.name}'" }
            }
        }

        log.i { "Migration complete: $migratedCount of ${programs.size} programs migrated" }
        return migratedCount
    }

    /**
     * Convert a single WeeklyProgram to a TrainingCycle.
     */
    fun migrateProgram(program: WeeklyProgramWithDays): TrainingCycle {
        val cycleId = generateUUID()

        // Convert days - dayOfWeek 1-7 becomes dayNumber 1-7
        val cycleDays = program.days
            .sortedBy { it.dayOfWeek }
            .mapIndexed { index, programDay ->
                CycleDay(
                    id = generateUUID(),
                    cycleId = cycleId,
                    dayNumber = index + 1,  // 1-based
                    name = getDayName(programDay.dayOfWeek),
                    routineId = programDay.routineId,
                    isRestDay = programDay.routineId == null
                )
            }

        return TrainingCycle(
            id = cycleId,
            name = program.program.name,
            description = buildMigrationDescription(program),
            days = cycleDays,
            createdAt = currentTimeMillis(),
            isActive = program.program.isActive
        )
    }

    /**
     * Get day name from ISO-8601 day of week.
     */
    private fun getDayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            1 -> "Monday"
            2 -> "Tuesday"
            3 -> "Wednesday"
            4 -> "Thursday"
            5 -> "Friday"
            6 -> "Saturday"
            7 -> "Sunday"
            else -> "Day $dayOfWeek"
        }
    }

    /**
     * Build description noting this was migrated.
     */
    private fun buildMigrationDescription(program: WeeklyProgramWithDays): String {
        val originalDesc = program.program.description
        val migrationNote = "Migrated from weekly program"

        return if (originalDesc.isNotBlank()) {
            "$originalDesc\n\n[$migrationNote]"
        } else {
            migrationNote
        }
    }
}

/**
 * Extension to create a training cycle from a weekly program.
 */
fun WeeklyProgramWithDays.toTrainingCycle(): TrainingCycle {
    val cycleId = generateUUID()

    val cycleDays = days
        .sortedBy { it.dayOfWeek }
        .mapIndexed { index, programDay ->
            CycleDay(
                id = generateUUID(),
                cycleId = cycleId,
                dayNumber = index + 1,
                name = when (programDay.dayOfWeek) {
                    1 -> "Monday"
                    2 -> "Tuesday"
                    3 -> "Wednesday"
                    4 -> "Thursday"
                    5 -> "Friday"
                    6 -> "Saturday"
                    7 -> "Sunday"
                    else -> "Day ${programDay.dayOfWeek}"
                },
                routineId = programDay.routineId,
                isRestDay = programDay.routineId == null
            )
        }

    return TrainingCycle(
        id = cycleId,
        name = program.name,
        description = if (program.description.isNotBlank()) {
            "${program.description}\n\n[Migrated from weekly program]"
        } else {
            "Migrated from weekly program"
        },
        days = cycleDays,
        createdAt = currentTimeMillis(),
        isActive = program.isActive
    )
}

/**
 * Preset cycle templates for quick creation.
 */
object CycleTemplates {

    /**
     * 3-Day Full Body template.
     */
    fun threeDay(): CycleTemplate {
        val fullBodyA = RoutineTemplate(
            name = "Full Body A",
            exercises = listOf(
                TemplateExercise("Squat", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Bench Press", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )
        val fullBodyB = RoutineTemplate(
            name = "Full Body B",
            exercises = listOf(
                TemplateExercise("Deadlift", 3, 5, ProgramMode.OldSchool),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT),
                TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TUT),
                TemplateExercise("Plank", 3, null, ProgramMode.OldSchool)  // null reps = timed
            )
        )
        val fullBodyC = RoutineTemplate(
            name = "Full Body C",
            exercises = listOf(
                TemplateExercise("Front Squat", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Bench Press - Wide Grip", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Shrug", 3, 12, ProgramMode.TUT)
            )
        )

        return CycleTemplate(
            id = "template_3day_fullbody",
            name = "3-Day Full Body",
            description = "Full body workout 3 times per week. Great for beginners or those with limited training time.",
            days = listOf(
                CycleDayTemplate.training(1, "Full Body A", fullBodyA),
                CycleDayTemplate.rest(2),
                CycleDayTemplate.training(3, "Full Body B", fullBodyB),
                CycleDayTemplate.rest(4),
                CycleDayTemplate.training(5, "Full Body C", fullBodyC),
                CycleDayTemplate.rest(6),
                CycleDayTemplate.rest(7)
            ),
            progressionRule = ProgressionRule.percentage(2.5f)
        )
    }

    /**
     * Push/Pull/Legs 6-day template.
     */
    fun pushPullLegs(): CycleTemplate {
        val pushA = RoutineTemplate(
            name = "Push A",
            exercises = listOf(
                TemplateExercise("Bench Press", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT),
                TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TUT)
            )
        )
        val pullA = RoutineTemplate(
            name = "Pull A",
            exercises = listOf(
                TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT)
            )
        )
        val legsA = RoutineTemplate(
            name = "Legs A",
            exercises = listOf(
                TemplateExercise("Squat", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lunges", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Leg Extension", 3, 12, ProgramMode.TUT),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )
        val pushB = RoutineTemplate(
            name = "Push B",
            exercises = listOf(
                TemplateExercise("Shoulder Press", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT),
                TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TUT)
            )
        )
        val pullB = RoutineTemplate(
            name = "Pull B",
            exercises = listOf(
                TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT)
            )
        )
        val legsB = RoutineTemplate(
            name = "Legs B",
            exercises = listOf(
                TemplateExercise("Deadlift", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Leg Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )

        return CycleTemplate(
            id = "template_ppl",
            name = "Push/Pull/Legs",
            description = "6-day split focusing on push, pull, and leg movements. Ideal for intermediate lifters seeking muscle growth.",
            days = listOf(
                CycleDayTemplate.training(1, "Push A", pushA),
                CycleDayTemplate.training(2, "Pull A", pullA),
                CycleDayTemplate.training(3, "Legs A", legsA),
                CycleDayTemplate.training(4, "Push B", pushB),
                CycleDayTemplate.training(5, "Pull B", pullB),
                CycleDayTemplate.training(6, "Legs B", legsB)
            ),
            progressionRule = ProgressionRule.percentage(2.5f)
        )
    }

    /**
     * Upper/Lower 5-day template with rest day.
     */
    fun upperLower(): CycleTemplate {
        val upperA = RoutineTemplate(
            name = "Upper A",
            exercises = listOf(
                TemplateExercise("Bench Press", 4, 6, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row", 4, 6, ProgramMode.OldSchool),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TUT)
            )
        )
        val lowerA = RoutineTemplate(
            name = "Lower A",
            exercises = listOf(
                TemplateExercise("Squat", 4, 6, ProgramMode.OldSchool),
                TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lunges", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )
        val upperB = RoutineTemplate(
            name = "Upper B",
            exercises = listOf(
                TemplateExercise("Incline Bench Press", 4, 8, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row - Wide Grip", 4, 8, ProgramMode.OldSchool),
                TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Skull Crusher", 3, 12, ProgramMode.TUT)
            )
        )
        val lowerB = RoutineTemplate(
            name = "Lower B",
            exercises = listOf(
                TemplateExercise("Deadlift", 4, 5, ProgramMode.OldSchool),
                TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Glute Kickback", 3, 12, ProgramMode.TUT)
            )
        )

        return CycleTemplate(
            id = "template_upper_lower",
            name = "Upper/Lower",
            description = "5-day split alternating between upper and lower body. Balanced approach for strength and hypertrophy.",
            days = listOf(
                CycleDayTemplate.training(1, "Upper A", upperA),
                CycleDayTemplate.training(2, "Lower A", lowerA),
                CycleDayTemplate.rest(3),
                CycleDayTemplate.training(4, "Upper B", upperB),
                CycleDayTemplate.training(5, "Lower B", lowerB)
            ),
            progressionRule = ProgressionRule.percentage(2.5f)
        )
    }

    /**
     * Get all available templates.
     */
    fun all(): List<CycleTemplate> = listOf(
        threeDay(),
        pushPullLegs(),
        upperLower()
    )
}
