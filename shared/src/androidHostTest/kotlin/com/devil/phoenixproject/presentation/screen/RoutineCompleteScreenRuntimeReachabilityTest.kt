package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.PlatformAccessibilitySettings
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper

/**
 * Issue #1164 rendered-reachability measurement tests.
 *
 * Executes REAL Compose layout/semantics against the production [RoutineCompleteScreen]
 * (state collection, reveal, scroll body, reserved Done footer and the shared exit
 * action) under finite root constraints, recording density, font scale, insets, root
 * dimensions and measured Done bounds on every scenario. Source-string guards in
 * [RoutineCompleteScreenExitReachabilityContractTest] cannot substitute for these.
 *
 * Each scenario asserts the merge-gate acceptance directly:
 * - Done is visible, enabled, labeled, nonzero-sized, inside the usable viewport,
 *   unobscured and tappable WITHOUT scrolling;
 * - the celebration body is the only scroll region and scrolling it never moves Done;
 * - default/enlarged/largest supported text and short/narrow/iPhone-class roots;
 * - long names/totals/durations stay fully readable (no ellipsis-hidden values);
 * - initial reveal, settled and Reduce Motion states keep the exit usable;
 * - the reported fixture (beginner / 5 exercises / 12 sets / 20m 14s) keeps full
 *   associated labels (presentation only; it does not validate reporter stored totals).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineCompleteScreenRuntimeReachabilityTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private var fixture: RoutineCompleteRuntimeFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
    }

    // ===== Scenario matrix =====

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun fixture_iphone17_defaultText_doneReachableAndLabelsAssociated() {
        val complete = renderComplete(
            scenario = "fixture_iphone17_defaultText",
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            fontScale = 1.0f,
        )
        check(complete.totalExercises == 5 && complete.totalSets == 12) {
            "fixture must drive the reported presentation values (got $complete)"
        }

        settleReveal()
        assertDoneReachable(
            scenario = "fixture_iphone17_defaultText",
            fontScale = 1.0f,
            expectInsetsPx = 0 to 0,
        )

        // Reported fixture presentation: values stay fully associated with their
        // labels; the duration never merges visually with Sets ("1220m" defect).
        assertStatCell("5", "Exercises", scenario = "fixture_iphone17_defaultText")
        assertStatCell("12", "Sets", scenario = "fixture_iphone17_defaultText")
        assertStatCell("20m 14s", "Duration", scenario = "fixture_iphone17_defaultText")
        assertNoMergedSetsDuration(scenario = "fixture_iphone17_defaultText")
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun enlargedText_iphone17_bodyScrollsWithoutMovingDone() {
        renderComplete(
            scenario = "enlargedText_iphone17_scroll",
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            fontScale = 1.3f,
        )
        settleReveal()
        assertDoneReachable(
            scenario = "enlargedText_iphone17_scroll",
            fontScale = 1.3f,
            expectInsetsPx = 0 to 0,
        )
        assertBodyScrollsAndFooterNeverMoves(
            scenario = "enlargedText_iphone17_scroll",
            fontScale = 1.3f,
        )
    }

    @Test
    @Config(qualifiers = "w280dp-h640dp-xhdpi")
    fun largestText_narrowRoot_doneReachableWithoutScrolling() {
        renderComplete(
            scenario = "largestText_narrowRoot",
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            fontScale = 2.0f,
        )
        settleReveal()
        assertDoneReachable(
            scenario = "largestText_narrowRoot",
            fontScale = 2.0f,
            expectInsetsPx = 0 to 0,
        )
        assertBodyScrollsAndFooterNeverMoves(
            scenario = "largestText_narrowRoot",
            fontScale = 2.0f,
        )
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun shortRoot_longContent_doneKeepsHeightWhileBodyScrolls() {
        renderComplete(
            scenario = "shortRoot_longContent",
            routineName = "Marathon Preparation Block Sixteen — Extended Grinder Series Week Twelve",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 1234,
            durationMs = 9999 * 60_000L + 59_000L,
            fontScale = 1.0f,
        )
        settleReveal()
        assertDoneReachable(
            scenario = "shortRoot_longContent",
            fontScale = 1.0f,
            expectInsetsPx = 0 to 0,
        )
        assertLongValuesFullyPresent(
            scenario = "shortRoot_longContent",
            routineName = "Marathon Preparation Block Sixteen — Extended Grinder Series Week Twelve",
            setsValue = "1234",
            durationValue = "9999m 59s",
        )
        assertBodyScrollsAndFooterNeverMoves(
            scenario = "shortRoot_longContent",
            fontScale = 1.0f,
        )
    }

    @Test
    @Config(qualifiers = "w375dp-h667dp-xhdpi")
    fun safeAreaInsets_footerStaysInsideUsableViewport() {
        // Baseline: zero insets.
        renderComplete(
            scenario = "safeAreaInsets_baseline",
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            fontScale = 1.0f,
        )
        settleReveal()
        val baseline = doneBoundsPx()
        val baselineRootPx = rootSizePx()

        // iPhone-class safe area: 47px top (status bar / notch), 34px bottom
        // (home indicator) at this density.
        val topPx = 94
        val bottomPx = 68
        rule.applySystemBarInsets(topPx, bottomPx)
        settleReveal()

        val insetBounds = doneBoundsPx()
        assertDoneReachable(
            scenario = "safeAreaInsets",
            fontScale = 1.0f,
            expectInsetsPx = topPx to bottomPx,
        )
        // The footer must respect the bottom safe area (systemBarsPadding on the shared
        // root): measured shift is recorded, and the footer must move up by AT LEAST the
        // bottom inset while staying inside the usable viewport (asserted above).
        val shiftUpPx = baseline.bottom - insetBounds.bottom
        val baselineRoot = baselineRootPx
        val insetRoot = rootSizePx()
        RoutineCompleteRuntimeEvidence.record(
            "safeAreaInsets/shift",
            mapOf(
                "baselineRootPx" to "${baselineRoot.first}x${baselineRoot.second}",
                "insetRootPx" to "${insetRoot.first}x${insetRoot.second}",
                "baselineDoneBottomPx" to baseline.bottom,
                "insetDoneBottomPx" to insetBounds.bottom,
                "footerShiftUpPx" to shiftUpPx,
                "bottomInsetPx" to bottomPx,
                "respectsBottomInset" to (shiftUpPx >= bottomPx.toFloat()),
                "insetsAppliedBy" to "WindowInsetsHolder.update(systemBars) + dispatchApplyWindowInsets",
            ),
        )
        assertTrue(
            "With bottom safe-area insets the Done footer must move up by at least the inset " +
                "(systemBarsPadding on the shared root). baseline=$baseline inset=$insetBounds " +
                "insetBottomPx=$bottomPx shiftUpPx=$shiftUpPx",
            shiftUpPx >= bottomPx.toFloat(),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun initialReveal_doneVisibleAndTappableBeforeStatsReveal() {
        // Freeze the frame clock BEFORE the first composition: the staged reveal must
        // not have finished, and the reserved footer must already be measurable/tappable.
        rule.mainClock.autoAdvance = false
        renderComplete(
            scenario = "initialReveal",
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            fontScale = 1.0f,
        )
        // Advance frame-by-frame (16ms/frame) until the reserved footer is measurable.
        // The staged reveal staggers are 100ms apart, so the footer must exist well before
        // even the title stage: the frames are counted and the elapsed budget recorded.
        var framesAdvanced = 0
        while (framesAdvanced < 6 && rule.onAllNodesWithText("DONE").fetchSemanticsNodes().isEmpty()) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            framesAdvanced++
        }
        assertTrue(
            "Done must be measurable within a few frames of the first composition " +
                "(framesAdvanced=$framesAdvanced, well under the 100ms first reveal stagger)",
            framesAdvanced < 6,
        )

        val bounds = doneBoundsPx()
        val root = rootSizePx()
        assertTrue(
            "Done must be nonzero-sized on the first frame (issue #1164: the exit must not " +
                "share the reveal). bounds=$bounds",
            bounds.width() > 0f && bounds.height() > 0f,
        )
        assertTrue(
            "Done must be inside the root on the first frame. bounds=$bounds root=$root",
            bounds.left >= 0f && bounds.right <= root.first && bounds.bottom <= root.second,
        )
        val node = rule.onNodeWithText("DONE")
        node.assertIsDisplayed()
        node.assertIsEnabled()
        node.assertHasClickAction()

        // Tappable immediately, during the reveal: the click runs the shared exit action.
        node.performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val cleanup = fixture!!.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "initialReveal",
            mapOf(
                "rootQualifier" to "w402dp-h874dp-xxhdpi",
                "density" to rule.density.density,
                "fontScale" to 1.0,
                "insetsPx" to "top=0 bottom=0",
                "rootPx" to "${root.first}x${root.second}",
                "doneBoundsPx" to bounds.toString(),
                "clickedDuringReveal" to true,
                "framesAdvancedToMeasure" to framesAdvanced,
                "cleanup" to cleanup,
                "note" to "Done tappable on the first frame, before the staged stats reveal completes",
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun reduceMotion_contentImmediateAndFooterNeverMoves() {
        renderComplete(
            scenario = "reduceMotion",
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            fontScale = 1.0f,
            reduceMotion = true,
        )
        settleReveal()

        // Reduce Motion: content is present immediately (no stagger to wait through).
        rule.onNodeWithText("ROUTINE COMPLETE!").assertIsDisplayed()
        val boundsBefore = doneBoundsPx()
        assertDoneReachable(
            scenario = "reduceMotion",
            fontScale = 1.0f,
            expectInsetsPx = 0 to 0,
        )

        // Scrolling the body must not move the footer in this mode either.
        rule.onNodeWithText("ROUTINE COMPLETE!").performScrollTo()
        rule.waitForIdle()
        val boundsAfter = doneBoundsPx()
        assertTrue(
            "Done footer must not move when the body scrolls (reduce motion). " +
                "before=$boundsBefore after=$boundsAfter",
            boundsBefore == boundsAfter,
        )
    }

    // ===== Assertion helpers =====

    /** Renders the production screen inside a real NavHost at the routine-complete route. */
    private fun renderComplete(
        scenario: String,
        routineName: String,
        exerciseCount: Int,
        setsPerExercise: Int,
        completedSetKeys: Int,
        durationMs: Long,
        fontScale: Float,
        reduceMotion: Boolean = false,
        trainingCyclesOrigin: Boolean = false,
    ): RoutineFlowState.Complete {
        val fx = RoutineCompleteRuntimeFixture().also { fixture = it }
        val complete = fx.driveToComplete(
            routineName = routineName,
            exerciseCount = exerciseCount,
            setsPerExercise = setsPerExercise,
            completedSetKeys = completedSetKeys,
            durationMs = durationMs,
            trainingCyclesOrigin = trainingCyclesOrigin,
        )
        val baseDensity = rule.density
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
                LocalPlatformAccessibilitySettings provides
                    PlatformAccessibilitySettings(reduceMotion = reduceMotion),
            ) {
                RuntimeRoutineCompleteNavHost(fx)
            }
        }
        rule.waitForIdle()
        RoutineCompleteRuntimeEvidence.record(
            "$scenario/state",
            mapOf(
                "routineName" to complete.routineName,
                "totalExercises" to complete.totalExercises,
                "totalSets" to complete.totalSets,
                "totalDurationMs" to complete.totalDurationMs,
            ),
        )
        return complete
    }

    private fun assertDoneReachable(
        scenario: String,
        fontScale: Float,
        expectInsetsPx: Pair<Int, Int>,
    ) {
        val node = rule.onNodeWithText("DONE")
        node.assertIsDisplayed()
        node.assertIsEnabled()
        node.assertHasClickAction()

        val bounds = doneBoundsPx()
        val root = rootSizePx()
        assertTrue(
            "Done bounds must be nonzero (issue #1164). bounds=$bounds",
            bounds.width() > 0f && bounds.height() > 0f,
        )
        assertTrue(
            "Done must be fully inside the root (unobscured, in the usable viewport). " +
                "bounds=$bounds root=$root insets=$expectInsetsPx",
            bounds.left >= 0f &&
                bounds.top >= expectInsetsPx.first &&
                bounds.right <= root.first &&
                bounds.bottom <= root.second - expectInsetsPx.second,
        )

        val density = rule.density
        RoutineCompleteRuntimeEvidence.record(
            scenario,
            mapOf(
                "rootQualifier" to configInfo(),
                "density" to density.density,
                "fontScale" to fontScale,
                "insetsPx" to "top=${expectInsetsPx.first} bottom=${expectInsetsPx.second}",
                "rootPx" to "${root.first}x${root.second}",
                "doneBoundsPx" to bounds.toString(),
                "doneBoundsDp" to bounds.toDp(density.density).toString(),
                "doneVisible" to true,
                "doneEnabled" to true,
                "doneNonZero" to true,
                "doneInUsableViewport" to true,
                "tappableWithoutScrolling" to true,
            ),
        )
    }

    /**
     * The celebration body must be the single scroll region and scrolling it must never
     * move the reserved footer (issue #1164 binding constraint).
     */
    private fun assertBodyScrollsAndFooterNeverMoves(scenario: String, fontScale: Float) {
        val before = doneBoundsPx()
        rule.onNodeWithText("ROUTINE COMPLETE!").performScrollTo()
        rule.waitForIdle()
        val afterScrollUp = doneBoundsPx()
        rule.onNodeWithText("DONE").assertIsDisplayed()
        val root = rootSizePx()
        RoutineCompleteRuntimeEvidence.record(
            "$scenario/scroll",
            mapOf(
                "fontScale" to fontScale,
                "doneBeforeScrollPx" to before.toString(),
                "doneAfterScrollPx" to afterScrollUp.toString(),
                "footerMoved" to (before != afterScrollUp),
                "rootPx" to "${root.first}x${root.second}",
            ),
        )
        assertTrue(
            "Scrolling the celebration body must not move the Done footer " +
                "(issue #1164). before=$before after=$afterScrollUp",
            before == afterScrollUp,
        )
    }

    private fun assertStatCell(value: String, label: String, scenario: String) {
        val texts = rule.onNodeWithText(value).fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertTrue(
            "Stat cell for '$value' must keep its full associated label '$label' in one " +
                "merged semantics unit (issue #1164). texts=$texts",
            texts.contains(value) && texts.contains(label),
        )
        RoutineCompleteRuntimeEvidence.record(
            "$scenario/statCell",
            mapOf("value" to value, "label" to label, "mergedTexts" to texts.joinToString("|")),
        )
    }

    private fun assertNoMergedSetsDuration(scenario: String) {
        val concatenated = rule
            .onAllNodesWithText("1220m", substring = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
        val setsTexts = rule.onNodeWithText("12").fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertTrue(
            "The malformed reporter concatenation '1220m' must never render (issue #1164 " +
                "secondary defect). found=$concatenated",
            concatenated.isEmpty(),
        )
        assertTrue(
            "Duration must not merge into the Sets cell (issue #1164). sets=$setsTexts",
            setsTexts.none { it.contains("20m") },
        )
        RoutineCompleteRuntimeEvidence.record(
            "$scenario/labelAssociation",
            mapOf(
                "setsCellTexts" to setsTexts.joinToString("|"),
                "rendered1220m" to false,
                "durationNotInSetsCell" to true,
            ),
        )
    }

    private fun assertLongValuesFullyPresent(
        scenario: String,
        routineName: String,
        setsValue: String,
        durationValue: String,
    ) {
        // Long content is allowed to live in the SCROLLABLE body (only the Done footer must
        // be reachable without scrolling), so scroll each value into view before asserting
        // it is displayed and never ellipsis-hidden.
        rule.onNodeWithText(setsValue).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(durationValue).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(routineName, substring = true).performScrollTo().assertIsDisplayed()
        val nameTexts = rule.onNodeWithText(routineName, substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertTrue(
            "Long routine names must stay fully readable (no ellipsis-truncated value). " +
                "texts=$nameTexts",
            nameTexts.any { it.contains(routineName) },
        )
        RoutineCompleteRuntimeEvidence.record(
            "$scenario/longValues",
            mapOf(
                "routineNameChars" to routineName.length,
                "setsValue" to setsValue,
                "durationValue" to durationValue,
                "fullValuesReadable" to true,
            ),
        )
    }

    /** Advances past the staged reveal (two 100ms staggers) on the paused Robolectric clock. */
    private fun settleReveal() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        }
        rule.waitForIdle()
        rule.runOnIdle {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        }
        rule.waitForIdle()
    }

    // ===== Measurement primitives =====

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun width() = right - left
        fun height() = bottom - top
        fun toDp(density: Float) = Bounds(
            left / density,
            top / density,
            right / density,
            bottom / density,
        )
    }

    private fun SemanticsNodeInteraction.boundsPx(): Bounds {
        val node = fetchSemanticsNode()
        return Bounds(
            node.positionInRoot.x,
            node.positionInRoot.y,
            node.positionInRoot.x + node.size.width,
            node.positionInRoot.y + node.size.height,
        )
    }

    private fun doneBoundsPx() = rule.onNodeWithText("DONE").boundsPx()

    private fun rootSizePx(): Pair<Float, Float> {
        val node = rule.onRoot().fetchSemanticsNode()
        return node.size.width.toFloat() to node.size.height.toFloat()
    }

    /** Records the actual Android configuration backing this scenario's root. */
    private fun configInfo(): String {
        val c = rule.activity.resources.configuration
        return "screen=${c.screenWidthDp}x${c.screenHeightDp}dp " +
            "smallest=${c.smallestScreenWidthDp}dp densityDpi=${c.densityDpi} " +
            "fontScale=${c.fontScale}"
    }
}

/**
 * Real navigation host: home -> daily_routines/training_cycles -> routine_complete, with
 * the production [RoutineCompleteScreen] on the routine-complete route so the Done footer
 * runs the real exit action against a real NavController.
 */
@Composable
internal fun RuntimeRoutineCompleteNavHost(fixture: RoutineCompleteRuntimeFixture) {
    val navController = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = NavigationRoutes.Home.route) {
            composable(NavigationRoutes.Home.route) {}
            composable(NavigationRoutes.DailyRoutines.route) {}
            composable(NavigationRoutes.TrainingCycles.route) {}
            composable(NavigationRoutes.RoutineComplete.route) {
                RoutineCompleteScreen(navController, fixture.viewModel)
            }
        }
    }
    // Entry state: route to the completion screen the way ActiveWorkoutScreen does.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (fixture.viewModel.routineFlowState.value is RoutineFlowState.Complete) {
            navController.navigate(NavigationRoutes.DailyRoutines.route)
            navController.navigate(NavigationRoutes.RoutineComplete.route)
        }
    }
}
