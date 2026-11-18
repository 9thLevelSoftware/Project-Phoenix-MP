package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1360: the reporter's screenshots put the app's logical width at ~320dp
 * (iPhone17,1 with Display Zoom). The rest-control Row was unweighted with
 * Arrangement.SpaceEvenly, so +30s and Pause took their intrinsic widths and
 * Reset was starved to a ~13dp sliver (0x0 at 2.0x text). Every rest control
 * must keep a full, non-overlapping, tappable box inside the card at narrow
 * widths and large text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h693dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Issue1360RestControlNarrowWidthTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun controlsKeepFullTapTargets_at320dp_defaultText() = assertControls(widthDp = 320, fontScale = 1.0f)

    @Test
    fun controlsKeepFullTapTargets_at320dp_largeText() = assertControls(widthDp = 320, fontScale = 1.35f)

    @Test
    fun controlsKeepFullTapTargets_at300dp_maxText() = assertControls(widthDp = 300, fontScale = 2.0f)

    @Test
    fun controlsKeepFullTapTargets_at402dp_defaultText() = assertControls(widthDp = 402, fontScale = 1.0f)

    private fun assertControls(widthDp: Int, fontScale: Float) {
        val extend = mutableIntStateOf(0)
        val toggles = mutableIntStateOf(0)
        val resets = mutableIntStateOf(0)
        val paused = mutableStateOf(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme {
                    // Match WorkoutTab's host: 20dp side padding + vertical scroll.
                    Column(
                        Modifier.width(widthDp.dp).padding(horizontal = 20.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        RestTimerCard(
                            restSecondsRemaining = 60,
                            nextExerciseName = "Squat",
                            isLastExercise = false,
                            currentSet = 1,
                            totalSets = 3,
                            isRestPaused = paused.value,
                            formatWeightWithUnit = { v, _ -> "$v kg" },
                            onSkipRest = {},
                            onExtendRest = { extend.intValue += it },
                            onToggleRestPause = { toggles.intValue++; paused.value = !paused.value },
                            onResetRest = { resets.intValue++ },
                            onEndWorkout = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        val minTouchPx = 48f * density

        val buttons = listOf(
            "+30s" to button("Add 30 seconds"),
            "Pause" to button("Pause"),
            "Reset" to button("Reset timer"),
        )
        val rects = buttons.map { (label, node) ->
            node.performScrollTo()
            val r = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label width ${r.width / density}dp < 48dp at ${widthDp}dp/${fontScale}x", r.width >= minTouchPx)
            assertTrue("$label height ${r.height / density}dp < 40dp at ${widthDp}dp/${fontScale}x", r.height >= 40f * density)
            assertTrue("$label $r escapes card width ${root.width} at ${widthDp}dp/${fontScale}x", r.left >= root.left - 0.5f && r.right <= root.right + 0.5f)
            label to r
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val (a, ra) = rects[i]
            val (b, rb) = rects[j]
            assertFalse("$a $ra overlaps $b $rb at ${widthDp}dp/${fontScale}x", ra.overlapsStrictly(rb))
        }

        // Taps at the centre of every control reach their own handler.
        buttons[0].second.performTouchInput { click() }
        buttons[1].second.performTouchInput { click() }
        button("Reset timer").performScrollTo().performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(30, extend.intValue)
        assertEquals(1, toggles.intValue)
        assertEquals(1, resets.intValue)
    }

    /** The FilledTonalButton node that merges the icon's content description. */
    private fun button(cd: String): SemanticsNodeInteraction =
        rule.onNode(hasClickAction() and (hasContentDescription(cd, substring = true) or hasText(cd)), useUnmergedTree = false)

    private fun Rect.overlapsStrictly(o: Rect): Boolean {
        val eps = 0.5f
        return left < o.right - eps && o.left < right - eps && top < o.bottom - eps && o.top < bottom - eps
    }

    @Suppress("unused")
    private fun SemanticsNodeInteraction.label(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
}
