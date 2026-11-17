@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.domain.model.BadgeRequirement
import com.devil.phoenixproject.domain.model.BadgeTier
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.calculateWindowSizeClass
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #1403 UIKit fixture — merge audit R2-F1 remediation.
 *
 * EVIDENCE TAXONOMY (each group below claims only what it measures):
 *
 * GROUP 1 — real-Dialog observed geometry (this remediation). Composes the FULL
 * production [BatchedBadgeCelebrationDialog] inside the real iOS Dialog layer and
 * records, via the production [BadgeDialogMeasurementProbe] seam, the OBSERVED
 * measure-time constraints at the dialog content root, the grid and the dismiss
 * button, plus OBSERVED rendered bounds cross-checked against semantics
 * [androidx.compose.ui.test.SemanticsNodeInteractionsProvider] lookups from the
 * actual Dialog subtree. Host geometry (iPad portrait 834x1194, landscape
 * 1194x834, split-narrow 507x834, short 400x400) is driven through REAL layout
 * measurement: the production chain is re-measured under explicit pixel
 * constraints via its real root BoxWithConstraints -> [calculateWindowSizeClass]
 * derivation — the same propagation path a window resize drives — including a
 * live-resize test that mutates geometry without recreating the composition.
 * Density/font-scale propagation is exercised via [LocalDensity], the production
 * channel; safe-drawing insets are read from the platform's [WindowInsets.safeDrawing]
 * and asserted to be subtracted exactly once in the rendered budget.
 *
 * HARNESS LIMIT (measured, 2026-11-17): the cmp-ui-test iOS host scene is fixed
 * at 1024x768pt and the simulator window cannot be resized from the test process
 * (a UIApplication/window-frame probe segfaulted the harness with signal 11).
 * Geometry changes therefore propagate through the real measure path rather than
 * a UIKit window-frame mutation. The harness Dialog layer observed here passes
 * scene-bounded constraints; the wrap-content Constraints.Infinity crash shape is
 * covered by GROUP 2's component tests and the Android sensitivity control
 * (Issue1403BadgeDialogUnboundedWidthRuntimeTest), which prove the missing-bound
 * failure against the exact unbounded shape.
 *
 * GROUP 2 — production-content component tests (retained, relabeled): the real
 * [BatchedBadgeCelebrationDialogContent] under a TEST-OWNED unbounded-width
 * layout. Test-supplied constraint SHAPES, not measured platform constraints.
 *
 * GROUP 3 — full-entry composition smoke (retained, relabeled): the public entry
 * composes without termination at three composition-local window labels. No
 * measurements are claimed.
 */
@OptIn(ExperimentalTestApi::class)
class Issue1403BadgeDialogUIKitFixtureTest {

    private fun badge(id: String, tier: BadgeTier = BadgeTier.GOLD) = Badge(
        id = id,
        name = "Badge $id",
        description = "Description for $id",
        category = BadgeCategory.CONSISTENCY,
        iconResource = "trophy",
        tier = tier,
        requirement = BadgeRequirement.TotalWorkouts(count = 1),
    )

    private fun badges(count: Int): List<Badge> = (1..count).map { badge("b$it") }

    // ── GROUP 1: real Dialog + observed constraints and bounds ──────────────

    /** Records what the production dialog ACTUALLY measured and rendered. */
    private class RecordingProbe : BadgeDialogMeasurementProbe {
        val constraints = linkedMapOf<String, Constraints>()
        val bounds = linkedMapOf<String, List<Float>>()

        override fun onConstraints(point: String, constraints: Constraints) {
            this.constraints[point] = constraints
        }

        override fun onBounds(point: String, left: Float, top: Float, width: Float, height: Float) {
            this.bounds[point] = listOf(left, top, width, height)
        }
    }

    /** One host geometry in real pixels (the stage's measured window size). */
    private data class Geom(val name: String, val widthPx: Int, val heightPx: Int)

    private val portrait = Geom("portrait", 834, 1194)
    private val landscape = Geom("landscape", 1194, 834)
    private val splitNarrow = Geom("split-narrow", 507, 834)
    private val shortWindow = Geom("short-400dp", 400, 400)

    /**
     * Measures its content under explicit host pixel constraints — the geometry
     * change a window resize produces at the root measure pass. Unlike the
     * GROUP 2 shim this wraps the WHOLE production chain (real Dialog included).
     */
    @Composable
    private fun HostGeometryStage(widthPx: Int, heightPx: Int, content: @Composable () -> Unit) {
        Layout(content = content) { measurables, _ ->
            val placeables = measurables.map {
                it.measure(Constraints(minWidth = 0, maxWidth = widthPx, minHeight = 0, maxHeight = heightPx))
            }
            layout(widthPx, heightPx) {
                placeables.forEach { it.placeRelative(0, 0) }
            }
        }
    }

    @Test
    fun fixture_realDialog_observedGeometry_portrait() =
        runComposeUiTest { assertObservedGeometry(listOf(portrait)) }

    @Test
    fun fixture_realDialog_observedGeometry_landscape() =
        runComposeUiTest { assertObservedGeometry(listOf(landscape)) }

    @Test
    fun fixture_realDialog_observedGeometry_splitNarrow() =
        runComposeUiTest { assertObservedGeometry(listOf(splitNarrow)) }

    @Test
    fun fixture_realDialog_liveResize_recomputesBudget() =
        runComposeUiTest { assertObservedGeometry(listOf(portrait, landscape, splitNarrow, shortWindow)) }

    /**
     * Drives one or more host geometries through the SAME composition: after the
     * first step, geometry changes are live measure passes (the state-driven stage
     * re-measures the production chain under new constraints) — a real resize
     * propagation, not a recomposition with swapped labels.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.assertObservedGeometry(geoms: List<Geom>) {
        val recorder = RecordingProbe()
        var insets = listOf(0f, 0f, 0f, 0f)
        var observedDensity: androidx.compose.ui.unit.Density = density
        var geometry by mutableStateOf(geoms.first())
        setContent {
            HostGeometryStage(geometry.widthPx, geometry.heightPx) {
                ProductionDialogUnderTest(recorder, badges(7)) { d, i ->
                    observedDensity = d
                    insets = i
                }
            }
        }
        waitForIdle()
        assertBudgetConsistent("${geoms.first().name}/initial", geoms.first(), recorder, observedDensity, insets, detailsExpanded = false)
        for (geom in geoms.drop(1)) {
            geometry = geom
            waitForIdle()
            assertBudgetConsistent("${geom.name}/live-resize", geom, recorder, observedDensity, insets, detailsExpanded = false)
        }
    }

    @Test
    fun fixture_realDialog_expandedDetails_insideRealDialog() = runComposeUiTest {
        val recorder = RecordingProbe()
        var insets = listOf(0f, 0f, 0f, 0f)
        var observedDensity: androidx.compose.ui.unit.Density = density
        // b1 carries a unique tier label so the grid item can be clicked by text.
        val badgeList = listOf(badge("b1", BadgeTier.SILVER)) + (2..7).map { badge("b$it") }
        setContent {
            HostGeometryStage(landscape.widthPx, landscape.heightPx) {
                ProductionDialogUnderTest(recorder, badgeList) { d, i ->
                    observedDensity = d
                    insets = i
                }
            }
        }
        waitForIdle()
        assertBudgetConsistent("landscape/expanded?no", landscape, recorder, observedDensity, insets, detailsExpanded = false)

        // Expand details through the REAL dialog semantics tree: click b1's grid
        // item via its unique tier label inside the actual Dialog subtree.
        onNodeWithText(BadgeTier.SILVER.displayName).performClick()
        waitForIdle()

        val detailsVisible = try {
            onNodeWithText("Description for b1").fetchSemanticsNode()
            true
        } catch (e: Throwable) {
            false
        }
        assertTrue(detailsVisible, "expanded details must render inside the real Dialog subtree")

        println("ISSUE1403_UIKIT_FIXTURE observed after expand: constraints=${recorder.constraints} bounds=${recorder.bounds}")
        assertBudgetConsistent("landscape/expanded?yes", landscape, recorder, observedDensity, insets, detailsExpanded = true)

        // Density/font-scale propagation through the production LocalDensity channel
        // is asserted by re-running the same geometry at density 2 / fontScale 2 and
        // requiring the rendered card width to match the budget at that density.
        // (Covered by fixture_realDialog_densityPropagation below.)
    }

    @Test
    fun fixture_realDialog_densityPropagation_cardWidthTracksDensity() = runComposeUiTest {
        val recorder = RecordingProbe()
        var insets = listOf(0f, 0f, 0f, 0f)
        var observedDensity: androidx.compose.ui.unit.Density = density
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(density = 2f, fontScale = 2f),
            ) {
                HostGeometryStage(landscape.widthPx, landscape.heightPx) {
                    ProductionDialogUnderTest(recorder, badges(7)) { d, i ->
                        observedDensity = d
                        insets = i
                    }
                }
            }
        }
        waitForIdle()
        println("ISSUE1403_UIKIT_FIXTURE observed at density=2/fontScale=2: constraints=${recorder.constraints} bounds=${recorder.bounds}")
        assertBudgetConsistent("landscape/density2-fontScale2", landscape, recorder, observedDensity, insets, detailsExpanded = false)
    }

    /**
     * The production composition exactly as ActiveWorkoutScreen hosts it:
     * real BoxWithConstraints -> real [calculateWindowSizeClass] derivation ->
     * real [BatchedBadgeCelebrationDialog] inside the real Dialog layer.
     */
    @Composable
    private fun ProductionDialogUnderTest(
        recorder: RecordingProbe,
        badgeList: List<Badge>,
        onObserved: (density: androidx.compose.ui.unit.Density, insets: List<Float>) -> Unit,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
            onObserved(
                density,
                listOf(
                    safeDrawing.calculateLeftPadding(layoutDirection).value,
                    safeDrawing.calculateRightPadding(layoutDirection).value,
                    safeDrawing.calculateTopPadding().value,
                    safeDrawing.calculateBottomPadding().value,
                ),
            )
            // Production metrics path (EnhancedMainScreen: BoxWithConstraints ->
            // calculateWindowSizeClass); observed values, not labels.
            val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)
            CompositionLocalProvider(
                LocalBadgeDialogMeasurementProbe provides recorder,
                LocalWindowSizeClass provides windowSizeClass,
            ) {
                BatchedBadgeCelebrationDialog(
                    badges = badgeList,
                    onDismiss = {},
                    onMarkAllCelebrated = {},
                    onSoundTrigger = {},
                )
            }
        }
    }

    /**
     * Asserts rendered == production budget derived from OBSERVED inputs
     * (observed window metrics, observed insets, observed density), and the
     * #1403 grid-width bound, from measurements taken inside the real Dialog.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.assertBudgetConsistent(
        step: String,
        geom: Geom,
        recorder: RecordingProbe,
        density: androidx.compose.ui.unit.Density,
        insets: List<Float>,
        detailsExpanded: Boolean,
    ) {
        val cardC = recorder.constraints["card"] ?: fail("step=$step: no OBSERVED constraints at the dialog content root")
        val gridC = recorder.constraints["grid"] ?: fail("step=$step: no OBSERVED constraints at the grid")
        val cardB = recorder.bounds["card"] ?: fail("step=$step: no OBSERVED card bounds in the Dialog subtree")
        val gridB = recorder.bounds["grid"] ?: fail("step=$step: no OBSERVED grid bounds in the Dialog subtree")
        val buttonB = recorder.bounds["dismissButton"] ?: fail("step=$step: no OBSERVED dismiss-button bounds")

        println(
            "ISSUE1403_UIKIT_FIXTURE step=$step OBSERVED " +
                "incomingContent=${cardC} grid=${gridC} " +
                "cardBounds=${cardB} gridBounds=${gridB} buttonBounds=${buttonB} insets=$insets",
        )

        // The #1403 crash bound, observed: the grid must be measured under a
        // finite positive width (the failing shape was maxWidth == Infinity).
        assertTrue(
            gridC.maxWidth != Constraints.Infinity && gridC.maxWidth > 0,
            "step=$step: grid must be measured under a finite positive maxWidth, observed ${gridC.maxWidth}",
        )
        assertTrue(
            gridC.maxWidth <= cardC.maxWidth,
            "step=$step: grid maxWidth ${gridC.maxWidth} must not exceed the content root maxWidth ${cardC.maxWidth}",
        )

        // Rendered budget consistency from OBSERVED inputs (insets subtracted once).
        val horizontalInsets = insets[0] + insets[1]
        val verticalInsets = insets[2] + insets[3]
        val windowWidthDp = with(density) { geom.widthPx.toDp() }
        val windowHeightDp = with(density) { geom.heightPx.toDp() }
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = windowWidthDp,
            windowHeight = windowHeightDp,
            horizontalInsets = with(density) { horizontalInsets.toDp() },
            verticalInsets = with(density) { verticalInsets.toDp() },
            badgeCount = 7,
            detailsExpanded = detailsExpanded,
            fontScale = density.fontScale,
        ) ?: fail("step=$step: budget must exist for observed metrics")

        val cardWidthPx = with(density) { budget.cardWidth.toPx() }
        val expectedRenderedCardWidth = minOf(cardWidthPx, cardC.maxWidth.toFloat())
        assertTrue(
            kotlin.math.abs(cardB[2] - expectedRenderedCardWidth) <= 2f,
            "step=$step: rendered card width ${cardB[2]}px must match the budget from observed " +
                "metrics ($expectedRenderedCardWidth px = min(${budget.cardWidth}, content max ${cardC.maxWidth}))",
        )

        // Positive displayed content inside the actual Dialog subtree.
        assertTrue(cardB[2] > 0f && cardB[3] > 0f, "step=$step: card must render with positive size, got $cardB")
        assertTrue(gridB[2] > 0f && gridB[3] > 0f, "step=$step: grid must render with positive size, got $gridB")
        assertTrue(gridB[2] <= cardB[2] + 1f, "step=$step: grid width ${gridB[2]} must fit the card width ${cardB[2]}")

        // Cross-check the probe against semantics lookups from the Dialog subtree.
        val cardSemantics = onNodeWithTag("badge-celebration-card").fetchSemanticsNode().boundsInRoot
        assertTrue(
            kotlin.math.abs(cardSemantics.width - cardB[2]) <= 2f,
            "step=$step: semantics card width ${cardSemantics.width} must agree with the probe's observed ${cardB[2]}",
        )

        // Reachable dismissal inside the visible budget.
        val buttonHeightPx = with(density) { 48.dp.toPx() }
        assertTrue(
            buttonB[3] >= buttonHeightPx - 2f,
            "step=$step: dismiss button height ${buttonB[3]}px must keep its accessible 48dp ($buttonHeightPx px)",
        )
        val cardTop = cardB[1]
        val budgetBottom = cardTop + with(density) { budget.maxContentHeight.toPx() }
        assertTrue(
            buttonB[1] + buttonB[3] <= budgetBottom + 2f,
            "step=$step: dismiss button bottom ${buttonB[1] + buttonB[3]}px must stay within the " +
                "${budget.maxContentHeight} content budget (bottom $budgetBottom px)",
        )
    }

    // ── GROUP 3: full-entry composition smoke (composition-local labels) ────

    @Test
    fun fixture_portrait_fullDialog_composesWithoutTermination() =
        assertFullDialogComposes("portrait", widthDp = 834.dp, heightDp = 1194.dp)

    @Test
    fun fixture_landscape_fullDialog_composesWithoutTermination() =
        assertFullDialogComposes("landscape", widthDp = 1194.dp, heightDp = 834.dp)

    @Test
    fun fixture_splitNarrow_fullDialog_composesWithoutTermination() =
        assertFullDialogComposes("split-narrow", widthDp = 507.dp, heightDp = 834.dp)

    private fun assertFullDialogComposes(name: String, widthDp: androidx.compose.ui.unit.Dp, heightDp: androidx.compose.ui.unit.Dp) =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides WindowSizeClass(
                        widthSizeClass = WindowWidthSizeClass.Expanded,
                        heightSizeClass = WindowHeightSizeClass.Expanded,
                        widthDp = widthDp,
                        heightDp = heightDp,
                    ),
                ) {
                    BatchedBadgeCelebrationDialog(
                        badges = badges(4),
                        onDismiss = {},
                        onMarkAllCelebrated = {},
                        onSoundTrigger = {},
                    )
                }
            }
            waitForIdle()
            // Composition-local label only — GROUP 3 claims no measurements.
            println(
                "ISSUE1403_UIKIT_FIXTURE window=$name entry=BatchedBadgeCelebrationDialog " +
                    "(composition-local label; no constraints measured in this group) " +
                    "result=COMPOSED_WITHOUT_TERMINATION",
            )
        }

    // ── GROUP 2: production content under a test-supplied constraint shape ──

    @Test
    fun fixture_portrait_expandedDetails_recordsConstraintsAndBounds() =
        assertContentBounds("portrait", windowWidth = 834.dp, windowHeight = 1194.dp)

    @Test
    fun fixture_landscape_expandedDetails_recordsConstraintsAndBounds() =
        assertContentBounds("landscape", windowWidth = 1194.dp, windowHeight = 834.dp)

    @Test
    fun fixture_splitNarrow_expandedDetails_recordsConstraintsAndBounds() =
        assertContentBounds("split-narrow", windowWidth = 507.dp, windowHeight = 834.dp)

    @Test
    fun fixture_shortWindow400dp_expandedDetails_recordsConstraintsAndBounds() =
        assertContentBounds("short-400dp", windowWidth = 400.dp, windowHeight = 400.dp)

    private fun assertContentBounds(
        name: String,
        windowWidth: androidx.compose.ui.unit.Dp,
        windowHeight: androidx.compose.ui.unit.Dp,
    ) = runComposeUiTest {
        var density = 1f
        var budget: BadgeCelebrationLayoutBudget? = null
        setContent {
            density = LocalDensity.current.density
            val b = badgeCelebrationLayoutBudget(
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                horizontalInsets = 0.dp,
                verticalInsets = 0.dp,
                badgeCount = 7,
                detailsExpanded = true,
                fontScale = LocalDensity.current.fontScale,
            )!!
            budget = b
            UnboundedDialogWidthLayout(maxHeightPx = with(LocalDensity.current) { windowHeight.toPx().toInt() }) {
                BatchedBadgeCelebrationDialogContent(
                    badges = badges(7),
                    budget = b,
                    selectedBadgeId = "b1",
                    onBadgeClick = {},
                    onMarkAllCelebrated = {},
                    onDismiss = {},
                    scale = 1f,
                    alpha = 1f,
                )
            }
        }
        waitForIdle()
        val b = budget ?: fail("fixture budget was not computed")

        // Component-level evidence: the constraint SHAPE below is TEST-SUPPLIED
        // (the unbounded-width crash shape), not measured platform constraints.
        println(
            "ISSUE1403_UIKIT_FIXTURE window=$name testShimImposedConstraints=" +
                "minWidth=0,maxWidth=TEST_UNBOUNDED,minHeight=0,maxHeight=${windowHeight} " +
                "(component test; platform constraints are GROUP 1's claim) " +
                "budget=${b.maxContentHeight}/grid=${b.maxGridHeight}/details=${b.maxDetailsHeight}",
        )

        val card = onNodeWithTag("badge-celebration-card").fetchSemanticsNode().boundsInRoot
        val grid = onNodeWithTag("badge-celebration-grid").fetchSemanticsNode().boundsInRoot
        val button = onNodeWithText("Awesome!").fetchSemanticsNode().boundsInRoot

        fun dp(px: Float) = px / density
        println(
            "ISSUE1403_UIKIT_FIXTURE window=$name cardBoundsDp=" +
                "x=${dp(card.left)},y=${dp(card.top)},w=${dp(card.width)},h=${dp(card.height)} " +
                "gridBoundsDp=w=${dp(grid.width)},h=${dp(grid.height)} " +
                "buttonBoundsDp=y=${dp(button.top)},h=${dp(button.height)},bottom=${dp(button.bottom)}",
        )

        // Rendered-bounds acceptance.
        assertTrue(
            dp(grid.width).isFinite() && dp(grid.width) > 0f,
            "window=$name: grid width must be finite and positive, got ${dp(grid.width)}dp",
        )
        assertTrue(
            dp(grid.width) <= dp(card.width),
            "window=$name: grid width must be bounded by the Card width",
        )
        assertTrue(
            dp(card.height) <= b.maxContentHeight.value + 1f,
            "window=$name: rendered card height ${dp(card.height)}dp exceeds " +
                "the ${b.maxContentHeight} content budget",
        )
        assertTrue(
            dp(button.height) >= 47.5f,
            "window=$name: dismiss button must keep its accessible 48dp height, " +
                "got ${dp(button.height)}dp",
        )
        assertTrue(
            dp(button.bottom) <= dp(card.top) + b.maxContentHeight.value + 1f,
            "window=$name: dismiss button bottom ${dp(button.bottom)}dp is outside " +
                "the ${b.maxContentHeight} budget — dismissal is not reachable",
        )
        assertTrue(
            dp(grid.height) <= b.maxGridHeight.value + 1f,
            "window=$name: grid height ${dp(grid.height)}dp exceeds its ${b.maxGridHeight} share",
        )
    }
}

/**
 * Measures its content with unbounded width constraints — the same constraint
 * shape the iOS wrap-content dialog layer produced for the #1403 crash. A
 * test-side constraints shim (GROUP 2), not a copy of production content and not
 * a measurement of the platform dialog layer.
 */
@Composable
private fun UnboundedDialogWidthLayout(maxHeightPx: Int, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = 0,
                    maxHeight = maxHeightPx,
                ),
            )
        }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}
