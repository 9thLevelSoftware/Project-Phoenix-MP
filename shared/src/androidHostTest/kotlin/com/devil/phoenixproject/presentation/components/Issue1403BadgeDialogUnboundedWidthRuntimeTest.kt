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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Constraints
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
 * [unboundedWidth_productionContentRendersWithoutTermination] composes the ACTUAL
 * production dialog content under the same unbounded outer width and asserts it
 * renders without the throw. [unboundedWidth_missingBoundStillFailsAsExpected] is
 * the sensitive control: the same unbounded harness over an unbound grid MUST still
 * fail with the framework's width-bound error, proving the harness can detect the
 * missing-bound implementation and is not passing vacuously.
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

    @Test
    fun unboundedWidth_productionContentRendersWithoutTermination() {
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 400.dp,
            windowHeight = 800.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 3,
            detailsExpanded = false,
        )!!
        rule.setContent {
            UnboundedWidthLayout {
                BatchedBadgeCelebrationDialogContent(
                    badges = listOf(badge("b1"), badge("b2"), badge("b3")),
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
                    // before the fix — fillMaxWidth under infinite constraints cannot
                    // bound the grid.
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
        val chain = generateSequence(thrown) { it.cause }
            .mapNotNull { it.message }
            .toList()
        val matched = chain.any { it.contains("width should be bound") }
        if (!matched) {
            fail(
                "Control was expected to fail with the LazyGrid width-bound error under " +
                    "unbounded outer width, but ${if (thrown == null) "nothing was thrown" else "threw: $chain"}. " +
                    "The harness is not sensitive to the #1403 missing-bound failure.",
            )
        }
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
