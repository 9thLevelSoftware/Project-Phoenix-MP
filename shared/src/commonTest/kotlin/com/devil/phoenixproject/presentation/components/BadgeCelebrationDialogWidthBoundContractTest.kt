package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #1403: source contract for the batched badge dialog's finite width bound.
 *
 * The TestFlight crash (EXC_CRASH SIGABRT) came from measuring the badge dialog's
 * LazyVerticalGrid under maxWidth == Constraints.Infinity (the iOS wrap-content
 * dialog layer with usePlatformDefaultWidth = false), where fillMaxWidth cannot
 * bound the grid and CMP 1.12.2 rememberColumnWidthSums throws. This contract pins
 * the remediation shape without a Compose UI test dependency:
 * - the finite layout budget is computed before the Dialog (outside the unbounded
 *   dialog layer) from LocalWindowSizeClass raw dimensions + safe-drawing insets,
 * - invalid metrics defer rendering before any dismissal/celebration callback,
 * - the Card gets the finite width before the grid is measured,
 * - no requiredWidth, no dialog-local BoxWithConstraints, and the grid is never
 *   nested under a vertical scroller (only the details block scrolls, locally).
 */
class BadgeCelebrationDialogWidthBoundContractTest {

    private val sourcePath =
        "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/BadgeCelebrationDialog.kt"

    private fun readDialogSource(): String {
        val src = readProjectFile(sourcePath)
        assertNotNull(src, "Could not read $sourcePath (badge dialog source).")
        return src
    }

    @Test
    fun budgetIsComputedFromLiveMetricsOutsideTheDialog() {
        val src = readDialogSource()
        val budgetIdx = src.indexOf("badgeCelebrationLayoutBudget(")
        val dialogIdx = src.indexOf("    Dialog(")
        assertTrue(budgetIdx >= 0, "layout budget seam must be used.")
        assertTrue(
            dialogIdx > budgetIdx,
            "The layout budget must be computed before the Dialog so metrics come " +
                "from outside the unbounded dialog layer.",
        )
        assertTrue(
            src.contains("LocalWindowSizeClass.current"),
            "Live window metrics must come from LocalWindowSizeClass raw dimensions.",
        )
        assertTrue(
            src.contains("WindowInsets.safeDrawing.asPaddingValues()"),
            "Insets must be read from safeDrawing and subtracted exactly once.",
        )
    }

    @Test
    fun invalidMetricsDeferBeforeAnyCallback() {
        val src = readDialogSource()
        val deferIdx = src.indexOf("if (layoutBudget == null) return")
        assertTrue(deferIdx >= 0, "Invalid metrics must defer rendering via a null budget.")
        val firstCallbackIdx = listOf(
            src.indexOf("onMarkAllCelebrated("),
            src.indexOf("onDismiss()"),
            src.indexOf("onSoundTrigger()"),
        ).filter { it >= 0 }.min()
        assertTrue(
            firstCallbackIdx > deferIdx,
            "The defer return must precede every dismissal/celebration/sound callback " +
                "so queued badges are neither dismissed nor marked celebrated.",
        )
    }

    @Test
    fun cardWidthIsBoundBeforeGridMeasurement() {
        val src = readDialogSource()
        val cardWidthIdx = src.indexOf(".width(budget.cardWidth)")
        val gridIdx = src.indexOf("LazyVerticalGrid(")
        assertTrue(cardWidthIdx >= 0, "The Card must take its finite width from the budget.")
        assertTrue(
            gridIdx > cardWidthIdx,
            "The finite Card width must be set before the grid is measured.",
        )
        assertTrue(
            src.contains(".heightIn(max = gridHeight)"),
            "The grid must keep its finite height bound.",
        )
    }

    @Test
    fun noForbiddenWidthTricks() {
        val src = readDialogSource()
        assertTrue(
            !Regex("""requiredWidth(In)?\s*\(""").containsMatchIn(src),
            "requiredWidth must not be used.",
        )
        assertTrue(
            !Regex("""BoxWithConstraints\s*\(""").containsMatchIn(src),
            "No dialog-local BoxWithConstraints: its constraints are unbounded on iOS.",
        )
        assertTrue(
            !src.contains("try {") && !src.contains("catch ("),
            "Layout exceptions must not be suppressed — the width bound is the fix.",
        )
    }

    @Test
    fun gridIsNeverNestedUnderAVerticalScroller() {
        val src = readDialogSource()
        // The scroller usage (not the import) must wrap the details block only.
        val scrollIdx = src.indexOf(".verticalScroll(")
        val gridIdx = src.indexOf("LazyVerticalGrid(")
        val detailsIdx = src.indexOf("Expandable detail area")
        assertTrue(scrollIdx >= 0, "The details block keeps its local scroller.")
        assertTrue(
            scrollIdx > gridIdx && scrollIdx > detailsIdx,
            "The only vertical scroller must wrap the details block, after the grid.",
        )
        // Exactly one scroller: the grid itself must not gain one.
        val occurrences = src.windowed(".verticalScroll(".length).count { it == ".verticalScroll(" }
        assertTrue(occurrences == 1, "Exactly one verticalScroll (details block), found $occurrences.")
    }

    @Test
    fun dialogShellAndCallbackContractPreserved() {
        val src = readDialogSource()
        assertTrue(
            src.contains("usePlatformDefaultWidth = false"),
            "The full-width dialog shell must be preserved.",
        )
        assertTrue(
            src.contains("dismissOnBackPress = true"),
            "System back must still dismiss.",
        )
        assertTrue(
            src.contains("dismissOnClickOutside = false"),
            "Scrim tap must not dismiss.",
        )
        // Public entry point keeps its source-compatible signature.
        val declaration = src.substringAfter("fun BatchedBadgeCelebrationDialog(")
        assertTrue(declaration.isNotEmpty(), "Public entry point must be preserved.")
        val paramsBlock = declaration.substringBefore(") {")
        val paramNames = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*:""")
            .findAll(paramsBlock)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            paramNames == listOf("badges", "onDismiss", "onMarkAllCelebrated", "onSoundTrigger"),
            "Public parameter list must stay source-compatible, found $paramNames.",
        )
    }
}
