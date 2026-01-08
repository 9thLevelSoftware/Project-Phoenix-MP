package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CableConfiguration
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ResolveRoutineWeightsUseCase.
 *
 * Issue #57: Verifies that PR percentage weight resolution works correctly
 * when resolving routine exercise weights at workout start time.
 */
class ResolveRoutineWeightsUseCaseTest {

    private lateinit var prRepository: FakePersonalRecordRepository
    private lateinit var useCase: ResolveRoutineWeightsUseCase

    private val testExercise = Exercise(
        id = "bench-press",
        name = "Bench Press",
        muscleGroup = "Chest",
        defaultCableConfig = CableConfiguration.DOUBLE
    )

    @BeforeTest
    fun setup() {
        prRepository = FakePersonalRecordRepository()
        prRepository.reset()
        useCase = ResolveRoutineWeightsUseCase(prRepository)
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
                volume = 1000f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 30f, // Fallback if no PR
            usePercentOfPR = true,
            weightPercentOfPR = 80, // 80% of PR
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 30f, // Fallback weight
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
        assertTrue(result.fallbackReason.orEmpty().contains("No PR found"))
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
                volume = 1000f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 35f,
            usePercentOfPR = false, // NOT using PR percentage
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
                volume = 940f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80, // 80% of 47 = 37.6
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
                volume = 1000f
            )
        )

        // Test 73% of 50kg = 36.5kg
        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 73, // 73% of 50 = 36.5
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
                volume = 1000f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            setReps = listOf(10, 8, 6),
            weightPerCableKg = 50f,
            usePercentOfPR = true,
            weightPercentOfPR = 80, // Base percentage (used for baseWeight)
            setWeightsPercentOfPR = listOf(70, 80, 90), // Per-set percentages
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
                volume = 470f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            setReps = listOf(10, 8, 6),
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(70, 80, 90),
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
                volume = 600f
            )
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
                volume = 1200f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100, // 100% of PR
            prTypeForScaling = PRType.MAX_VOLUME, // Use volume PR
            programMode = ProgramMode.OldSchool
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
            muscleGroup = "Chest"
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = exerciseWithoutId,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 25f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
                volume = 1000f
            )
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
                volume = 1200f
            )
        )

        val routineExerciseOldSchool = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool // Old School mode
        )

        val routineExerciseEcho = RoutineExercise(
            id = "routine-ex-2",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 1,
            weightPerCableKg = 30f,
            usePercentOfPR = true,
            weightPercentOfPR = 100,
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.Echo // Echo mode
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
                volume = 1000f
            )
        )

        val routineExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = testExercise,
            cableConfig = CableConfiguration.DOUBLE,
            orderIndex = 0,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 30f, // Fallback absolute weight
            setWeightsPerCableKg = emptyList(), // No per-set absolute weights
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = emptyList(), // No per-set percentages
            prTypeForScaling = PRType.MAX_WEIGHT,
            programMode = ProgramMode.OldSchool
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
