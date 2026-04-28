package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PersonalRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for PersonalRecord.cableCount.
 *
 * Verifies backward compatibility (legacy PRs default to null) and correct
 * storage of explicit single/dual cable counts introduced in v0.9.0.
 */
class PersonalRecordCableCountTest {

    @Test
    fun cableCountDefaultsToNullForLegacyData() {
        val pr = PersonalRecord(
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            weightPerCableKg = 50f,
            reps = 10,
            oneRepMax = 65f,
            timestamp = 1000L,
            workoutMode = "OLD_SCHOOL",
            volume = 500f,
        )
        assertNull(pr.cableCount, "Legacy PRs without cableCount should default to null")
    }

    @Test
    fun cableCountStoresDualCable() {
        val pr = PersonalRecord(
            exerciseId = "bench-press",
            exerciseName = "Bench Press",
            weightPerCableKg = 50f,
            reps = 10,
            oneRepMax = 65f,
            timestamp = 1000L,
            workoutMode = "OLD_SCHOOL",
            volume = 500f,
            cableCount = 2,
        )
        assertEquals(2, pr.cableCount)
    }

    @Test
    fun cableCountStoresSingleCable() {
        val pr = PersonalRecord(
            exerciseId = "bicep-curl",
            exerciseName = "Bicep Curl",
            weightPerCableKg = 20f,
            reps = 12,
            oneRepMax = 28f,
            timestamp = 1000L,
            workoutMode = "OLD_SCHOOL",
            volume = 240f,
            cableCount = 1,
        )
        assertEquals(1, pr.cableCount)
    }
}
