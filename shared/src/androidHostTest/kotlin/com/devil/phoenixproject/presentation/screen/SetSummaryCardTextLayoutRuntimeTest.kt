package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.util.ContentAwareFit
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.ui.theme.PhoenixTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1345/#1385: on an iPhone the set summary rendered labels one character per
 * line (`(lbs/cable)`, an eccentric force value, and the whole expanded RPE slider
 * squeezed into a strip beside "How hard was that?"). Each was a Row child measured
 * after its siblings had taken the width, so it was left a sliver and the text broke
 * between every character.
 *
 * #1385 merge-gate rework: the harness renders the real [SetSummaryCard] through the
 * production WorkoutTab ancestor chain (scrolling Column with 20dp horizontal padding
 * and navigation-bar insets, summary wrapper Box with system-bar insets and 16dp/8dp
 * padding) in the real [PhoenixTheme], at 402dp and narrower supported widths across
 * host font scales 1/1.3/2/2.25/2.5/3. A genuine TUT fixture mirrors the #1345 metrics
 * screenshot (0:23, 1 kCal, 30.0 lb configured/peak, 28.7/29.5 lb average forces,
 * 599.7 lb total volume) with unit/arrow semantics asserted. Beyond line boundaries
 * the checks cover glyph/parent bounds, overlap and touch reachability, exact-once RPE
 * confirmation with the selected value, cancel-without-save at every scale, collapsed
 * states, read-only History, and content- and locale-aware layout decisions. Fonts are
 * Robolectric's, not iOS's, so this pins the layout contract rather than exact iOS
 * metrics; native iPhone full-screen verification remains a separate delivery gate.
 */
@RunWith(RobolectricTestRunner::class)
// iPhone 16 Pro (iPhone17,1): 402 x 874 pt at 3x. The matrix pins narrower widths
// via requiredSize on the production host wrapper.
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

    /**
     * Genuine TUT fixture from the #1345 metrics screenshot (#1385 acceptance):
     * 30.0 lb configured/peak, 28.7/29.5 lb average forces, 0:23 TUT, 1 kCal and a
     * 599.7 lb total volume. Unit and arrow semantics are asserted on this fixture.
     */
    private val tutSummary = WorkoutState.SetSummary(
        metrics = emptyList(),
        peakLoadKgPerCable = 13.6078f,
        avgLoadKgPerCable = 13.381f,
        repCount = 10,
        durationMs = 23_000L,
        totalVolumeKg = 272f,
        configuredWeightKgPerCable = 13.6078f,
        heaviestLiftKgPerCable = 13.6078f,
        peakForceConcentricA = 13.6078f,
        peakForceEccentricA = 13.6078f,
        avgForceConcentricA = 13.0181f,
        avgForceEccentricA = 13.381f,
        estimatedCalories = 1f,
    )

    // ===== Production host =====

    /** Pixels per dp for the rendered host; bounds from SemanticsNode are pixel Rects. */
    private var pxPerDp = 3f

    private class HostArgs(val workoutMode: String, val onRpeLogged: ((Int) -> Unit)?)

    /**
     * The production ancestor chain for the live set summary (WorkoutTab): a scrolling
     * Column with 20dp horizontal padding and navigation-bar insets, then the summary
     * wrapper Box with system-bar insets and 16dp/8dp padding. The width is pinned to
     * [widthDp] so the matrix covers supported widths regardless of the Robolectric
     * window size.
     */
    private fun setContentFor(
        widthDp: State<Float>,
        fontScale: State<Float>,
        workoutMode: String,
        onRpeLogged: ((Int) -> Unit)? = {},
        content: @Composable (HostArgs) -> Unit,
    ) {
        rule.setContent {
            val density = LocalDensity.current.density
            pxPerDp = density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                Box(
                    Modifier
                        .requiredSize(width = widthDp.value.dp, height = 874.dp)
                        .testTag("production-host"),
                ) {
                    PhoenixTheme(themeMode = ThemeMode.LIGHT) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 8.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .systemBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                content(HostArgs(workoutMode, onRpeLogged))
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** [SetSummaryCard] wired like WorkoutTab's live call (no auto-continue, RPE enabled). */
    @Composable
    private fun SetSummaryCardFor(
        args: HostArgs,
        summary: WorkoutState.SetSummary,
        isHistoryView: Boolean = false,
        savedRpe: Int? = null,
    ) {
        SetSummaryCard(
            summary = summary,
            workoutMode = args.workoutMode,
            weightUnit = WeightUnit.LB,
            kgToDisplay = { kg, unit -> WeightDisplayFormatter.toDisplayWeight(kg, unit) },
            onContinue = {},
            autoplayEnabled = false,
            summaryCountdownSeconds = 0,
            onRpeLogged = args.onRpeLogged,
            isHistoryView = isHistoryView,
            savedRpe = savedRpe,
        )
    }

    // ===== Text inspection =====

    /** The layout of every rendered text node, keyed by its text. */
    private fun renderedTextLayouts(): List<Pair<String, TextLayoutResult>> {
        val nodes = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        return nodes.mapNotNull { node -> layoutOf(node)?.let { it.layoutInput.text.text to it } }
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
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

    /**
     * [required] texts must render at all; [singleLine] texts must also stay on one
     * line; nothing anywhere may break mid-word.
     */
    private fun assertWhole(context: String, singleLine: List<String>, required: List<String> = emptyList()) {
        val layouts = renderedTextLayouts()
        val rendered = layouts.map { it.first }
        val missing = (singleLine + required).filterNot { it in rendered }
        assertTrue("$context: expected texts were not inspected: $missing (saw $rendered)", missing.isEmpty())

        val split = layouts
            .filter { (text, layout) -> text in singleLine && layout.lineCount > 1 }
            .map { (text, layout) -> "\"$text\" (${layout.lineCount} lines)" }
        assertTrue("$context: these must render on one line (#1345): $split", split.isEmpty())

        val broken = layouts.mapNotNull { (text, layout) ->
            midWordBreaks(text, layout).takeIf { it.isNotEmpty() }
                ?.let { "\"$text\" (${layout.lineCount} lines, mid-word breaks at $it)" }
        }
        assertTrue("$context: text must not break between characters (#1345): $broken", broken.isEmpty())
    }

    /**
     * Placed bounds in px. `SemanticsNode.boundsInRoot` is clipped to the scroll
     * viewport, so nodes below the fold report zeroed/trimmed rects even though they
     * are laid out correctly — position + size is the true placed box. A zero size is
     * a genuinely unplaced node and stays a failure.
     */
    private fun placedBounds(node: SemanticsNode): Rect {
        val p = node.positionInRoot
        val s = node.size
        return Rect(p.x, p.y, p.x + s.width, p.y + s.height)
    }

    /**
     * Glyph/parent bounds and overlap: every rendered text keeps nonzero bounds, stays
     * inside its parent semantics node (no clipping), and never overlaps another text.
     */
    private fun assertGlyphsFitParentsAndDoNotOverlap(context: String) {
        val nodes = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val entries = nodes.mapNotNull { node ->
            layoutOf(node)?.let { Triple(node, it.layoutInput.text.text, placedBounds(node)) }
        }
        val tol = pxPerDp
        val empty = entries.filter { it.second.isNotBlank() && (it.third.width <= 0f || it.third.height <= 0f) }
        if (empty.isNotEmpty()) {
            val detail = empty.joinToString("; ") { (node, text, _) ->
                val p = node.parent
                "\"$text\" parent=${p?.let { layoutOf(it)?.layoutInput?.text?.text ?: it.id }} " +
                    "parentBounds=${p?.let { placedBounds(it) }} siblings=${p?.children?.size}"
            }
            throw AssertionError("$context: ${empty.size} unplaced texts: $detail")
        }
        for ((node, text, bounds) in entries) {
            val parent = node.parent ?: continue
            val p = placedBounds(parent)
            val fits = bounds.left >= p.left - tol && bounds.right <= p.right + tol &&
                bounds.top >= p.top - tol && bounds.bottom <= p.bottom + tol
            assertTrue(
                "$context: \"$text\" bounds $bounds spill outside its parent bounds $p (glyph clipping)",
                fits,
            )
        }
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val a = entries[i].third
                val b = entries[j].third
                val overlaps = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertTrue(
                    "$context: \"${entries[i].second}\" $a overlaps \"${entries[j].second}\" $b",
                    !overlaps,
                )
            }
        }
    }

    /** Touch reachability: every clickable keeps a sane tap size and covers its own label. */
    private fun assertTouchTargetsReachable(context: String) {
        val clickables = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val tol = pxPerDp
        val minTapPx = 24f * pxPerDp
        for (node in clickables) {
            val b = placedBounds(node)
            assertTrue("$context: clickable bounds $b smaller than 24dp", b.width >= minTapPx && b.height >= minTapPx)
            for (textNode in descendantsWithText(node)) {
                val t = placedBounds(textNode)
                val covered = t.left >= b.left - tol && t.right <= b.right + tol &&
                    t.top >= b.top - tol && t.bottom <= b.bottom + tol
                assertTrue(
                    "$context: clickable $b does not cover its label \"${layoutOf(textNode)?.layoutInput?.text?.text}\" at $t",
                    covered,
                )
            }
        }
    }

    private fun descendantsWithText(node: SemanticsNode): List<SemanticsNode> =
        if (layoutOf(node) != null) {
            listOf(node)
        } else {
            node.children.flatMap { descendantsWithText(it) }
        }

    private fun textBounds(text: String): Rect {
        val node = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().firstOrNull { layoutOf(it)?.layoutInput?.text?.text == text }
            ?: throw AssertionError("text \"$text\" not found")
        return placedBounds(node)
    }

    /** Two texts share a horizontal band and sit side by side. */
    private fun assertSideBySide(context: String, a: String, b: String) {
        val ba = textBounds(a)
        val bb = textBounds(b)
        val tol = pxPerDp
        val sameBand = ba.top <= bb.bottom && bb.top <= ba.bottom
        assertTrue("$context: \"$a\" $ba and \"$b\" $bb should share a row", sameBand)
        assertTrue(
            "$context: \"$a\" $ba and \"$b\" $bb should not overlap",
            ba.right <= bb.left + tol || bb.right <= ba.left + tol,
        )
    }

    /** One text ends before the other starts: vertically stacked. */
    private fun assertStacked(context: String, a: String, b: String) {
        val ba = textBounds(a)
        val bb = textBounds(b)
        val tol = pxPerDp
        val stacked = ba.bottom <= bb.top + tol || bb.bottom <= ba.top + tol
        assertTrue("$context: \"$a\" $ba and \"$b\" $bb should stack, not share a row", stacked)
    }

    /** An RPE choice chip: its value text lives inside a clickable ancestor. */
    private fun rpeChoice(rpe: Int) = rule.onNode(
        SemanticsMatcher("RPE choice '$rpe'") { node ->
            if (layoutOf(node)?.layoutInput?.text?.text != "$rpe") {
                false
            } else {
                var n: SemanticsNode? = node
                var found = false
                while (n != null && !found) {
                    found = n.config.contains(SemanticsActions.OnClick)
                    n = n.parent
                }
                found
            }
        },
        useUnmergedTree = true,
    )

    // ===== Production matrix =====

    /**
     * #1385 matrix: genuine TUT fixture at 402dp and narrower supported widths, host
     * scales 1/1.3/2/2.25/2.5/3. No mid-word breaks anywhere; TUT values, units and
     * arrow semantics stay intact; glyphs fit parents without overlap and touch
     * targets stay reachable.
     */
    @Test
    fun productionMatrix_tutFixture_textStaysWhole_allWidthsAllScales() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, tutSummary) }
        for (width in SUPPORTED_WIDTHS) {
            widthDp.floatValue = width
            for (s in SCALES) {
                scale.floatValue = s
                rule.waitForIdle()
                val context = "TUT width ${width}dp fontScale $s"
                assertWhole(context, TUT_SINGLE_LINE, TUT_REQUIRED)
                assertGlyphsFitParentsAndDoNotOverlap(context)
                assertTouchTargetsReachable(context)
            }
        }
    }

    /**
     * #1345 report fixture at the production host, all scales: the original unit, value
     * and RPE control tokens must render on one line with the slider expanded.
     */
    @Test
    fun productionMatrix_reportFixture_textStaysWhole_allScales() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, summary) }
        for (s in SCALES) {
            scale.floatValue = s
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()) {
                rule.onNodeWithText("Log RPE").performScrollTo().performClick()
                rule.waitForIdle()
            }
            val context = "report fixture fontScale $s"
            assertWhole(context, REPORT_SINGLE_LINE)
            assertGlyphsFitParentsAndDoNotOverlap(context)
        }
    }

    /**
     * #1385: the total-volume header token must stay whole at every scale, independent
     * of the responsive metric grid — the header is outside the grid, so its token must
     * hold whether or not the grid stacks.
     */
    @Test
    fun totalVolumeHeader_599_7_staysWhole_independentlyOfGrid_allScales() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, tutSummary) }
        for (s in SCALES) {
            scale.floatValue = s
            rule.waitForIdle()
            val context = "header fontScale $s"
            assertWhole(context, listOf("599.7", "10", "Total reps"))
            // The header sits above the metric grid: the token is the header's own.
            assertTrue(
                "$context: 599.7 should render in the header above the grid",
                textBounds("599.7").top < textBounds("Mode").top,
            )
        }
    }

    /**
     * #1385: Echo mode — peak text, phase labels and phase values stay whole at all
     * scales through the production host.
     */
    @Test
    fun echoPhaseText_staysWhole_allScales() {
        val echoSummary = summary.copy(
            isEchoMode = true,
            warmupAvgWeightKg = 9.07f,
            workingAvgWeightKg = 13.6f,
            burnoutAvgWeightKg = 6.8f,
            warmupReps = 3,
            workingReps = 5,
            burnoutReps = 2,
            peakWeightKg = 13.6f,
            measuredWorkingPeakKgPerCable = 13.6f,
        )
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, echoSummary) }
        for (s in SCALES) {
            scale.floatValue = s
            rule.waitForIdle()
            val context = "Echo fontScale $s"
            assertWhole(context, listOf(), listOf("Peak: 30.0 lbs/cable", "Warmup", "Working", "Burnout"))
            assertGlyphsFitParentsAndDoNotOverlap(context)
        }
    }

    // ===== Content-aware grid =====

    /**
     * #1385: identical viewport and font scale, longer content — the Mode/Set Weight
     * row must stack because the long value cannot fit a half-width cell. Together with
     * [normalContent_gridKeepsTwoColumns_atDefaultScale] this proves the layout decision
     * is driven by measured content, not by a width/fontScale ratio.
     */
    @Test
    fun longContent_gridStacks_withoutMidWordBreaks() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Extraordinarily Long Program Name") { SetSummaryCardFor(it, tutSummary) }
        rule.waitForIdle()
        val context = "long content fontScale 1"
        assertWhole(context, TUT_SINGLE_LINE, TUT_REQUIRED)
        assertGlyphsFitParentsAndDoNotOverlap(context)
        assertStacked("long content stacks", "Mode", "Set Weight")
    }

    /** Positive control for [longContent_gridStacks_withoutMidWordBreaks]. */
    @Test
    fun normalContent_gridKeepsTwoColumns_atDefaultScale() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, tutSummary) }
        rule.waitForIdle()
        assertSideBySide("normal content keeps two columns", "Mode", "Set Weight")
    }

    // ===== RPE interaction =====

    /**
     * #1385: every choice 6-10 is selectable at every scale, "10" stays intact, the
     * selection is reflected in the header, and one Confirm click fires exactly one
     * callback with the selected value — then the slider collapses.
     */
    @Test
    fun rpeInteraction_allChoices_exactlyOneConfirm_allScales() {
        var confirmCount = 0
        var lastRpe = -1
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School", onRpeLogged = { confirmCount++; lastRpe = it }) {
            SetSummaryCardFor(it, tutSummary)
        }
        for (s in SCALES) {
            scale.floatValue = s
            rule.waitForIdle()
            val opener = if (confirmCount == 0) "Log RPE" else "RPE 10"
            rule.onAllNodesWithText(opener).onFirst().performScrollTo().performClick()
            rule.waitForIdle()
            for (rpe in 6..10) {
                rpeChoice(rpe).performScrollTo().performClick()
                rule.waitForIdle()
                val reflected = rule.onAllNodesWithText("RPE $rpe").fetchSemanticsNodes().isNotEmpty()
                assertTrue("fontScale $s: selecting $rpe must be reflected in the header", reflected)
            }
            // The double-digit choice must stay intact, not split into "1" and "0".
            val tenLayout = layoutOf(rpeChoice(10).fetchSemanticsNode())
            assertEquals("fontScale $s: the '10' choice must render on one line", 1, tenLayout?.lineCount)
            // Description and actions reachable.
            rule.onNodeWithText("Log your perceived exertion").assertExists()
            rule.onNodeWithText("Cancel").assertExists()
            rule.onNodeWithText("Confirm").assertExists()
            val before = confirmCount
            rule.onNodeWithText("Confirm").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(2000)
            rule.waitForIdle()
            assertEquals("fontScale $s: Confirm must fire exactly one callback", before + 1, confirmCount)
            assertEquals("fontScale $s: the confirmed value must be the selection", 10, lastRpe)
            // Collapsed after confirm: actions gone, logged RPE shown.
            rule.onNodeWithText("Confirm").assertDoesNotExist()
            rule.onNodeWithText("Cancel").assertDoesNotExist()
            assertTrue(rule.onAllNodesWithText("RPE 10").fetchSemanticsNodes().isNotEmpty())
        }
        assertEquals("one confirmation per scale", SCALES.size, confirmCount)
    }

    /** #1385: cancel must never save, at any scale. */
    @Test
    fun rpeInteraction_cancelNeverSaves_allScales() {
        var confirmCount = 0
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School", onRpeLogged = { confirmCount++ }) {
            SetSummaryCardFor(it, tutSummary)
        }
        for (s in SCALES) {
            scale.floatValue = s
            rule.waitForIdle()
            rule.onNodeWithText("Log RPE").performScrollTo().performClick()
            rule.waitForIdle()
            rpeChoice(9).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Cancel").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals("fontScale $s: cancel must not trigger save", 0, confirmCount)
            rule.onNodeWithText("Confirm").assertDoesNotExist()
            rule.onNodeWithText("Log RPE").assertExists()
        }
    }

    /** #1385: collapsed state shows only the launcher, no slider actions. */
    @Test
    fun rpeInteraction_collapsedInitially() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, tutSummary) }
        rule.waitForIdle()
        rule.onNodeWithText("Log RPE").assertExists()
        rule.onNodeWithText("Confirm").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    /** #1385: read-only History shows the saved value with no interactive controls. */
    @Test
    fun rpeInteraction_readOnlyHistory_hasNoControls() {
        val widthDp = mutableFloatStateOf(402f)
        val scale = mutableFloatStateOf(1f)
        setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, tutSummary, isHistoryView = true, savedRpe = 8) }
        for (s in listOf(1f, 3f)) {
            scale.floatValue = s
            rule.waitForIdle()
            val context = "read-only history fontScale $s"
            assertWhole(context, listOf("8/10", "RPE"))
            rule.onNodeWithText("Log RPE").assertDoesNotExist()
            rule.onNodeWithText("Confirm").assertDoesNotExist()
            rule.onNodeWithText("Done").assertDoesNotExist()
            assertGlyphsFitParentsAndDoNotOverlap(context)
        }
    }

    /**
     * #1385: localized action labels are measured, not guessed. German
     * "Abbrechen"/"Bestätigen" are wider than English "Cancel"/"Confirm": at this
     * viewport and scale they must stack instead of breaking, and stay intact.
     */
    @Test
    @Config(qualifiers = "de-w402dp-h874dp-xxhdpi")
    fun localizedRpeActions_german_stackInsteadOfBreaking() {
        Locale.setDefault(Locale.GERMAN)
        try {
            val widthDp = mutableFloatStateOf(402f)
            val scale = mutableFloatStateOf(1.3f)
            setContentFor(widthDp, scale, "Old School") { SetSummaryCardFor(it, tutSummary) }
            rule.waitForIdle()
            rule.onNodeWithText("Log RPE").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Abbrechen").assertExists()
            rule.onNodeWithText("Bestätigen").assertExists()
            assertWhole("German actions fontScale 1.3", listOf("Abbrechen", "Bestätigen"))
            assertStacked("German actions must stack", "Abbrechen", "Bestätigen")
        } finally {
            Locale.setDefault(Locale.ENGLISH)
        }
    }

    /**
     * #1385: deliberately squeezed failing detector control — a text node forced into
     * a narrow box must register mid-word breaks, proving the detector works.
     */
    @Test
    fun detectorControl_catchesDeliberateSqueezing() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = 350.dp)) {
                    // Very narrow box forces the text to wrap character-by-character
                    Box(Modifier.fillMaxWidth().padding(start = 195.dp)) {
                        Text(
                            text = "SqueezedText",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val layouts = renderedTextLayouts()
        val squeezed = layouts.firstOrNull { it.first == "SqueezedText" }
        assertTrue("Detector control: squeezed text should be found", squeezed != null)
        val breaks = squeezed?.let { midWordBreaks(it.first, it.second) }
        assertTrue(
            "Detector control: squeezed text must register mid-word breaks (got $breaks)",
            breaks != null && breaks.isNotEmpty(),
        )
    }

    // ===== ContentAwareFit seam =====

    @Test
    fun contentAwareFit_longestUnbreakableRun_splitsOnWhitespaceAndBreakChars() {
        assertEquals("599.7", ContentAwareFit.longestUnbreakableRun("599.7"))
        assertEquals("cable)", ContentAwareFit.longestUnbreakableRun("(lbs/cable)"))
        assertEquals("School", ContentAwareFit.longestUnbreakableRun("Old School"))
        assertEquals("Bestätigen", ContentAwareFit.longestUnbreakableRun("Bestätigen"))
    }

    @Test
    fun contentAwareFit_equalColumnsFit_usesEqualWeightHalves() {
        // Two 100dp cells in 208dp with 8dp spacing: each half is 100dp — fits exactly.
        assertTrue(ContentAwareFit.equalColumnsFit(100f, 100f, 8f, 208f))
        assertTrue(!ContentAwareFit.equalColumnsFit(100f, 100f, 8f, 207f))
        // Sum-fits but unequal halves: 120+8+60 = 188 fits, yet each equal half is only
        // 90dp and the 120dp cell would break — must stack.
        assertTrue(!ContentAwareFit.equalColumnsFit(120f, 60f, 8f, 188f))
    }

    @Test
    fun contentAwareFit_singleLineFitFontScale_shrinksOnlyWhenNeeded() {
        val measured = mutableMapOf<String, Float>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 3f)) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val style = MaterialTheme.typography.displaySmall
                measured["fits"] = ContentAwareFit.singleLineFitFontScale("599.7", style, 600f, measurer, density)
                measured["overflows"] = ContentAwareFit.singleLineFitFontScale("599.7", style, 200f, measurer, density)
            }
        }
        rule.waitForIdle()
        assertEquals(1f, measured.getValue("fits"), 0.001f)
        val shrink = measured.getValue("overflows")
        assertTrue("overflowing token must shrink ($shrink)", shrink < 1f && shrink >= 0.25f)
    }

    /**
     * #1385: real measured localized action widths drive the decision. At fontScale
     * 1.3 the English pair fits an equal half in 274dp while the wider Dutch pair does
     * not — same viewport, same code path, only the content differs.
     */
    @Test
    fun contentAwareFit_measuredLocalizedActionWidths_flipTheDecision() {
        val measured = mutableMapOf<String, Float>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1.3f)) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val style = MaterialTheme.typography.labelLarge
                for (text in listOf("Cancel", "Confirm", "Annuleren", "Bevestigen")) {
                    measured[text] =
                        ContentAwareFit.requiredTextWidthDp(text, style, singleLine = true, measurer, density).value + 2f * 24f
                }
            }
        }
        rule.waitForIdle()
        val englishFits = ContentAwareFit.equalColumnsFit(measured.getValue("Cancel"), measured.getValue("Confirm"), 8f, 274f)
        val dutchFits = ContentAwareFit.equalColumnsFit(measured.getValue("Annuleren"), measured.getValue("Bevestigen"), 8f, 274f)
        assertTrue("English actions must fit side by side at fontScale 1.3 (widths $measured)", englishFits)
        assertTrue("Dutch actions must stack at fontScale 1.3 (widths $measured)", !dutchFits)
    }

    private companion object {
        /** Characters a line may legitimately break after inside a run of text. */
        val BREAK_AFTER = setOf('-', '/', '•', '—')

        /** Supported phone widths in dp: 402 (iPhone 16 Pro) down to a 360dp stress width. */
        val SUPPORTED_WIDTHS = listOf(360f, 375f, 390f, 402f)

        /** Host font scales: default through maximum accessibility. */
        val SCALES = listOf(1f, 1.3f, 2f, 2.25f, 2.5f, 3f)

        /**
         * TUT fixture tokens that must stay on one line: the reported values, units,
         * duration, energy, the independent header token and the RPE launcher.
         */
        val TUT_SINGLE_LINE = listOf(
            "30.0",
            "(lbs/cable)",
            "0:23",
            "(kCal)",
            "1",
            "599.7",
            "Log RPE",
        )

        /**
         * Unit/arrow semantics from the TUT screenshot. Force values carry their arrows
         * ("28.7 ↑") and may wrap at the space before the arrow, so presence is asserted
         * here while the mid-word check covers their integrity.
         */
        val TUT_REQUIRED = listOf(
            "30.0 ↑",
            "30.0 ↓",
            "28.7 ↑",
            "29.5 ↓",
            "Total volume (lbs)",
        )

        /** Short tokens from the #1345 report that must never wrap at these scales. */
        val REPORT_SINGLE_LINE = listOf(
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
