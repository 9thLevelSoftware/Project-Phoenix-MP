package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1403: pure contract tests for the badge dialog's finite window-metric seam.
 *
 * The dialog layer measures content with maxWidth == Constraints.Infinity on iOS
 * (usePlatformDefaultWidth = false); the grid must therefore get a finite width
 * computed from live window metrics BEFORE measurement. These tests pin the width
 * policy (approximately 90% of inset-adjusted usable width, capped at 580dp), the
 * invalid-metric defer signal, and — per the merge audit F1 — the TOTAL height fit:
 * chrome + grid + details must never exceed the declared content budget at any
 * window height, font scale, badge count or expansion state (no minimum floors).
 */
class BadgeCelebrationLayoutBudgetTest {

    private fun assertDpEquals(expected: Dp, actual: Dp, message: String) {
        assertTrue(
            kotlin.math.abs(actual.value - expected.value) < 0.1f,
            "$message: expected $expected, got $actual",
        )
    }

    // ── Card width: ~90% of usable width, capped at 580dp ──────────────────

    @Test
    fun wideWindow_cardWidthIsCappedAt580dp() {
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 1280.dp,
            windowHeight = 800.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 3,
            detailsExpanded = false,
        )!!
        assertDpEquals(580.dp, budget.cardWidth, "cap must bind on tablets")
    }

    @Test
    fun phoneWindow_cardWidthIsNinetyPercentOfUsableWidth() {
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 400.dp,
            windowHeight = 800.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 3,
            detailsExpanded = false,
        )!!
        assertDpEquals(360.dp, budget.cardWidth, "90% of 400dp")
    }

    @Test
    fun insetsAreSubtractedExactlyOnce() {
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 400.dp,
            windowHeight = 800.dp,
            horizontalInsets = 40.dp,
            verticalInsets = 0.dp,
            badgeCount = 3,
            detailsExpanded = false,
        )!!
        // (400 - 40) * 0.9 == 324dp — subtracted once, not applied twice.
        assertDpEquals(324.dp, budget.cardWidth, "90% of usable width after one inset subtraction")
    }

    @Test
    fun tighterUsableWidthBelowCapStaysUncapped() {
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 800.dp,
            windowHeight = 800.dp,
            horizontalInsets = 80.dp,
            verticalInsets = 0.dp,
            badgeCount = 3,
            detailsExpanded = false,
        )!!
        // (800 - 80) * 0.9 == 648 -> capped at 580dp
        assertDpEquals(580.dp, budget.cardWidth, "cap must bind at 648dp")
    }

    // ── Invalid metrics defer rendering (null) ─────────────────────────────

    @Test
    fun zeroWindowMetrics_defer() {
        assertNull(
            badgeCelebrationLayoutBudget(0.dp, 800.dp, 0.dp, 0.dp, 3, false),
            "zero width must defer",
        )
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 0.dp, 0.dp, 0.dp, 3, false),
            "zero height must defer",
        )
    }

    @Test
    fun negativeInsets_defer() {
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 800.dp, (-1).dp, 0.dp, 3, false),
            "negative horizontal insets must defer",
        )
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 800.dp, 0.dp, (-1).dp, 3, false),
            "negative vertical insets must defer",
        )
    }

    @Test
    fun insetsSwallowingTheWindow_defer() {
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 800.dp, 400.dp, 0.dp, 3, false),
            "no usable width must defer",
        )
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 800.dp, 0.dp, 800.dp, 3, false),
            "no usable height must defer",
        )
    }

    @Test
    fun nonFiniteMetrics_defer() {
        assertNull(
            badgeCelebrationLayoutBudget(Dp.Infinity, 800.dp, 0.dp, 0.dp, 3, false),
            "infinite width must defer — an infinite bound is not a bound",
        )
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, Dp.Infinity, 0.dp, 0.dp, 3, false),
            "infinite height must defer",
        )
        assertNull(
            badgeCelebrationLayoutBudget(Dp.Unspecified, 800.dp, 0.dp, 0.dp, 3, false),
            "NaN (Unspecified) width must defer",
        )
    }

    @Test
    fun nonFiniteFontScale_defer() {
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 800.dp, 0.dp, 0.dp, 3, false, fontScale = Float.NaN),
            "NaN font scale must defer",
        )
        assertNull(
            badgeCelebrationLayoutBudget(400.dp, 800.dp, 0.dp, 0.dp, 3, false, fontScale = 0f),
            "zero font scale must defer",
        )
    }

    // ── Merge audit F1: total height fit (no over-allocating floors) ───────

    /**
     * The audit's named failure case: 400dp usable height, 7 badges with expanded
     * details previously reserved 300 + 110 + 96 = 506dp against a 368dp budget.
     * The budget must fit its declared total exactly, by construction.
     */
    @Test
    fun shortWindow400dpExpandedDetails_fitsTotalBudget() {
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 400.dp,
            windowHeight = 400.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 7,
            detailsExpanded = true,
        )!!
        val maxContent = 400.dp * 0.92f
        assertDpEquals(maxContent, budget.maxContentHeight, "content budget")
        assertTrue(
            budget.chromeUpperBound + budget.maxGridHeight + budget.maxDetailsHeight <=
                budget.maxContentHeight + 0.1.dp,
            "chrome (${budget.chromeUpperBound}) + grid (${budget.maxGridHeight}) + " +
                "details (${budget.maxDetailsHeight}) must fit the " +
                "${budget.maxContentHeight} content budget",
        )
        assertTrue(budget.maxGridHeight >= 0.dp, "grid share must not go negative")
        assertTrue(budget.maxDetailsHeight >= 0.dp, "details share must not go negative")
    }

    @Test
    fun totalFitHoldsAcrossHeightFontScaleCountMatrix() {
        val heights = listOf(200.dp, 280.dp, 320.dp, 400.dp, 600.dp, 720.dp, 1194.dp)
        val fontScales = listOf(1f, 1.5f, 2f)
        val counts = listOf(1, 3, 4, 6, 7, 12)
        for (height in heights) {
            for (fontScale in fontScales) {
                for (count in counts) {
                    for (expanded in listOf(false, true)) {
                        val label = "height=$height fontScale=$fontScale count=$count expanded=$expanded"
                        val budget = badgeCelebrationLayoutBudget(
                            windowWidth = 800.dp,
                            windowHeight = height,
                            horizontalInsets = 0.dp,
                            verticalInsets = 0.dp,
                            badgeCount = count,
                            detailsExpanded = expanded,
                            fontScale = fontScale,
                        )!!
                        assertTrue(
                            budget.chromeUpperBound + budget.maxGridHeight + budget.maxDetailsHeight <=
                                budget.maxContentHeight + 0.1.dp,
                            "$label: total overflow — chrome=${budget.chromeUpperBound} " +
                                "grid=${budget.maxGridHeight} details=${budget.maxDetailsHeight} " +
                                "budget=${budget.maxContentHeight}",
                        )
                        assertTrue(
                            budget.maxGridHeight <= budget.gridContentHeight + 0.1.dp,
                            "$label: grid cap must not exceed natural rows",
                        )
                        assertTrue(
                            budget.maxGridHeight >= 0.dp && budget.maxDetailsHeight >= 0.dp,
                            "$label: areas must not go negative",
                        )
                        assertTrue(
                            budget.maxDetailsHeight <= 140.dp + 0.1.dp,
                            "$label: details share must stay within its reserve",
                        )
                        if (!expanded) {
                            assertTrue(
                                budget.maxDetailsHeight == 0.dp,
                                "$label: collapsed details must not reserve space",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fontScaleDrivesHeaderAllowanceNotAFixedConstant() {
        val normal = badgeCelebrationLayoutBudget(
            800.dp, 600.dp, 0.dp, 0.dp, 3, false, fontScale = 1f,
        )!!
        val scaled = badgeCelebrationLayoutBudget(
            800.dp, 600.dp, 0.dp, 0.dp, 3, false, fontScale = 2f,
        )!!
        assertTrue(
            scaled.chromeUpperBound > normal.chromeUpperBound,
            "chrome must grow with the actual font scale (no fixed 300dp reserve)",
        )
    }

    @Test
    fun extremeShortWindowShrinksOnlyTheDecorativeTrophy() {
        // 320dp height at 2x font scale: chrome without shrinking exceeds the
        // budget; only the decorative trophy may shrink — never the dismiss
        // button (48dp stays inside CHROME_FIXED at full accessible size).
        val budget = badgeCelebrationLayoutBudget(
            800.dp, 320.dp, 0.dp, 0.dp, 3, true, fontScale = 2f,
        )!!
        assertTrue(
            budget.trophySize < 80.dp,
            "decorative trophy must absorb the deficit first",
        )
        assertTrue(
            budget.trophySize >= 0.dp,
            "trophy size must not go negative",
        )
        assertTrue(
            budget.chromeUpperBound <= budget.maxContentHeight + 0.1.dp,
            "even fully shrunk chrome must fit the budget",
        )
    }

    // ── Short-window height budget keeps dismissal reachable ───────────────

    @Test
    fun shortWindow_expandedDetailsClampGridAndDetails() {
        // iPad-mini-class landscape: 720dp usable height, 7+ badges with expanded
        // details. Natural grid (280dp) must clamp so the whole dialog fits.
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 1133.dp,
            windowHeight = 720.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 7,
            detailsExpanded = true,
        )!!
        val maxContent = 720.dp * 0.92f
        assertDpEquals(maxContent, budget.maxContentHeight, "content budget")
        assertTrue(
            budget.maxGridHeight < budget.gridContentHeight,
            "grid must clamp below its natural row height in short windows",
        )
        assertTrue(
            budget.chromeUpperBound + budget.maxGridHeight + budget.maxDetailsHeight <=
                budget.maxContentHeight + 0.1.dp,
            "chrome + grid + details must fit the content budget",
        )
    }

    @Test
    fun tallWindow_gridAndDetailsKeepNaturalSizes() {
        // Roomy window: caps sit at or above natural sizes — no visual change.
        val budget = badgeCelebrationLayoutBudget(
            windowWidth = 1280.dp,
            windowHeight = 1200.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 7,
            detailsExpanded = true,
        )!!
        assertDpEquals(280.dp, budget.maxGridHeight, "3+ row grid keeps its natural height")
        assertTrue(
            budget.maxDetailsHeight >= 140.dp,
            "details keep room for the expanded block",
        )
    }

    @Test
    fun collapsedDetailsReserveMoreGridThanExpanded() {
        val collapsed = badgeCelebrationLayoutBudget(
            windowWidth = 400.dp,
            windowHeight = 400.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 7,
            detailsExpanded = false,
        )!!
        val expanded = badgeCelebrationLayoutBudget(
            windowWidth = 400.dp,
            windowHeight = 400.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = 7,
            detailsExpanded = true,
        )!!
        assertTrue(
            collapsed.maxGridHeight >= expanded.maxGridHeight,
            "expanding details may only shrink or keep the grid budget",
        )
    }

    @Test
    fun gridRowHeightBucketsMatchRowCount() {
        fun gridContentHeight(count: Int): Dp = badgeCelebrationLayoutBudget(
            windowWidth = 1280.dp,
            windowHeight = 1200.dp,
            horizontalInsets = 0.dp,
            verticalInsets = 0.dp,
            badgeCount = count,
            detailsExpanded = false,
        )!!.gridContentHeight

        assertDpEquals(110.dp, gridContentHeight(1), "1 badge = 1 row")
        assertDpEquals(110.dp, gridContentHeight(3), "3 badges = 1 row")
        assertDpEquals(220.dp, gridContentHeight(4), "4 badges = 2 rows")
        assertDpEquals(220.dp, gridContentHeight(6), "6 badges = 2 rows")
        assertDpEquals(280.dp, gridContentHeight(7), "7+ badges = 3 rows capped with scroll")
    }
}
