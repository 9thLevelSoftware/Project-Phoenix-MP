package com.devil.phoenixproject.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationRoutesTest {
    @Test
    fun singleExerciseForExerciseRoute_preservesNormalExerciseIds() {
        assertEquals(
            "single_exercise/bench-press_1.2~custom",
            NavigationRoutes.SingleExerciseForExercise.createRoute("bench-press_1.2~custom"),
        )
    }

    @Test
    fun singleExerciseForExerciseRoute_percentEncodesSpecialCharacters() {
        assertEquals(
            "single_exercise/exercise%2Fwith%20space%3F",
            NavigationRoutes.SingleExerciseForExercise.createRoute("exercise/with space?"),
        )
    }
}
