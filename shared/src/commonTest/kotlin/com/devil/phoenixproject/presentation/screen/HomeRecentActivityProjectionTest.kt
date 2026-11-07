package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem
import com.devil.phoenixproject.presentation.manager.lazyColumnKey
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRecentActivityProjectionTest {
    @Test
    fun `groups before limiting and keeps distinct older workout visible`() {
        val newestRoutine = GroupedRoutineHistoryItem(
            routineSessionId = "run-new",
            routineName = "Push Day",
            sessions = listOf(
                WorkoutSession(id = "set-1", timestamp = 3_000L, routineSessionId = "run-new", totalReps = 5),
                WorkoutSession(id = "set-2", timestamp = 4_000L, routineSessionId = "run-new", totalReps = 5),
            ),
            totalDuration = 2_000L,
            totalReps = 10,
            exerciseCount = 1,
            timestamp = 3_000L,
        )
        val olderWorkout = SingleSessionHistoryItem(
            WorkoutSession(id = "standalone-old", timestamp = 2_000L, exerciseId = "squat", totalReps = 3),
        )

        val projected = projectHomeRecentActivities(listOf(newestRoutine, olderWorkout))

        assertEquals(listOf("routine:run-new", "single:standalone-old"), projected.map(HistoryItem::lazyColumnKey))
    }

    @Test
    fun `identical looking executions remain separate with deterministic timestamp ties`() {
        val firstExecution = GroupedRoutineHistoryItem("run-a", "Same Routine", emptyList(), 0, 5, 1, 8_000L)
        val secondExecution = GroupedRoutineHistoryItem("run-b", "Same Routine", emptyList(), 0, 5, 1, 8_000L)

        val projected = projectHomeRecentActivities(listOf(secondExecution, firstExecution))

        assertEquals(listOf("routine:run-a", "routine:run-b"), projected.map(HistoryItem::lazyColumnKey))
    }

    @Test
    fun `null id standalone sessions remain individually represented`() {
        val standaloneA = SingleSessionHistoryItem(WorkoutSession(id = "z-session", timestamp = 8_000L, totalReps = 5))
        val standaloneB = SingleSessionHistoryItem(WorkoutSession(id = "a-session", timestamp = 8_000L, totalReps = 5))

        val projected = projectHomeRecentActivities(listOf(standaloneA, standaloneB))

        assertEquals(listOf("single:a-session", "single:z-session"), projected.map(HistoryItem::lazyColumnKey))
    }

    @Test
    fun `cached recent activities from another profile are hidden before projection`() {
        val oldRoutine = GroupedRoutineHistoryItem(
            routineSessionId = "profile-a-run",
            routineName = "Push Day",
            sessions = listOf(
                WorkoutSession(
                    id = "profile-a-set",
                    routineSessionId = "profile-a-run",
                    profileId = "profile-a",
                    totalReps = 8,
                ),
            ),
            totalDuration = 0L,
            totalReps = 8,
            exerciseCount = 1,
            timestamp = 2_000L,
        )
        val oldStandalone = SingleSessionHistoryItem(
            WorkoutSession(id = "profile-a-single", profileId = "profile-a", totalReps = 5),
        )
        val currentStandalone = SingleSessionHistoryItem(
            WorkoutSession(id = "profile-b-single", profileId = "profile-b", totalReps = 5),
        )

        val visible = filterRecentActivityHistoryForProfile(
            listOf(oldRoutine, oldStandalone, currentStandalone),
            profileId = "profile-b",
        )

        assertEquals(listOf("single:profile-b-single"), visible.map(HistoryItem::lazyColumnKey))
    }
}
