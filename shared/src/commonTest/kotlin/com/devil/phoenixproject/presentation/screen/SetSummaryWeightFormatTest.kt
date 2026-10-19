package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Set summary load cells must use [com.devil.phoenixproject.presentation.util.WeightDisplayFormatter]
 * so a fractional kg/lb value stays visible. Integer rounding (20.4 -> 20, 20.6 -> 21) disagrees
 * with the primary set-weight cell, which already uses that formatter.
 */
class SetSummaryWeightFormatTest {

    @Test
    fun setSummaryCard_formatsLoadCellsWithSharedDisplayNumber() {
        val card = requireNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt",
            ),
        ) { "SetSummaryCard.kt must be readable from the host test." }

        val formattedCells = listOf(
            "WeightDisplayFormatter.formatDisplayNumber(totalVolumeDisplay)",
            "WeightDisplayFormatter.formatDisplayNumber(concentricValue)",
            "WeightDisplayFormatter.formatDisplayNumber(eccentricValue)",
            "WeightDisplayFormatter.formatDisplayNumber(peakWeight)",
            "WeightDisplayFormatter.formatDisplayNumber(avgWeight)",
        )
        formattedCells.forEach { call ->
            assertTrue(card.contains(call), "Set summary must format through the shared formatter: $call")
        }

        val truncatedCells = listOf(
            "totalVolumeDisplay.roundToInt()",
            "concentricValue.roundToInt()",
            "eccentricValue.roundToInt()",
            "peakWeight.roundToInt()",
            "avgWeight.roundToInt()",
        )
        truncatedCells.forEach { call ->
            assertFalse(card.contains(call), "Set summary must not round this load to an integer: $call")
        }
    }
}
