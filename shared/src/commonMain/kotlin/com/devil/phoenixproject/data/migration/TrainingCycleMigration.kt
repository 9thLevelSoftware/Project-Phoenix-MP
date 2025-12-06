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
    fun threeDay(): TrainingCycle {
        val cycleId = generateUUID()
        return TrainingCycle(
            id = cycleId,
            name = "3-Day Full Body",
            description = "Full body workout 3 times per week with rest days",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Full Body A"),
                CycleDay.restDay(cycleId = cycleId, dayNumber = 2, name = "Rest"),
                CycleDay.create(cycleId = cycleId, dayNumber = 3, name = "Full Body B"),
                CycleDay.restDay(cycleId = cycleId, dayNumber = 4, name = "Rest"),
                CycleDay.create(cycleId = cycleId, dayNumber = 5, name = "Full Body C"),
                CycleDay.restDay(cycleId = cycleId, dayNumber = 6, name = "Rest"),
                CycleDay.restDay(cycleId = cycleId, dayNumber = 7, name = "Rest")
            ),
            createdAt = currentTimeMillis(),
            isActive = false
        )
    }

    /**
     * Push/Pull/Legs 6-day template.
     */
    fun pushPullLegs(): TrainingCycle {
        val cycleId = generateUUID()
        return TrainingCycle(
            id = cycleId,
            name = "Push/Pull/Legs",
            description = "6-day split focusing on push, pull, and legs movements",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Push"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Pull"),
                CycleDay.create(cycleId = cycleId, dayNumber = 3, name = "Legs"),
                CycleDay.create(cycleId = cycleId, dayNumber = 4, name = "Push"),
                CycleDay.create(cycleId = cycleId, dayNumber = 5, name = "Pull"),
                CycleDay.create(cycleId = cycleId, dayNumber = 6, name = "Legs")
            ),
            createdAt = currentTimeMillis(),
            isActive = false
        )
    }

    /**
     * Upper/Lower 4-day template.
     */
    fun upperLower(): TrainingCycle {
        val cycleId = generateUUID()
        return TrainingCycle(
            id = cycleId,
            name = "Upper/Lower",
            description = "4-day split alternating between upper and lower body",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Upper"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Lower"),
                CycleDay.restDay(cycleId = cycleId, dayNumber = 3, name = "Rest"),
                CycleDay.create(cycleId = cycleId, dayNumber = 4, name = "Upper"),
                CycleDay.create(cycleId = cycleId, dayNumber = 5, name = "Lower")
            ),
            createdAt = currentTimeMillis(),
            isActive = false
        )
    }

    /**
     * Get all available templates.
     */
    fun all(): List<TrainingCycle> = listOf(
        threeDay(),
        pushPullLegs(),
        upperLower()
    )
}
