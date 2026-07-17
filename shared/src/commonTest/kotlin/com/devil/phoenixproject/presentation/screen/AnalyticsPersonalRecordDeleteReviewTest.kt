package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

class AnalyticsPersonalRecordDeleteReviewTest {
    @Test
    fun `personal record delete uses record profile and always dismisses with visible failures`() {
        val analytics = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt",
            ),
        )

        assertContains(analytics, "personalRecordRepository.deletePR(pr.id, pr.profileId)")
        assertContains(analytics, "if (error is CancellationException) throw error")
        assertContains(analytics, "onDeletePersonalRecordError(error)")
        assertContains(
            analytics,
            """finally {
                                deletingRecord = false
                                pendingDelete = null
                            }""",
        )
        assertContains(analytics, "exportMessage = error.message?.takeIf { it.isNotBlank() }")
    }
}
