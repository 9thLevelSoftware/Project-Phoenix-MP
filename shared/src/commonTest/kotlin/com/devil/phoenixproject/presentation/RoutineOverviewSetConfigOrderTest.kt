package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #767: SET CONFIGURATION field order must match Rest Timer
 * (Target Reps before Weight per cable) so users don't adjust the
 * opposite control when hopping between screens.
 *
 * Source-level wiring test follows RestTimerProgressionWiringTest /
 * SetReadyScreenScrollWiringTest — this is a layout-order invariant,
 * not a runtime Compose harness.
 */
class RoutineOverviewSetConfigOrderTest {

    @Test
    fun routineOverview_standardMode_rendersTargetRepsBeforeWeight() {
        val src = readOverviewSource()
        val standardBlock = extractStandardModeBlock(src)

        val repsIdx = standardBlock.indexOf("label = \"Target Reps\"")
        val amrapIdx = standardBlock.indexOf("stringResource(Res.string.target_reps)")
        val weightIdx = standardBlock.indexOf("label = \"Weight per cable\"")

        assertTrue(weightIdx >= 0, "Standard-mode SET CONFIGURATION must render Weight per cable.")
        assertTrue(
            repsIdx >= 0 || amrapIdx >= 0,
            "Standard-mode SET CONFIGURATION must render Target Reps or the AMRAP target-reps row.",
        )

        val firstRepsIdx = listOf(repsIdx, amrapIdx).filter { it >= 0 }.minOrNull()
            ?: error("Target Reps control missing from standard-mode block")
        assertTrue(
            firstRepsIdx < weightIdx,
            "Issue #767: Target Reps must appear before Weight per cable in RoutineOverviewScreen " +
                "standard-mode SET CONFIGURATION (canonical order matches RestTimerCard).",
        )
    }

    @Test
    fun restTimerCard_keepsCanonicalRepsBeforeWeightOrder() {
        val src = readRestTimerSource()
        val repsIdx = src.indexOf("Res.string.rest_target_reps")
        val weightIdx = src.indexOf("Res.string.rest_weight_per_cable")

        assertTrue(repsIdx >= 0, "RestTimerCard must render rest_target_reps.")
        assertTrue(weightIdx >= 0, "RestTimerCard must render rest_weight_per_cable.")
        assertTrue(
            repsIdx < weightIdx,
            "RestTimerCard NEXT SET CONFIGURATION is the canonical reps-first order; do not invert it.",
        )
    }

    private fun extractStandardModeBlock(src: String): String {
        val marker = "// Standard modes:"
        val start = src.indexOf(marker)
        assertTrue(start >= 0, "RoutineOverviewScreen must keep a Standard modes SET CONFIGURATION branch.")
        val completedOverlay = src.indexOf("// Completed overlay", start)
        val end = if (completedOverlay >= 0) completedOverlay else src.length
        return src.substring(start, end)
    }

    private fun readOverviewSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt",
        )
        assertNotNull(src, "Could not locate RoutineOverviewScreen.kt")
        return src
    }

    private fun readRestTimerSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt",
        )
        assertNotNull(src, "Could not locate RestTimerCard.kt")
        return src
    }
}
