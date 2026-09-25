package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #893: regression guard for the Android v1.0.3 measure-time crash
 * "Vertically scrollable component was measured with an infinity maximum height
 * constraints, which is disallowed."
 *
 * The crash is a deterministic measurement-constraint violation:
 *
 *   WorkoutTab.kt:380
 *     Column(Modifier.fillMaxSize()...verticalScroll(rememberScrollState()))  // outer scroll
 *       ... OVERLAYS `when (workoutState)` (WorkoutTab.kt:427)
 *         is WorkoutState.Resting -> RestTimerCard(...)                      // WorkoutTab.kt:594/607
 *
 *   RestTimerCard.kt body Column (crash node before this fix)
 *     Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))   // inner scroll
 *
 * A `verticalScroll` container measures its children with
 * `Constraints(maxHeight = Infinity)`, so any nested vertically scrollable
 * child runs `checkScrollableContainerConstraints` and throws. PR #787
 * (6cb3bf05, issue #582) added the inner scroll to RestTimerCard so the
 * equipment rack card would stay reachable on short screens; the parent
 * WorkoutTab scroll already provides that reachability, so the inner scroll
 * was both redundant and illegal.
 *
 * This test pins the measurement contract (the fix must FAIL before and PASS
 * after):
 * 1. RestTimerCard's body Column must NOT be vertically scrollable — no nested
 *    full-body scroll under the scrolling WorkoutTab column.
 * 2. WorkoutTab.kt keeps exactly one `.verticalScroll(...)` — it is the sole
 *    full-screen scroll owner.
 * 3. The OVERLAYS `when` (including the Resting branch and its RestTimerCard
 *    call) stays inside that scrolling Column — it must not be moved out as a
 *    workaround.
 * 4. EquipmentRackSelectionCard keeps its independently bounded local list
 *    scroll (`heightIn(max = 260.dp)` + `verticalScroll`) so rack items remain
 *    reachable without reintroducing a nested full-body scroll.
 *
 * ## Why source-level assertions instead of a Compose measurement test?
 *
 * Same boundary as [SetReadyScreenScrollWiringTest] and
 * [JustLiftScreenWeightSliderWiringTest]: the repo has no Compose UI /
 * Robolectric runtime harness for these screens (see SetReadyScreenScrollWiringTest
 * for the full rationale), and wiring one up is out of scope for issue #893.
 * The measurement semantics themselves were verified against the app's exact
 * Compose Multiplatform version (1.11.1) with an executed `runComposeUiTest`
 * harness mirroring the production modifier chains: the pre-fix hierarchy
 * throws the exact issue #893 IllegalStateException frame-for-frame, and the
 * post-fix hierarchy measures cleanly under bounded root constraints (evidence
 * in the bug recreation packet and the fix PR). When the repo gains a Compose
 * UI test harness for WorkoutTab, this guard should be replaced with a runtime
 * measurement assertion of the WorkoutTab Resting branch under a compact
 * bounded root.
 */
class WorkoutTabRestingScrollMeasurementContractTest {

    @Test
    fun restTimerCardBody_isNotNestedScrollable() {
        val src = stripComments(readRestTimerCardSource())

        // The crash node: a verticalScroll inside WorkoutTab's scrolling Column
        // is measured with infinite maxHeight and throws. This assertion FAILS
        // on the pre-fix source (the body Column had
        // `.verticalScroll(rememberScrollState())`) and passes after the fix.
        assertTrue(
            !src.contains(".verticalScroll("),
            "RestTimerCard.kt must not apply Modifier.verticalScroll(...) anywhere: it is " +
                "composed inside WorkoutTab's vertically scrolling Column (WorkoutTab.kt:380), " +
                "which measures children with infinite maxHeight. A nested scrollable throws " +
                "\"Vertically scrollable component was measured with an infinity maximum height " +
                "constraints\" at measure time (issue #893). WorkoutTab's scroll is the sole " +
                "full-screen scroll owner.",
        )
        assertTrue(
            !src.contains("rememberScrollState"),
            "RestTimerCard.kt must not hoist rememberScrollState() after the issue #893 fix: " +
                "with no scrollable in this composable the state is unused and its presence " +
                "signals a reintroduced nested scroll.",
        )
    }

    @Test
    fun workoutTab_isSoleFullScreenScrollOwner() {
        val src = stripComments(readWorkoutTabSource())

        val scrollCount = Regex("""\.verticalScroll\(""").findAll(src).count()
        assertTrue(
            scrollCount == 1,
            "WorkoutTab.kt must contain exactly one .verticalScroll(...) call (the full-screen " +
                "scroll owner at the body Column). Found $scrollCount. Issue #893: nested " +
                "full-body scrolls throw at measure time; scroll ownership must stay singular.",
        )
        assertTrue(
            src.contains(".verticalScroll(rememberScrollState())"),
            "WorkoutTab.kt body Column must keep `.verticalScroll(rememberScrollState())` as the " +
                "sole full-screen scroll owner (issue #893 fix direction: keep WorkoutTab.kt:380).",
        )
    }

    @Test
    fun restingOverlayStaysInsideScrollingColumn() {
        val src = stripComments(readWorkoutTabSource())

        val scrollIdx = src.indexOf(".verticalScroll(")
        val restingIdx = src.indexOf("is WorkoutState.Resting ->")
        val restCardIdx = src.indexOf("RestTimerCard(")
        assertTrue(scrollIdx >= 0, "WorkoutTab.kt must keep its full-screen .verticalScroll(...).")
        assertTrue(
            restingIdx >= 0 && restCardIdx >= 0,
            "WorkoutTab.kt must keep the WorkoutState.Resting branch and its RestTimerCard(...) " +
                "call site (issue #893: the Resting overlay renders inside the scrolling Column).",
        )
        assertTrue(
            scrollIdx < restingIdx && restingIdx < restCardIdx,
            "The OVERLAYS `when` Resting branch and RestTimerCard(...) call must remain inside " +
                "WorkoutTab's scrolling Column (after the .verticalScroll(...) modifier). Do not " +
                "move the in-column OVERLAYS `when` out of the scrolling Column as a workaround " +
                "(issue #893 architecture review binding constraint).",
        )
    }

    @Test
    fun equipmentRackListScrollRemainsBounded() {
        val src = stripComments(readEquipmentRackSelectionCardSource())

        // The rack card's own list scroll is legal because heightIn(max = 260.dp)
        // gives it a finite maxHeight — unlike the removed RestTimerCard body
        // scroll. Pin the bounded pair so a future "cleanup" cannot either drop
        // the bound (crash) or drop the local scroll (rack items unreachable).
        assertTrue(
            src.contains(".heightIn(max = 260.dp)"),
            "EquipmentRackSelectionCard.kt must keep its bounded rack list height " +
                "`.heightIn(max = 260.dp)` (issue #893 architecture review binding constraint).",
        )
        val boundIdx = src.indexOf(".heightIn(max = 260.dp)")
        val scrollIdx = src.indexOf(".verticalScroll(", boundIdx)
        assertTrue(
            scrollIdx >= 0 && scrollIdx - boundIdx < 120,
            "EquipmentRackSelectionCard.kt must keep its local rack list scroll " +
                "`.verticalScroll(rememberScrollState())` immediately after the " +
                "heightIn(max = 260.dp) bound — the bounded local scroll is what keeps rack " +
                "items reachable without a nested full-body scroll (issue #893).",
        )
    }

    /**
     * Strip line and block comments so documentation explaining the fix (which
     * necessarily mentions `verticalScroll`) does not trip the guards — same
     * technique as SetReadyScreenScrollWiringTest.
     */
    private fun stripComments(src: String): String = src
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun readWorkoutTabSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt",
        )
        assertNotNull(src, "Could not locate WorkoutTab.kt on disk (issue #893 test dependency).")
        return src
    }

    private fun readRestTimerCardSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt",
        )
        assertNotNull(src, "Could not locate RestTimerCard.kt on disk (issue #893 test dependency).")
        return src
    }

    private fun readEquipmentRackSelectionCardSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EquipmentRackSelectionCard.kt",
        )
        assertNotNull(
            src,
            "Could not locate EquipmentRackSelectionCard.kt on disk (issue #893 test dependency).",
        )
        return src
    }
}
