package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1358 visual QA F1 regression: the Resistance Type chips must never
 * wrap "Bodyweight" mid-word. 1.5 font scale is Android's documented failing
 * 360dp/150% configuration; 2.0 is a beyond-accessibility-medium stress case.
 * The 312.dp container width mirrors the dialog's inner width at 360dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResistanceTypeSelectorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setSelectorContent(fontScale: Float) {
        rule.setContent {
            MaterialTheme {
                // Density 1f keeps dp == px inside the 320px-wide Robolectric root;
                // dp-space layout is density independent, so wrap behavior matches device.
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.width(312.dp)) {
                        ResistanceTypeSelector(usesCables = true, onUsesCablesChange = {})
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
    fun `bodyweight label stays on one line at android 150 percent font scale`() {
        setSelectorContent(fontScale = 1.5f)
        val layout = textLayoutResult("Bodyweight")
        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `bodyweight label stays on one line at stress accessibility font scale`() {
        setSelectorContent(fontScale = 2f)
        val layout = textLayoutResult("Bodyweight")
        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `both chips remain inside the dialog width at 150 percent font scale`() {
        setSelectorContent(fontScale = 1.5f)
        val maxWidthPx = 312f // container width in dp == px at density 1f
        for (label in listOf("Cables", "Bodyweight")) {
            val node = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(
                node.positionInRoot.x + node.size.width <= maxWidthPx + 1f,
                "$label must not overflow the dialog horizontally",
            )
        }
    }

    @Test
    fun `both labels stay single line at default font scale`() {
        setSelectorContent(fontScale = 1f)
        assertEquals(1, textLayoutResult("Cables").lineCount)
        assertEquals(1, textLayoutResult("Bodyweight").lineCount)
    }
}
