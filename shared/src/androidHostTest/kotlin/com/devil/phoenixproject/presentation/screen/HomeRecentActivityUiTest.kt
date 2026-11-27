package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem
import com.devil.phoenixproject.presentation.util.AchievedLoadPresentation
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRecentActivityUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun groupedRoutineRendersOneNonClickableSummaryAndStandaloneKeepsReplay() {
        val routineId = "routine-execution-1"
        val groupedRoutine = GroupedRoutineHistoryItem(
            routineSessionId = routineId,
            routineName = "Push Day",
            sessions = listOf(
                WorkoutSession(
                    id = "routine-set-1",
                    timestamp = 1_000L,
                    routineSessionId = routineId,
                    exerciseId = "bench",
                    totalReps = 8,
                ),
                WorkoutSession(
                    id = "routine-set-2",
                    timestamp = 2_000L,
                    routineSessionId = routineId,
                    exerciseId = "row",
                    totalReps = 10,
                ),
            ),
            totalDuration = 2_000L,
            totalReps = 18,
            exerciseCount = 2,
            timestamp = 1_000L,
        )
        val standalone = SingleSessionHistoryItem(
            WorkoutSession(
                id = "standalone-1",
                timestamp = 172_801_000L,
                exerciseId = "squat",
                exerciseName = "Squat",
                totalReps = 5,
                weightPerCableKg = 30f,
            ),
        )
        val clickedExercises = mutableListOf<String>()
        val history: List<HistoryItem> = listOf(standalone, groupedRoutine)

        rule.setContent {
            MaterialTheme {
                HomeRecentActivityContent(
                    history = history,
                    weightUnit = WeightUnit.KG,
                    onExerciseClick = clickedExercises::add,
                )
            }
        }

        val datePattern = "MMM d"
        val timePattern = "h:mm a"
        val separator = " • "

        rule.onAllNodesWithText("Push Day").assertCountEquals(1)
        rule.onNodeWithText("Push Day").assertIsDisplayed().assertHasNoClickAction()
        rule.onNodeWithText("2 sets • 18 total reps").assertIsDisplayed()
        rule.onNodeWithText(
            formatRecentActivityDateTime(
                groupedRoutine.timestamp,
                datePattern = datePattern,
                timePattern = timePattern,
                separator = separator,
            ),
        ).assertIsDisplayed()

        rule.onNodeWithText("Squat").assertIsDisplayed().assertHasClickAction().performClick()
        rule.onNodeWithText(AchievedLoadPresentation.homeRecentActivityLine(standalone.session, WeightUnit.KG))
            .assertIsDisplayed()
        rule.onNodeWithText(
            formatRecentActivityDateTime(
                standalone.timestamp,
                datePattern = datePattern,
                timePattern = timePattern,
                separator = separator,
            ),
        ).assertIsDisplayed()
        assertEquals(listOf("squat"), clickedExercises)
    }

    @Test
    fun homeRecentActivityProjectsGroupedRoutineAsSingleSummaryInsteadOfRawSets() {
        val rawSet1 = WorkoutSession(
            id = "raw-1",
            timestamp = 172_801_000L,
            routineSessionId = "routine-abc",
            routineName = "Upper A",
            exerciseId = "bench",
            exerciseName = "Bench Press",
            totalReps = 8,
            workingReps = 8,
            weightPerCableKg = 25f,
        )
        val rawSet2 = WorkoutSession(
            id = "raw-2",
            timestamp = 172_802_000L,
            routineSessionId = "routine-abc",
            routineName = "Upper A",
            exerciseId = "row",
            exerciseName = "Cable Row",
            totalReps = 10,
            workingReps = 10,
            weightPerCableKg = 30f,
        )
        val grouped = GroupedRoutineHistoryItem(
            routineSessionId = "routine-abc",
            routineName = "Upper A",
            sessions = listOf(rawSet1, rawSet2),
            totalDuration = 120_000L,
            totalReps = 18,
            exerciseCount = 2,
            timestamp = 172_801_000L,
        )
        val projected = projectHomeRecentActivities(listOf(grouped))
        assertEquals(1, projected.size)

        rule.setContent {
            MaterialTheme {
                HomeRecentActivityContent(
                    history = projected,
                    weightUnit = WeightUnit.KG,
                    onExerciseClick = {},
                )
            }
        }

        rule.onAllNodesWithText("Upper A").assertCountEquals(1)
        rule.onNodeWithText("2 sets • 18 total reps").assertIsDisplayed()
        rule.onNodeWithText("Bench Press").assertDoesNotExist()
        rule.onNodeWithText("Cable Row").assertDoesNotExist()
    }
}
