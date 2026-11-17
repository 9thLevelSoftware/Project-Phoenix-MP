package com.devil.phoenixproject.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.domain.model.BadgeRequirement
import com.devil.phoenixproject.domain.model.BadgeTier
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import kotlin.math.abs
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1403 runtime regression harness.
 *
 * The TestFlight crash: the badge dialog's LazyVerticalGrid was measured with
 * maxWidth == Constraints.Infinity (iOS wrap-content dialog layer with
 * usePlatformDefaultWidth = false), and CMP 1.12.2 rememberColumnWidthSums threw
 * "LazyVerticalGrid's width should be bound by parent." (EXC_CRASH SIGABRT).
 *
 * Coverage (merge audit remediation items 1, 3 and 4) — COMPONENT-LEVEL dialog
 * coverage only, not screen runtime coverage: it exercises the badge dialog
 * composable and its layout-budget seam, not ActiveWorkoutScreen's queue
 * state/gating or the PR-to-badge handoff (covered by
 * [Issue1403BadgeQueueScreenConsumerTest]):
 * - [unboundedWidth_productionContentRendersWithoutTermination] composes the ACTUAL
 *   production dialog content under the same unbounded outer width.
 * - [unboundedWidth_missingBoundStillFailsAsExpected] is the harness-sensitivity
 *   control; [productionWidthBoundRemoved_stillFailsAsExpected] is the
 *   production-bound-removal control (the exact remediation mutation).
 * - [unboundedWidth_productionGridMaxWidthIsRecordedFinite] records/asserts the
 *   finite grid max width the production bound enforces.
 * - Short-window rendered-bounds cases (400dp and 320dp, font scales 1.0/1.5/2.0,
 *   expanded details) assert actual rendered card and dismiss-button bounds.
 * - Counts 1/3/4/6/7+, selection, larger-grid scrolling, dismissal with exact
 *   celebration IDs, and invalid-metrics-to-valid queue retention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Issue1403BadgeDialogUnboundedWidthRuntimeTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private fun badge(id: String) = Badge(
        id = id,
        name = "Badge $id",
        description = "Description for $id",
        category = BadgeCategory.CONSISTENCY,
        iconResource = "trophy",
        tier = BadgeTier.GOLD,
        requirement = BadgeRequirement.TotalWorkouts(count = 1),
    )

    private fun badges(count: Int): List<Badge> = (1..count).map { badge("b$it") }

    private fun budget(
        windowWidth: Dp,
        windowHeight: Dp,
        count: Int,
        expanded: Boolean,
        fontScale: Float = 1f,
    ): BadgeCelebrationLayoutBudget = badgeCelebrationLayoutBudget(
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        horizontalInsets = 0.dp,
        verticalInsets = 0.dp,
        badgeCount = count,
        detailsExpanded = expanded,
        fontScale = fontScale,
    )!!

    private fun assertDp(expected: Dp, actual: Dp, tolerance: Dp = 1.dp, message: String = "") {
        check(abs(actual.value - expected.value) <= tolerance.value) { "$message: expected $expected, got $actual" }
    }

    private fun assertTrue(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    private fun dismissButton() = rule.onNode(hasText("Awesome!") and hasClickAction())

    // DpRect exposes only left/top/right/bottom in this Compose version.
    private val DpRect.w: Dp get() = right - left
    private val DpRect.h: Dp get() = bottom - top

    // ── Unbounded-width crash regression (item 4) ──────────────────────────

    @Test
    fun unboundedWidth_productionContentRendersWithoutTermination() {
        val budget = budget(windowWidth = 400.dp, windowHeight = 800.dp, count = 3, expanded = false)
        rule.setContent {
            UnboundedWidthLayout {
                BatchedBadgeCelebrationDialogContent(
                    badges = badges(3),
                    budget = budget,
                    selectedBadgeId = null,
                    onBadgeClick = {},
                    onMarkAllCelebrated = {},
                    onDismiss = {},
                    scale = 1f,
                    alpha = 1f,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("3 Badges Earned!").assertIsDisplayed()
        rule.onNodeWithContentDescription("Badge b1, Gold tier", substring = true).assertIsDisplayed()
    }

    @Test
    fun unboundedWidth_missingBoundStillFailsAsExpected() {
        var thrown: Throwable? = null
        try {
            rule.setContent {
                UnboundedWidthLayout {
                    // The missing-bound implementation: same grid shape as production
                    // before the remediation — fillMaxWidth under infinite constraints
                    // cannot bound the grid.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 110.dp),
                    ) {
                        itemsIndexed(items = listOf(1, 2, 3)) { _, item -> Text("Item $item") }
                    }
                }
            }
            rule.waitForIdle()
            rule.onNodeWithText("Item 1").assertIsDisplayed()
        } catch (t: Throwable) {
            thrown = t
        }
        assertWidthBoundFailure(thrown, "isolated unbound grid control")
    }

    /**
     * Merge audit item 4: the sensitivity control must cover the production bound
     * itself — removing the Card's finite width from the real production content
     * (the exact remediation mutation) must reproduce the failure.
     */
    @Test
    fun productionWidthBoundRemoved_stillFailsAsExpected() {
        val unboundedBudget = budget(400.dp, 800.dp, 3, false).copy(cardWidth = Dp.Infinity)
        var thrown: Throwable? = null
        try {
            rule.setContent {
                UnboundedWidthLayout {
                    BatchedBadgeCelebrationDialogContent(
                        badges = badges(3),
                        budget = unboundedBudget,
                        selectedBadgeId = null,
                        onBadgeClick = {},
                        onMarkAllCelebrated = {},
                        onDismiss = {},
                        scale = 1f,
                        alpha = 1f,
                    )
                }
            }
            rule.waitForIdle()
        } catch (t: Throwable) {
            thrown = t
        }
        assertWidthBoundFailure(thrown, "production width bound removed")
    }

    /**
     * Merge audit item 4: the finite grid max width is recorded and asserted, not
     * just assumed — under the unbounded outer width the production Card width
     * (400dp * 0.9 = 360dp) is what bounds the grid to 360 - 48 = 312dp.
     */
    @Test
    fun unboundedWidth_productionGridMaxWidthIsRecordedFinite() {
        val budget = budget(windowWidth = 400.dp, windowHeight = 800.dp, count = 3, expanded = false)
        rule.setContent {
            UnboundedWidthLayout {
                BatchedBadgeCelebrationDialogContent(
                    badges = badges(3),
                    budget = budget,
                    selectedBadgeId = null,
                    onBadgeClick = {},
                    onMarkAllCelebrated = {},
                    onDismiss = {},
                    scale = 1f,
                    alpha = 1f,
                )
            }
        }
        rule.waitForIdle()
        val cardBounds = rule.onNodeWithTag("badge-celebration-card").getUnclippedBoundsInRoot()
        assertTrue(cardBounds.w.value.isFinite(), "recorded card width must be finite, got ${cardBounds.w}")
        assertDp(360.dp, cardBounds.w, message = "Card width must be the finite production bound (90% of 400dp)")

        val gridBounds = rule.onNodeWithTag("badge-celebration-grid").getUnclippedBoundsInRoot()
        assertTrue(gridBounds.w.value.isFinite(), "recorded grid maxWidth must be finite, got ${gridBounds.w}")
        assertDp(312.dp, gridBounds.w, message = "grid width = card width - 24dp*2 padding")
    }

    // ── Merge audit F1: actual rendered button/card bounds ─────────────────

    private fun assertShortWindowBounds(
        windowWidth: Dp,
        windowHeight: Dp,
        fontScale: Float,
        count: Int = 7,
    ) {
        val budget = budget(windowWidth, windowHeight, count, expanded = true, fontScale = fontScale)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                UnboundedWidthLayout {
                    BatchedBadgeCelebrationDialogContent(
                        badges = badges(count),
                        budget = budget,
                        selectedBadgeId = "b1",
                        onBadgeClick = {},
                        onMarkAllCelebrated = {},
                        onDismiss = {},
                        scale = 1f,
                        alpha = 1f,
                    )
                }
            }
        }
        rule.waitForIdle()

        // Actual rendered card bounds stay inside the declared content budget.
        val cardBounds = rule.onNodeWithTag("badge-celebration-card").getUnclippedBoundsInRoot()
        assertTrue(
            cardBounds.h <= budget.maxContentHeight + 1.dp,
            "window=${windowHeight}x${windowWidth} fontScale=$fontScale: rendered card height " +
                "${cardBounds.h} exceeds content budget ${budget.maxContentHeight}",
        )
        assertTrue(
            cardBounds.bottom <= budget.maxContentHeight + 1.dp,
            "window=$windowHeight fontScale=$fontScale: card bottom ${cardBounds.bottom} " +
                "must stay inside the ${budget.maxContentHeight} budget",
        )

        // The dismiss button keeps its full accessible size and stays reachable.
        val buttonBounds = dismissButton().getUnclippedBoundsInRoot()
        assertTrue(
            buttonBounds.h >= 47.5.dp,
            "window=$windowHeight fontScale=$fontScale: dismiss button shrank below " +
                "its accessible 48dp height: ${buttonBounds.h}",
        )
        assertTrue(
            buttonBounds.bottom <= budget.maxContentHeight + 1.dp,
            "window=$windowHeight fontScale=$fontScale: dismiss button bottom " +
                "${buttonBounds.bottom} exceeds the ${budget.maxContentHeight} budget — " +
                "dismissal is not reachable",
        )
        dismissButton().assertIsDisplayed()

        // Grid and details are independently bounded (each scrolls locally).
        val gridBounds = rule.onNodeWithTag("badge-celebration-grid").getUnclippedBoundsInRoot()
        assertTrue(
            gridBounds.h <= budget.maxGridHeight + 1.dp,
            "grid height ${gridBounds.h} exceeds its ${budget.maxGridHeight} share",
        )
        if (budget.maxDetailsHeight >= 40.dp) {
            rule.onNodeWithText("Badge b1").assertIsDisplayed()
        } else {
            // The details block still composes and scrolls locally even when the
            // short-window share leaves only a sliver of it visible.
            rule.onNodeWithText("Badge b1").assertExists()
        }
    }

    @Test
    fun shortWindow400dp_expandedDetails_boundsFit_fontScale1() =
        assertShortWindowBounds(windowWidth = 400.dp, windowHeight = 400.dp, fontScale = 1f)

    @Test
    fun shortWindow400dp_expandedDetails_boundsFit_fontScale1_5() =
        assertShortWindowBounds(windowWidth = 400.dp, windowHeight = 400.dp, fontScale = 1.5f)

    @Test
    fun shortWindow400dp_expandedDetails_boundsFit_fontScale2() =
        assertShortWindowBounds(windowWidth = 400.dp, windowHeight = 400.dp, fontScale = 2f)

    @Test
    fun tighterWindow320dp_expandedDetails_boundsFit_fontScale2() =
        assertShortWindowBounds(windowWidth = 400.dp, windowHeight = 320.dp, fontScale = 2f)

    @Test
    fun tighterWindow320dp_expandedDetails_boundsFit_fontScale1() =
        assertShortWindowBounds(windowWidth = 400.dp, windowHeight = 320.dp, fontScale = 1f)

    // ── Counts, selection, scrolling, dismissal IDs, queue retention ──────

    @Test
    fun countsMatrix_selectionDetailsDismissalExactIds() {
        var current by mutableStateOf(badges(3))
        val marked = mutableListOf<List<String>>()
        var dismissed = false
        rule.setContent {
            CompositionLocalProvider(
                LocalWindowSizeClass provides WindowSizeClass(
                    widthSizeClass = WindowWidthSizeClass.Expanded,
                    heightSizeClass = WindowHeightSizeClass.Medium,
                    widthDp = 400.dp,
                    heightDp = 400.dp,
                ),
            ) {
                BatchedBadgeCelebrationDialog(
                    badges = current,
                    onDismiss = { dismissed = true },
                    onMarkAllCelebrated = { marked += it },
                    onSoundTrigger = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("3 Badges Earned!").assertIsDisplayed()

        // Selection shows the details block for the tapped badge.
        rule.onNodeWithContentDescription("Badge b1, Gold tier", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Badge b1").assertIsDisplayed()

        // Dismissal reports the exact celebration IDs, in order.
        dismissButton().performClick()
        rule.waitForIdle()
        assertTrue(dismissed, "dismiss callback must fire")
        assertTrue(
            marked == listOf(listOf("b1", "b2", "b3")),
            "onMarkAllCelebrated must receive the exact badge IDs in order, got $marked",
        )
    }

    @Test
    fun countMatrix_singleAndMany_renderCorrectHeaderAndIds() {
        var count by mutableStateOf(1)
        val marked = mutableListOf<List<String>>()
        rule.setContent {
            val budget = budget(400.dp, 400.dp, count, expanded = false)
            UnboundedWidthLayout {
                BatchedBadgeCelebrationDialogContent(
                    badges = badges(count),
                    budget = budget,
                    selectedBadgeId = null,
                    onBadgeClick = {},
                    onMarkAllCelebrated = { marked += it },
                    onDismiss = {},
                    scale = 1f,
                    alpha = 1f,
                )
            }
        }
        for (n in listOf(1, 4, 6, 7)) {
            rule.runOnIdle { count = n }
            rule.waitForIdle()
            val expectedHeader = if (n == 1) "Badge Earned!" else "$n Badges Earned!"
            rule.onNodeWithText(expectedHeader).assertIsDisplayed()
            // The capped grid shows its first row; later rows are covered by the
            // capped-grid scrolling test below.
            for (i in 1..minOf(n, 3)) {
                rule.onNodeWithContentDescription("Badge b$i, Gold tier", substring = true)
                    .assertIsDisplayed()
            }
            dismissButton().performClick()
            rule.waitForIdle()
            assertTrue(
                marked == listOf((1..n).map { "b$it" }),
                "count=$n: dismissal must report exact celebration IDs, got $marked",
            )
            marked.clear()
        }
    }

    @Test
    fun sevenBadges_cappedGridScrollsToReachLaterBadges() {
        val budget = budget(windowWidth = 400.dp, windowHeight = 400.dp, count = 7, expanded = false)
        rule.setContent {
            UnboundedWidthLayout {
                BatchedBadgeCelebrationDialogContent(
                    badges = badges(7),
                    budget = budget,
                    selectedBadgeId = null,
                    onBadgeClick = {},
                    onMarkAllCelebrated = {},
                    onDismiss = {},
                    scale = 1f,
                    alpha = 1f,
                )
            }
        }
        rule.waitForIdle()
        // The capped grid keeps independent scrolling (never nested under a scroller).
        val grid = rule.onNodeWithTag("badge-celebration-grid")
        assertTrue(
            hasScrollAction().matches(grid.fetchSemanticsNode()),
            "the capped grid must expose its own scroll action",
        )
        assertTrue(
            grid.getUnclippedBoundsInRoot().h <= budget.maxGridHeight + 1.dp,
            "grid must be capped at ${budget.maxGridHeight}",
        )
        // Scroll behaviour: the last badge row is reachable inside the capped grid.
        grid.performTouchInput { swipeUp() }
        grid.performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Badge b7, Gold tier", substring = true).assertIsDisplayed()
    }

    @Test
    fun invalidThenValidMetrics_deferThenRender_withoutPrematureCelebration() {
        var window by mutableStateOf(
            WindowSizeClass(
                widthSizeClass = WindowWidthSizeClass.Expanded,
                heightSizeClass = WindowHeightSizeClass.Medium,
                widthDp = 0.dp,
                heightDp = 400.dp,
            ),
        )
        var soundCount = 0
        val marked = mutableListOf<List<String>>()
        rule.setContent {
            CompositionLocalProvider(LocalWindowSizeClass provides window) {
                BatchedBadgeCelebrationDialog(
                    badges = badges(3),
                    onDismiss = {},
                    onMarkAllCelebrated = { marked += it },
                    onSoundTrigger = { soundCount++ },
                )
            }
        }
        rule.waitForIdle()
        // Invalid metrics: nothing renders, no sound, and queued badges stay queued.
        rule.onNodeWithText("3 Badges Earned!").assertDoesNotExist()
        assertTrue(soundCount == 0, "invalid metrics must not trigger the celebration sound")
        assertTrue(marked.isEmpty(), "invalid metrics must not mark badges celebrated")

        // Metrics become valid: the dialog renders and the queue is preserved.
        rule.runOnIdle {
            window = WindowSizeClass(
                widthSizeClass = WindowWidthSizeClass.Expanded,
                heightSizeClass = WindowHeightSizeClass.Medium,
                widthDp = 400.dp,
                heightDp = 400.dp,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("3 Badges Earned!").assertIsDisplayed()
        assertTrue(soundCount == 1, "valid metrics trigger the celebration sound exactly once")
        assertTrue(
            marked.isEmpty(),
            "badges must not be marked celebrated before the user dismisses, got $marked",
        )

        // Only the user's dismissal reports the exact celebration IDs.
        dismissButton().performClick()
        rule.waitForIdle()
        assertTrue(
            marked == listOf(listOf("b1", "b2", "b3")),
            "dismissal must report the queued badge IDs exactly, got $marked",
        )
    }

    @Test
    fun productionDialog_showsBadgeContentWithLiveWindowMetrics() {
        rule.setContent {
            CompositionLocalProvider(
                LocalWindowSizeClass provides WindowSizeClass(
                    widthSizeClass = WindowWidthSizeClass.Expanded,
                    heightSizeClass = WindowHeightSizeClass.Medium,
                    widthDp = 1280.dp,
                    heightDp = 800.dp,
                ),
            ) {
                BatchedBadgeCelebrationDialog(
                    badges = listOf(badge("b1")),
                    onDismiss = {},
                    onMarkAllCelebrated = {},
                    onSoundTrigger = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Badge Earned!").assertIsDisplayed()
        rule.onNodeWithContentDescription("Badge b1, Gold tier", substring = true).assertIsDisplayed()
    }

    private fun assertWidthBoundFailure(thrown: Throwable?, scenario: String) {
        val chain = generateSequence(thrown) { it.cause }
            .mapNotNull { it.message }
            .toList()
        val matched = chain.any { it.contains("width should be bound") }
        if (!matched) {
            fail(
                "$scenario: expected the LazyGrid width-bound failure under unbounded " +
                    "outer width, but ${if (thrown == null) "nothing was thrown" else "threw: $chain"}. " +
                    "The harness is not sensitive to the #1403 missing-bound failure.",
            )
        }
    }
}

/**
 * Measures its content with unbounded width constraints — the same constraint
 * shape the iOS wrap-content dialog layer produced for the #1403 crash.
 */
@Composable
private fun UnboundedWidthLayout(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                ),
            )
        }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}
