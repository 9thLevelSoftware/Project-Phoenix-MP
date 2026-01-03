package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake TrainingCycleRepository for testing.
 * Provides in-memory storage for training cycles and their progress.
 */
class FakeTrainingCycleRepository : TrainingCycleRepository {

    private val cycles = mutableMapOf<String, TrainingCycle>()
    private val cycleDays = mutableMapOf<String, MutableList<CycleDay>>()
    private val cycleProgress = mutableMapOf<String, CycleProgress>()
    private val cycleProgressions = mutableMapOf<String, CycleProgression>()
    private var activeCycleId: String? = null

    private val _cyclesFlow = MutableStateFlow<List<TrainingCycle>>(emptyList())
    private val _activeCycleFlow = MutableStateFlow<TrainingCycle?>(null)

    // Test control methods
    fun addCycle(cycle: TrainingCycle) {
        cycles[cycle.id] = cycle
        cycleDays[cycle.id] = cycle.days.toMutableList()
        updateFlows()
    }

    fun reset() {
        cycles.clear()
        cycleDays.clear()
        cycleProgress.clear()
        cycleProgressions.clear()
        activeCycleId = null
        updateFlows()
    }

    private fun updateFlows() {
        _cyclesFlow.value = cycles.values.sortedByDescending { it.createdAt }
        _activeCycleFlow.value = activeCycleId?.let { cycles[it] }
    }

    // ========== TrainingCycleRepository interface implementation ==========

    override fun getAllCycles(): Flow<List<TrainingCycle>> = _cyclesFlow

    override suspend fun getCycleById(cycleId: String): TrainingCycle? = cycles[cycleId]

    override fun getActiveCycle(): Flow<TrainingCycle?> = _activeCycleFlow

    override suspend fun getCycleWithProgress(cycleId: String): Pair<TrainingCycle, CycleProgress?>? {
        val cycle = cycles[cycleId] ?: return null
        return cycle to cycleProgress[cycleId]
    }

    override suspend fun saveCycle(cycle: TrainingCycle) {
        cycles[cycle.id] = cycle
        cycleDays[cycle.id] = cycle.days.toMutableList()
        updateFlows()
    }

    override suspend fun updateCycle(cycle: TrainingCycle) {
        cycles[cycle.id] = cycle
        cycleDays[cycle.id] = cycle.days.toMutableList()
        updateFlows()
    }

    override suspend fun setActiveCycle(cycleId: String) {
        activeCycleId = cycleId
        updateFlows()
    }

    override suspend fun deleteCycle(cycleId: String) {
        cycles.remove(cycleId)
        cycleDays.remove(cycleId)
        cycleProgress.remove(cycleId)
        cycleProgressions.remove(cycleId)
        if (activeCycleId == cycleId) {
            activeCycleId = null
        }
        updateFlows()
    }

    override suspend fun getCycleDays(cycleId: String): List<CycleDay> {
        return cycleDays[cycleId]?.sortedBy { it.dayNumber } ?: emptyList()
    }

    override suspend fun addCycleDay(day: CycleDay) {
        cycleDays.getOrPut(day.cycleId) { mutableListOf() }.add(day)
    }

    override suspend fun updateCycleDay(day: CycleDay) {
        cycleDays[day.cycleId]?.let { days ->
            val index = days.indexOfFirst { it.id == day.id }
            if (index >= 0) {
                days[index] = day
            }
        }
    }

    override suspend fun deleteCycleDay(dayId: String) {
        cycleDays.values.forEach { days ->
            days.removeAll { it.id == dayId }
        }
    }

    override suspend fun reorderCycleDays(cycleId: String, dayIds: List<String>) {
        cycleDays[cycleId]?.let { days ->
            val reordered = dayIds.mapIndexedNotNull { index, dayId ->
                days.find { it.id == dayId }?.copy(dayNumber = index + 1)
            }
            days.clear()
            days.addAll(reordered)
        }
    }

    override suspend fun getCycleProgress(cycleId: String): CycleProgress? {
        return cycleProgress[cycleId]
    }

    override suspend fun initializeProgress(cycleId: String): CycleProgress {
        val now = currentTimeMillis()
        val progress = CycleProgress(
            id = generateUUID(),
            cycleId = cycleId,
            currentDayNumber = 1,
            lastCompletedDate = null,
            cycleStartDate = now,
            lastAdvancedAt = null,
            completedDays = emptySet(),
            missedDays = emptySet(),
            rotationCount = 0
        )
        cycleProgress[cycleId] = progress
        return progress
    }

    override suspend fun advanceToNextDay(cycleId: String): Int {
        val progress = cycleProgress[cycleId] ?: initializeProgress(cycleId)
        val days = cycleDays[cycleId] ?: return 1
        val totalDays = days.size

        val newProgress = progress.advanceToNextDay(totalDays, markMissed = false)
        cycleProgress[cycleId] = newProgress

        return newProgress.currentDayNumber
    }

    override suspend fun resetProgress(cycleId: String) {
        cycleProgress[cycleId]?.let { progress ->
            cycleProgress[cycleId] = progress.copy(
                currentDayNumber = 1,
                lastCompletedDate = null,
                lastAdvancedAt = null,
                completedDays = emptySet(),
                missedDays = emptySet(),
                rotationCount = 0
            )
        }
    }

    override suspend fun jumpToDay(cycleId: String, dayNumber: Int) {
        cycleProgress[cycleId]?.let { progress ->
            cycleProgress[cycleId] = progress.copy(
                currentDayNumber = dayNumber
            )
        }
    }

    override suspend fun markDayCompleted(cycleId: String) {
        cycleProgress[cycleId]?.let { progress ->
            val days = cycleDays[cycleId] ?: return
            val newProgress = progress.markDayCompleted(progress.currentDayNumber, days.size)
            cycleProgress[cycleId] = newProgress
        }
    }

    override suspend fun updateCycleProgress(progress: CycleProgress) {
        cycleProgress[progress.cycleId] = progress
    }

    override suspend fun checkAndAutoAdvance(cycleId: String): CycleProgress? {
        return cycleProgress[cycleId]
    }

    override suspend fun getCycleProgression(cycleId: String): CycleProgression? {
        return cycleProgressions[cycleId]
    }

    override suspend fun saveCycleProgression(progression: CycleProgression) {
        cycleProgressions[progression.cycleId] = progression
    }

    override suspend fun deleteCycleProgression(cycleId: String) {
        cycleProgressions.remove(cycleId)
    }

    override suspend fun getCycleItems(cycleId: String): List<CycleItem> {
        val days = cycleDays[cycleId] ?: return emptyList()
        val progress = cycleProgress[cycleId]

        return days.sortedBy { it.dayNumber }.map { day ->
            CycleItem.fromCycleDay(
                day = day,
                routineName = day.routineId,
                exerciseCount = 0
            )
        }
    }
}
