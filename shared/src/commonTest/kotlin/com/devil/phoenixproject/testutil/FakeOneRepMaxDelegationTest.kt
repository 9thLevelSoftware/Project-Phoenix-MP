package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest

class FakeOneRepMaxDelegationTest {

    @Test
    fun fakePersonalRecordRepositoryUsesHybridEstimateAtElevenReps() = runTest {
        val repository = FakePersonalRecordRepository()
        val weightKg = 100f
        val reps = 11

        repository.updatePRIfBetter(
            exerciseId = "bench-press",
            weightPerCableKg = weightKg,
            reps = reps,
            workoutMode = "OldSchool",
            timestamp = 1L,
            profileId = "default",
        )

        val pr = repository.getLatestPR("bench-press", "OldSchool", "default")
        assertEquals(OneRepMaxCalculator.estimate(weightKg, reps), pr!!.oneRepMax)
        assertEquals(OneRepMaxCalculator.epley(weightKg, reps), pr.oneRepMax)
        assertNotEquals(OneRepMaxCalculator.brzycki(weightKg, reps), pr.oneRepMax)
    }

    @Test
    fun testFixturesUseHybridEstimateAtElevenReps() {
        val pr = TestFixtures.createPersonalRecord(weightPerCableKg = 50f, reps = 11)
        val totalWeight = 100f
        assertEquals(OneRepMaxCalculator.estimate(totalWeight, 11), pr.oneRepMax)
        assertEquals(OneRepMaxCalculator.epley(totalWeight, 11), pr.oneRepMax)
        assertNotEquals(OneRepMaxCalculator.brzycki(totalWeight, 11), pr.oneRepMax)
    }
}
