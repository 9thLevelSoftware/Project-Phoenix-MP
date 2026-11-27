package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem
import com.devil.phoenixproject.presentation.util.AchievedLoadPresentation
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.util.UnitConverter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryTabEchoLoadTest {

    @get:Rule
    val rule = createComposeRule()

    private val formatWeight: (Float, WeightUnit) -> String = { kg, unit ->
        UnitConverter.formatWeight(kg, useLb = unit == WeightUnit.LB)
    }

    private val kgToDisplay: (Float, WeightUnit) -> Float = { kg, unit ->
        WeightDisplayFormatter.toDisplayWeight(kg, unit)
    }

    @Test
    fun singleSessionCard_echoWithMeasuredLoad_displaysAchievedLoadNotAdaptive() {
        val session = WorkoutSession(
            id = "echo-measured-1",
            timestamp = 1_700_000_000_000L,
            mode = "Echo",
            exerciseName = "Echo Press",
            weightPerCableKg = 10f,
            heaviestLiftKg = 42f,
            peakForceEccentricA = 45f,
            totalReps = 8,
            workingReps = 8,
        )

        rule.setContent {
            MaterialTheme {
                WorkoutHistoryCard(
                    session = session,
                    weightUnit = WeightUnit.KG,
                    formatWeight = formatWeight,
                    kgToDisplay = kgToDisplay,
                    exerciseRepository = FakeExerciseRepository(),
                    repMetricRepository = FakeRepMetricRepository(),
                    onDelete = {},
                )
            }
        }

        rule.onNodeWithText("42 kg").assertIsDisplayed()
        rule.onNodeWithText("Adaptive").assertDoesNotExist()
    }

    @Test
    fun singleSessionCard_echoZeroSentinel_displaysLoadUnavailable() {
        val session = WorkoutSession(
            id = "echo-unmeasured-1",
            timestamp = 1_700_000_000_000L,
            mode = "Echo",
            exerciseName = "Echo Row",
            weightPerCableKg = 10f,
            heaviestLiftKg = 0f,
            totalReps = 6,
            workingReps = 6,
        )

        rule.setContent {
            MaterialTheme {
                WorkoutHistoryCard(
                    session = session,
                    weightUnit = WeightUnit.KG,
                    formatWeight = formatWeight,
                    kgToDisplay = kgToDisplay,
                    exerciseRepository = FakeExerciseRepository(),
                    repMetricRepository = FakeRepMetricRepository(),
                    onDelete = {},
                )
            }
        }

        rule.onNodeWithText(AchievedLoadPresentation.LOAD_UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText("Adaptive").assertDoesNotExist()
        rule.onNodeWithText("10 kg").assertDoesNotExist()
        rule.onNodeWithText("0 kg").assertDoesNotExist()
    }

    @Test
    fun groupedRoutineCard_echoGroups_displayAchievedLoadOrLoadUnavailable() {
        val measuredSet = WorkoutSession(
            id = "routine-echo-1",
            timestamp = 1_700_000_000_000L,
            routineSessionId = "routine-1",
            mode = "Echo",
            exerciseId = "ex-measured",
            exerciseName = "Echo Curl",
            weightPerCableKg = 10f,
            heaviestLiftKg = 42f,
            peakForceEccentricA = 44f,
            totalReps = 10,
            workingReps = 10,
        )
        val unmeasuredSet = WorkoutSession(
            id = "routine-echo-2",
            timestamp = 1_700_000_060_000L,
            routineSessionId = "routine-1",
            mode = "Echo",
            exerciseId = "ex-unmeasured",
            exerciseName = "Echo Fly",
            weightPerCableKg = 12f,
            heaviestLiftKg = 0f,
            totalReps = 8,
            workingReps = 8,
        )
        val grouped = GroupedRoutineHistoryItem(
            routineSessionId = "routine-1",
            routineName = "Echo Routine",
            sessions = listOf(measuredSet, unmeasuredSet),
            totalDuration = 120_000L,
            totalReps = 18,
            exerciseCount = 2,
            timestamp = 1_700_000_000_000L,
        )

        rule.setContent {
            MaterialTheme {
                GroupedRoutineCard(
                    groupedItem = grouped,
                    weightUnit = WeightUnit.KG,
                    formatWeight = formatWeight,
                    kgToDisplay = kgToDisplay,
                    exerciseRepository = FakeExerciseRepository(),
                    repMetricRepository = FakeRepMetricRepository(),
                    onDeleteRoutineGroup = { _, _ -> },
                )
            }
        }

        rule.onNodeWithText("42 kg").assertIsDisplayed()
        rule.onNodeWithText(AchievedLoadPresentation.LOAD_UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText("Adaptive").assertDoesNotExist()
    }
}
