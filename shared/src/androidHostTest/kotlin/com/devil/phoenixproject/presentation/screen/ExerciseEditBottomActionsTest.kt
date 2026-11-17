package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * Issue #1358 visual QA F2 regression: Configure Exercise's primary action
 * ("Start Workout" from the use-selected flow) must grow with its wrapped
 * label at accessibility text scales instead of clipping it. 1.5 font scale
 * is Android's 150% configuration; iOS accessibility-medium is proven by the
 * recaptured simulator frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseEditBottomActionsTest {

    @get:Rule
    val rule = createComposeRule()

    // Density 1f keeps dp == px inside the 320px-wide Robolectric root.
    private val density = 1f

    private fun setActionsContent(fontScale: Float) {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.width(320.dp)) {
                        ExerciseEditBottomActions(
                            buttonText = "Start Workout",
                            enabled = true,
                            onCancel = {},
                            onSave = {},
                        )
                    }
                }
            }
        }
    }

    private fun textLayoutResult(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun `start workout label is not clipped at 150 percent font scale`() {
        setActionsContent(fontScale = 1.5f)

        val layout = textLayoutResult("Start Workout")
        assertFalse(layout.didOverflowHeight)

        val textNode = rule.onNodeWithText("Start Workout", useUnmergedTree = true)
            .fetchSemanticsNode()
        val buttonNode = rule.onNodeWithText("Start Workout").fetchSemanticsNode()
        val textTop = textNode.positionInRoot.y
        val textBottom = textTop + textNode.size.height
        val buttonTop = buttonNode.positionInRoot.y
        val buttonBottom = buttonTop + buttonNode.size.height
        assertTrue(
            textTop >= buttonTop - 1f,
            "label must not be clipped at the button's top edge",
        )
        assertTrue(
            textBottom <= buttonBottom + 1f,
            "label must not be clipped at the button's bottom edge",
        )
    }

    @Test
    fun `start workout label is not clipped at stress accessibility font scale`() {
        setActionsContent(fontScale = 2f)
        val layout = textLayoutResult("Start Workout")
        assertFalse(layout.didOverflowHeight)
    }

    @Test
    fun `buttons keep 56dp height at default font scale`() {
        setActionsContent(fontScale = 1f)
        val expectedPx = 56f * density
        val buttonNode = rule.onNodeWithText("Start Workout").fetchSemanticsNode()
        assertEquals(expectedPx, buttonNode.size.height.toFloat(), 1f)
    }

    @Test
    fun `buttons grow with wrapped label at 150 percent font scale`() {
        setActionsContent(fontScale = 1.5f)
        val buttonNode = rule.onNodeWithText("Start Workout").fetchSemanticsNode()
        assertTrue(
            buttonNode.size.height.toFloat() > 56f * density,
            "button must grow past the 56dp floor when the label wraps",
        )
    }
}
