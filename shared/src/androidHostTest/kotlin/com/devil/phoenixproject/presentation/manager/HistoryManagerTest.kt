package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.util.KmpLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryManagerTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakeWorkoutRepository: FakeWorkoutRepository
    private lateinit var fakePersonalRecordRepository: FakePersonalRecordRepository

    @Before
    fun setup() {
        fakeWorkoutRepository = FakeWorkoutRepository()
        fakePersonalRecordRepository = FakePersonalRecordRepository()
    }

    @Test
    fun `groupedWorkoutHistory groups routine sessions and keeps singles separate`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = HistoryManager(fakeWorkoutRepository, fakePersonalRecordRepository, managerScope)
            var latestHistory: List<HistoryItem>? = null
            val collectJob = launch {
                manager.groupedWorkoutHistory.collect { latestHistory = it }
            }
            advanceUntilIdle()

            fakeWorkoutRepository.addSession(
                WorkoutSession(
                    id = "routine-set-1",
                    timestamp = 1_000L,
                    routineSessionId = "routine-session-1",
                    routineName = "Push Day",
                    exerciseId = "ex-1",
                    totalReps = 8,
                    duration = 60L
                )
            )
            fakeWorkoutRepository.addSession(
                WorkoutSession(
                    id = "routine-set-2",
                    timestamp = 2_000L,
                    routineSessionId = "routine-session-1",
                    routineName = "Push Day",
                    exerciseId = "ex-2",
                    totalReps = 10,
                    duration = 90L
                )
            )
            fakeWorkoutRepository.addSession(
                WorkoutSession(
                    id = "single-session",
                    timestamp = 3_000L,
                    routineSessionId = null,
                    totalReps = 12,
                    duration = 45L
                )
            )
            advanceUntilIdle()

            val historyItems = assertNotNull(latestHistory)
            assertEquals(2, historyItems.size)

            val single = assertIs<SingleSessionHistoryItem>(historyItems[0])
            assertEquals("single-session", single.session.id)

            val grouped = assertIs<GroupedRoutineHistoryItem>(historyItems[1])
            assertEquals("routine-session-1", grouped.routineSessionId)
            assertEquals("Push Day", grouped.routineName)
            assertEquals(2, grouped.sessions.size)
            assertEquals(18, grouped.totalReps)
            assertEquals(150L, grouped.totalDuration)
            assertEquals(2, grouped.exerciseCount)

            collectJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `workoutStreak returns consecutive day count when streak is current`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = HistoryManager(fakeWorkoutRepository, fakePersonalRecordRepository, managerScope)
            val today = KmpLocalDate.today()
            var latestStreak: Int? = Int.MIN_VALUE
            val collectJob = launch {
                manager.workoutStreak.collect { latestStreak = it }
            }
            advanceUntilIdle()

            fakeWorkoutRepository.addSession(WorkoutSession(id = "d0", timestamp = timestampForDate(today)))
            fakeWorkoutRepository.addSession(WorkoutSession(id = "d1", timestamp = timestampForDate(today.minusDays(1))))
            fakeWorkoutRepository.addSession(WorkoutSession(id = "d2", timestamp = timestampForDate(today.minusDays(2))))
            advanceUntilIdle()

            assertEquals(3, latestStreak)
            collectJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `workoutStreak returns null when latest workout is older than yesterday`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = HistoryManager(fakeWorkoutRepository, fakePersonalRecordRepository, managerScope)
            val today = KmpLocalDate.today()
            var latestStreak: Int? = Int.MIN_VALUE
            val collectJob = launch {
                manager.workoutStreak.collect { latestStreak = it }
            }
            advanceUntilIdle()

            fakeWorkoutRepository.addSession(WorkoutSession(id = "old-1", timestamp = timestampForDate(today.minusDays(3))))
            fakeWorkoutRepository.addSession(WorkoutSession(id = "old-2", timestamp = timestampForDate(today.minusDays(4))))
            advanceUntilIdle()

            assertNull(latestStreak)
            collectJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `progressPercentage compares latest two workout volumes`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = HistoryManager(fakeWorkoutRepository, fakePersonalRecordRepository, managerScope)
            var latestProgress: Int? = Int.MIN_VALUE
            val collectJob = launch {
                manager.progressPercentage.collect { latestProgress = it }
            }
            advanceUntilIdle()

            fakeWorkoutRepository.addSession(
                WorkoutSession(
                    id = "previous",
                    timestamp = 1_000L,
                    weightPerCableKg = 10f,
                    totalReps = 10
                )
            )
            fakeWorkoutRepository.addSession(
                WorkoutSession(
                    id = "latest",
                    timestamp = 2_000L,
                    weightPerCableKg = 20f,
                    totalReps = 10
                )
            )
            advanceUntilIdle()

            assertEquals(100, latestProgress)
            collectJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `delete methods remove sessions from history`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = HistoryManager(fakeWorkoutRepository, fakePersonalRecordRepository, managerScope)
            var latestHistory: List<WorkoutSession> = emptyList()
            val collectJob = launch {
                manager.workoutHistory.collect { latestHistory = it }
            }
            advanceUntilIdle()

            fakeWorkoutRepository.addSession(WorkoutSession(id = "session-1"))
            fakeWorkoutRepository.addSession(WorkoutSession(id = "session-2"))
            advanceUntilIdle()
            assertEquals(2, latestHistory.size)

            manager.deleteWorkout("session-1")
            advanceUntilIdle()
            assertEquals(1, latestHistory.size)

            manager.deleteAllWorkouts()
            advanceUntilIdle()
            assertEquals(0, latestHistory.size)

            collectJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    private fun timestampForDate(date: KmpLocalDate): Long =
        LocalDate(date.year, date.month, date.dayOfMonth)
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
}
