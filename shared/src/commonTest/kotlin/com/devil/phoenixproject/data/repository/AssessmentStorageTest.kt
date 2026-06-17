package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class AssessmentStorageTest {
    @Test
    fun totalAssessmentOneRmConvertsToPerCableForDualCableExercises() {
        assertEquals(50f, assessmentTotalOneRmToPerCableKg(100f, 2))
    }

    @Test
    fun totalAssessmentOneRmConvertsToPerCableForSingleCableExercises() {
        assertEquals(80f, assessmentTotalOneRmToPerCableKg(80f, 1))
    }

    @Test
    fun totalAssessmentOneRmGuardsAgainstInvalidCableCount() {
        assertEquals(100f, assessmentTotalOneRmToPerCableKg(100f, 0))
    }
}
