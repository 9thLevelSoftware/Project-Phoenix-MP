package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.TrainingMaxSource
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The baseline a routine's "% of PR" weight is scaled from must come from the profile
 * that is about to lift. Before the per-profile training max (migration 49) a household
 * member's PR save wrote one global `Exercise.one_rep_max_kg` that every profile then
 * scaled from, so one person getting stronger raised what the machine pulled for
 * everybody.
 */
class ResolveRoutineScalingBaselineUseCaseTest {

    private lateinit var prRepository: FakePersonalRecordRepository
    private lateinit var exerciseRepository: FakeExerciseRepository
    private lateinit var velocityRepository: FakeVelocityOneRepMaxRepository
    private lateinit var resolve: ResolveRoutineScalingBaselineUseCase

    @BeforeTest
    fun setup() {
        prRepository = FakePersonalRecordRepository()
        exerciseRepository = FakeExerciseRepository()
        velocityRepository = FakeVelocityOneRepMaxRepository()
        exerciseRepository.addExercise(
            Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR"),
        )
        resolve = ResolveRoutineScalingBaselineUseCase(
            prRepository,
            exerciseRepository,
            velocityRepository,
        )
    }

    @Test
    fun `profile A's PR is not a baseline for profile B, on any basis`() = runTest {
        prRepository.addRecord(benchWeightPr(profileId = "alice", weightPerCableKg = 100f))

        assertEquals(100f, baseline("alice", ScalingBasis.MAX_WEIGHT_PR)?.weightPerCableKg)
        assertEquals(100f, baseline("alice", ScalingBasis.ESTIMATED_1RM)?.weightPerCableKg)

        // Bob has no data of his own: nothing resolves, so the caller falls back to the
        // routine's absolute weight rather than to alice's load.
        assertNull(baseline("bob", ScalingBasis.MAX_WEIGHT_PR))
        assertNull(baseline("bob", ScalingBasis.ESTIMATED_1RM))
    }

    @Test
    fun `profile A's training max is not a baseline for profile B, on any basis`() = runTest {
        exerciseRepository.setTrainingMaxDirectly("bench", "alice", 120f)

        assertEquals(120f, baseline("alice", ScalingBasis.MAX_WEIGHT_PR)?.weightPerCableKg)
        assertEquals(120f, baseline("alice", ScalingBasis.ESTIMATED_1RM)?.weightPerCableKg)
        assertEquals(120f, baseline("alice", ScalingBasis.MAX_VOLUME_PR)?.weightPerCableKg)

        assertNull(baseline("bob", ScalingBasis.MAX_WEIGHT_PR))
        assertNull(baseline("bob", ScalingBasis.ESTIMATED_1RM))
        assertNull(baseline("bob", ScalingBasis.MAX_VOLUME_PR))
    }

    @Test
    fun `each profile scales from its own training max, not the other's`() = runTest {
        exerciseRepository.setTrainingMaxDirectly("bench", "alice", 120f)
        exerciseRepository.setTrainingMaxDirectly("bench", "bob", 60f)

        assertEquals(120f, baseline("alice", ScalingBasis.ESTIMATED_1RM)?.weightPerCableKg)
        assertEquals(60f, baseline("bob", ScalingBasis.ESTIMATED_1RM)?.weightPerCableKg)
    }

    @Test
    fun `an unclaimed legacy value is never a baseline for anyone`() = runTest {
        // Migration 49 could not attribute this value, so it sits on the catalogue row
        // waiting for a claim. No profile may scale from it in the meantime.
        exerciseRepository.unassignedLegacyTrainingMaxes["bench"] = 140f

        assertNull(baseline("alice", ScalingBasis.ESTIMATED_1RM))
        assertNull(baseline("bob", ScalingBasis.ESTIMATED_1RM))

        exerciseRepository.setTrainingMax("bench", "alice", 140f, TrainingMaxSource.CLAIMED_LEGACY)

        assertEquals(140f, baseline("alice", ScalingBasis.ESTIMATED_1RM)?.weightPerCableKg)
        assertNull(baseline("bob", ScalingBasis.ESTIMATED_1RM))
    }

    @Test
    fun `a profile's own PR still beats its own training max for the max-weight basis`() = runTest {
        exerciseRepository.setTrainingMaxDirectly("bench", "alice", 120f)
        prRepository.addRecord(benchWeightPr(profileId = "alice", weightPerCableKg = 100f))

        val resolved = baseline("alice", ScalingBasis.MAX_WEIGHT_PR)
        assertEquals(100f, resolved?.weightPerCableKg)
        assertEquals(RoutineScalingBaselineSource.CURRENT_MODE_PR, resolved?.source)
    }

    private suspend fun baseline(profileId: String, basis: ScalingBasis) = resolve(
        exerciseId = "bench",
        mode = ProgramMode.OldSchool,
        profileId = profileId,
        basis = basis,
    )

    private fun benchWeightPr(profileId: String, weightPerCableKg: Float) = PersonalRecord(
        exerciseId = "bench",
        exerciseName = "Bench Press",
        weightPerCableKg = weightPerCableKg,
        reps = 1,
        oneRepMax = weightPerCableKg,
        timestamp = 1L,
        workoutMode = ProgramMode.OldSchool.displayName,
        prType = PRType.MAX_WEIGHT,
        volume = weightPerCableKg,
        phase = WorkoutPhase.COMBINED,
        profileId = profileId,
    )
}
