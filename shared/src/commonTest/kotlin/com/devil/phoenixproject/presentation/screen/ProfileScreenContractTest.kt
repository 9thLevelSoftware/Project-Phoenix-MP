package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.buildProfileRecentHistory
import com.devil.phoenixproject.presentation.viewmodel.ProfileIdentityMutationKind
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileScreenContractTest {
    private val screenPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt"
    private val insightsPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileExerciseInsights.kt"

    @Test
    fun screenTagAndNonReadyLoaderUseTheRealLoadingIndicatorApi() {
        val screen = source(screenPath)
        val tags = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt",
        )

        assertContains(tags, "const val SCREEN_PROFILE")
        assertContains(screen, "TestTags.SCREEN_PROFILE")
        assertContains(screen, "LoadingIndicator(LoadingIndicatorSize.Large)")
        assertContains(screen, "Res.string.profile_switching")
        assertFalse(screen.contains("message ="))
    }

    @Test
    fun selectionFailurePrecedesMissingAndEmptyWhilePickerRemainsAvailable() {
        val insights = source(insightsPath)
        val selector = insights.indexOf("onChooseExercise")
        val failure = insights.indexOf("selectionFailure != null")
        val missing = insights.indexOf("missingExerciseId != null")
        val empty = insights.indexOf("Res.string.profile_no_exercise_history")

        assertTrue(selector >= 0)
        assertTrue(failure in 0 until missing, "selection failure must precede missing")
        assertTrue(missing in 0 until empty, "missing must precede ordinary empty")
        assertContains(insights, "Res.string.profile_insights_load_failed")
    }

    @Test
    fun pickerAndInsightsUseTheCompleteResourceAndSourceInventory() {
        val screen = source(screenPath)
        val insights = source(insightsPath)

        assertContains(screen, "ExercisePickerDialog(")
        assertContains(screen, "enableCustomExercises = false")
        assertFalse(insights.contains("oneRepMaxKg"))
        listOf(
            "profile_exercise_insights",
            "profile_choose_exercise",
            "profile_no_exercise_history",
            "profile_current_one_rep_max",
            "profile_one_rep_max_source_velocity",
            "profile_one_rep_max_source_assessment",
            "profile_one_rep_max_source_session",
            "profile_one_rep_max_source_none",
            "profile_pr_highlights",
            "profile_pr_max_weight",
            "profile_pr_estimated_one_rep_max",
            "profile_pr_max_volume",
            "profile_recent_history",
            "profile_view_full_history",
            "profile_missing_exercise",
            "profile_insights_load_failed",
        ).forEach { key -> assertContains(insights, "Res.string.$key", message = key) }
    }

    @Test
    fun recentHistoryIsDeterministicBoundedChronologicalAndFinite() {
        val result = buildProfileRecentHistory(
            sessions = listOf(
                session("old", 1, 10f),
                session("outside", 2, 20f),
                session("zero", 3, 0f),
                session("middle", 4, 40f),
                session("nan", 5, Float.NaN),
                session("tie-a", 6, 50f),
                session("tie-b", 6, 60f),
            ),
            labelForTimestamp = Long::toString,
        )

        assertEquals(
            listOf("tie-b", "tie-a", "nan", "middle", "zero"),
            result.sessionsNewestFirst.map(WorkoutSession::id),
        )
        assertEquals(listOf("4", "6", "6"), result.chartPointsOldestFirst.map { it.label })
        assertEquals(listOf(40f, 50f, 60f), result.chartPointsOldestFirst.map { it.volume })
        assertTrue(result.chartPointsOldestFirst.all { it.volume.isFinite() && it.volume > 0f })
    }

    @Test
    fun prVolumeIsExplicitlyPerCableAndNeverUsesCableMultiplication() {
        val insights = source(insightsPath)
        val start = insights.indexOf("fun ProfilePrHighlightsCard(")
        val end = insights.indexOf("fun ProfileRecentHistoryCard(")
        assertTrue(start >= 0 && end > start)
        val prCard = insights.substring(start, end)

        assertContains(prCard, "maxVolumeKg")
        assertContains(prCard, "formatPerCableWeight")
        assertContains(prCard, "per-cable kg x reps")
        assertFalse(prCard.contains("cableCount"))
        assertFalse(prCard.contains("effectiveTotalVolumeKg"))
    }

    @Test
    fun overlaysEventsActionsAndTagsAreProfileScopedAndAccessible() {
        val screen = source(screenPath)
        val dialogs = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt",
        )

        listOf("pickerProfileId", "editTargetProfileId", "deleteTargetProfileId").forEach {
            assertContains(screen, it)
        }
        assertContains(screen, "event.profileId")
        assertContains(screen, "event.kind")
        assertContains(screen, "ProfileUiEvent.ProfileRecoveryRequired")
        assertContains(screen, "heightIn(min = 48.dp)")
        assertContains(screen, "Role.Button")
        assertContains(screen, "contentDescription")
        assertEquals(1, Regex("TestTags\\.ACTION_EDIT_PROFILE").findAll(screen).count())
        assertEquals(1, Regex("TestTags\\.ACTION_DELETE_PROFILE").findAll(screen).count())
        assertFalse(dialogs.contains("TestTags.ACTION_EDIT_PROFILE"))
        assertFalse(dialogs.contains("TestTags.ACTION_DELETE_PROFILE"))
    }

    @Test
    fun deletionOverlayOwnershipSurvivesSwitchingRollbackAndScopedFailure() {
        val initial = ProfileIdentityOverlayOwnership(
            deleteTargetProfileId = "a",
            pendingIdentityProfileId = "a",
        )

        val switching = retainProfileIdentityOverlayOwnership(
            ownership = initial,
            readyProfileId = null,
        )
        assertEquals(initial, switching)

        val rolledBack = retainProfileIdentityOverlayOwnership(
            ownership = switching,
            readyProfileId = "a",
        )
        assertEquals(initial, rolledBack)

        val failure = applyProfileIdentityFailure(
            ownership = rolledBack,
            profileId = "a",
            kind = ProfileIdentityMutationKind.DELETE,
        )
        assertTrue(failure.showError)
        assertEquals(
            ProfileIdentityOverlayOwnership(deleteTargetProfileId = "a"),
            failure.ownership,
        )

        assertEquals(
            ProfileIdentityOverlayOwnership(),
            retainProfileIdentityOverlayOwnership(
                ownership = rolledBack,
                readyProfileId = "default",
            ),
        )
    }

    private fun session(id: String, timestamp: Long, totalVolumeKg: Float) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        totalVolumeKg = totalVolumeKg,
        totalReps = 5,
    )

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }
}
