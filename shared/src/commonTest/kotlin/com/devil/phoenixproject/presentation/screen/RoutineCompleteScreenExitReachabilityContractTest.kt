package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #1164: Routine Complete was a terminal dead end — the only exit (Done)
 * was measured last inside the same non-scrollable celebration layout (and the
 * same AnimatedVisibility reveal) as the trophy/title/stats content, while the
 * app hides all normal navigation chrome on this route and the iOS BackHandler
 * is a no-op. On content-starved roots the trailing button could be squeezed to
 * zero/hidden height, leaving no way out except force-exiting the app.
 *
 * Fix contract (RCA + architecture review binding constraints):
 * 1. A reserved always-visible Done footer exists OUTSIDE every AnimatedVisibility
 *    reveal block, as a sibling of the scroll body — never the final child inside
 *    the reveal or the scroll region.
 * 2. The celebration/stats body is the ONE weighted vertical-scroll sibling under
 *    a bounded (max-height) root column. No `requiredHeight`, fixed-spacer
 *    make-room hacks, global text shrinking, or animation-delay tweaks.
 * 3. The exit action order is preserved everywhere: routineExitDestination() →
 *    exitRoutineFlow() → safePopOrNavigate(dest) (destination read BEFORE the
 *    origin-clearing exitRoutineFlow()).
 * 4. Stats cells have balanced widths at ordinary sizes and stack/reflow at
 *    narrow widths or enlarged accessibility text; full values stay readable —
 *    no maxLines=1 ellipsis hiding totals or duration.
 * 5. Safe-area padding stays on the root that contains BOTH footer and scroll
 *    body (the scaffold applies zero window insets on this route).
 *
 * ## Why source-level assertions instead of a Compose measurement test?
 *
 * Same boundary as WorkoutTabRestingScrollMeasurementContractTest and
 * SetReadyScreenScrollWiringTest: the repo has no Compose UI / Robolectric
 * runtime harness for these screens, and wiring one up is out of scope for
 * issue #1164. These guards pin the layout/exit-ownership structure that makes
 * exit reachability independent of content measurement; rendered reachability
 * (measured Done bounds, tap callback, iOS exit capture) remains a QA
 * acceptance item tracked on the issue.
 */
class RoutineCompleteScreenExitReachabilityContractTest {

    @Test
    fun doneFooter_isOutsideEveryAnimatedVisibilityReveal() {
        val src = stripComments(readRoutineCompleteScreenSource())
        val doneIdx = src.indexOf("label_done")
        assertTrue(doneIdx >= 0, "RoutineCompleteScreen.kt must keep the Done footer (label_done).")

        val revealRegions = callLambdaRegions(src, "AnimatedVisibility(")
        assertTrue(
            revealRegions.isNotEmpty(),
            "RoutineCompleteScreen.kt must keep its staged reveal (AnimatedVisibility) blocks.",
        )
        for (region in revealRegions) {
            assertTrue(
                doneIdx < region.first || doneIdx > region.last,
                "The Done footer must live OUTSIDE every AnimatedVisibility reveal block " +
                    "(issue #1164: the exit must exist immediately on entry, including while the " +
                    "reveal is animating and with Reduce Motion). Found label_done inside a reveal " +
                    "region [${region.first}, ${region.last}].",
            )
        }
    }

    @Test
    fun revealGatesCelebrationContentOnly() {
        val src = stripComments(readRoutineCompleteScreenSource())

        // Exactly the three celebration stages (icon, title, stats card). The old
        // bug put the Done button inside the third stage (stats + button cascade).
        val revealCount = Regex("""AnimatedVisibility\(""").findAll(src).count()
        assertTrue(
            revealCount == 3,
            "RoutineCompleteScreen.kt must keep exactly 3 AnimatedVisibility reveal blocks " +
                "(icon, title, stats card). Found $revealCount. Issue #1164: the Done footer must " +
                "not be gated by any reveal flag.",
        )

        // Every reference to the reveal flags must sit inside reveal content, i.e.
        // before the footer — the footer must not depend on icon/title/statsVisible.
        val doneIdx = src.indexOf("label_done")
        for (flag in listOf("iconVisible", "titleVisible", "statsVisible")) {
            val lastIdx = src.lastIndexOf(flag)
            assertTrue(
                lastIdx in 0 until doneIdx,
                "Reveal flag '$flag' must not gate the Done footer (issue #1164): the footer is " +
                    "reserved outside the reveal and present immediately on entry.",
            )
        }
    }

    @Test
    fun body_isSingleWeightedVerticalScrollSibling() {
        val src = stripComments(readRoutineCompleteScreenSource())

        val scrollCount = Regex("""\.verticalScroll\(""").findAll(src).count()
        assertTrue(
            scrollCount == 1,
            "RoutineCompleteScreen.kt must contain exactly one .verticalScroll(...) — the single " +
                "celebration/stats body scroll region (issue #1164). Found $scrollCount.",
        )

        val scrollIdx = src.indexOf(".verticalScroll(")
        assertTrue(scrollIdx >= 0, "RoutineCompleteScreen.kt must keep its body .verticalScroll(...).")

        // The scroll region must be the weighted sibling under the bounded root
        // column (weight(1f) + fillMaxWidth in the body modifier chain), so the
        // footer keeps its height and the body takes only the remaining space.
        val chainStart = src.lastIndexOf("modifier = Modifier", scrollIdx)
        assertTrue(
            chainStart in 0 until scrollIdx,
            "The body scroll must be applied through a Modifier chain on the body column.",
        )
        val chain = src.substring(chainStart, scrollIdx)
        assertTrue(
            chain.contains("weight(1f)"),
            "The scrolling celebration body must use Modifier.weight(1f) under the bounded root " +
                "column so the reserved Done footer keeps its own height (issue #1164 binding " +
                "constraint: max-height column with one weighted vertical-scroll sibling).",
        )
        assertTrue(
            chain.contains("fillMaxWidth()"),
            "The scrolling celebration body must fill the root width (issue #1164).",
        )

        // Forbidden height hacks: the exit/content split must come from the
        // weighted scroll sibling, not from fixed-height reservations.
        for (forbidden in listOf("requiredHeight", "heightIn(min =", "requiredSize")) {
            assertTrue(
                !src.contains(forbidden),
                "RoutineCompleteScreen.kt must not use '$forbidden' to reserve space " +
                    "(issue #1164 binding constraint: use a max-height column with one weighted " +
                    "vertical-scroll sibling instead).",
            )
        }
    }

    @Test
    fun exitActionOrder_isPreservedForDoneAndBack() {
        val src = stripComments(readRoutineCompleteScreenSource())

        // BackHandler block: mirrors Done.
        val backRegions = callLambdaRegions(src, "BackHandler {")
        assertTrue(backRegions.size == 1, "RoutineCompleteScreen.kt must keep exactly one BackHandler.")
        assertExitOrder(src.substring(backRegions[0].first, backRegions[0].last + 1), "BackHandler")

        // Footer Button block.
        val buttonRegions = callLambdaRegions(src, "Button(")
        val doneRegion = assertNotNull(
            buttonRegions.firstOrNull { region ->
                src.substring(region.first, region.last + 1).contains("label_done")
            },
            "RoutineCompleteScreen.kt must keep the Done Button footer.",
        )
        assertExitOrder(
            src.substring(doneRegion.first, doneRegion.last + 1),
            "Done footer",
        )
    }

    @Test
    fun statsCells_useBalancedWidthsAndReflowStacking() {
        val src = stripComments(readRoutineCompleteScreenSource())

        // Balanced widths: the ordinary-size row gives every stat cell an equal
        // weight(1f) share instead of letting early cells starve the duration cell.
        val weightedStatCalls = Regex("""StatItem\(""").findAll(src).count {
            val tail = src.substring(it.range.first, minOf(src.length, it.range.first + 400))
            tail.contains("Modifier.weight(1f)")
        }
        assertTrue(
            weightedStatCalls >= 3,
            "All three stat cells must use Modifier.weight(1f) balanced widths at ordinary sizes " +
                "(issue #1164 stat-row wrap defect: the duration cell must not receive only " +
                "residual width). Found $weightedStatCalls weighted StatItem calls.",
        )

        // Reflow path for narrow roots / enlarged accessibility text.
        assertTrue(
            src.contains("BoxWithConstraints"),
            "The stats card must branch on available width (BoxWithConstraints) to stack/reflow " +
                "stats at narrow widths (issue #1164).",
        )
        assertTrue(
            src.contains("fontScale"),
            "The stats card must account for accessibility text scale when choosing the stacked " +
                "layout so full values stay readable (issue #1164).",
        )

        // Values must never be hidden behind a single-line ellipsis.
        assertTrue(
            !src.contains("maxLines = 1"),
            "Stat values/labels must not be truncated with maxLines = 1 ellipsis: the full " +
                "exercise/set totals and duration must remain readable (issue #1164).",
        )

        // StatItem must accept a caller-supplied modifier (needed for weight/fill).
        val statItemDef = src.indexOf("fun StatItem(")
        assertTrue(statItemDef >= 0, "RoutineCompleteScreen.kt must keep its StatItem composable.")
        val statItemBody = src.substring(statItemDef, minOf(src.length, statItemDef + 400))
        assertTrue(
            statItemBody.contains("modifier: Modifier"),
            "StatItem must accept a Modifier parameter so cells can take balanced widths " +
                "(issue #1164).",
        )
    }

    @Test
    fun safeAreaPaddingStaysOnRootContainingFooterAndBody() {
        val src = stripComments(readRoutineCompleteScreenSource())

        val paddingIdx = src.indexOf(".systemBarsPadding()")
        val scrollIdx = src.indexOf(".verticalScroll(")
        val doneIdx = src.indexOf("label_done")
        assertTrue(
            paddingIdx in 0 until scrollIdx && scrollIdx in 0 until doneIdx,
            "Safe-area padding (.systemBarsPadding()) must stay on the root that contains BOTH the " +
                "scroll body and the Done footer (the scaffold applies zero window insets on the " +
                "routine-complete route; issue #1164 binding constraint).",
        )
    }

    /** Asserts routineExitDestination() → exitRoutineFlow() → safePopOrNavigate(dest) order. */
    private fun assertExitOrder(region: String, where: String) {
        val destIdx = region.indexOf("routineExitDestination()")
        val exitIdx = region.indexOf("exitRoutineFlow()")
        val navIdx = region.indexOf("safePopOrNavigate(")
        assertTrue(
            destIdx in 0 until exitIdx && exitIdx in 0 until navIdx,
            "$where must keep the exit action order: routineExitDestination() first, then " +
                "exitRoutineFlow(), then safePopOrNavigate(dest) (issue #1164: exitRoutineFlow() " +
                "clears the launch origin, so the destination must be read first). " +
                "Found offsets dest=$destIdx exit=$exitIdx nav=$navIdx.",
        )
    }

    /**
     * Regions (inclusive index ranges) of each call's lambda body for every
     * occurrence of [callPrefix]: balanced-paren match for the call arguments,
     * then balanced-brace match for the trailing lambda. Used to prove the Done
     * footer is not nested inside any reveal/intercept block.
     */
    private fun callLambdaRegions(src: String, callPrefix: String): List<IntRange> {
        val regions = mutableListOf<IntRange>()
        var searchFrom = 0
        while (true) {
            val callIdx = src.indexOf(callPrefix, searchFrom)
            if (callIdx < 0) break
            searchFrom = callIdx + callPrefix.length
            var i = callIdx + callPrefix.length - 1 // at '(' or '{'
            if (src[i] == '{') {
                val end = matchDelimiter(src, i, '{', '}') ?: continue
                regions.add(callIdx..end)
                continue
            }
            val argsEnd = matchDelimiter(src, i, '(', ')') ?: continue
            val braceStart = src.indexOf('{', argsEnd)
            if (braceStart < 0) continue
            val end = matchDelimiter(src, braceStart, '{', '}') ?: continue
            regions.add(callIdx..end)
        }
        return regions
    }

    /** Index of the delimiter closing the one opened at [openIdx], or null. */
    private fun matchDelimiter(src: String, openIdx: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in openIdx until src.length) {
            when (src[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * Strip line and block comments so documentation explaining the fix (which
     * necessarily mentions AnimatedVisibility/verticalScroll) does not trip the
     * guards — same technique as WorkoutTabRestingScrollMeasurementContractTest.
     */
    private fun stripComments(src: String): String = src
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun readRoutineCompleteScreenSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineCompleteScreen.kt",
        )
        assertNotNull(
            src,
            "Could not locate RoutineCompleteScreen.kt on disk (issue #1164 test dependency).",
        )
        return src
    }
}
