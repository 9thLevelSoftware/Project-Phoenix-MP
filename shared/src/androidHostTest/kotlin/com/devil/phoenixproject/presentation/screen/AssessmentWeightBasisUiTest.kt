package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.viewmodel.AssessmentStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssessmentWeightBasisUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun independentLoadsLabelSuggestionAndCapturePerCable() {
        showProgressiveLoading(isTotalLoad = false, hasBleMetrics = true)
        rule.onNodeWithText("Suggested Weight (Per cable)").assertExists()
        rule.onNodeWithText("Set the machine to 10.0 kg (Per cable)").assertExists()
        rule.onNodeWithText("Suggested Weight (Total)").assertDoesNotExist()
    }

    @Test
    fun unifiedLoadsLabelSuggestionAndCaptureTotal() {
        showProgressiveLoading(isTotalLoad = true, hasBleMetrics = true)
        rule.onNodeWithText("Suggested Weight (Total)").assertExists()
        rule.onNodeWithText("Set the machine to 20.0 kg (Total)").assertExists()
    }

    @Test
    fun independentManualEntryLabelsPerCableWithPounds() {
        showProgressiveLoading(isTotalLoad = false, hasBleMetrics = false, weightUnit = WeightUnit.LB)
        rule.onNodeWithText("Actual Weight Used (Per cable)").assertExists()
        rule.onNodeWithText("lbs").assertExists()
        rule.onNodeWithText("Actual Weight Used (kg)").assertDoesNotExist()
    }

    @Test
    fun unifiedManualEntryLabelsTotal() {
        showProgressiveLoading(isTotalLoad = true, hasBleMetrics = false)
        rule.onNodeWithText("Actual Weight Used (Total)").assertExists()
    }

    private fun showProgressiveLoading(
        isTotalLoad: Boolean,
        hasBleMetrics: Boolean,
        weightUnit: WeightUnit = WeightUnit.KG,
    ) {
        rule.setContent {
            MaterialTheme {
                ProgressiveLoadingContent(
                    step = AssessmentStep.ProgressiveLoading(
                        suggestedWeightKg = if (isTotalLoad) 20f else 10f,
                        isTotalLoad = isTotalLoad,
                    ),
                    weightUnit = weightUnit,
                    onRecordSet = { _, _ -> },
                    onStartCapture = {},
                    onStopCapture = {},
                    hasBleMetrics = hasBleMetrics,
                    onCancel = {},
                )
            }
        }
    }
}
