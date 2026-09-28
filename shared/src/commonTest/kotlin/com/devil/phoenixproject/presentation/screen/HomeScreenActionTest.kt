package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeScreenActionTest {
    @Test
    fun homeShortcutsAreSingleExerciseRoutinesAndCycles() {
        var singleExerciseCount = 0
        var routinesCount = 0
        var cyclesCount = 0

        val actions = buildHomeShortcutActions(
            onSingleExercise = { singleExerciseCount++ },
            onRoutines = { routinesCount++ },
            onCycles = { cyclesCount++ },
        )

        assertEquals(listOf("Single Exercise", "Routines", "Cycles"), actions.map { it.label })
        assertTrue(actions.none { it.label == "Assess 1RM" })
        assertTrue(actions.all { it.enabled })

        actions.single { it.label == "Single Exercise" }.onClick()
        actions.single { it.label == "Routines" }.onClick()
        actions.single { it.label == "Cycles" }.onClick()

        assertEquals(1, singleExerciseCount)
        assertEquals(1, routinesCount)
        assertEquals(1, cyclesCount)
    }
}
