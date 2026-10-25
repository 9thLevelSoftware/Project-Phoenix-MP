package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1345: on an iPhone the set summary rendered labels one character per line
 * (`(lbs/cable)`, an eccentric force value, and the whole expanded RPE slider squeezed
 * into a strip beside "How hard was that?"). Each was a Row child measured after its
 * siblings had taken the width, so it was left a sliver and the text broke between
 * every character.
 *
 * Renders the real [SetSummaryCard] at a phone width and fails if any text line breaks
 * inside a word: a line may only start after whitespace or a break-friendly separator.
 * Fonts are Robolectric's, not iOS's, so this pins the layout rather than exact iOS metrics.
 * Covered up to fontScale 2.0; around 3.0 single words outgrow a half-width card, which
 * needs a one-column layout rather than wrapping.
 */
@RunWith(RobolectricTestRunner::class)
// iPhone 16 Pro (iPhone17,1): 402 x 874 pt at 3x.
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetSummaryCardTextLayoutRuntimeTest {

    @get:Rule
    val rule = createComposeRule()

    // 30 lb per cable configured; peak/avg forces around 30 lb and 29.5 lb, as in the report.
    private val summary = WorkoutState.SetSummary(
        metrics = emptyList(),
        peakLoadKgPerCable = 13.6f,
        avgLoadKgPerCable = 13.4f,
        repCount = 10,
        durationMs = 42_000L,
        totalVolumeKg = 272f,
        configuredWeightKgPerCable = 13.6078f,
        heaviestLiftKgPerCable = 13.6078f,
        peakForceConcentricA = 13.6078f,
        peakForceEccentricA = 13.6078f,
        avgForceConcentricA = 13.381f,
        avgForceEccentricA = 13.381f,
        estimatedCalories = 12f,
    )

    private fun render(fontScale: Float) {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        SetSummaryCard(
                            summary = summary,
                            workoutMode = "Old School",
                            weightUnit = WeightUnit.LB,
                            kgToDisplay = { kg, unit -> WeightDisplayFormatter.toDisplayWeight(kg, unit) },
                            onContinue = {},
                            autoplayEnabled = false,
                            summaryCountdownSeconds = 0,
                            onRpeLogged = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }


    /** The layout of every rendered text node, keyed by its text. */
    private fun renderedTextLayouts(): List<Pair<String, TextLayoutResult>> {
        val nodes = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        return nodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.firstOrNull() ?: return@mapNotNull null
            layout.layoutInput.text.text to layout
        }
    }

    /** Line starts that fall inside a word: not after whitespace or a break-friendly separator. */
    private fun midWordBreaks(text: String, layout: TextLayoutResult): List<Int> = (1 until layout.lineCount)
        .map { layout.getLineStart(it) }
        .filter { start ->
            start in 1 until text.length &&
                !text[start - 1].isWhitespace() &&
                !text[start].isWhitespace() &&
                text[start - 1] !in BREAK_AFTER
        }

    /** Opens the RPE slider, then checks the whole card: collapsed rows plus the slider. */
    private fun assertTextStaysWhole(fontScale: Float) {
        render(fontScale)
        rule.onNodeWithText("Log RPE").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("RPE 8").assertExists()

        val context = "fontScale $fontScale"
        val layouts = renderedTextLayouts()
        val rendered = layouts.map { it.first }
        val missing = SINGLE_LINE.filterNot { it in rendered }
        assertTrue("$context: expected texts were not inspected: $missing (saw $rendered)", missing.isEmpty())

        val split = layouts
            .filter { (text, layout) -> text in SINGLE_LINE && layout.lineCount > 1 }
            .map { (text, layout) -> "\"$text\" (${layout.lineCount} lines)" }
        assertTrue("$context: these must render on one line (#1345): $split", split.isEmpty())

        val broken = layouts.mapNotNull { (text, layout) ->
            midWordBreaks(text, layout).takeIf { it.isNotEmpty() }
                ?.let { "\"$text\" (${layout.lineCount} lines, mid-word breaks at $it)" }
        }
        assertTrue("$context: text must not break between characters (#1345): $broken", broken.isEmpty())
    }

    @Test
    fun setSummaryText_staysWhole_atDefaultTextSize() = assertTextStaysWhole(fontScale = 1f)

    @Test
    fun setSummaryText_staysWhole_atLargeTextSize() = assertTextStaysWhole(fontScale = 1.3f)

    @Test
    fun setSummaryText_staysWhole_atAccessibilityTextSize() = assertTextStaysWhole(fontScale = 2f)

    private companion object {
        /** Characters a line may legitimately break after inside a run of text. */
        val BREAK_AFTER = setOf('-', '/', '•', '—')

        /**
         * Short tokens that must never wrap at these scales, even at a legal break such as the
         * '/' in the unit: the values and units from the report, and the RPE controls. The force
         * values ("30.0 ↑") may wrap at the space before the arrow, so the mid-word check covers them.
         */
        val SINGLE_LINE = listOf(
            "30.0",
            "(lbs/cable)",
            "0:42",
            "(kCal)",
            "Log RPE",
            "RPE 8",
            "Cancel",
            "Confirm",
        )
    }
}
