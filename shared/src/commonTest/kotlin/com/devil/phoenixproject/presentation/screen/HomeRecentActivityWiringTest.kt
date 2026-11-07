package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeRecentActivityWiringTest {
    @Test
    fun recentActivityUsesGroupedVisibleFeedAndWeeklyComplianceKeepsRawFeed() {
        val homeScreen = assertNotNull(
            readProjectFile("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt"),
        )

        assertTrue(homeScreen.contains("history = recentActivityHistory"))
        assertTrue(homeScreen.contains("profileId = profileId"))
        assertTrue(homeScreen.contains("filterRecentActivityHistoryForProfile(history, profileId)"))
        assertTrue(homeScreen.contains("history = recentSessions"))
        assertTrue(homeScreen.contains("history = projectHomeRecentActivities("))
    }

    @Test
    fun homeRecentActivityFeedIsFilteredToTheActiveProfile() {
        val historyManager = assertNotNull(
            readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/HistoryManager.kt"),
        )

        assertTrue(historyManager.contains("val homeRecentActivityHistory: StateFlow<List<HistoryItem>>"))
        assertTrue(historyManager.contains("sessions.filter { it.profileId == profileId }"))
    }
}
