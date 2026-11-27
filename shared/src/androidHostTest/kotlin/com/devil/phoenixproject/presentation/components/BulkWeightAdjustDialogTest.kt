package com.devil.phoenixproject.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
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
class BulkWeightAdjustDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun routineExercise(
        id: String,
        name: String,
        weightKg: Float,
    ) = RoutineExercise(
        id = id,
        exercise = Exercise(id = id, name = name, muscleGroup = "Chest"),
        orderIndex = 0,
        weightPerCableKg = weightKg,
        programMode = ProgramMode.OldSchool,
    )

    private val formatWeight: (Float, WeightUnit) -> String = { kg, unit ->
        UnitConverter.formatWeight(kg, useLb = unit == WeightUnit.LB)
    }

    @Test
    fun upperBoundClamp_displaysClampedSuffix() {
        val exercises = listOf(
            routineExercise(id = "ex-max", name = "Heavy Press", weightKg = 108f),
        )

        rule.setContent {
            MaterialTheme {
                BulkWeightAdjustDialog(
                    exercises = exercises,
                    weightUnit = WeightUnit.KG,
                    formatWeight = formatWeight,
                    onApply = {},
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithText("+10%").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("110 kg").assertIsDisplayed()
        rule.onNodeWithText(" (clamped)").assertIsDisplayed()
    }

    @Test
    fun zeroWeightExercise_withPercentageIncrease_doesNotShowClampedSuffix() {
        val exercises = listOf(
            routineExercise(id = "ex-zero", name = "Bodyweight Hold", weightKg = 0f),
            routineExercise(id = "ex-normal", name = "Cable Curl", weightKg = 20f),
        )

        rule.setContent {
            MaterialTheme {
                BulkWeightAdjustDialog(
                    exercises = exercises,
                    weightUnit = WeightUnit.KG,
                    formatWeight = formatWeight,
                    onApply = {},
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithText("+10%").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("22 kg").assertIsDisplayed()
        rule.onNodeWithText(" (clamped)").assertDoesNotExist()
    }

    @Test
    fun lowerBoundClamp_displaysClampedSuffix() {
        val exercises = listOf(
            routineExercise(id = "ex-low", name = "Lateral Raise", weightKg = 2f),
        )

        rule.setContent {
            MaterialTheme {
                BulkWeightAdjustDialog(
                    exercises = exercises,
                    weightUnit = WeightUnit.KG,
                    formatWeight = formatWeight,
                    onApply = {},
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithText("Absolute").performClick()
        rule.onNodeWithText("Weight change").performTextReplacement("-5")
        rule.waitForIdle()

        rule.onNodeWithText("0 kg").assertIsDisplayed()
        rule.onNodeWithText(" (clamped)").assertIsDisplayed()
    }
}
