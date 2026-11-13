package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.OneRepMaxCalculator
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
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
    private lateinit var baselineRepository: FakeProfileExerciseBaselineRepository
    private lateinit var velocityRepository: FakeVelocityOneRepMaxRepository
    private lateinit var completedSetRepository: FakeCompletedSetRepository
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
        baselineRepository = FakeProfileExerciseBaselineRepository()
        velocityRepository = FakeVelocityOneRepMaxRepository()
        completedSetRepository = FakeCompletedSetRepository()
        useCase = ResolveRoutineWeightsUseCase(prRepository, baselineRepository, velocityRepository, completedSetRepository)
    }

    @Test
    fun `Echo mode does not use rep-derived completed working sets`() = runTest {
        val now = currentTimeMillis()
        completedSetRepository.eligibleWorkingSetsForOneRepMax += CompletedSet(
            id = "echo-completed-set",
            sessionId = "echo-session",
            plannedSetId = "echo-planned-set",
            setNumber = 0,
            setType = SetType.STANDARD,
            actualReps = 10,
            actualWeightKg = 50f,
            loggedRpe = null,
            isPr = false,
            completedAt = now - 1_000L,
            setEndReason = SetEndReason.TARGET_REPS_REACHED,
        )
        val resolver = ResolveRoutineScalingBaselineUseCase(
            prRepository = prRepository,
            baselineRepository = baselineRepository,
            velocityOneRepMaxRepository = velocityRepository,
            completedSetRepository = completedSetRepository,
            nowMillis = { now },
        )

        val result = resolver(
            exerciseId = "bench-press",
            mode = ProgramMode.Echo,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNull(result)
    }

    @Test
    fun `modifier-adjusted working set cannot create a rep-derived baseline`() = runTest {
        val now = currentTimeMillis()
        completedSetRepository.registerSessionContext(
            sessionId = "active-recovery-session",
            exerciseId = "bench-press",
            profileId = "default",
            mode = ProgramMode.OldSchool.displayName,
            routineModifierApplied = true,
        )
        completedSetRepository.eligibleWorkingSetsForOneRepMax += CompletedSet(
            id = "active-recovery-set",
            sessionId = "active-recovery-session",
            plannedSetId = "planned-set",
            setNumber = 0,
            setType = SetType.STANDARD,
            actualReps = 10,
            actualWeightKg = 50f,
            loggedRpe = null,
            isPr = false,
            completedAt = now,
            setEndReason = SetEndReason.TARGET_REPS_REACHED,
        )
        val resolver = ResolveRoutineScalingBaselineUseCase(
            prRepository = prRepository,
            baselineRepository = baselineRepository,
            velocityOneRepMaxRepository = velocityRepository,
            completedSetRepository = completedSetRepository,
            nowMillis = { now },
        )

        val result = resolver(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNull(result)
    }

    @Test
    fun `expired passing velocity estimate falls through to stored 1RM`() = runTest {
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = 1L,
            profileId = "default",
        )
        baselineRepository.set("default", "bench-press", 120f, 1L)

        val result = useCase(
            RoutineExercise(
                id = "routine-ex-expired-velocity",
                exercise = testExercise,
                orderIndex = 0,
                weightPerCableKg = 30f,
                usePercentOfPR = true,
                weightPercentOfPR = 80,
                scalingBasis = ScalingBasis.ESTIMATED_1RM,
                programMode = ProgramMode.OldSchool,
            ),
        )

        assertEquals(96f, result.baseWeight)
        assertEquals(120f, result.usedPR)
        assertTrue(result.isFromPR)
    }

    @Test
    fun `exact 28 day velocity boundary remains valid and reports its age`() = runTest {
        val now = currentTimeMillis()
        val twentyEightDays = 28L * 24L * 60L * 60L * 1_000L
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 102L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now - twentyEightDays,
            profileId = "default",
        )
        val resolver = ResolveRoutineScalingBaselineUseCase(
            prRepository = prRepository,
            baselineRepository = baselineRepository,
            velocityOneRepMaxRepository = velocityRepository,
            completedSetRepository = completedSetRepository,
            nowMillis = { now },
        )

        val result = resolver(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.VELOCITY_ESTIMATE, result.source)
        assertEquals(28L, result.sourceAgeDays)
    }

    @Test
    fun `future velocity timestamp is rejected in favor of stored 1RM`() = runTest {
        val now = currentTimeMillis()
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 103L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now + 1L,
            profileId = "default",
        )
        baselineRepository.set("default", "bench-press", 85f, now)
        val resolver = ResolveRoutineScalingBaselineUseCase(
            prRepository = prRepository,
            baselineRepository = baselineRepository,
            velocityOneRepMaxRepository = velocityRepository,
            completedSetRepository = completedSetRepository,
            nowMillis = { now },
        )

        val result = resolver(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.STORED_ONE_REP_MAX, result.source)
        assertEquals(85f, result.weightPerCableKg)
    }

    @Test
    fun `eligible newer constant-load set displaces a contradicted velocity estimate`() = runTest {
        val now = currentTimeMillis()
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 101L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 80f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now - 1_000L,
            profileId = "default",
        )
        baselineRepository.set("default", "bench-press", 60f, 1L)
        completedSetRepository.registerSessionContext(
            sessionId = "eligible-session",
            exerciseId = "bench-press",
            profileId = "default",
            mode = ProgramMode.OldSchool.displayName,
        )
        completedSetRepository.eligibleWorkingSetsForOneRepMax += CompletedSet(
            id = "eligible-bench-set",
            sessionId = "eligible-session",
            plannedSetId = "planned-set-1",
            setNumber = 0,
            setType = SetType.STANDARD,
            actualReps = 10,
            actualWeightKg = 90f,
            loggedRpe = null,
            isPr = false,
            completedAt = now - 500L,
            setEndReason = SetEndReason.TARGET_REPS_REACHED,
        )

        val weightsUseCase = ResolveRoutineWeightsUseCase(
            prRepository = prRepository,
            baselineRepository = baselineRepository,
            velocityOneRepMaxRepository = velocityRepository,
            completedSetRepository = completedSetRepository,
        )
        val result = weightsUseCase(
            RoutineExercise(
                id = "routine-ex-rep-derived-fallback",
                exercise = testExercise,
                orderIndex = 0,
                weightPerCableKg = 30f,
                usePercentOfPR = true,
                weightPercentOfPR = 80,
                scalingBasis = ScalingBasis.ESTIMATED_1RM,
                programMode = ProgramMode.OldSchool,
            ),
        )

        assertEquals(96f, result.baseWeight)
        assertEquals(120f, result.usedPR)
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
        baselineRepository.set("default", "bench-press", 100f, 1L)

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
        baselineRepository.set("default", "bench-press", 100f, 1L)
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
            computedAt = currentTimeMillis(),
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
        // Given: velocity repo returns null and the active profile has a scoped 120 kg baseline.
        velocityRepository.latestPassing = null
        baselineRepository.set("default", "bench-press", 120f, 1L)

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
    fun `ESTIMATED_1RM keeps absolute weight when only a raw max-weight PR exists`() = runTest {
        // Raw max-weight PRs and phase peaks are not validated 1RM inputs.
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

        assertEquals(30f, result.baseWeight)
        assertNull(result.usedPR)
        assertNull(result.percentOfPR)
        assertFalse(result.isFromPR)
        assertNotNull(result.fallbackReason)
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
            computedAt = currentTimeMillis(), // fresh: the floor value must be rejected by isUsableEstimate, not by staleness
            profileId = "default",
        )
        baselineRepository.set("default", "bench-press", 120f, 1L)

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
    fun `ESTIMATED_1RM keeps absolute weight when a floor-clamped velocity row and raw max-weight PR exist`() = runTest {
        // Neither a rejected velocity estimate nor a raw max-weight PR is a valid 1RM baseline.
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 2L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 1.0f,
            mvtUsedMs = 0.3f,
            r2 = 0.95f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = currentTimeMillis(),
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

        // Only the saved absolute load is safe without an eligible 1RM source.
        assertEquals(30f, result.baseWeight)
        assertNull(result.usedPR)
        assertNull(result.percentOfPR)
        assertFalse(result.isFromPR)
        assertNotNull(result.fallbackReason)
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
            computedAt = currentTimeMillis(),
            profileId = "default",
        )
        baselineRepository.set("default", "bench-press", 120f, 1L)

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

    // ========== Issue #1383 review fixes: rep-derived tier policy ==========

    private fun eligibleWorkingSet(
        id: String,
        sessionId: String,
        weightKg: Float,
        reps: Int,
        completedAt: Long,
    ) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = 0,
        setType = SetType.STANDARD,
        actualReps = reps,
        actualWeightKg = weightKg,
        loggedRpe = null,
        isPr = false,
        completedAt = completedAt,
        setEndReason = SetEndReason.TARGET_REPS_REACHED,
    )

    private fun registerOldSchoolSession(sessionId: String) {
        completedSetRepository.registerSessionContext(
            sessionId = sessionId,
            exerciseId = "bench-press",
            profileId = "default",
            mode = ProgramMode.OldSchool.displayName,
        )
    }

    private fun estimatorResolver(now: Long) = ResolveRoutineScalingBaselineUseCase(
        prRepository = prRepository,
        baselineRepository = baselineRepository,
        velocityOneRepMaxRepository = velocityRepository,
        completedSetRepository = completedSetRepository,
        nowMillis = { now },
    )

    // (a) rep-bound: hybrid Brzycki/Epley estimates beyond ~12 reps are not comparable evidence.
    @Test
    fun `rep-derived tier ignores eligible working sets above the 12 rep bound`() = runTest {
        val now = currentTimeMillis()
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 200L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now - 1_000L,
            profileId = "default",
        )
        registerOldSchoolSession("high-rep-session")
        // A 13-rep row's out-of-range Epley estimate (286.7) would contradict and displace
        // the velocity estimate if the rep bound did not reject the row outright.
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "high-rep-set",
            sessionId = "high-rep-session",
            weightKg = 200f,
            reps = 13,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.VELOCITY_ESTIMATE, result.source)
        assertEquals(100f, result.weightPerCableKg)
    }

    // (b) contradiction rule: only a newer multi-rep set whose 1RM estimate clears the
    // velocity estimate by the margin contradicts it.
    @Test
    fun `newer multi-rep set whose estimate clears the margin contradicts and displaces the velocity estimate`() = runTest {
        val now = currentTimeMillis()
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 201L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now - 1_000L,
            profileId = "default",
        )
        registerOldSchoolSession("contradicting-session")
        // Brzycki(80, 10) = 106.7 > 100 * 1.05 = 105 → contradicts and displaces.
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "contradicting-set",
            sessionId = "contradicting-session",
            weightKg = 80f,
            reps = 10,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.REP_DERIVED_ESTIMATE, result.source)
        assertEquals(OneRepMaxCalculator.estimate(80f, 10), result.weightPerCableKg)
    }

    @Test
    fun `newer single-rep set does not contradict a quality-passing velocity estimate`() = runTest {
        val now = currentTimeMillis()
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 202L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now - 1_000L,
            profileId = "default",
        )
        registerOldSchoolSession("single-session")
        // Singles must not displace a multi-load quality-gated velocity estimate even when
        // the raw load exceeds it.
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "single-set",
            sessionId = "single-session",
            weightKg = 110f,
            reps = 1,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.VELOCITY_ESTIMATE, result.source)
        assertEquals(100f, result.weightPerCableKg)
    }

    @Test
    fun `newer set whose estimate stays within the contradiction margin does not displace the velocity estimate`() = runTest {
        val now = currentTimeMillis()
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 203L,
            exerciseId = "bench-press",
            estimatedPerCableKg = 100f,
            mvtUsedMs = 0.3f,
            r2 = 0.98f,
            distinctLoads = 4,
            passedQualityGate = true,
            computedAt = now - 1_000L,
            profileId = "default",
        )
        registerOldSchoolSession("within-margin-session")
        // Brzycki(78, 10) = 104.0 <= 100 * 1.05 = 105 → no contradiction.
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "within-margin-set",
            sessionId = "within-margin-session",
            weightKg = 78f,
            reps = 10,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.VELOCITY_ESTIMATE, result.source)
        assertEquals(100f, result.weightPerCableKg)
    }

    // (c) precedence margin: a noisy eligible set cannot silently demote a stored 1RM.
    @Test
    fun `rep-derived estimate below the stored 1RM margin keeps the labeled stored baseline`() = runTest {
        val now = currentTimeMillis()
        baselineRepository.set("default", "bench-press", 105f, now - 2_000L)
        registerOldSchoolSession("below-margin-session")
        // Brzycki(70, 10) = 93.3 <= 105 * 1.05 = 110.25 → margin not cleared.
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "below-margin-set",
            sessionId = "below-margin-session",
            weightKg = 70f,
            reps = 10,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.STORED_ONE_REP_MAX, result.source)
        assertEquals("Stored 1RM", result.source.displayLabel)
        assertEquals(105f, result.weightPerCableKg)
        assertNotNull(result.resolutionReason)
        assertTrue(result.resolutionReason.orEmpty().contains("did not clear the stored 1RM by the required margin"))
    }

    @Test
    fun `rep-derived estimate clearing the stored 1RM margin wins over the stored baseline`() = runTest {
        val now = currentTimeMillis()
        baselineRepository.set("default", "bench-press", 100f, now - 2_000L)
        registerOldSchoolSession("above-margin-session")
        // estimate(100, 5) = 112.5 > 100 * 1.05 = 105 → margin cleared, rep-derived wins.
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "above-margin-set",
            sessionId = "above-margin-session",
            weightKg = 100f,
            reps = 5,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.OldSchool,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.REP_DERIVED_ESTIMATE, result.source)
        assertEquals(OneRepMaxCalculator.estimate(100f, 5), result.weightPerCableKg)
    }

    // (d) Echo mode routes the rep-derived tier empty.
    @Test
    fun `Echo mode returns no rep-derived tier`() = runTest {
        val now = currentTimeMillis()
        baselineRepository.set("default", "bench-press", 120f, 1L)
        completedSetRepository.registerSessionContext(
            sessionId = "echo-session",
            exerciseId = "bench-press",
            profileId = "default",
            mode = ProgramMode.Echo.displayName,
        )
        completedSetRepository.eligibleWorkingSetsForOneRepMax += eligibleWorkingSet(
            id = "echo-set",
            sessionId = "echo-session",
            weightKg = 90f,
            reps = 10,
            completedAt = now - 500L,
        )

        val result = estimatorResolver(now)(
            exerciseId = "bench-press",
            mode = ProgramMode.Echo,
            profileId = "default",
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(result)
        assertEquals(RoutineScalingBaselineSource.STORED_ONE_REP_MAX, result.source)
        assertEquals(120f, result.weightPerCableKg)
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
