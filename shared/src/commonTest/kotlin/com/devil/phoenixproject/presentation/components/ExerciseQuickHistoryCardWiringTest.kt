package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #671: Source-level wiring tests for ExerciseQuickHistoryCard.
 *
 * These tests pin the composable's structure, styling, and behavior at the source
 * level so a future refactor cannot accidentally break the integration contract
 * with SetReadyScreen.
 *
 * When the repo gains a Compose UI test harness for SetReadyScreen, these guards
 * should be replaced with semantic assertions on the rendered composition.
 */
class ExerciseQuickHistoryCardWiringTest {

    // ── Packet 2: Composable contract ──────────────────────────────────────

    @Test
    fun historyCard_emptySessionsGuard() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("if (sessions.isEmpty()) return"),
            "ExerciseQuickHistoryCard must return immediately when sessions is empty " +
                "(no card rendered, no empty state).",
        )
    }

    @Test
    fun historyCard_collapsibleDefaultThreeSessions() {
        val src = readHistoryCardSource()
        // Default shows 3 sessions, expandable to all (up to 5)
        assertTrue(
            src.contains("sessions.take(3)") || src.contains("take(3)"),
            "ExerciseQuickHistoryCard must show 3 sessions by default (collapsed state).",
        )
        assertTrue(
            src.contains("expanded") && (src.contains("mutableStateOf(false)") || src.contains("mutableStateOf(value = false)")),
            "ExerciseQuickHistoryCard must use expand/collapse state defaulting to collapsed.",
        )
    }

    @Test
    fun historyCard_expandCollapseIcon() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("KeyboardArrowDown"),
            "ExerciseQuickHistoryCard must show a down arrow when collapsed.",
        )
        assertTrue(
            src.contains("KeyboardArrowUp"),
            "ExerciseQuickHistoryCard must show an up arrow when expanded.",
        )
    }

    @Test
    fun historyCard_headerText() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("RECENT SESSIONS"),
            "ExerciseQuickHistoryCard header must display 'RECENT SESSIONS'.",
        )
    }

    @Test
    fun historyCard_cardStyling() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("surfaceContainerHighest"),
            "ExerciseQuickHistoryCard must use surfaceContainerHighest background " +
                "to match SET CONFIGURATION card.",
        )
        assertTrue(
            src.contains("MaterialTheme.shapes.medium"),
            "ExerciseQuickHistoryCard must use MaterialTheme.shapes.medium.",
        )
    }

    @Test
    fun historyCard_dateFormatting() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("MMM dd"),
            "ExerciseQuickHistoryCard must format dates as 'MMM dd' (e.g., 'Jul 16').",
        )
        assertTrue(
            src.contains("formatTimestamp"),
            "ExerciseQuickHistoryCard must use KmpUtils.formatTimestamp for date display.",
        )
    }

    @Test
    fun historyCard_weightDisplay() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("formatWeight"),
            "ExerciseQuickHistoryCard must use the formatWeight callback for weight display.",
        )
        assertTrue(
            src.contains("weightUnit"),
            "ExerciseQuickHistoryCard must respect weightUnit parameter.",
        )
    }

    @Test
    fun historyCard_repsDisplay() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("workingReps") && src.contains("reps"),
            "ExerciseQuickHistoryCard must display workingReps with 'reps' suffix.",
        )
    }

    @Test
    fun historyCard_durationFormatting() {
        val src = readHistoryCardSource()
        // Duration must be formatted as M:SS
        assertTrue(
            src.contains("formatDuration"),
            "ExerciseQuickHistoryCard must have a formatDuration helper for M:SS display.",
        )
        assertTrue(
            src.contains("durationMs") || src.contains("duration"),
            "ExerciseQuickHistoryCard must reference duration field.",
        )
        assertTrue(
            src.contains("padStart(2, '0')"),
            "Duration formatting must pad seconds to 2 digits (e.g., '0:42' not '0:2').",
        )
    }

    @Test
    fun historyCard_durationOmittedWhenZero() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("duration > 0"),
            "ExerciseQuickHistoryCard must omit duration when session.duration is 0.",
        )
    }

    @Test
    fun historyCard_columnLayout() {
        val src = readHistoryCardSource()
        // Must have Date, Weight, Reps columns with weight(1f)
        assertTrue(
            src.contains("weight(1f)"),
            "ExerciseQuickHistoryCard must use Modifier.weight(1f) for column layout.",
        )
        assertTrue(
            src.contains("weight(0.8f)"),
            "ExerciseQuickHistoryCard must use Modifier.weight(0.8f) for duration column.",
        )
    }

    @Test
    fun historyCard_accessibilitySemantics() {
        val src = readHistoryCardSource()
        assertTrue(
            src.contains("contentDescription"),
            "ExerciseQuickHistoryCard must provide contentDescription for accessibility.",
        )
        assertTrue(
            src.contains("Recent Sessions"),
            "ExerciseQuickHistoryCard header must have 'Recent Sessions' in contentDescription.",
        )
    }

    @Test
    fun historyCard_sessionKeyedExpandState() {
        val src = readHistoryCardSource()
        // expand/collapse state must reset when sessions change (exercise navigation)
        assertTrue(
            src.contains("key(sessions)") || src.contains("remember(sessions)"),
            "ExerciseQuickHistoryCard must key expand/collapse state on sessions " +
                "so it resets when exercise changes.",
        )
    }

    private fun readHistoryCardSource(): String {
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseQuickHistoryCard.kt"
        val src = readProjectFile(relativePath)
        assertNotNull(
            src,
            "Could not locate ExerciseQuickHistoryCard.kt on disk. The test relies on the project " +
                "root being discoverable from the test runner's working directory.",
        )
        return src
    }
}
