package com.devil.phoenixproject.presentation.navigation

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AssessmentProfileOwnershipTest {
    @Test
    fun `route owned by A invalidates through switching and Ready B without binding B`() {
        val states = listOf(
            ready("athlete-a"),
            ActiveProfileContext.Switching("athlete-b"),
            ready("athlete-b"),
        ).map { context ->
            resolveAssessmentProfileDestination(
                routeProfileId = "athlete-a",
                context = context,
            )
        }

        assertEquals(
            listOf(
                AssessmentProfileDestinationState.Bound("athlete-a"),
                AssessmentProfileDestinationState.Invalidated,
                AssessmentProfileDestinationState.Invalidated,
            ),
            states,
        )
        assertFalse(
            states.any { it == AssessmentProfileDestinationState.Bound("athlete-b") },
        )
    }

    @Test
    fun `all assessment entry points pass Ready profile IDs into route factories`() {
        val navGraph = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt",
        )
        val analytics = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt",
        )
        val detail = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt",
        )
        assertNotNull(navGraph)
        assertNotNull(analytics)
        assertNotNull(detail)

        assertContains(
            navGraph,
            "NavigationRoutes.StrengthAssessmentPicker.createRoute(profileId)",
        )
        assertContains(
            navGraph,
            "NavigationRoutes.StrengthAssessment.createRoute(profileId, exerciseId)",
        )
        assertContains(
            navGraph,
            "val activeProfileContext by profileRepository.activeProfileContext.collectAsState()",
        )
        assertFalse(navGraph.contains("navigate(NavigationRoutes.StrengthAssessmentPicker.route)"))
        assertFalse(navGraph.contains("ownerProfileId"))
        assertContains(analytics, "assessmentProfileId: String?")
        assertContains(analytics, "onNavigateToStrengthAssessment: (String) -> Unit")
        assertContains(detail, "assessmentProfileId: String?")
        assertContains(detail, "onNavigateToStrengthAssessment: (String) -> Unit")
        assertFalse(detail.contains("NavigationRoutes.StrengthAssessment"))
    }

    private fun ready(profileId: String): ActiveProfileContext.Ready {
        val repository = FakeUserProfileRepository()
        repository.setActiveProfileForTest(profileId)
        return repository.activeProfileContext.value as ActiveProfileContext.Ready
    }
}
