package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.test.runTest

class ApplyRoutineModifierUseCaseTest {
    private lateinit var prRepository: FakePersonalRecordRepository
    private lateinit var baselineRepository: FakeProfileExerciseBaselineRepository
    private lateinit var velocityRepository: FakeVelocityOneRepMaxRepository
    private lateinit var completedSetRepository: FakeCompletedSetRepository
    private lateinit var useCase: ApplyRoutineModifierUseCase

    private val cableExercise = Exercise(
        id = "bench",
        name = "Bench Press",
        muscleGroup = "Chest",
        equipment = "BAR",
    )
    private val bodyweightExercise = Exercise(
        id = "pushup",
        name = "Push-Up",
        muscleGroup = "Chest",
        equipment = "",
    )

    @BeforeTest
    fun setup() {
        prRepository = FakePersonalRecordRepository()
        baselineRepository = FakeProfileExerciseBaselineRepository()
        velocityRepository = FakeVelocityOneRepMaxRepository()
        completedSetRepository = FakeCompletedSetRepository()
        useCase = ApplyRoutineModifierUseCase(prRepository, baselineRepository, velocityRepository, completedSetRepository)
    }

    @Test
    fun `active recovery scales weights from stored baseline and keeps working reps`() = runTest {
        baselineRepository.set("default", "bench", 100f, 1L)
        val routine = routineWith(
            routineExercise(
                weight = 70f,
                setWeights = listOf(70f, 72.5f, 75f),
                reps = listOf(10, 8, 6),
            ),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 55))
        val exercise = adjusted.exercises.single()

        assertEquals(55f, exercise.weightPerCableKg)
        assertEquals(listOf(55f, 55f, 55f), exercise.setWeightsPerCableKg)
        assertEquals(listOf(10, 8, 6), exercise.setReps)
    }

    @Test
    fun `active recovery resolves rep-derived one rep max through the default constructor`() = runTest {
        val now = currentTimeMillis()
        completedSetRepository.registerSessionContext(
            sessionId = "rep-derived-session",
            exerciseId = "bench",
            profileId = "default",
            mode = ProgramMode.OldSchool.displayName,
        )
        completedSetRepository.eligibleWorkingSetsForOneRepMax += CompletedSet(
            id = "rep-derived-set",
            sessionId = "rep-derived-session",
            plannedSetId = null,
            setNumber = 0,
            setType = SetType.STANDARD,
            actualReps = 10,
            actualWeightKg = 90f,
            loggedRpe = null,
            isPr = false,
            completedAt = now,
            setEndReason = SetEndReason.TARGET_REPS_REACHED,
        )
        val routine = routineWith(
            routineExercise(weight = 40f, scalingBasis = ScalingBasis.ESTIMATED_1RM).copy(usePercentOfPR = true),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 80))

        // Brzycki estimates 90 kg x 10 as 120 kg; Active Recovery uses 80% of that baseline.
        assertEquals(96f, adjusted.exercises.single().weightPerCableKg)
        assertEquals(true, adjusted.exercises.single().isLaunchAdjustedByRoutineModifier)
    }

    @Test
    fun `active recovery ignores high-rep set for one rep max and preserves stored baseline`() = runTest {
        val now = currentTimeMillis()
        baselineRepository.seed("default", "bench", 50f, now)
        completedSetRepository.registerSessionContext(
            sessionId = "high-rep-session",
            exerciseId = "bench",
            profileId = "default",
            mode = ProgramMode.OldSchool.displayName,
        )
        completedSetRepository.eligibleWorkingSetsForOneRepMax += CompletedSet(
            id = "high-rep-set",
            sessionId = "high-rep-session",
            plannedSetId = null,
            setNumber = 0,
            setType = SetType.STANDARD,
            actualReps = 30,
            actualWeightKg = 30f,
            loggedRpe = null,
            isPr = false,
            completedAt = now,
            setEndReason = SetEndReason.TARGET_REPS_REACHED,
        )
        val routine = routineWith(
            routineExercise(weight = 40f, scalingBasis = ScalingBasis.ESTIMATED_1RM).copy(usePercentOfPR = true),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 80))

        // The 30-rep candidate is rejected, leaving the stored 50 kg baseline at 80%.
        assertEquals(40f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery drops later warmups and scales first warmup reps`() = runTest {
        val routine = routineWith(
            routineExercise(
                warmups = listOf(WarmupSet(12, 50), WarmupSet(8, 70), WarmupSet(4, 85)),
            ),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        assertEquals(listOf(WarmupSet(6, 50)), adjusted.exercises.single().warmupSets)
    }

    @Test
    fun `heavy deload keeps weights and scales working and warmup reps`() = runTest {
        val routine = routineWith(
            routineExercise(
                weight = 42.5f,
                setWeights = listOf(40f, 42.5f, 45f),
                reps = listOf(10, 8, null),
                warmups = listOf(WarmupSet(12, 50), WarmupSet(8, 70)),
            ),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 50))
        val exercise = adjusted.exercises.single()

        assertEquals(42.5f, exercise.weightPerCableKg)
        assertEquals(listOf(40f, 42.5f, 45f), exercise.setWeightsPerCableKg)
        assertEquals(listOf(5, 4, null), exercise.setReps)
        assertEquals(listOf(WarmupSet(6, 50), WarmupSet(4, 70)), exercise.warmupSets)
        assertEquals(true, exercise.isLaunchAdjustedByRoutineModifier)
    }

    @Test
    fun `heavy deload scales timed exercise duration`() = runTest {
        val routine = routineWith(
            routineExercise(duration = 45, reps = listOf(null)),
            routineExercise(duration = 10, reps = listOf(null)),
            routineExercise(duration = 0, reps = listOf(10)),
            routineExercise(duration = 301, reps = listOf(10)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 50))

        assertEquals(23, adjusted.exercises[0].duration)
        assertEquals(23, adjusted.exercises[0].executionTimedDurationSeconds)
        assertEquals(5, adjusted.exercises[1].duration)
        assertEquals(5, adjusted.exercises[1].executionTimedDurationSeconds)
        assertEquals(null, adjusted.exercises[1].supportedTimedDurationSeconds)
        assertEquals(0, adjusted.exercises[2].duration)
        assertEquals(null, adjusted.exercises[2].executionTimedDurationSeconds)
        assertEquals(301, adjusted.exercises[3].duration)
        assertEquals(null, adjusted.exercises[3].executionTimedDurationSeconds)
    }

    @Test
    fun `bodyweight exercise weight is not modified by active recovery`() = runTest {
        val routine = routineWith(
            routineExercise(exercise = bodyweightExercise, weight = 0f, reps = listOf(15, 12)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 60))

        assertEquals(0f, adjusted.exercises.single().weightPerCableKg)
        assertEquals(listOf(15, 12), adjusted.exercises.single().setReps)
    }

    @Test
    fun `superset metadata and input routine are preserved`() = runTest {
        val superset = Superset(id = "superset-1", routineId = "routine-1", name = "A", orderIndex = 0)
        val originalExercise = routineExercise(supersetId = superset.id, orderInSuperset = 2, warmups = listOf(WarmupSet(12, 50)))
        val routine = Routine(id = "routine-1", name = "Routine", exercises = listOf(originalExercise), supersets = listOf(superset))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 50))
        val adjustedExercise = adjusted.exercises.single()

        assertNotSame(routine, adjusted)
        assertEquals(listOf(WarmupSet(12, 50)), routine.exercises.single().warmupSets)
        assertEquals(superset.id, adjustedExercise.supersetId)
        assertEquals(2, adjustedExercise.orderInSuperset)
        assertEquals(routine.supersets, adjusted.supersets)
    }

    @Test
    fun `active recovery falls back to max weight PR one rep max and rounds to half kg`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 81f,
                reps = 3,
                oneRepMax = 81f,
                timestamp = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 243f,
                phase = WorkoutPhase.CONCENTRIC,
            ),
        )
        val routine = routineWith(routineExercise(weight = 40f))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 55))

        assertEquals(44.5f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery uses supplied profile when falling back to PR one rep max`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 120f,
                reps = 1,
                oneRepMax = 120f,
                timestamp = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 120f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 2L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "profile-b",
            ),
        )
        val routine = routineWith(routineExercise(weight = 40f))

        val adjusted = useCase(
            routine = routine,
            modifier = AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50),
            profileId = "profile-b",
        )

        assertEquals(40f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery prefers profile PR over unscoped stored baseline`() = runTest {
        baselineRepository.set("default", "bench", 120f, 1L)
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "profile-b",
            ),
        )
        val routine = routineWith(routineExercise(weight = 40f))

        val adjusted = useCase(
            routine = routine,
            modifier = AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50),
            profileId = "profile-b",
        )

        assertEquals(40f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `mixed routine applies consistent active recovery semantics at 50 percent`() = runTest {
        // Row 1: 1RM baseline (PR 80) beats programmed 60 -> 40.
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        val programmedOnly = Exercise(id = "row", name = "Cable Row", muscleGroup = "Back", equipment = "BAR")
        val derivedBodyweight = Exercise(id = "windmill", name = "Windmill", muscleGroup = "Core", equipment = "other")
        val perSetOnly = Exercise(id = "facepull", name = "Face Pull", muscleGroup = "Back", equipment = "ROPE")

        val routine = routineWith(
            routineExercise(weight = 60f, setWeights = listOf(60f, 60f, 60f)),
            routineExercise(exercise = programmedOnly, weight = 50f),
            routineExercise(exercise = derivedBodyweight, weight = 10f),
            routineExercise(exercise = bodyweightExercise, weight = 0f),
            routineExercise(exercise = perSetOnly, weight = 0f, setWeights = listOf(40f, 45f)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // PR 80 at 50% -> 40, flat per-set output preserved on the 1RM branch.
        assertEquals(40f, adjusted.exercises[0].weightPerCableKg)
        assertEquals(listOf(40f, 40f, 40f), adjusted.exercises[0].setWeightsPerCableKg)
        // No PR, programmed 50 -> 25.
        assertEquals(25f, adjusted.exercises[1].weightPerCableKg)
        // Positive load on a catalogue-derived bodyweight row: 10 -> 5.
        assertEquals(5f, adjusted.exercises[2].weightPerCableKg)
        // Genuine zero-load bodyweight row keeps 0.
        assertEquals(0f, adjusted.exercises[3].weightPerCableKg)
        // Zero scalar with per-set-only loads: 40/45 -> 20/22.5 with set variation kept.
        assertEquals(20f, adjusted.exercises[4].weightPerCableKg)
        assertEquals(listOf(20f, 22.5f), adjusted.exercises[4].setWeightsPerCableKg)
        // Working reps stay the same on every row.
        adjusted.exercises.forEach { assertEquals(listOf(10, 10, 10), it.setReps) }
    }

    @Test
    fun `active recovery scales positive load on bodyweight-classified rows`() = runTest {
        val otherEquipment = Exercise(id = "windmill", name = "Windmill", muscleGroup = "Core", equipment = "other")
        val nullEquipment = Exercise(id = "crunch", name = "Crunch", muscleGroup = "Core", equipment = "")

        val routine = routineWith(
            routineExercise(exercise = otherEquipment, weight = 10f),
            routineExercise(exercise = nullEquipment, weight = 10f),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // Both rows derive Exercise.isBodyweight = true from their equipment, but positive
        // programmed load still scales (issue #882).
        assertEquals(5f, adjusted.exercises[0].weightPerCableKg)
        assertEquals(5f, adjusted.exercises[1].weightPerCableKg)
    }

    @Test
    fun `active recovery bases max volume scaling on the volume PR`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 200f,
                reps = 1,
                oneRepMax = 200f,
                timestamp = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 200f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 100f,
                reps = 5,
                oneRepMax = 117f,
                timestamp = 2L,
                workoutMode = "Old School",
                prType = PRType.MAX_VOLUME,
                volume = 500f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        val routine = routineWith(routineExercise(weight = 60f, prTypeForScaling = PRType.MAX_VOLUME))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // 50% of the MAX_VOLUME PR baseline (100), not the max-weight PR (200).
        assertEquals(50f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery bases estimated 1rm scaling on the velocity estimate`() = runTest {
        velocityRepository.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench",
            estimatedPerCableKg = 60f,
            mvtUsedMs = 0.5f,
            r2 = 0.99f,
            distinctLoads = 3,
            passedQualityGate = true,
            computedAt = currentTimeMillis(),
            profileId = "default",
        )
        val routine = routineWith(routineExercise(weight = 40f, scalingBasis = ScalingBasis.ESTIMATED_1RM))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        assertEquals(30f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery prefers current mode PR over cross mode PR and stored baseline`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 120f,
                reps = 1,
                oneRepMax = 120f,
                timestamp = 2L,
                workoutMode = "Pump",
                prType = PRType.MAX_WEIGHT,
                volume = 120f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        baselineRepository.set("default", "bench", 200f, 1L)
        val routine = routineWith(routineExercise(weight = 60f))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // 50% of the current-mode PR (80), not the cross-mode PR (120) or stored 1RM (200).
        assertEquals(40f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery falls back to cross mode PR before stored baseline`() = runTest {
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 1L,
                workoutMode = "Pump",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        baselineRepository.set("default", "bench", 200f, 1L)
        val routine = routineWith(routineExercise(weight = 60f))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // 50% of the cross-mode PR (80), not the stored 1RM (200).
        assertEquals(40f, adjusted.exercises.single().weightPerCableKg)
    }

    @Test
    fun `active recovery fallback rounds to half kg and keeps set variation`() = runTest {
        val routine = routineWith(
            routineExercise(weight = 41f),
            routineExercise(weight = 41f, setWeights = listOf(41f, 40f)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 55))

        // 22.55 kg rounds down to 22.5 kg; the scalar-only row keeps an empty set list.
        assertEquals(22.5f, adjusted.exercises[0].weightPerCableKg)
        assertEquals(emptyList(), adjusted.exercises[0].setWeightsPerCableKg)
        // Per-set variation survives the fallback scaling.
        assertEquals(listOf(22.5f, 22f), adjusted.exercises[1].setWeightsPerCableKg)
    }

    @Test
    fun `active recovery never floors positive per set only loads on a zero scalar`() = runTest {
        val routine = routineWith(routineExercise(weight = 0f, setWeights = listOf(40f, 45f)))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))
        val exercise = adjusted.exercises.single()

        assertEquals(listOf(20f, 22.5f), exercise.setWeightsPerCableKg)
        assertEquals(20f, exercise.weightPerCableKg)
    }

    @Test
    fun `active recovery leaves the source routine unmodified`() = runTest {
        val routine = routineWith(
            routineExercise(weight = 60f, setWeights = listOf(60f, 65f), warmups = listOf(WarmupSet(12, 50))),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        assertNotSame(routine, adjusted)
        assertEquals(60f, routine.exercises.single().weightPerCableKg)
        assertEquals(listOf(60f, 65f), routine.exercises.single().setWeightsPerCableKg)
        assertEquals(listOf(WarmupSet(12, 50)), routine.exercises.single().warmupSets)
    }

    @Test
    fun `adjusted loads flow through the execution set weight resolver`() = runTest {
        baselineRepository.set("default", "bench", 100f, 1L)
        val programmedOnly = Exercise(id = "row", name = "Cable Row", muscleGroup = "Back", equipment = "BAR")
        val routine = routineWith(
            routineExercise(weight = 60f),
            routineExercise(exercise = programmedOnly, weight = 0f, setWeights = listOf(40f, 45f)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // 1RM-baseline row: flat 50 (50% of stored 1RM 100) reaches execution.
        assertEquals(50f, executionWeight(adjusted.exercises[0], 0))
        assertEquals(50f, executionWeight(adjusted.exercises[0], 1))
        // Fallback row: per-set variation reaches execution unchanged.
        assertEquals(20f, executionWeight(adjusted.exercises[1], 0))
        assertEquals(22.5f, executionWeight(adjusted.exercises[1], 1))
    }

    // ==================== Issue #1408: Heavy Deload AMRAP scaling ====================

    @Test
    fun `heavy deload converts per set amrap to scaled reps from last amrap history`() = runTest {
        seedCompletedSet(
            sessionId = "amrap-session",
            exerciseId = "bench",
            setNumber = 2,
            actualReps = 10,
            completedAt = 1_000L,
            routineExerciseId = "routine-ex-bench",
        )
        val routine = routineWith(
            routineExercise(
                weight = 45f,
                setWeights = listOf(45f, 45f, 45f),
                reps = listOf(10, 10, null),
            ),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 60))
        val exercise = adjusted.exercises.single()

        // 10 AMRAP reps at 60% -> 6 fixed reps; weights untouched.
        assertEquals(listOf(6, 6, 6), exercise.setReps)
        assertEquals(45f, exercise.weightPerCableKg)
        assertEquals(listOf(45f, 45f, 45f), exercise.setWeightsPerCableKg)
        assertEquals(true, exercise.isLaunchAdjustedByRoutineModifier)
        assertEquals(false, exercise.isAmrapSet(2))
    }

    @Test
    fun `heavy deload keeps per set amrap when no amrap history exists`() = runTest {
        val routine = routineWith(routineExercise(reps = listOf(10, 10, null)))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 60))
        val exercise = adjusted.exercises.single()

        assertEquals(listOf(6, 6, null), exercise.setReps)
        assertEquals(true, exercise.isAmrapSet(2))
    }

    @Test
    fun `heavy deload converts last set amrap flag from history and clears flag on copy`() = runTest {
        seedCompletedSet(
            sessionId = "amrap-session",
            exerciseId = "bench",
            setNumber = 2,
            actualReps = 12,
            completedAt = 1_000L,
            routineExerciseId = "routine-ex-bench",
        )
        val input = routineWith(routineExercise(reps = listOf(10, 10, 10), isAmrap = true))

        val adjusted = useCase(input, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 50))
        val exercise = adjusted.exercises.single()

        // Flag placeholder converts from history: 12 at 50% -> 6; 5 on the fixed sets.
        assertEquals(listOf(5, 5, 6), exercise.setReps)
        assertEquals(false, exercise.isAMRAP)
        assertEquals(false, exercise.isAmrapSet(2))
        // The input routine is untouched: the flag only clears on the copy.
        assertEquals(routineWith(routineExercise(reps = listOf(10, 10, 10), isAmrap = true)), input)
    }

    @Test
    fun `heavy deload leaves last set amrap placeholder unscaled without history`() = runTest {
        val routine = routineWith(routineExercise(reps = listOf(10, 10, 10), isAmrap = true))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 60))
        val exercise = adjusted.exercises.single()

        assertEquals(listOf(6, 6, 10), exercise.setReps)
        assertEquals(true, exercise.isAMRAP)
        assertEquals(true, exercise.isAmrapSet(2))
    }

    @Test
    fun `heavy deload prefers same routine exercise and set number amrap reference`() = runTest {
        // Newest first: other routine (15) > same routine-exercise other set (11) > same slot (9).
        seedCompletedSet(sessionId = "s-other", exerciseId = "bench", setNumber = 1, actualReps = 15, completedAt = 3_000L, routineExerciseId = "other-routine-ex")
        seedCompletedSet(sessionId = "s-same-re", exerciseId = "bench", setNumber = 0, actualReps = 11, completedAt = 2_000L, routineExerciseId = "routine-ex-bench")
        seedCompletedSet(sessionId = "s-slot", exerciseId = "bench", setNumber = 1, actualReps = 9, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val routine = routineWith(routineExercise(reps = listOf(10, null)))

        val sameSlotWins = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 100))
        assertEquals(listOf(10, 9), sameSlotWins.exercises.single().setReps)

        // Drop the same-slot row: the same routine-exercise row wins.
        setup()
        seedCompletedSet(sessionId = "s-other", exerciseId = "bench", setNumber = 1, actualReps = 15, completedAt = 3_000L, routineExerciseId = "other-routine-ex")
        seedCompletedSet(sessionId = "s-same-re", exerciseId = "bench", setNumber = 0, actualReps = 11, completedAt = 2_000L, routineExerciseId = "routine-ex-bench")
        val sameRoutineExerciseWins = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 100))
        assertEquals(listOf(10, 11), sameRoutineExerciseWins.exercises.single().setReps)

        // Drop both: any completed AMRAP set of the exercise is the reference.
        setup()
        seedCompletedSet(sessionId = "s-other", exerciseId = "bench", setNumber = 1, actualReps = 15, completedAt = 3_000L, routineExerciseId = "other-routine-ex")
        val anyRowWorks = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 100))
        assertEquals(listOf(10, 15), anyRowWorks.exercises.single().setReps)
    }

    @Test
    fun `heavy deload ignores standard and zero rep rows as amrap reference`() = runTest {
        seedCompletedSet(sessionId = "s-standard", exerciseId = "bench", setNumber = 0, setType = SetType.STANDARD, actualReps = 6, completedAt = 3_000L, routineExerciseId = "routine-ex-bench")
        seedCompletedSet(sessionId = "s-zero", exerciseId = "bench", setNumber = 0, actualReps = 0, completedAt = 2_000L, routineExerciseId = "routine-ex-bench")
        seedCompletedSet(sessionId = "s-amrap", exerciseId = "bench", setNumber = 0, actualReps = 10, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val routine = routineWith(routineExercise(reps = listOf(null)))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 50))

        // The older AMRAP 10 row is the only valid reference: 10 at 50% -> 5.
        assertEquals(listOf(5), adjusted.exercises.single().setReps)
    }

    @Test
    fun `heavy deload queries history with the supplied profile`() = runTest {
        seedCompletedSet(
            sessionId = "p1-session",
            exerciseId = "bench",
            setNumber = 2,
            actualReps = 10,
            completedAt = 1_000L,
            routineExerciseId = "routine-ex-bench",
            profileId = "p1",
        )
        val routine = routineWith(routineExercise(reps = listOf(10, 10, null)))

        val adjusted = useCase(
            routine = routine,
            modifier = AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 60),
            profileId = "p2",
        )

        assertEquals(listOf("p2"), completedSetRepository.recentCompletedSetRequests.map { it.third })
        // The p1-only history is invisible to p2: the AMRAP set stays AMRAP.
        assertEquals(listOf(6, 6, null), adjusted.exercises.single().setReps)
    }

    @Test
    fun `heavy deload leaves bodyweight and timed amrap sets unchanged by history`() = runTest {
        seedCompletedSet(sessionId = "s-bw", exerciseId = "pushup", setNumber = 1, actualReps = 12, completedAt = 2_000L, routineExerciseId = "routine-ex-pushup")
        seedCompletedSet(sessionId = "s-timed", exerciseId = "bench", setNumber = 0, actualReps = 12, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val routine = routineWith(
            routineExercise(exercise = bodyweightExercise, weight = 0f, reps = listOf(10, null)),
            routineExercise(duration = 30, reps = listOf(null)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 60))

        // Bodyweight row: today's output — programmed reps scale, AMRAP entries stay null.
        assertEquals(listOf(6, null), adjusted.exercises[0].setReps)
        assertEquals(0f, adjusted.exercises[0].weightPerCableKg)
        // Timed row: today's output — AMRAP entries stay null, duration scales.
        assertEquals(listOf(null), adjusted.exercises[1].setReps)
        assertEquals(18, adjusted.exercises[1].duration)
        assertEquals(true, adjusted.exercises[1].isLaunchAdjustedDuration)
        // Ineligible exercises never read AMRAP history.
        assertEquals(0, completedSetRepository.recentCompletedSetRequests.size)
    }

    @Test
    fun `heavy deload rounds history reps and floors at one`() = runTest {
        seedCompletedSet(sessionId = "s-round", exerciseId = "bench", setNumber = 0, actualReps = 7, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val sevenAtSixty = useCase(routineWith(routineExercise(reps = listOf(null))), AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 60))
        // 7 x 60% = 4.2 rounds to 4.
        assertEquals(listOf(4), sevenAtSixty.exercises.single().setReps)

        setup()
        seedCompletedSet(sessionId = "s-floor", exerciseId = "bench", setNumber = 0, actualReps = 1, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val oneAtTen = useCase(routineWith(routineExercise(reps = listOf(null))), AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 10))
        // 1 x 10% = 0.1 rounds to 0 and floors at 1.
        assertEquals(listOf(1), oneAtTen.exercises.single().setReps)
    }

    @Test
    fun `heavy deload queries history once per exercise`() = runTest {
        seedCompletedSet(sessionId = "s1", exerciseId = "bench", setNumber = 1, actualReps = 10, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val routine = routineWith(routineExercise(reps = listOf(10, null, null)))

        useCase(routine, AppliedRoutineModifier(RoutineModifierType.HEAVY_DELOAD, 50))

        // Two AMRAP sets, one exercise: a single bounded lookup (limit 50, supplied profile).
        assertEquals(1, completedSetRepository.recentCompletedSetRequests.size)
        assertEquals(listOf("bench"), completedSetRepository.recentCompletedSetRequests.map { it.first })
        assertEquals(listOf(50), completedSetRepository.recentCompletedSetRequests.map { it.second })
        assertEquals(listOf("default"), completedSetRepository.recentCompletedSetRequests.map { it.third })
    }

    @Test
    fun `active recovery does not read amrap history`() = runTest {
        seedCompletedSet(sessionId = "s1", exerciseId = "bench", setNumber = 1, actualReps = 10, completedAt = 1_000L, routineExerciseId = "routine-ex-bench")
        val routine = routineWith(routineExercise(weight = 60f, reps = listOf(10, null)))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        assertEquals(0, completedSetRepository.recentCompletedSetRequests.size)
        assertEquals(listOf(10, null), adjusted.exercises.single().setReps)
        assertEquals(30f, adjusted.exercises.single().weightPerCableKg)
    }

    /**
     * Seeds one completed set with explicit [completedAt] ordering: registers the session
     * scope (exercise/profile/mode) the way production writes do, then stores the row.
     */
    private suspend fun seedCompletedSet(
        sessionId: String,
        exerciseId: String,
        setNumber: Int,
        actualReps: Int,
        completedAt: Long,
        setType: SetType = SetType.AMRAP,
        routineExerciseId: String? = null,
        profileId: String = "default",
    ) {
        completedSetRepository.registerSessionContext(
            sessionId = sessionId,
            exerciseId = exerciseId,
            profileId = profileId,
            mode = ProgramMode.OldSchool.displayName,
        )
        completedSetRepository.insertCompletedSet(
            CompletedSet(
                id = "$sessionId-set-$setNumber-at-$completedAt",
                sessionId = sessionId,
                plannedSetId = null,
                setNumber = setNumber,
                setType = setType,
                actualReps = actualReps,
                actualWeightKg = 45f,
                loggedRpe = null,
                isPr = false,
                completedAt = completedAt,
                routineExerciseId = routineExerciseId,
            ),
        )
    }

    private fun executionWeight(exercise: RoutineExercise, setIndex: Int): Float = RoutineSetWeightResolver(
        RoutineSetWeightRequest(
            exercise = exercise,
            setIndex = setIndex,
            currentPrKg = null,
        ),
    )

    private fun routineWith(vararg exercises: RoutineExercise): Routine = Routine(
        id = "routine-1",
        name = "Routine",
        exercises = exercises.toList(),
    )

    private fun routineExercise(
        exercise: Exercise = cableExercise,
        weight: Float = 50f,
        setWeights: List<Float> = emptyList(),
        reps: List<Int?> = listOf(10, 10, 10),
        warmups: List<WarmupSet> = emptyList(),
        duration: Int? = null,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
        prTypeForScaling: PRType = PRType.MAX_WEIGHT,
        scalingBasis: ScalingBasis? = null,
        isAmrap: Boolean = false,
    ): RoutineExercise = RoutineExercise(
        id = "routine-ex-${exercise.id}",
        exercise = exercise,
        orderIndex = 0,
        setReps = reps,
        weightPerCableKg = weight,
        setWeightsPerCableKg = setWeights,
        programMode = ProgramMode.OldSchool,
        warmupSets = warmups,
        duration = duration,
        isAMRAP = isAmrap,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
        prTypeForScaling = prTypeForScaling,
        scalingBasis = scalingBasis,
    )
}
