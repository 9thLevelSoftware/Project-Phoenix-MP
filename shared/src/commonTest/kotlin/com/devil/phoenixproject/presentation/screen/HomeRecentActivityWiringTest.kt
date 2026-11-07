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

        assertTrue(homeScreen.contains("viewModel.groupedWorkoutHistory.collectAsState()"))
        assertTrue(homeScreen.contains("history = recentActivityHistory"))
        assertTrue(homeScreen.contains("history = recentSessions"))
    }
}
