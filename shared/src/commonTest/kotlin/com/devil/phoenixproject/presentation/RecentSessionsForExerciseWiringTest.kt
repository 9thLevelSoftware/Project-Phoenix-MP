package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #671: Source-level wiring tests for the recentSessionsForExercise ViewModel method.
 *
 * These tests pin the method's filtering, sorting, and StateFlow contract so a future
 * refactor cannot accidentally change the data pipeline without breaking these guards.
 *
 * When the repo gains a full ViewModel test harness with fakes, these source-level
 * assertions should be replaced with behavioral unit tests.
 */
class RecentSessionsForExerciseWiringTest {

    // ── Packet 1: ViewModel method contract ────────────────────────────────

    @Test
    fun recentSessions_methodExists() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("fun recentSessionsForExercise("),
            "MainViewModel must have a recentSessionsForExercise method.",
        )
    }

    @Test
    fun recentSessions_filtersByProfileAndExercise() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("it.profileId == profileId") && src.contains("it.exerciseId == exerciseId"),
            "recentSessionsForExercise must filter by both profileId and exerciseId.",
        )
    }

    @Test
    fun recentSessions_sortedByTimestampDesc() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("compareByDescending<WorkoutSession> { it.timestamp }"),
            "recentSessionsForExercise must sort by timestamp descending (most recent first).",
        )
        assertTrue(
            src.contains("thenByDescending { it.id }"),
            "recentSessionsForExercise must use id as secondary sort key for stable ordering.",
        )
    }

    @Test
    fun recentSessions_limitsToN() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains(".take(limit)"),
            "recentSessionsForExercise must respect the limit parameter via .take(limit).",
        )
        assertTrue(
            src.contains("limit: Int = 5"),
            "recentSessionsForExercise must default to 5 sessions.",
        )
    }

    @Test
    fun recentSessions_excludesZeroReps() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("it.workingReps > 0 || it.totalReps > 0"),
            "recentSessionsForExercise must exclude sessions with both workingReps == 0 and totalReps == 0.",
        )
    }

    @Test
    fun recentSessions_emptyWhenNullIds() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("profileId == null") && src.contains("exerciseId == null"),
            "recentSessionsForExercise must return empty list when profileId or exerciseId is null.",
        )
        assertTrue(
            src.contains("emptyList()"),
            "recentSessionsForExercise must use emptyList() as fallback for null IDs.",
        )
    }

    @Test
    fun recentSessions_usesWhileSubscribed() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("WhileSubscribed(5000)"),
            "recentSessionsForExercise must use WhileSubscribed(5000) to prevent recomputation " +
                "during brief recompositions.",
        )
    }

    @Test
    fun recentSessions_returnsStateFlow() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("StateFlow<List<WorkoutSession>>"),
            "recentSessionsForExercise must return a StateFlow<List<WorkoutSession>>.",
        )
        assertTrue(
            src.contains(".stateIn("),
            "recentSessionsForExercise must convert to StateFlow via .stateIn().",
        )
    }

    @Test
    fun recentSessions_usesAllWorkoutSessionsSource() {
        val src = readMainViewModelSource()
        assertTrue(
            src.contains("historyManager.allWorkoutSessions") || src.contains("allWorkoutSessions"),
            "recentSessionsForExercise must derive from allWorkoutSessions (not a new DB query).",
        )
    }

    private fun readMainViewModelSource(): String {
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt"
        val src = readProjectFile(relativePath)
        assertNotNull(
            src,
            "Could not locate MainViewModel.kt on disk. The test relies on the project " +
                "root being discoverable from the test runner's working directory.",
        )
        return src
    }
}
