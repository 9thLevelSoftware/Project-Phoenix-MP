@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #1403 UIKit fixture (merge audit remediation item 2).
 *
 * Runs on the real iOS Kotlin/Native + UIKit runtime via `:shared:iosSimulatorArm64Test`.
 * Evidence retained from this run (stdout lines prefixed `ISSUE1403_UIKIT_FIXTURE`)
 * records, for iPad portrait, landscape and split/narrow geometries:
 * - the incoming constraints the wrap-content dialog layer imposes
 *   (maxWidth == Constraints.Infinity — the #1403 crash condition),
 * - the rendered Card/content bounds,
 * - the rendered grid bounds (finite max width asserted),
 * - the rendered dismiss-button bounds (accessible size and reachability).
 *
 * The fixture composes the ACTUAL production content
 * ([BatchedBadgeCelebrationDialogContent] — the real Card/grid/details/button used
 * by [BatchedBadgeCelebrationDialog]), not a copied subtree. The full public entry
 * is exercised in its own fixture asserting it composes without termination.
 */
@OptIn(ExperimentalTestApi::class)
class Issue1403BadgeDialogUIKitFixtureTest {

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

    // ── Full public entry: composes without termination ────────────────────

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
            println(
                "ISSUE1403_UIKIT_FIXTURE window=$name entry=BatchedBadgeCelebrationDialog " +
                    "incomingMaxWidth=INFINITY result=COMPOSED_WITHOUT_TERMINATION",
            )
        }

    // ── Production content: recorded constraints + rendered-bounds evidence ─

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

        // Incoming constraints: the wrap-content dialog layer shape that caused #1403.
        println(
            "ISSUE1403_UIKIT_FIXTURE window=$name incomingConstraints=" +
                "minWidth=0,maxWidth=INFINITY,minHeight=0,maxHeight=${windowHeight} " +
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
 * shape the iOS wrap-content dialog layer produced for the #1403 crash. A test-side
 * constraints shim, not a copy of production content.
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
