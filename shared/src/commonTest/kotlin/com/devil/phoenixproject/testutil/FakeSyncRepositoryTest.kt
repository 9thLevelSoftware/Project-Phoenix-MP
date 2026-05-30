package com.devil.phoenixproject.testutil

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeSyncRepositoryTest {

    @Test
    fun getExerciseMuscleGroupIgnoresBlankExerciseNames() = runTest {
        val repository = FakeSyncRepository().apply {
            muscleGroupLookupResults = mapOf(
                "" to "Should Not Match",
                "   " to "Should Not Match",
                "Bench Press" to "Chest",
            )
        }

        assertEquals(null, repository.getExerciseMuscleGroup(null, ""))
        assertEquals(null, repository.getExerciseMuscleGroup(null, "   "))
        assertEquals("Chest", repository.getExerciseMuscleGroup(null, "Bench Press"))
    }
}
