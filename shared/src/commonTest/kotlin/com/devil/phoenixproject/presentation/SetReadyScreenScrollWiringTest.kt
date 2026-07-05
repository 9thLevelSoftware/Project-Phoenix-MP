package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #582: regression guard for the "Equipment Rack card is missing on cable
 * exercises" SetReady layout overflow bug.
 *
 * The original symptom was that the new collapsible Equipment Rack card appeared
 * to be missing from cable exercises, but the code-path inspection proved the
 * card was always rendered — it was simply pushed below the visible viewport on
 * small portrait phones because the SetReadyScreen body Column lacked
 * `verticalScroll` and the cable-only SET CONFIGURATION / ECHO SETTINGS card
 * consumed the remaining height.
 *
 * This test pins the wiring that the fix introduced:
 * 1. The SetReady body Column must be vertically scrollable.
 * 2. The body Column must NOT use `Arrangement.SpaceBetween` (it forces
 *    everything but the first and last child to collapse, which makes the rack
 *    card invisible when content overflows the viewport).
 * 3. The trailing `Spacer(Modifier.weight(1f))` must be gone — `Modifier.weight`
 *    is illegal inside a scrollable Column and would throw at composition.
 * 4. `EquipmentRackSelectionCard` must be invoked with a `modifier = Modifier
 *    .testTag(SetReadyTestTags.RACK_CARD)` argument so a future Compose UI /
 *    screenshot test can verify the rack node is reachable on a phone-sized
 *    portrait screen with a cable exercise selected.
 *
 * ## Why source-level assertions instead of Compose UI tests?
 *
 * The repo's existing precedent for this exact situation is
 * [JustLiftScreenWeightSliderWiringTest] (Issue #571). That test was added
 * intentionally instead of a Compose UI test because:
 * 1. The bug class is "the widget is wired wrong" — these are source-level
 *    invariants. A Compose UI test would still pass if someone reverted the
 *    `.verticalScroll` wrapper (the rack card would just be off-screen again,
 *    exactly the original bug).
 * 2. Wiring up Compose UI / Robolectric runtime for SetReadyScreen is out of
 *    scope for issue #582 — the same boundary JustLiftScreenWeightSliderWiringTest
 *    documents. When the repo gains the harness, this guard can be replaced with
 *    `composeTestRule.onNodeWithTag(SetReadyTestTags.RACK_CARD).assertExists()`
 *    + scroll assertions.
 * 3. The assertions are scoped to a small set of exact string checks. A future
 *    refactor that legitimately restructures SetReadyScreen will need to update
 *    these strings — that is the intended failure mode.
 *
 * When the repo gains a Compose UI test harness for SetReadyScreen, this guard
 * should be replaced with semantic assertions on the rendered composition (e.g.
 * `composeTestRule.onNodeWithTag(SetReadyTestTags.RACK_CARD).performScrollTo()
 * .assertIsDisplayed()`).
 */
class SetReadyScreenScrollWiringTest {

    @Test
    fun setReadyBody_isVerticallyScrollable() {
        val src = readSetReadyScreenSource()

        // The body Column inside the Scaffold content lambda must be wrapped with
        // verticalScroll, driven by a hoisted rememberScrollState(). A bare
        // rememberScrollState() is not enough — the .verticalScroll(...) modifier
        // call must actually be applied to the body Column. Pin both.
        assertTrue(
            src.contains("rememberScrollState()"),
            "SetReadyScreen.kt must hoist a rememberScrollState() inside the Scaffold content " +
                "lambda so the body Column can scroll. Without it the Equipment Rack card is " +
                "pushed off-screen on small portrait phones (issue #582).",
        )
        assertTrue(
            src.contains(".verticalScroll("),
            "SetReadyScreen.kt must apply Modifier.verticalScroll(...) to the body Column " +
                "so the rack card is reachable when the cable SET CONFIGURATION card overflows " +
                "the viewport (issue #582).",
        )
    }

    @Test
    fun setReadyBody_doesNotUseSpaceBetween() {
        val src = readSetReadyScreenSource()

        // Arrangement.SpaceBetween on the body Column hides everything but the
        // first and last child when content overflows the viewport — exactly the
        // original symptom. The fix removes it; pin that here.
        assertTrue(
            !src.contains("verticalArrangement = Arrangement.SpaceBetween"),
            "SetReadyScreen.kt body Column must not use Arrangement.SpaceBetween. " +
                "It collapses mid-content (e.g. the Equipment Rack card) on overflow, which " +
                "reproduces issue #582.",
        )
    }

    @Test
    fun setReadyBody_doesNotUseWeightSpacer() {
        val src = readSetReadyScreenSource()

        // Modifier.weight(1f) is illegal inside a vertically-scrollable Column
        // (Compose throws IllegalStateException at composition). The original
        // SetReadyScreen had a trailing `Spacer(Modifier.weight(1f))` after the
        // content column to push everything above the bottomBar; the scroll
        // wrapper makes that spacer unnecessary, and the weight modifier must
        // be gone from the body Column scope.
        //
        // The implementation file is allowed to mention `Modifier.weight(...)`
        // inside a comment that documents why the call site was removed, and
        // other components in the same file (e.g. EchoLevelPillSelector's
        // inner Row) are allowed to keep horizontal `Modifier.weight(1f)` —
        // those are legal in Row scopes. So this test checks only the body
        // Column's children (the `Column { ... }` block inside the Scaffold
        // content lambda) for the forbidden pattern.
        val bodyColumnOpenIdx = src.indexOf(".verticalScroll(")
        assertTrue(
            bodyColumnOpenIdx >= 0,
            "Could not locate the scrollable body Column in SetReadyScreen.kt. " +
                "Issue #582 requires the body Column to be wrapped in verticalScroll; " +
                "this test depends on that wiring being present.",
        )
        // Find the Column lambda body: skip past `.verticalScroll(...)` and read
        // until the matching closing `}` of the Column body. Use a simple
        // brace-balance search bounded by the file end. We start at the index
        // of the body Column's `Column(` call, which precedes the body content.
        val columnCallIdx = src.lastIndexOf("Column(", bodyColumnOpenIdx)
        assertTrue(
            columnCallIdx >= 0,
            "Could not locate the body Column(...) call in SetReadyScreen.kt.",
        )
        val fromColumnCall = src.substring(columnCallIdx)
        // Find the Column lambda body: scan from after `Column(` to the matching
        // close paren, then the `{ ... }` body. Heuristic: locate the first `{`
        // after the closing `)` of `Column(...)` arguments, then bracket-count
        // braces until balanced. This is a source-level layout test, not a parser.
        val openParenIdx = fromColumnCall.indexOf('(')
        val argStart = openParenIdx + 1
        var depth = 1
        var argEnd = -1
        for (i in argStart until fromColumnCall.length) {
            when (fromColumnCall[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        argEnd = i
                        break
                    }
                }
            }
        }
        assertTrue(
            argEnd >= 0,
            "Could not locate the end of the body Column(...) argument list.",
        )
        val bodyStart = fromColumnCall.indexOf('{', argEnd)
        assertTrue(
            bodyStart >= 0,
            "Could not locate the body Column's opening '{' after its argument list.",
        )
        var bodyDepth = 1
        var bodyEnd = -1
        for (i in bodyStart + 1 until fromColumnCall.length) {
            when (fromColumnCall[i]) {
                '{' -> bodyDepth++
                '}' -> {
                    bodyDepth--
                    if (bodyDepth == 0) {
                        bodyEnd = i
                        break
                    }
                }
            }
        }
        assertTrue(
            bodyEnd >= 0,
            "Could not locate the body Column's closing '}'.",
        )
        val columnBody = fromColumnCall.substring(bodyStart + 1, bodyEnd)
        // Strip line comments and block comments before regex matching so the
        // documentation comment that explains *why* the spacer was removed does
        // not trip the regression guard.
        val strippedColumnBody = columnBody
            .replace(Regex("//[^\\n]*"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        assertTrue(
            !Regex("Spacer\\s*\\(\\s*Modifier\\.weight\\(").containsMatchIn(strippedColumnBody),
            "SetReadyScreen.kt body Column must not contain Spacer(Modifier.weight(...)). " +
                "weight is illegal inside a vertically-scrollable Column and throws at " +
                "composition. See issue #582 fix direction.",
        )
    }

    @Test
    fun equipmentRackCard_isTaggedForRegressionTests() {
        val src = readSetReadyScreenSource()

        // The rack card call site in SetReadyScreen must pass the regression tag so a
        // future Compose UI / screenshot test can assert reachability on cable
        // exercises without depending on the card's content text.
        assertTrue(
            src.contains("testTag(SetReadyTestTags.RACK_CARD)"),
            "EquipmentRackSelectionCard(...) call in SetReadyScreen.kt must pass " +
                "`modifier = Modifier.testTag(SetReadyTestTags.RACK_CARD)` so issue #582 " +
                "can be guarded by a Compose UI / screenshot test that verifies the rack " +
                "card is reachable by scrolling.",
        )
        assertTrue(
            src.contains("object SetReadyTestTags"),
            "SetReadyScreen.kt must define an object SetReadyTestTags that owns the " +
                "regression tag constants (currently RACK_CARD).",
        )
        assertTrue(
            src.contains("\"set_ready_rack_card\""),
            "SetReadyTestTags.RACK_CARD must stringify to \"set_ready_rack_card\" so " +
                "Compose UI / screenshot tests have a stable identifier.",
        )
    }

    @Test
    fun setReadyProgressionControl_isTaggedForRegressionTests() {
        val src = readSetReadyScreenSource()

        assertTrue(
            src.contains("testTag(SetReadyTestTags.PROGRESSION_CONTROL)"),
            "SetReadyScreen.kt must tag the Weight Change / Rep control so issue #604 can be guarded by UI/screenshot tests.",
        )
        assertTrue(
            src.contains("\"set_ready_progression_control\""),
            "SetReadyTestTags.PROGRESSION_CONTROL must stringify to \"set_ready_progression_control\".",
        )
    }

    @Test
    fun setReadyProgressionControl_wiresToViewModel() {
        val src = readSetReadyScreenSource()

        assertTrue(
            src.contains("WeightChangePerRepControl"),
            "Cable non-Echo SetReady configuration must render WeightChangePerRepControl for issue #604.",
        )
        assertTrue(
            src.contains("valueKg = setReadyState.adjustedProgressionKg"),
            "SetReadyScreen.kt must seed Weight Change / Rep from RoutineFlowState.SetReady.adjustedProgressionKg.",
        )
        assertTrue(
            src.contains("viewModel.updateSetReadyProgressionKg(it)"),
            "SetReadyScreen.kt must route Weight Change / Rep edits through MainViewModel.updateSetReadyProgressionKg.",
        )
    }

    @Test
    fun setReadyConfiguration_appearsBeforeVideo() {
        val src = readSetReadyScreenSource()
        val configIdx = src.indexOf("if (isEchoMode) \"ECHO SETTINGS\" else \"SET CONFIGURATION\"")
        val rackIdx = src.indexOf("testTag(SetReadyTestTags.RACK_CARD)")
        val videoIdx = src.indexOf("Video thumbnail stays available")

        assertTrue(configIdx >= 0, "SetReady SET CONFIGURATION label must exist.")
        assertTrue(rackIdx >= 0, "SetReady equipment rack tag must exist.")
        assertTrue(videoIdx >= 0, "SetReady compact/lower video block must exist.")
        assertTrue(
            configIdx < rackIdx && rackIdx < videoIdx,
            "Issue #604 signed-off layout requires Set Configuration before Equipment Rack, with video moved below primary controls.",
        )
    }

    @Test
    fun equipmentRackCard_callSiteUsesIssueScopeComment() {
        val src = readSetReadyScreenSource()

        // Pin the existence of an "Issue #582" comment near the rack card call site
        // so future readers can trace the wiring back to the RCA. This is purely
        // documentation; failing here means the comment was accidentally removed
        // along with the bugfix.
        val rackCallIdx = src.indexOf("testTag(SetReadyTestTags.RACK_CARD)")
        assertTrue(
            rackCallIdx >= 0,
            "EquipmentRackSelectionCard call site with the regression tag must exist " +
                "in SetReadyScreen.kt (issue #582).",
        )
        // Look at a 600-char window centred on the call site (covers the trailing
        // modifier argument). The Issue #582 comment is on the line(s) directly
        // above the `modifier = Modifier.testTag(...)` argument.
        val windowStart = (rackCallIdx - 600).coerceAtLeast(0)
        val window = src.substring(windowStart, rackCallIdx)
        assertTrue(
            "Issue #582" in window,
            "EquipmentRackSelectionCard call site must carry an Issue #582 marker comment " +
                "so future readers can trace the wiring back to the RCA. " +
                "Window searched: ${window.takeLast(400)}",
        )
    }

    @Test
    fun setReadyBody_keepsBottomBarAnchored() {
        val src = readSetReadyScreenSource()

        // The Scaffold bottomBar must still wrap the PREV/START/NEXT/STOP row so
        // the action buttons stay anchored above the navigation bar. The fix must
        // not have moved the action buttons into the scrollable Column.
        assertTrue(
            src.contains("bottomBar = {"),
            "SetReadyScreen.kt must keep `bottomBar = { ... }` on the Scaffold so the " +
                "PREV / START / NEXT / STOP buttons stay anchored above the navigation bar " +
                "(issue #582 acceptance criteria).",
        )
        assertTrue(
            src.contains(".navigationBarsPadding()"),
            "SetReadyScreen.kt bottomBar Surface must still apply navigationBarsPadding() " +
                "so the action buttons clear the system gesture bar (issue #582 acceptance " +
                "criteria).",
        )
    }

    @Test
    fun setReadyBody_keepsEquipmentRackCallUnconditional() {
        val src = readSetReadyScreenSource()

        // The RCA proved that EquipmentRackSelectionCard is rendered unconditionally
        // for both cable and bodyweight exercises. The fix must NOT introduce an
        // `if (!isBodyweight)` gate around the rack card call — that would be a
        // different (and incorrect) interpretation of the reporter's symptom.
        // Pin:
        //   (a) exactly one real (non-import, non-comment) call site exists; AND
        //   (b) that call site sits outside any `if (!isBodyweight)` block — i.e.
        //       the rack card renders for cable AND bodyweight exercises.
        val callSite = firstRealCallSite(
            src = src,
            symbol = "EquipmentRackSelectionCard",
        )
        assertTrue(
            callSite != null,
            "SetReadyScreen.kt must contain exactly one real (non-import, non-comment) " +
                "EquipmentRackSelectionCard call site (issue #582). Found 0.",
        )
        assertNotNull(callSite)
        // Look at the 60 source-code (non-comment, non-blank) lines preceding the
        // call site. If we ever wrap the call in `if (!isBodyweight) { ... }`, one
        // of those preceding lines will contain that exact gate.
        val precedingSrc = src.substring(0, callSite.offset)
        val precedingNonCommentLines = precedingSrc
            .split('\n')
            .filter { line ->
                val t = line.trimStart()
                t.isNotEmpty() && !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")
            }
        val recent = precedingNonCommentLines.takeLast(60)
        assertTrue(
            recent.none { it.contains("if (!isBodyweight)") || it.contains("if (isBodyweight)") },
            "EquipmentRackSelectionCard call site must NOT sit inside an " +
                "`if (!isBodyweight) { ... }` (or `if (isBodyweight) { ... }`) block. " +
                "The rack card must render for both cable and bodyweight exercises. " +
                "Issue #582 fix direction.",
        )
    }

    /**
     * Find the first non-import, non-comment occurrence of `symbol(` in [src]
     * and return its source offset (or null if none). The heuristic scans the
     * file line-by-line, skipping imports, KDoc, and line/block comments before
     * matching, so a future comment that documents the symbol does not count.
     */
    private fun firstRealCallSite(src: String, symbol: String): CallSiteRef? {
        val importLines = src.lineSequence()
            .withIndex()
            .filter { it.value.trimStart().startsWith("import ") }
            .map { it.index }
            .toSet()
        val lines = src.lines()
        val commentLineSet = mutableSetOf<Int>()
        var inBlockComment = false
        lines.forEachIndexed { idx, raw ->
            val trimmed = raw.trimStart()
            if (inBlockComment) {
                commentLineSet += idx
                if (raw.contains("*/")) inBlockComment = false
                return@forEachIndexed
            }
            if (trimmed.startsWith("//")) {
                commentLineSet += idx
            } else if (raw.contains("/*")) {
                commentLineSet += idx
                if (!raw.contains("*/")) inBlockComment = true
            } else if (raw.contains("*/")) {
                commentLineSet += idx
                inBlockComment = false
            }
        }
        val pattern = Regex("$symbol\\s*\\(")
        var runningOffset = 0
        lines.forEachIndexed { idx, line ->
            if (idx in importLines || idx in commentLineSet) {
                runningOffset += line.length + 1 // +1 for newline
                return@forEachIndexed
            }
            val m = pattern.find(line)
            if (m != null) {
                return CallSiteRef(offset = runningOffset + m.range.first)
            }
            runningOffset += line.length + 1
        }
        return null
    }

    /** Reference to a real (non-import, non-comment) call site in a source file. */
    private data class CallSiteRef(val offset: Int)

    private fun readSetReadyScreenSource(): String {
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt"
        val src = readProjectFile(relativePath)
        assertNotNull(
            src,
            "Could not locate SetReadyScreen.kt on disk. The test relies on the project " +
                "root being discoverable from the test runner's working directory. " +
                "If you are running this from an unusual cwd, set the working directory " +
                "to the shared/ module's root before invoking the test.",
        )
        return src
    }
}
