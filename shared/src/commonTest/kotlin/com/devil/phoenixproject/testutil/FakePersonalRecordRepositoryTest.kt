package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The fake must rank the max-weight PR the way SqlDelightPersonalRecordRepository
 * does — by weight, not volume — or fake-backed tests can pass on behaviour
 * production does not have (GitHub #854, codex 4078341752).
 */
class FakePersonalRecordRepositoryTest {
    private fun record(mode: String, weightPerCableKg: Float, reps: Int) = PersonalRecord(
        exerciseId = "bench",
        exerciseName = "Bench",
        weightPerCableKg = weightPerCableKg,
        reps = reps,
        oneRepMax = 0f,
        timestamp = 1L,
        workoutMode = mode,
        prType = PRType.MAX_WEIGHT,
        volume = weightPerCableKg * reps,
        phase = WorkoutPhase.COMBINED,
    )

    @Test
    fun bestAndGroupedMaxWeightPrsRankByWeightNotVolume() = runTest {
        val repository = FakePersonalRecordRepository()
        repository.addRecord(record("Old School", weightPerCableKg = 50f, reps = 10))
        repository.addRecord(record("Pump", weightPerCableKg = 60f, reps = 5))

        assertEquals(60f, repository.getBestWeightPR("bench", "default")?.weightPerCableKg)
        assertEquals(60f, repository.getAllPRsGrouped("default").first().single().weightPerCableKg)
    }

    @Test
    fun storedOneRepMaxUsesHybridEstimate() = runTest {
        val repository = FakePersonalRecordRepository()

        repository.updatePRsIfBetter(
            exerciseId = "bench",
            weightPRWeightPerCableKg = 30f,
            volumePRWeightPerCableKg = 20f,
            reps = 15,
            workoutMode = "OldSchool",
            timestamp = 1L,
            profileId = "default",
        )

        val highRepEstimate = OneRepMaxCalculator.estimate(30f, 15)
        assertEquals(highRepEstimate, repository.getWeightPR("bench", "Old School", "default")?.oneRepMax)
        assertEquals(highRepEstimate, repository.getVolumePR("bench", "Old School", "default")?.oneRepMax)
        assertNotEquals(30f * (36f / (37 - 15)), highRepEstimate)

        repository.updatePRsIfBetter(
            exerciseId = "row",
            weightPerCableKg = 40f,
            reps = 8,
            workoutMode = "Echo",
            timestamp = 2L,
            profileId = "default",
        )
        assertEquals(
            OneRepMaxCalculator.estimate(40f, 8),
            repository.getWeightPR("row", "Echo", "default")?.oneRepMax,
        )

        repository.updatePhaseSpecificPRs(
            exerciseId = "squat",
            workoutMode = "Pump",
            timestamp = 3L,
            reps = 12,
            peakConcentricForceKg = 25f,
            peakEccentricForceKg = 50f,
            profileId = "default",
        )
        assertEquals(
            OneRepMaxCalculator.estimate(25f, 12),
            repository.getBestWeightPR("squat", "Pump", "default", WorkoutPhase.CONCENTRIC)?.oneRepMax,
        )
        assertEquals(
            OneRepMaxCalculator.estimate(50f, 12),
            repository.getBestWeightPR("squat", "Pump", "default", WorkoutPhase.ECCENTRIC)?.oneRepMax,
        )
    }
}
