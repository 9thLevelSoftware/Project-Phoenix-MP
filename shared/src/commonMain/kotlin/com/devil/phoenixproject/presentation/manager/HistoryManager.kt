package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.PersonalRecordEntity
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.KmpLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sealed class hierarchy for workout history items.
 * Moved from MainViewModel during monolith decomposition.
 */
sealed class HistoryItem {
    abstract val timestamp: Long
}

data class SingleSessionHistoryItem(val session: WorkoutSession) : HistoryItem() {
    override val timestamp: Long = session.timestamp
}

data class GroupedRoutineHistoryItem(
    val routineSessionId: String,
    val routineName: String,
    val sessions: List<WorkoutSession>,
    val totalDuration: Long,
    val totalReps: Int,
    val exerciseCount: Int,
    override val timestamp: Long
) : HistoryItem()

/**
 * Manages workout history and personal records display.
 * Extracted from MainViewModel — pure read-only computed flows.
 */
class HistoryManager(
    private val workoutRepository: WorkoutRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val scope: CoroutineScope
) {
    private val _workoutHistory = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val workoutHistory: StateFlow<List<WorkoutSession>> = _workoutHistory.asStateFlow()

    val allWorkoutSessions: StateFlow<List<WorkoutSession>> =
        workoutRepository.getAllSessions()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val groupedWorkoutHistory: StateFlow<List<HistoryItem>> = allWorkoutSessions.map { sessions ->
        val groupedByRoutine = sessions.filter { it.routineSessionId != null }
            .groupBy { it.routineSessionId!! }
            .map { (id, sessionList) ->
                GroupedRoutineHistoryItem(
                    routineSessionId = id,
                    routineName = sessionList.first().routineName ?: "Unnamed Routine",
                    sessions = sessionList.sortedBy { it.timestamp },
                    totalDuration = sessionList.sumOf { it.duration },
                    totalReps = sessionList.sumOf { it.totalReps },
                    exerciseCount = sessionList.mapNotNull { it.exerciseId }.distinct().count(),
                    timestamp = sessionList.minOf { it.timestamp }
                )
            }
        val singleSessions = sessions.filter { it.routineSessionId == null }
            .map { SingleSessionHistoryItem(it) }

        (groupedByRoutine + singleSessions).sortedByDescending { it.timestamp }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPersonalRecords: StateFlow<List<PersonalRecord>> =
        personalRecordRepository.getAllPRsGrouped()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    @Suppress("unused")
    val personalBests: StateFlow<List<PersonalRecordEntity>> =
        workoutRepository.getAllPersonalRecords()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val completedWorkouts: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        sessions.size.takeIf { it > 0 }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Calculate current workout streak (consecutive days with workouts).
     * Returns null if no workouts or streak is broken.
     */
    val workoutStreak: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        if (sessions.isEmpty()) {
            return@map null
        }

        // Get unique workout dates, sorted descending (most recent first)
        val workoutDates = sessions
            .map { KmpLocalDate.fromTimestamp(it.timestamp) }
            .distinctBy { it.toKey() }
            .sortedDescending()

        val today = KmpLocalDate.today()
        val lastWorkoutDate = workoutDates.first()

        // Check if streak is current (workout today or yesterday)
        if (lastWorkoutDate.isBefore(today.minusDays(1))) {
            return@map null // Streak broken - no workout today or yesterday
        }

        // Count consecutive days
        var streak = 1
        for (i in 1 until workoutDates.size) {
            val expected = workoutDates[i - 1].minusDays(1)
            if (workoutDates[i] == expected) {
                streak++
            } else {
                break // Found a gap
            }
        }
        streak
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    val progressPercentage: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        if (sessions.size < 2) return@map null
        val latest = sessions[0]
        val previous = sessions[1]
        val latestVol = (latest.weightPerCableKg * 2) * latest.totalReps
        val prevVol = (previous.weightPerCableKg * 2) * previous.totalReps
        if (prevVol <= 0f) return@map null
        ((latestVol - prevVol) / prevVol * 100).toInt()
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Load recent history (moved from MainViewModel init L483-487)
        scope.launch {
            workoutRepository.getAllSessions().collect { sessions ->
                _workoutHistory.value = sessions.take(20)
            }
        }
    }

    fun deleteWorkout(sessionId: String) {
        scope.launch { workoutRepository.deleteSession(sessionId) }
    }

    fun deleteAllWorkouts() {
        scope.launch { workoutRepository.deleteAllSessions() }
    }
}
