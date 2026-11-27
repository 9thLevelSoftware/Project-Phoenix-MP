package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetSummaryWeightFormatUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun setSummaryCard_rendersFractionalKgLoadsWithoutIntegerTruncation() {
        val summary = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 20.4f,
            avgLoadKgPerCable = 19.6f,
            repCount = 5,
            durationMs = 20_000L,
            totalVolumeKg = 102.4f,
            configuredWeightKgPerCable = 20.4f,
            heaviestLiftKgPerCable = 20.4f,
            peakForceConcentricA = 20.4f,
            peakForceEccentricA = 20.6f,
            avgForceConcentricA = 19.3f,
            avgForceEccentricA = 19.7f,
            estimatedCalories = 5f,
        )

        rule.setContent {
            MaterialTheme {
                SetSummaryCard(
                    summary = summary,
                    workoutMode = "Old School",
                    weightUnit = WeightUnit.KG,
                    kgToDisplay = { kg, unit -> WeightDisplayFormatter.toDisplayWeight(kg, unit) },
                    onContinue = {},
                    autoplayEnabled = false,
                    summaryCountdownSeconds = 0,
                )
            }
        }
        rule.waitForIdle()

        assertTrue(rule.onAllNodesWithText("20.4", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("20.6", substring = true).assertIsDisplayed()
        rule.onNodeWithText("19.3", substring = true).assertIsDisplayed()
        rule.onNodeWithText("19.7", substring = true).assertIsDisplayed()
        rule.onNodeWithText("102.4", substring = true).assertIsDisplayed()
    }
}
