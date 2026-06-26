package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.onerepmax.MvtProvider
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class FakeExercise(
    override val name: String,
    override val muscleGroups: String,
    override val mvtOverrideMs: Float?,
) : MvtExerciseView

class ComputeVelocityOneRepMaxUseCaseTest {
    @Test fun `computes and persists an estimate from windowed points`() = runTest {
        val points = listOf(
            WorkoutVelocityPoint(40f, 1200f, timestampMs = 5L, workingReps = 5),
            WorkoutVelocityPoint(80f, 600f, timestampMs = 6L, workingReps = 5),
        )
        val inserted = mutableListOf<VelocityOneRepMaxResult>()
        val useCase = ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { _, _, _ -> points },
            exerciseLookup = { _ -> FakeExercise(name = "Back Squat", muscleGroups = "Legs", mvtOverrideMs = null) },
            personalMvtLookup = { _, _ -> null },
            mvtProvider = MvtProvider(),
            estimator = VelocityOneRepMaxEstimator(AssessmentEngine()),
            persist = { result, _, _, _ -> inserted += result },
        )

        val result = useCase("ex1", "default", nowMs = 1_000_000L)
        assertNotNull(result)
        assertTrue(result.passedQualityGate)
        assertEquals(1, inserted.size)
        assertEquals(result.estimatedPerCableKg, inserted.first().estimatedPerCableKg)
    }

    @Test fun `returns null and persists nothing when fewer than two loads`() = runTest {
        val inserted = mutableListOf<VelocityOneRepMaxResult>()
        val useCase = ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { _, _, _ -> listOf(WorkoutVelocityPoint(50f, 700f, 1L, 5)) },
            exerciseLookup = { _ -> FakeExercise("Curl", "Biceps", null) },
            personalMvtLookup = { _, _ -> null },
            mvtProvider = MvtProvider(),
            estimator = VelocityOneRepMaxEstimator(AssessmentEngine()),
            persist = { r, _, _, _ -> inserted += r },
        )
        assertEquals(null, useCase("ex1", "default", nowMs = 1L))
        assertEquals(0, inserted.size)
    }
}
