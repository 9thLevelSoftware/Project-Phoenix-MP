package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeRecentActivityWiringTest {
    @Test
    fun recentActivityUsesGroupedHistoryAndWeeklyComplianceKeepsRawFeed() {
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
            .substringBefore("private fun RecentActivitySummary(")

        assertTrue(homeScreen.contains("viewModel.groupedWorkoutHistory.collectAsState()"))
        assertTrue(weeklyComplianceSection.contains("history = recentSessions"))
        assertTrue(recentActivitySection.contains("HomeRecentActivityContent("))
        assertTrue(recentActivitySection.contains("history = recentActivityHistory"))
        assertTrue(!recentActivitySection.contains("history = recentSessions"))
        assertTrue(recentActivityContentBody.contains("history = projectHomeRecentActivities(history)"))

    }
}
