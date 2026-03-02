package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightTrainingCycleRepository(
    private val db: VitruvianDatabase
) : TrainingCycleRepository {

    private val queries = db.vitruvianDatabaseQueries

    // ==================== Mapping Functions ====================

    private suspend fun mapToTrainingCycle(
        id: String,
        name: String,
        description: String?,
        created_at: Long,
        is_active: Long
    ): TrainingCycle {
        val days = getCycleDays(id)
        return TrainingCycle(
            id = id,
            name = name,
            description = description,
            days = days,
            createdAt = created_at,
            isActive = is_active == 1L
        )
    }

    private fun mapToCycleDay(
        id: String,
        cycle_id: String,
        day_number: Long,
        name: String?,
        routine_id: String?,
        is_rest_day: Long,
        echo_level: String?,
        eccentric_load_percent: Long?,
        weight_progression_percent: Double?,
        rep_modifier: Long?,
        rest_time_override_seconds: Long?
    ): CycleDay {
        return CycleDay(
            id = id,
            cycleId = cycle_id,
            dayNumber = day_number.toInt(),
            name = name,
            routineId = routine_id,
            isRestDay = is_rest_day == 1L,
            echoLevel = echo_level?.let { parseEchoLevel(it) },
            eccentricLoadPercent = eccentric_load_percent?.toInt(),
            weightProgressionPercent = weight_progression_percent?.toFloat(),
            repModifier = rep_modifier?.toInt(),
            restTimeOverrideSeconds = rest_time_override_seconds?.toInt()
        )
    }

    private fun mapToCycleProgress(
        id: String,
        cycle_id: String,
        current_day_number: Long,
        last_completed_date: Long?,
        cycle_start_date: Long,
        last_advanced_at: Long?,
        completed_days: String?,
        missed_days: String?,
        rotation_count: Long
    ): CycleProgress {
        return CycleProgress(
            id = id,
            cycleId = cycle_id,
            currentDayNumber = current_day_number.toInt(),
            lastCompletedDate = last_completed_date,
            cycleStartDate = cycle_start_date,
            lastAdvancedAt = last_advanced_at,
            completedDays = parseIntSet(completed_days),
            missedDays = parseIntSet(missed_days),
            rotationCount = rotation_count.toInt()
        )
    }

    // ==================== Helper Functions ====================

    private fun parseEchoLevel(value: String): EchoLevel? {
        return try {
            EchoLevel.valueOf(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun parseIntSet(json: String?): Set<Int> {
        if (json.isNullOrBlank()) return emptySet()
        return json.trim('[', ']')
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    private fun intSetToJson(set: Set<Int>): String {
        return set.sorted().joinToString(",", "[", "]")
    }

    // ==================== Training Cycles ====================

    override fun getAllCycles(): Flow<List<TrainingCycle>> {
        return queries.selectAllTrainingCycles { id, name, description, created_at, is_active ->
            TrainingCycle(
                id = id,
                name = name,
                description = description,
                days = emptyList(), // Will be loaded separately when needed
                createdAt = created_at,
                isActive = is_active == 1L
            )
        }
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { basicCycles ->
                // Load days for each cycle
                basicCycles.map { cycle ->
                    val days = getCycleDays(cycle.id)
                    cycle.copy(days = days)
                }
            }
    }

    override suspend fun getCycleById(cycleId: String): TrainingCycle? {
        return withContext(Dispatchers.IO) {
            val row = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull()
                ?: return@withContext null

            val days = getCycleDays(cycleId)

            TrainingCycle(
                id = row.id,
                name = row.name,
                description = row.description,
                days = days,
                createdAt = row.created_at,
                isActive = row.is_active == 1L
            )
        }
    }

    override fun getActiveCycle(): Flow<TrainingCycle?> {
        return queries.selectActiveTrainingCycle { id, name, description, created_at, is_active ->
            TrainingCycle(
                id = id,
                name = name,
                description = description,
                days = emptyList(), // Will be loaded separately
                createdAt = created_at,
                isActive = is_active == 1L
            )
        }
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { cycle ->
                cycle?.let {
                    val days = getCycleDays(it.id)
                    it.copy(days = days)
                }
            }
    }

    override suspend fun getCycleWithProgress(cycleId: String): Pair<TrainingCycle, CycleProgress?>? {
        return withContext(Dispatchers.IO) {
            val cycle = getCycleById(cycleId) ?: return@withContext null
            val progress = getCycleProgress(cycleId)
            Pair(cycle, progress)
        }
    }

    override suspend fun saveCycle(cycle: TrainingCycle) {
        withContext(Dispatchers.IO) {
            db.transaction {
                // Insert the training cycle
                queries.insertTrainingCycle(
                    id = cycle.id,
                    name = cycle.name,
                    description = cycle.description,
                    created_at = cycle.createdAt,
                    is_active = if (cycle.isActive) 1L else 0L
                )

                // Insert all days
                cycle.days.forEach { day ->
                    queries.insertCycleDay(
                        id = day.id,
                        cycle_id = cycle.id,
                        day_number = day.dayNumber.toLong(),
                        name = day.name,
                        routine_id = day.routineId,
                        is_rest_day = if (day.isRestDay) 1L else 0L,
                        echo_level = day.echoLevel?.name,
                        eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                        weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                        rep_modifier = day.repModifier?.toLong(),
                        rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong()
                    )
                }

                // If cycle is active, deactivate all others and initialize progress
                if (cycle.isActive) {
                    queries.setActiveTrainingCycle(cycle.id)
                    // Initialize progress if it doesn't exist (uses INSERT OR IGNORE to prevent UNIQUE constraint violations)
                    val now = currentTimeMillis()
                    val progressId = generateUUID()
                    queries.insertCycleProgressIfNotExists(
                        id = progressId,
                        cycle_id = cycle.id,
                        current_day_number = 1L,
                        last_completed_date = null,
                        cycle_start_date = now,
                        last_advanced_at = null,
                        completed_days = null,
                        missed_days = null,
                        rotation_count = 0L
                    )
                }
            }
        }
    }

    override suspend fun updateCycle(cycle: TrainingCycle) {
        withContext(Dispatchers.IO) {
            db.transaction {
                // Update the training cycle
                queries.updateTrainingCycle(
                    name = cycle.name,
                    description = cycle.description,
                    is_active = if (cycle.isActive) 1L else 0L,
                    id = cycle.id
                )

                // Delete existing days and re-insert
                queries.deleteCycleDaysByCycle(cycle.id)

                // Insert all days
                cycle.days.forEach { day ->
                    queries.insertCycleDay(
                        id = day.id,
                        cycle_id = cycle.id,
                        day_number = day.dayNumber.toLong(),
                        name = day.name,
                        routine_id = day.routineId,
                        is_rest_day = if (day.isRestDay) 1L else 0L,
                        echo_level = day.echoLevel?.name,
                        eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                        weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                        rep_modifier = day.repModifier?.toLong(),
                        rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong()
                    )
                }

                // If cycle is active, deactivate all others
                if (cycle.isActive) {
                    queries.setActiveTrainingCycle(cycle.id)
                }
            }
        }
    }

    override suspend fun setActiveCycle(cycleId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                // Set this cycle as active and all others as inactive
                queries.setActiveTrainingCycle(cycleId)

                // Initialize progress if it doesn't exist (uses INSERT OR IGNORE to prevent race conditions)
                val now = currentTimeMillis()
                val progressId = generateUUID()
                queries.insertCycleProgressIfNotExists(
                    id = progressId,
                    cycle_id = cycleId,
                    current_day_number = 1L,
                    last_completed_date = null,
                    cycle_start_date = now,
                    last_advanced_at = null,
                    completed_days = null,
                    missed_days = null,
                    rotation_count = 0L
                )

                // Always reset progress on activation so deactivate/reactivate starts fresh
                queries.resetCycleProgress(
                    cycle_start_date = now,
                    cycle_id = cycleId
                )
            }
        }
    }

    override suspend fun clearActiveCycle() {
        withContext(Dispatchers.IO) {
            queries.deactivateAllCycles()
        }
    }

    override suspend fun deleteCycle(cycleId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                // Delete progress first
                queries.deleteCycleProgress(cycleId)

                // Delete days (should cascade, but be explicit)
                queries.deleteCycleDaysByCycle(cycleId)

                // Delete the cycle
                queries.deleteTrainingCycle(cycleId)
            }
        }
    }

    // ==================== Cycle Days ====================

    override suspend fun getCycleDays(cycleId: String): List<CycleDay> {
        return withContext(Dispatchers.IO) {
            queries.selectCycleDaysByCycle(cycleId, ::mapToCycleDay)
                .executeAsList()
        }
    }

    override suspend fun addCycleDay(day: CycleDay) {
        withContext(Dispatchers.IO) {
            queries.insertCycleDay(
                id = day.id,
                cycle_id = day.cycleId,
                day_number = day.dayNumber.toLong(),
                name = day.name,
                routine_id = day.routineId,
                is_rest_day = if (day.isRestDay) 1L else 0L,
                echo_level = day.echoLevel?.name,
                eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                rep_modifier = day.repModifier?.toLong(),
                rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong()
            )
        }
    }

    override suspend fun updateCycleDay(day: CycleDay) {
        withContext(Dispatchers.IO) {
            queries.updateCycleDay(
                day_number = day.dayNumber.toLong(),
                name = day.name,
                routine_id = day.routineId,
                is_rest_day = if (day.isRestDay) 1L else 0L,
                echo_level = day.echoLevel?.name,
                eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                rep_modifier = day.repModifier?.toLong(),
                rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                id = day.id
            )
        }
    }

    override suspend fun deleteCycleDay(dayId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteCycleDay(dayId)
        }
    }

    override suspend fun reorderCycleDays(cycleId: String, dayIds: List<String>) {
        withContext(Dispatchers.IO) {
            // Get all days for the cycle
            val days = getCycleDays(cycleId)

            // Create a map of dayId to CycleDay
            val dayMap = days.associateBy { it.id }

            // Update day numbers based on the new order
            dayIds.forEachIndexed { index, dayId ->
                val day = dayMap[dayId]
                if (day != null) {
                    val updatedDay = day.copy(dayNumber = index + 1)
                    updateCycleDay(updatedDay)
                }
            }
        }
    }

    // ==================== Cycle Progress ====================

    override suspend fun getCycleProgress(cycleId: String): CycleProgress? {
        return withContext(Dispatchers.IO) {
            queries.selectCycleProgressByCycle(cycleId, ::mapToCycleProgress)
                .executeAsOneOrNull()
        }
    }

    override suspend fun initializeProgress(cycleId: String): CycleProgress {
        return withContext(Dispatchers.IO) {
            val now = currentTimeMillis()
            val progressId = generateUUID()

            val progress = CycleProgress(
                id = progressId,
                cycleId = cycleId,
                currentDayNumber = 1,
                lastCompletedDate = null,
                cycleStartDate = now,
                lastAdvancedAt = null,
                completedDays = emptySet(),
                missedDays = emptySet(),
                rotationCount = 0
            )

            queries.insertCycleProgress(
                id = progress.id,
                cycle_id = progress.cycleId,
                current_day_number = progress.currentDayNumber.toLong(),
                last_completed_date = progress.lastCompletedDate,
                cycle_start_date = progress.cycleStartDate,
                last_advanced_at = progress.lastAdvancedAt,
                completed_days = intSetToJson(progress.completedDays),
                missed_days = intSetToJson(progress.missedDays),
                rotation_count = progress.rotationCount.toLong()
            )

            progress
        }
    }

    override suspend fun advanceToNextDay(cycleId: String): Int {
        return withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId)
                ?: throw IllegalStateException("No progress found for cycle $cycleId")

            val cycle = getCycleById(cycleId)
                ?: throw IllegalStateException("Cycle not found: $cycleId")

            if (cycle.days.isEmpty()) {
                return@withContext progress.currentDayNumber
            }

            val updated = progress.advanceToNextDay(
                totalDays = cycle.days.size,
                markMissed = false
            )

            queries.updateCycleProgress(
                current_day_number = updated.currentDayNumber.toLong(),
                last_completed_date = updated.lastCompletedDate,
                cycle_start_date = updated.cycleStartDate,
                last_advanced_at = updated.lastAdvancedAt,
                completed_days = intSetToJson(updated.completedDays),
                missed_days = intSetToJson(updated.missedDays),
                rotation_count = updated.rotationCount.toLong(),
                cycle_id = cycleId
            )

            updated.currentDayNumber
        }
    }

    override suspend fun resetProgress(cycleId: String) {
        withContext(Dispatchers.IO) {
            val now = currentTimeMillis()

            queries.resetCycleProgress(
                cycle_start_date = now,
                cycle_id = cycleId
            )
        }
    }

    override suspend fun jumpToDay(cycleId: String, dayNumber: Int) {
        withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId)
                ?: throw IllegalStateException("No progress found for cycle $cycleId")

            queries.updateCycleProgress(
                current_day_number = dayNumber.toLong(),
                last_completed_date = progress.lastCompletedDate,
                cycle_start_date = progress.cycleStartDate,
                last_advanced_at = progress.lastAdvancedAt,
                completed_days = intSetToJson(progress.completedDays),
                missed_days = intSetToJson(progress.missedDays),
                rotation_count = progress.rotationCount.toLong(),
                cycle_id = cycleId
            )
        }
    }

    override suspend fun markDayCompleted(cycleId: String) {
        withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId)
                ?: throw IllegalStateException("No progress found for cycle $cycleId")

            val now = currentTimeMillis()
            val updatedCompletedDays = progress.completedDays + progress.currentDayNumber

            queries.updateCycleProgress(
                current_day_number = progress.currentDayNumber.toLong(),
                last_completed_date = now,
                cycle_start_date = progress.cycleStartDate,
                last_advanced_at = progress.lastAdvancedAt,
                completed_days = intSetToJson(updatedCompletedDays),
                missed_days = intSetToJson(progress.missedDays),
                rotation_count = progress.rotationCount.toLong(),
                cycle_id = cycleId
            )
        }
    }

    override suspend fun updateCycleProgress(progress: CycleProgress) {
        withContext(Dispatchers.IO) {
            queries.updateCycleProgress(
                current_day_number = progress.currentDayNumber.toLong(),
                last_completed_date = progress.lastCompletedDate,
                cycle_start_date = progress.cycleStartDate,
                last_advanced_at = progress.lastAdvancedAt,
                completed_days = intSetToJson(progress.completedDays),
                missed_days = intSetToJson(progress.missedDays),
                rotation_count = progress.rotationCount.toLong(),
                cycle_id = progress.cycleId
            )
        }
    }

    override suspend fun checkAndAutoAdvance(cycleId: String): CycleProgress? {
        return withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId) ?: return@withContext null
            val cycle = getCycleById(cycleId) ?: return@withContext null

            if (cycle.days.isEmpty()) {
                return@withContext progress
            }

            val daysToAdvance = progress.pendingAutoAdvanceDays()

            if (daysToAdvance > 0) {
                var updated = progress
                repeat(daysToAdvance) {
                    // Only mark as missed if the current day wasn't already completed
                    val shouldMarkMissed = updated.currentDayNumber !in updated.completedDays
                    updated = updated.advanceToNextDay(cycle.days.size, markMissed = shouldMarkMissed)
                }

                queries.updateCycleProgress(
                    current_day_number = updated.currentDayNumber.toLong(),
                    last_completed_date = updated.lastCompletedDate,
                    cycle_start_date = updated.cycleStartDate,
                    last_advanced_at = updated.lastAdvancedAt,
                    completed_days = intSetToJson(updated.completedDays),
                    missed_days = intSetToJson(updated.missedDays),
                    rotation_count = updated.rotationCount.toLong(),
                    cycle_id = cycleId
                )

                updated
            } else {
                progress
            }
        }
    }

    // ==================== Cycle Progression ====================

    override suspend fun getCycleProgression(cycleId: String): CycleProgression? {
        return withContext(Dispatchers.IO) {
            queries.selectCycleProgression(cycleId).executeAsOneOrNull()?.let { row ->
                CycleProgression(
                    cycleId = row.cycle_id,
                    frequencyCycles = row.frequency_cycles.toInt(),
                    weightIncreasePercent = row.weight_increase_percent?.toFloat(),
                    echoLevelIncrease = row.echo_level_increase != 0L,
                    eccentricLoadIncreasePercent = row.eccentric_load_increase_percent?.toInt()
                )
            }
        }
    }

    override suspend fun saveCycleProgression(progression: CycleProgression) {
        withContext(Dispatchers.IO) {
            queries.upsertCycleProgression(
                cycle_id = progression.cycleId,
                frequency_cycles = progression.frequencyCycles.toLong(),
                weight_increase_percent = progression.weightIncreasePercent?.toDouble(),
                echo_level_increase = if (progression.echoLevelIncrease) 1L else 0L,
                eccentric_load_increase_percent = progression.eccentricLoadIncreasePercent?.toLong()
            )
        }
    }

    override suspend fun deleteCycleProgression(cycleId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteCycleProgression(cycleId)
        }
    }

    override suspend fun getCycleItems(cycleId: String): List<CycleItem> {
        return withContext(Dispatchers.IO) {
            val days = getCycleDays(cycleId)
            val routineIds = days.mapNotNull { it.routineId }.distinct()

            // Fetch routine info for all referenced routines
            // Triple: name, exerciseCount, exerciseNames
            val routineInfo = mutableMapOf<String, Triple<String, Int, List<String>>>()
            routineIds.forEach { routineId ->
                queries.selectRoutineById(routineId).executeAsOneOrNull()?.let { routine ->
                    val exercises = queries.selectExercisesByRoutine(routineId).executeAsList()
                    routineInfo[routineId] = Triple(
                        routine.name,
                        exercises.size,
                        exercises.map { it.exerciseName }
                    )
                }
            }

            days.map { day ->
                val info = day.routineId?.let { routineInfo[it] }
                CycleItem.fromCycleDay(
                    day = day,
                    routineName = info?.first,
                    exerciseCount = info?.second ?: 0,
                    exerciseNames = info?.third ?: emptyList()
                )
            }
        }
    }
}
