package com.devil.phoenixproject.presentation.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.unit.dp

/**
 * Behavior contract for the `RoutineCard` 2.dp / 8.dp elevation flicker under
 * Dark + Material You (issue #640).
 *
 * The RCA traces the per-card "flicker" the reporter sees on expand/collapse to
 * RoutineCard's collapsed/expanded elevation delta. The production call site now
 * reads this value through [routineCardDefaultElevation], which gives this test a
 * stable semantic contract instead of parsing formatting-sensitive Kotlin source.
 */
class RoutineCardElevationContractTest {

    @Test
    fun routineCard_elevationIsTwoOrEightDp() {
        assertEquals(
            2.dp,
            routineCardDefaultElevation(expanded = false),
            "Collapsed RoutineCard elevation must stay 2.dp so the elevation-tint " +
                "delta remains the RCA/acceptance-tested 6-dp step.",
        )
        assertEquals(
            8.dp,
            routineCardDefaultElevation(expanded = true),
            "Expanded RoutineCard elevation must stay 8.dp so the elevation-tint " +
                "delta remains the RCA/acceptance-tested 6-dp step.",
        )
    }
}
