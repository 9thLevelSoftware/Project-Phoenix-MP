package com.devil.phoenixproject.presentation.components

import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WeightTextScale
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.screen.SetRow
import com.devil.phoenixproject.presentation.screen.WarmupSetRow
import com.devil.phoenixproject.presentation.screen.WeightConfigurationCard
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.WindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.viewmodel.ExerciseType
import com.devil.phoenixproject.presentation.viewmodel.SetConfiguration
import com.devil.phoenixproject.presentation.viewmodel.SetMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runtime Robolectric Compose tests verifying that the Weight text size preference
 * (`LocalWeightTextScale`) impacts `CompactNumberPicker` weight wheels and the
 * `ExerciseEditBottomSheet` exercise-configuration surfaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h693dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseConfigWeightTextScaleRuntimeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun findNumberPickers(view: View): List<NumberPicker> {
        val result = mutableListOf<NumberPicker>()
        if (view is NumberPicker) {
            result.add(view)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                result.addAll(findNumberPickers(view.getChildAt(index)))
            }
        }
        return result
    }

    private fun textLayoutResult(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun `weight CompactNumberPicker scales textSize monotonically and caps at 2x`() {
        val scaleState = mutableStateOf(WeightTextScale.STANDARD)
        val fontScaleState = mutableFloatStateOf(1.0f)
        var rootView: View? = null

        rule.setContent {
            rootView = LocalView.current
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScaleState.floatValue),
                    LocalWeightTextScale provides scaleState.value,
                ) {
                    Box(Modifier.width(320.dp)) {
                        CompactNumberPicker(
                            value = 20f,
                            onValueChange = {},
                            range = 1f..100f,
                            step = 0.5f,
                            label = "Weight per Cable",
                            suffix = "kg",
                            isWeightValue = true,
                        )
                    }
                }
            }
        }

        rule.waitForIdle()
        val picker = findNumberPickers(requireNotNull(rootView)).single()
        val standardPx = picker.textSize

        scaleState.value = WeightTextScale.LARGE
        rule.waitForIdle()
        val largePx = picker.textSize

        scaleState.value = WeightTextScale.EXTRA_LARGE
        rule.waitForIdle()
        val extraLargePx = picker.textSize

        assertTrue(
            largePx > standardPx,
            "Expected Large textSize ($largePx) > Standard ($standardPx)",
        )
        assertTrue(
            extraLargePx > largePx,
            "Expected Extra large textSize ($extraLargePx) > Large ($largePx)",
        )
        assertEquals(standardPx * 1.5f, largePx, 1.5f)
        assertEquals(standardPx * 2.0f, extraLargePx, 1.5f)

        // Cap check: Extra large at OS 2.0x stays within the 2.0x physical base ceiling
        // (on Android 14+ nonlinear scaling, 20sp at 2.0x maps to ~34px <= 40px) and matches
        // Standard at OS 2.0x because both hit the 2x cap.
        fontScaleState.floatValue = 2.0f
        rule.waitForIdle()
        val extraLargeAtOs2Px = picker.textSize
        assertTrue(
            extraLargeAtOs2Px <= standardPx * 2.0f + 1.5f,
            "Expected capped textSize ($extraLargeAtOs2Px) <= 2x base (${standardPx * 2.0f})",
        )
        assertTrue(
            extraLargeAtOs2Px > standardPx,
            "Expected capped textSize at OS 2.0x ($extraLargeAtOs2Px) > 1x base ($standardPx)",
        )

        scaleState.value = WeightTextScale.STANDARD
        rule.waitForIdle()
        val standardAtOs2Px = picker.textSize
        assertEquals(standardAtOs2Px, extraLargeAtOs2Px, 0.01f)
    }

    @Test
    fun `non-weight CompactNumberPicker ignores WeightTextScale`() {
        val scaleState = mutableStateOf(WeightTextScale.STANDARD)
        var rootView: View? = null

        rule.setContent {
            rootView = LocalView.current
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1.0f),
                    LocalWeightTextScale provides scaleState.value,
                ) {
                    Box(Modifier.width(320.dp)) {
                        CompactNumberPicker(
                            value = 10,
                            onValueChange = {},
                            range = 1..50,
                            label = "Reps",
                            suffix = "reps",
                        )
                    }
                }
            }
        }

        rule.waitForIdle()
        val picker = findNumberPickers(requireNotNull(rootView)).single()
        val standardPx = picker.textSize

        scaleState.value = WeightTextScale.EXTRA_LARGE
        rule.waitForIdle()
        val afterExtraLargePx = picker.textSize

        assertEquals(standardPx, afterExtraLargePx, 0.01f)
    }

    @Test
    fun `SetRow places Reps and Weight side-by-side at Standard and stacks when enlarged`() {
        val scaleState = mutableStateOf(WeightTextScale.STANDARD)
        val compactWindow = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 320.dp,
            heightDp = 800.dp,
        )

        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1.0f),
                    LocalWindowSizeClass provides compactWindow,
                    LocalWeightTextScale provides scaleState.value,
                ) {
                    Box(Modifier.width(320.dp)) {
                        SetRow(
                            setConfig = SetConfiguration(setNumber = 1, reps = 10, weightPerCable = 20f),
                            setMode = SetMode.REPS,
                            exerciseType = ExerciseType.STANDARD,
                            weightSuffix = "kg",
                            maxWeight = 100f,
                            canDelete = true,
                            onRepsChange = {},
                            onWeightChange = {},
                            onDurationChange = {},
                            onRestChange = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }

        rule.waitForIdle()
        val repsStd = rule.onNodeWithText("Reps").fetchSemanticsNode().boundsInRoot
        val weightStd = rule.onNodeWithText("Weight per Cable").fetchSemanticsNode().boundsInRoot
        assertTrue(
            weightStd.left >= repsStd.right - 1f,
            "At Standard, Weight per Cable ($weightStd) should sit to the right of Reps ($repsStd)",
        )

        scaleState.value = WeightTextScale.LARGE
        rule.waitForIdle()
        val repsLarge = rule.onNodeWithText("Reps").fetchSemanticsNode().boundsInRoot
        val weightLarge = rule.onNodeWithText("Weight per Cable").fetchSemanticsNode().boundsInRoot
        assertTrue(
            weightLarge.top >= repsLarge.bottom - 1f,
            "At Large, Weight per Cable ($weightLarge) should stack below Reps ($repsLarge)",
        )

        scaleState.value = WeightTextScale.EXTRA_LARGE
        rule.waitForIdle()
        val repsXl = rule.onNodeWithText("Reps").fetchSemanticsNode().boundsInRoot
        val weightXl = rule.onNodeWithText("Weight per Cable").fetchSemanticsNode().boundsInRoot
        assertTrue(
            weightXl.top >= repsXl.bottom - 1f,
            "At Extra large, Weight per Cable ($weightXl) should stack below Reps ($repsXl)",
        )
    }

    @Test
    fun `WeightConfigurationCard scales resolved weight text`() {
        val scaleState = mutableStateOf(WeightTextScale.STANDARD)
        val compactWindow = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 320.dp,
            heightDp = 800.dp,
        )

        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1.0f),
                    LocalWindowSizeClass provides compactWindow,
                    LocalWeightTextScale provides scaleState.value,
                ) {
                    Box(Modifier.width(320.dp)) {
                        WeightConfigurationCard(
                            usePercentOfPR = true,
                            weightPercentOfPR = 80,
                            currentExercisePR = null,
                            effectiveScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
                            baselineWeightKg = 50f,
                            hasAnyBaseline = true,
                            weightUnit = WeightUnit.KG,
                            formatWeight = { kg, _ -> "${kg.toInt()} kg" },
                            onUsePercentOfPRChange = {},
                            onWeightPercentOfPRChange = {},
                        )
                    }
                }
            }
        }

        rule.waitForIdle()
        val stdLayout = textLayoutResult("= 40 kg/cable")

        scaleState.value = WeightTextScale.EXTRA_LARGE
        rule.waitForIdle()
        val xlLayout = textLayoutResult("= 40 kg/cable")

        assertTrue(
            xlLayout.layoutInput.style.fontSize.value > stdLayout.layoutInput.style.fontSize.value,
            "Expected Extra large resolved weight fontSize (${xlLayout.layoutInput.style.fontSize}) > Standard (${stdLayout.layoutInput.style.fontSize})",
        )
        assertFalse(xlLayout.didOverflowHeight)
    }

    @Test
    fun `WarmupSetRow scales calculated weight without wrapping digits`() {
        val scaleState = mutableStateOf(WeightTextScale.STANDARD)

        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1.0f),
                    LocalWeightTextScale provides scaleState.value,
                ) {
                    Box(Modifier.width(320.dp)) {
                        WarmupSetRow(
                            index = 0,
                            warmupSet = WarmupSet(reps = 8, percentOfWorking = 75),
                            workingWeight = 150f,
                            weightSuffix = "lbs",
                            onUpdateReps = {},
                            onUpdatePercent = {},
                            onRemove = {},
                        )
                    }
                }
            }
        }

        rule.waitForIdle()
        val stdLayout = textLayoutResult("112.5")

        scaleState.value = WeightTextScale.EXTRA_LARGE
        rule.waitForIdle()
        val xlLayout = textLayoutResult("112.5")

        assertTrue(
            xlLayout.layoutInput.style.fontSize.value > stdLayout.layoutInput.style.fontSize.value,
            "Expected Extra large warmup weight fontSize (${xlLayout.layoutInput.style.fontSize}) > Standard (${stdLayout.layoutInput.style.fontSize})",
        )
        assertEquals(1, xlLayout.lineCount, "Warmup calculated weight 112.5 must stay on one line")
        assertFalse(xlLayout.didOverflowHeight)
    }
}
