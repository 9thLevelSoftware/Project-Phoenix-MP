package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeScreenActionTest {
    @Test
    fun assessOneRepMaxShortcutIsTemporarilyDisabledAndShowsComingSoon() {
        var navigateCount = 0
        var comingSoonCount = 0

        val action = buildHomeShortcutActions(
            onSingleExercise = { navigateCount++ },
            onRoutines = { navigateCount++ },
            onCycles = { navigateCount++ },
            onAssessOneRepMaxComingSoon = { comingSoonCount++ },
        ).single { it.label == "Assess 1RM" }

        assertFalse(action.enabled)

        action.onClick()

        assertEquals(0, navigateCount)
        assertEquals(1, comingSoonCount)
    }
}
