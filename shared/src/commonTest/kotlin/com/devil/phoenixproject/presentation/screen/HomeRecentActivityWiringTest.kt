package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem
import com.devil.phoenixproject.presentation.manager.lazyColumnKey
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeRecentActivityWiringTest {
    @Test
    fun recentActivityUsesGroupedHistoryAndCapsTheProductionProjectionAtTwoRows() {
        val homeScreen = assertNotNull(
            readProjectFile("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt"),
        )
        val weeklyComplianceSection = homeScreen
            .substringAfter("item(key = \"weekly-compliance\")")
            .substringBefore("item(key = \"recent-activity\")")
        val recentActivitySection = homeScreen
            .substringAfter("item(key = \"recent-activity\")")
            .substringBefore("connectionError?.let")
        val recentActivityContentBody = homeScreen
            .substringAfter("internal fun HomeRecentActivityContent(")
            .substringBefore("@Composable\nprivate fun RecentActivitySummary(")

        assertTrue(homeScreen.contains("viewModel.groupedWorkoutHistory.collectAsState()"))
        assertTrue(weeklyComplianceSection.contains("history = recentSessions"))
        assertTrue(recentActivitySection.contains("HomeRecentActivityContent("))
        assertTrue(recentActivitySection.contains("history = recentActivityHistory"))
        assertTrue(!recentActivitySection.contains("history = recentSessions"))
        assertTrue(recentActivityContentBody.contains("history = projectHomeRecentActivities(history)"))

        val latestRoutine = GroupedRoutineHistoryItem(
            routineSessionId = "latest-run",
            routineName = "Latest Routine",
            sessions = listOf(
                WorkoutSession(id = "latest-set-1", routineSessionId = "latest-run", timestamp = 6_000L, totalReps = 5),
                WorkoutSession(id = "latest-set-2", routineSessionId = "latest-run", timestamp = 7_000L, totalReps = 5),
            ),
            totalDuration = 1_000L,
            totalReps = 10,
            exerciseCount = 1,
            timestamp = 6_000L,
        )
        val previousWorkout = SingleSessionHistoryItem(
            WorkoutSession(id = "previous-workout", timestamp = 4_000L, totalReps = 3),
        )
        val olderRoutine = GroupedRoutineHistoryItem(
            routineSessionId = "older-run",
            routineName = "Older Routine",
            sessions = listOf(WorkoutSession(id = "older-set", routineSessionId = "older-run", timestamp = 2_000L)),
            totalDuration = 0L,
            totalReps = 1,
            exerciseCount = 1,
            timestamp = 2_000L,
        )

        val displayedActivities = projectHomeRecentActivities(
            listOf(latestRoutine, previousWorkout, olderRoutine),
        )

        assertEquals(
            listOf("routine:latest-run", "single:previous-workout"),
            displayedActivities.map(HistoryItem::lazyColumnKey),
        )
        assertEquals(2, displayedActivities.size)
    }
}
