package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
