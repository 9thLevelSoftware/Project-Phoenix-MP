package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #671: Source-level wiring tests for ExerciseQuickHistoryCard integration
 * in SetReadyScreen.
 *
 * These tests pin the integration contract — state collection, card placement,
 * and import wiring — so a future refactor cannot accidentally break the
 * ExerciseQuickHistoryCard integration.
 */
class SetReadyHistoryCardIntegrationWiringTest {

    // ── Packet 3: SetReadyScreen integration ──────────────────────────────

    @Test
    fun setReady_importsExerciseQuickHistoryCard() {
        val src = readSetReadyScreenSource()
        assertTrue(
            src.contains("import com.devil.phoenixproject.presentation.components.ExerciseQuickHistoryCard"),
            "SetReadyScreen must import ExerciseQuickHistoryCard.",
        )
    }

    @Test
    fun setReady_collectsActiveProfileId() {
        val src = readSetReadyScreenSource()
        assertTrue(
            src.contains("viewModel.activeProfileId.collectAsState()"),
            "SetReadyScreen must collect activeProfileId from the ViewModel.",
        )
    }

    @Test
    fun setReady_collectsRecentSessions() {
        val src = readSetReadyScreenSource()
        assertTrue(
            src.contains("recentSessionsForExercise("),
            "SetReadyScreen must call recentSessionsForExercise on the ViewModel.",
        )
        assertTrue(
            src.contains("collectAsState()"),
            "SetReadyScreen must collect the recent sessions StateFlow.",
        )
    }

    @Test
    fun setReady_cardCallWithCorrectArgs() {
        val src = readSetReadyScreenSource()
        assertTrue(
            src.contains("ExerciseQuickHistoryCard("),
            "SetReadyScreen must invoke ExerciseQuickHistoryCard composable.",
        )
        assertTrue(
            src.contains("sessions = recentSessions"),
            "ExerciseQuickHistoryCard must receive recentSessions as sessions parameter.",
        )
        assertTrue(
            src.contains("weightUnit = weightUnit"),
            "ExerciseQuickHistoryCard must receive weightUnit.",
        )
        assertTrue(
            src.contains("formatWeight = viewModel::formatWeight"),
            "ExerciseQuickHistoryCard must receive viewModel::formatWeight callback.",
        )
    }

    @Test
    fun setReady_cardPlacedBeforeEquipmentRack() {
        val src = readSetReadyScreenSource()
        val historyCardIdx = src.indexOf("ExerciseQuickHistoryCard(")
        val rackCardIdx = src.indexOf("EquipmentRackSelectionCard(")
        assertTrue(
            historyCardIdx >= 0 && rackCardIdx >= 0 && historyCardIdx < rackCardIdx,
            "ExerciseQuickHistoryCard must appear before EquipmentRackSelectionCard in the layout.",
        )
    }

    @Test
    fun setReady_cardPlacedAfterSetConfiguration() {
        val src = readSetReadyScreenSource()
        // SET CONFIGURATION section must precede the history card
        val setConfigIdx = src.indexOf("SET CONFIGURATION")
        val historyCardIdx = src.indexOf("ExerciseQuickHistoryCard(")
        assertTrue(
            setConfigIdx >= 0 && historyCardIdx >= 0 && setConfigIdx < historyCardIdx,
            "ExerciseQuickHistoryCard must appear after SET CONFIGURATION section.",
        )
    }

    @Test
    fun setReady_passesExerciseIdFromCurrentExercise() {
        val src = readSetReadyScreenSource()
        assertTrue(
            src.contains("currentExercise.exercise.id"),
            "recentSessionsForExercise must receive currentExercise.exercise.id as exerciseId.",
        )
    }

    private fun readSetReadyScreenSource(): String {
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt"
        val src = readProjectFile(relativePath)
        assertNotNull(
            src,
            "Could not locate SetReadyScreen.kt on disk. The test relies on the project " +
                "root being discoverable from the test runner's working directory.",
        )
        return src
    }
}
