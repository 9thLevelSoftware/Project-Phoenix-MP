package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for ResolveRoutineWeightsUseCase.
 *
 * Issue #57: Verifies that PR percentage weight resolution works correctly
 * when resolving routine exercise weights at workout start time.
 */
class ResolveRoutineWeightsUseCaseTest {

    private lateinit var prRepository: FakePersonalRecordRepository
    private lateinit var exerciseRepository: FakeExerciseRepository
    private lateinit var velocityRepository: FakeVelocityOneRepMaxRepository
    private lateinit var useCase: ResolveRoutineWeightsUseCase

    private val testExercise = Exercise(
        id = "bench-press",
        name = "Bench Press",
        muscleGroup = "Chest",
    )

    @BeforeTest
    fun setup() {
        prRepository = FakePersonalRecordRepository()
        prRepository.reset()
        exerciseRepository = FakeExerciseRepository()
        exerciseRepository.reset()
        velocityRepository = FakeVelocityOneRepMaxRepository()
        useCase = ResolveRoutineWeightsUseCase(prRepository, exerciseRepository, velocityRepository)
    }

    // ========== Test 1: Resolves percentage to absolute weight using PR ==========

    @Test
    fun `resolves percentage to absolute weight using PR`() = runTest {
        // Given: Exercise at 80% of PR, PR is 50kg
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 50f,
                reps = 10,
                oneRepMax = 65f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 1000f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f, // Fallback if no PR
            usePercentOfPR = true,
            weightPercentOfPR = 80, // 80% of PR
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        // When: invoke(exercise)
        val result = useCase(routineExercise)

        // Then: baseWeight = 40kg (80% of 50)
        assertEquals(40f, result.baseWeight)
        assertEquals(50f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    // ========== Test 2: Falls back to absolute weight when no PR exists ==========

    @Test
    fun `falls back to absolute weight when no PR exists`() = runTest {
        // Given: Exercise uses PR percentage but no PR exists
        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f, // Fallback weight
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        // When: invoke(exercise)
        val result = useCase(routineExercise)

        // Then: returns fallback with absolute weightPerCableKg
        assertEquals(30f, result.baseWeight)
        assertNull(result.usedPR)
        assertNull(result.percentOfPR)
        assertFalse(result.isFromPR)
        // And: fallbackReason is set
        assertNotNull(result.fallbackReason)
        assertTrue(result.fallbackReason.orEmpty().contains("No PR or stored 1RM"))
    }

    @Test
    fun `resolves percentage using stored exercise 1RM when no PR exists`() = runTest {
        exerciseRepository.addExercise(testExercise.copy(oneRepMaxKg = 100f))

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        assertEquals(80f, result.baseWeight)
        assertEquals(100f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `prefers PR weight over stored exercise 1RM when both exist`() = runTest {
        exerciseRepository.addExercise(testExercise.copy(oneRepMaxKg = 100f))
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 50f,
                reps = 5,
                oneRepMax = 60f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 250f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        assertEquals(40f, result.baseWeight)
        assertEquals(50f, result.usedPR)
    }

    // ========== Test 3: Returns absolute weight when usePercentOfPR is false ==========

    @Test
    fun `returns absolute weight when usePercentOfPR is false`() = runTest {
        // Given: Exercise with usePercentOfPR = false
        // Even if PR exists, it should not be used
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 50f,
                reps = 10,
                oneRepMax = 65f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 1000f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 35f,
            usePercentOfPR = false, // NOT using PR percentage
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        // When: invoke(exercise)
        val result = useCase(routineExercise)

        // Then: returns weightPerCableKg directly
        assertEquals(35f, result.baseWeight)
        // And: usedPR is null
        assertNull(result.usedPR)
        assertNull(result.percentOfPR)
        assertFalse(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    // ========== Test 4: Rounds weight to nearest half kg ==========

    @Test
    fun `rounds weight to nearest half kg`() = runTest {
        // Given: 80% of 47kg = 37.6kg
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 47f, // 80% = 37.6kg
                reps = 10,
                oneRepMax = 60f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 940f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80, // 80% of 47 = 37.6
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        // When: resolved
        val result = useCase(routineExercise)

        // Then: rounds to 37.5kg (nearest 0.5kg)
        assertEquals(37.5f, result.baseWeight)
    }

    @Test
    fun `rounds weight up when closer to upper half kg`() = runTest {
        // Given: 70% of 50kg = 35.0kg (exactly on half kg boundary)
        // And: 75% of 50kg = 37.5kg (exactly on half kg boundary)
        // And: 77% of 50kg = 38.5kg (rounds from 38.5)
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 50f,
                reps = 10,
                oneRepMax = 65f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 1000f,
            ),
        )

        // Test 73% of 50kg = 36.5kg
        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 73, // 73% of 50 = 36.5
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)
        assertEquals(36.5f, result.baseWeight)
    }

    // ========== Test 5: Resolves per-set percentages correctly ==========

    @Test
    fun `resolves per-set percentages correctly`() = runTest {
        // Given: Exercise with setWeightsPercentOfPR = [70, 80, 90]
        // And: PR = 100kg
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 100f, // 100kg PR
                reps = 5,
                oneRepMax = 115f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 1000f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            setReps = listOf(10, 8, 6),
            weightPerCableKg = 50f,
            usePercentOfPR = true,
            weightPercentOfPR = 80, // Base percentage (used for baseWeight)
            setWeightsPercentOfPR = listOf(70, 80, 90), // Per-set percentages
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        // When: resolved
        val result = useCase(routineExercise)

        // Then: setWeights = [70, 80, 90] (70%, 80%, 90% of 100kg)
        assertEquals(3, result.setWeights.size)
        assertEquals(70f, result.setWeights[0])
        assertEquals(80f, result.setWeights[1])
        assertEquals(90f, result.setWeights[2])
        // And base weight should be 80% of 100kg
        assertEquals(80f, result.baseWeight)
    }

    @Test
    fun `resolves per-set percentages with rounding`() = runTest {
        // Given: PR = 47kg, percentages = [70, 80, 90]
        // 70% of 47 = 32.9 -> 33.0
        // 80% of 47 = 37.6 -> 37.5
        // 90% of 47 = 42.3 -> 42.5
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 47f,
                reps = 5,
                oneRepMax = 55f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 470f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            setReps = listOf(10, 8, 6),
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(70, 80, 90),
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // Verify rounding to nearest 0.5kg
        assertEquals(33f, result.setWeights[0]) // 32.9 -> 33.0
        assertEquals(37.5f, result.setWeights[1]) // 37.6 -> 37.5
        assertEquals(42.5f, result.setWeights[2]) // 42.3 -> 42.5
    }

    // ========== Additional tests for edge cases ==========

    @Test
    fun `uses volume PR when prTypeForScaling is MAX_VOLUME`() = runTest {
        // Given: Both weight and volume PRs exist, but using MAX_VOLUME
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 60f, // Higher weight PR
                reps = 5,
                oneRepMax = 70f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 600f,
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                id = 2,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 40f, // Lower weight but higher volume PR
                reps = 15,
                oneRepMax = 55f,
                timestamp = 2000L,
                workoutMode = "Old School",
                prType = PRType.MAX_VOLUME,
                volume = 1200f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100, // 100% of PR
            prTypeForScaling = PRType.MAX_VOLUME, // Use volume PR
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // Should use volume PR weight (40kg), not weight PR (60kg)
        assertEquals(40f, result.baseWeight)
        assertEquals(40f, result.usedPR)
    }

    @Test
    fun `falls back when exercise has no ID`() = runTest {
        // Given: Exercise without an ID
        val exerciseWithoutId = Exercise(
            id = null, // No ID
            name = "Custom Exercise",
            muscleGroup = "Chest",
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = exerciseWithoutId,
            orderIndex = 0,
            weightPerCableKg = 25f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // Should fall back to absolute weight
        assertEquals(25f, result.baseWeight)
        assertNull(result.usedPR)
        assertNotNull(result.fallbackReason)
        assertTrue(result.fallbackReason.orEmpty().contains("no ID"))
    }

    @Test
    fun `respects program mode for PR lookup`() = runTest {
        // Given: Different PRs for different modes
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 50f,
                reps = 10,
                oneRepMax = 65f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 1000f,
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                id = 2,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 40f,
                reps = 15,
                oneRepMax = 55f,
                timestamp = 2000L,
                workoutMode = "Echo",
                prType = PRType.MAX_WEIGHT,
                volume = 1200f,
            ),
        )

        val routineExerciseOldSchool = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool, // Old School mode
        )

        val routineExerciseEcho = RoutineExercise(
            id = "routine-ex-2",
            exercise = testExercise,
            orderIndex = 1,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.Echo, // Echo mode
        )

        val resultOldSchool = useCase(routineExerciseOldSchool)
        val resultEcho = useCase(routineExerciseEcho)

        // Old School should use 50kg PR
        assertEquals(50f, resultOldSchool.baseWeight)
        assertEquals(50f, resultOldSchool.usedPR)

        // Echo should use 40kg PR
        assertEquals(40f, resultEcho.baseWeight)
        assertEquals(40f, resultEcho.usedPR)
    }

    @Test
    fun `normal mode percent of PR uses concentric PR instead of stronger eccentric PR`() = runTest {
        prRepository.addRecord(
            phaseRecord(id = 1, mode = "Old School", phase = WorkoutPhase.COMBINED, weight = 35f),
        )
        prRepository.addRecord(
            phaseRecord(id = 2, mode = "Old School", phase = WorkoutPhase.CONCENTRIC, weight = 45f),
        )
        prRepository.addRecord(
            phaseRecord(id = 3, mode = "Old School", phase = WorkoutPhase.ECCENTRIC, weight = 90f),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-phase",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        assertEquals(45f, result.baseWeight)
        assertEquals(45f, result.usedPR)
    }

    @Test
    fun `eccentric only percent of PR uses eccentric phase PR`() = runTest {
        prRepository.addRecord(
            phaseRecord(id = 1, mode = "Eccentric Only", phase = WorkoutPhase.COMBINED, weight = 35f),
        )
        prRepository.addRecord(
            phaseRecord(id = 2, mode = "Eccentric Only", phase = WorkoutPhase.CONCENTRIC, weight = 45f),
        )
        prRepository.addRecord(
            phaseRecord(id = 3, mode = "Eccentric Only", phase = WorkoutPhase.ECCENTRIC, weight = 90f),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-eccentric",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.EccentricOnly,
        )

        val result = useCase(routineExercise)

        assertEquals(90f, result.baseWeight)
        assertEquals(90f, result.usedPR)
    }

    // ========== Tests for ESTIMATED_1RM scaling basis (#517) ==========

    @Test
    fun `ESTIMATED_1RM uses latest passing velocity estimate`() = runTest {
        // Given: velocity repo returns an estimate of 100 kg per-cable for this exercise
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = 1000L,
            profileId = "default",
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1rm",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // 80% of 100 = 80 kg
        assertEquals(80f, result.baseWeight)
        assertEquals(100f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `ESTIMATED_1RM falls back to stored 1RM when no estimate exists`() = runTest {
        // Given: velocity repo returns null; exercise has oneRepMaxKg = 120f
        velocityRepository.latestPassing = null
        exerciseRepository.addExercise(testExercise.copy(oneRepMaxKg = 120f))

        val routineExercise = RoutineExercise(
            id = "routine-ex-1rm-fallback",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // 80% of 120 = 96 kg
        assertEquals(96f, result.baseWeight)
        assertEquals(120f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `ESTIMATED_1RM falls back to max-weight PR when no estimate and no stored 1RM`() = runTest {
        // Given: no velocity estimate, no stored oneRepMaxKg, but a max-weight PR exists
        velocityRepository.latestPassing = null
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 60f,
                reps = 5,
                oneRepMax = 70f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 300f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1rm-pr-fallback",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // 80% of 60 (max-weight PR) = 48 kg — must scale off PR, not drop to absolute 30
        assertEquals(48f, result.baseWeight)
        assertEquals(60f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    // Issue #644: a velocity-1RM row at the 1.0 kg hardware floor must not be selected as the
    // baseline — it would short-circuit stored-1RM / max-weight PR fallback and collapse 80%
    // scaling to 2.2 lb/cable. The repo now filters such rows out; verify the resolver agrees
    // and falls through to the PR / stored-1RM baseline instead.

    @Test
    fun `ESTIMATED_1RM ignores floor-clamped velocity row and falls back to stored 1RM`() = runTest {
        // Given: a poisoned velocity row at the 1.0 kg floor plus a sane stored 1RM
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 1.0f, // AssessmentEngine floor clamp
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true, // legacy row from a previous build
            computedAt = 1000L,
            profileId = "default",
        )
        exerciseRepository.addExercise(testExercise.copy(oneRepMaxKg = 120f))

        val routineExercise = RoutineExercise(
            id = "routine-ex-1rm-floor-stored",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // 80% of stored 120 = 96 kg — not 1.0 kg / 2.2 lb
        assertEquals(96f, result.baseWeight)
        assertEquals(120f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `ESTIMATED_1RM ignores floor-clamped velocity row and falls back to max-weight PR`() = runTest {
        // Given: floor-clamped velocity row + no stored 1RM + a max-weight PR
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 2L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 1.0f,
            mvtUsedMs = 0.3f,
            r2 = 0.95f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = 1000L,
            profileId = "default",
        )
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 60f,
                reps = 5,
                oneRepMax = 70f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 300f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1rm-floor-pr",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // 80% of 60 (max-weight PR) = 48 kg — not 1.0 kg
        assertEquals(48f, result.baseWeight)
        assertEquals(60f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `ESTIMATED_1RM still trusts a sane velocity row above the floor`() = runTest {
        // Sanity: the new filter must not strip legitimate estimates.
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 3L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = 1000L,
            profileId = "default",
        )
        exerciseRepository.addExercise(testExercise.copy(oneRepMaxKg = 120f))

        val routineExercise = RoutineExercise(
            id = "routine-ex-1rm-sane",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.ESTIMATED_1RM,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // 80% of 100 = 80 kg
        assertEquals(80f, result.baseWeight)
        assertEquals(100f, result.usedPR)
        assertEquals(80, result.percentOfPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `max weight basis falls back to same profile cross mode PR when selected mode has none`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                id = 10,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 62f,
                reps = 5,
                oneRepMax = 70f,
                timestamp = 2_000L,
                workoutMode = "Pump",
                prType = PRType.MAX_WEIGHT,
                volume = 310f,
                profileId = "default",
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                id = 11,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 90f,
                reps = 5,
                oneRepMax = 100f,
                timestamp = 3_000L,
                workoutMode = "Pump",
                prType = PRType.MAX_WEIGHT,
                volume = 450f,
                profileId = "other-profile",
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-cross-mode",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 5f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            scalingBasis = ScalingBasis.MAX_WEIGHT_PR,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise, profileId = "default")

        assertEquals(49.5f, result.baseWeight)
        assertEquals(62f, result.usedPR)
        assertTrue(result.isFromPR)
        assertNull(result.fallbackReason)
    }

    @Test
    fun `max volume basis falls back to same profile cross mode volume PR when selected mode has none`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                id = 12,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 45f,
                reps = 15,
                oneRepMax = 65f,
                timestamp = 2_000L,
                workoutMode = "Pump",
                prType = PRType.MAX_VOLUME,
                volume = 675f,
                profileId = "default",
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-cross-volume",
            exercise = testExercise,
            orderIndex = 0,
            weightPerCableKg = 5f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            scalingBasis = ScalingBasis.MAX_VOLUME_PR,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise, profileId = "default")

        assertEquals(45f, result.baseWeight)
        assertEquals(45f, result.usedPR)
        assertTrue(result.isFromPR)
    }

    @Test
    fun `set weights default to base percentage when no per-set percentages defined`() = runTest {
        // Given: Exercise uses PR percentage for base weight, but no per-set percentages
        prRepository.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = "bench-press",
                exerciseName = "Bench Press",
                weightPerCableKg = 50f,
                reps = 10,
                oneRepMax = 65f,
                timestamp = 1000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 1000f,
            ),
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 30f, // Fallback absolute weight
            setWeightsPerCableKg = emptyList(), // No per-set absolute weights
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = emptyList(), // No per-set percentages
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool,
        )

        val result = useCase(routineExercise)

        // Base weight should be PR-resolved (80% of 50kg = 40kg)
        assertEquals(40f, result.baseWeight)
        assertTrue(result.isFromPR)

        // Per-set weights default to base percentage when no setWeightsPercentOfPR defined
        assertEquals(3, result.setWeights.size)
        result.setWeights.forEach { weight ->
            assertEquals(40f, weight) // Uses base 80% of PR for all sets
        }
    }
}

private fun phaseRecord(id: Long, mode: String, phase: WorkoutPhase, weight: Float) = PersonalRecord(
    id = id,
    exerciseId = "bench-press",
    exerciseName = "Bench Press",
    weightPerCableKg = weight,
    reps = 10,
    oneRepMax = weight * 1.2f,
    timestamp = 1_000L + id,
    workoutMode = mode,
    prType = PRType.MAX_WEIGHT,
    volume = weight * 10,
    phase = phase,
)
