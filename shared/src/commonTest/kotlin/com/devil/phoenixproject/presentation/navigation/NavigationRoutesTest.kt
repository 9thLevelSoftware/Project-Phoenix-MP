package com.devil.phoenixproject.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun strengthAssessmentRoutes_encodeImmutableProfileOwnership() {
        assertEquals(
            "strength_assessment_picker/athlete%2FA",
            NavigationRoutes.StrengthAssessmentPicker.createRoute("athlete/A"),
        )
        assertEquals(
            "strength_assessment/athlete%2FA/bench%20press",
            NavigationRoutes.StrengthAssessment.createRoute("athlete/A", "bench press"),
        )
    }

    @Test
    fun strengthAssessmentRoutes_rejectBlankOwnership() {
        assertFailsWith<IllegalArgumentException> {
            NavigationRoutes.StrengthAssessmentPicker.createRoute(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            NavigationRoutes.StrengthAssessment.createRoute(" ", "bench")
        }
        assertFailsWith<IllegalArgumentException> {
            NavigationRoutes.StrengthAssessment.createRoute("athlete-a", " ")
        }
    }
}
