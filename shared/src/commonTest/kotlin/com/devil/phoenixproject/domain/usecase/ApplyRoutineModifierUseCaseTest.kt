package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutPhase
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
        useCase = ApplyRoutineModifierUseCase(
            ResolveRoutineScalingBaselineUseCase(prRepository, baselineRepository, velocityRepository),
        )
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

        // Percent-of-baseline semantics: the scalar lands exactly on 55% of the 100 kg baseline,
        // and programmed per-set variation is preserved proportionally with per-set half-kg
        // rounding (issue #882: per-set weights must not be flattened).
        assertEquals(55f, exercise.weightPerCableKg)
        assertEquals(listOf(55f, 57f, 59f), exercise.setWeightsPerCableKg)
        assertEquals(listOf(10, 8, 6), exercise.setReps)
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

    // ===== Issue #882: mixed-routine application matrix =====

    @Test
    fun `active recovery mixed routine scales positive loads and leaves zero load rows`() = runTest {
        // Baseline row: 80 kg max-weight PR in the exercise's own mode.
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
            ),
        )
        val noPrExercise = Exercise(id = "squat", name = "Back Squat", muscleGroup = "Legs", equipment = "BARBELL")
        // Issue #635 class: unknown equipment derives isBodyweight=true despite positive load.
        val misclassifiedExercise = Exercise(id = "row", name = "Seated Cable Row", muscleGroup = "Back", equipment = "other")
        val perSetExercise = Exercise(id = "fly", name = "Cable Fly", muscleGroup = "Chest", equipment = "CABLE")

        val routine = routineWith(
            routineExercise(exercise = cableExercise, weight = 60f),
            routineExercise(exercise = noPrExercise, weight = 50f),
            routineExercise(exercise = misclassifiedExercise, weight = 10f),
            routineExercise(exercise = bodyweightExercise, weight = 0f),
            routineExercise(exercise = perSetExercise, weight = 0f, setWeights = listOf(40f, 45f)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        // PR 80 / programmed 60 at 50% -> 40 (percent-of-1RM).
        assertEquals(40f, adjusted.exercises[0].weightPerCableKg)
        // No-PR programmed 50 -> 25 (percent-of-programmed-load fallback).
        assertEquals(25f, adjusted.exercises[1].weightPerCableKg)
        // Positive-load 'other'-classified 10 -> 5 (load-based eligibility, not isBodyweight).
        assertEquals(5f, adjusted.exercises[2].weightPerCableKg)
        // Genuine zero-load bodyweight -> 0 (untouched).
        assertEquals(0f, adjusted.exercises[3].weightPerCableKg)
        assertEquals(emptyList(), adjusted.exercises[3].setWeightsPerCableKg)
        // Zero scalar / per-set 40 -> 20 with distinct set variation preserved.
        assertEquals(0f, adjusted.exercises[4].weightPerCableKg)
        assertEquals(listOf(20f, 22.5f), adjusted.exercises[4].setWeightsPerCableKg)
    }

    @Test
    fun `active recovery scales misclassified bodyweight rows that carry load`() = runTest {
        // Empty equipment derives isBodyweight=true (pre-migration-39 rows).
        val derivedBodyweight = Exercise(id = "row", name = "Seated Cable Row", muscleGroup = "Back", equipment = "")
        // Explicit bodyweight flag on a loaded cable lift (retired portal 'Bodyweight' sentinel).
        val sentinelBodyweight = Exercise(
            id = "fly",
            name = "Cable Fly",
            muscleGroup = "Chest",
            equipment = "CABLE",
            isBodyweightOverride = true,
        )
        val routine = routineWith(
            routineExercise(exercise = derivedBodyweight, weight = 50f, setWeights = listOf(50f, 50f)),
            routineExercise(exercise = sentinelBodyweight, weight = 40f, setWeights = listOf(40f, 42f)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        assertEquals(25f, adjusted.exercises[0].weightPerCableKg)
        assertEquals(listOf(25f, 25f), adjusted.exercises[0].setWeightsPerCableKg)
        assertEquals(20f, adjusted.exercises[1].weightPerCableKg)
        assertEquals(listOf(20f, 21f), adjusted.exercises[1].setWeightsPerCableKg)
    }

    @Test
    fun `active recovery no baseline fallback preserves per set variation`() = runTest {
        val routine = routineWith(
            routineExercise(weight = 60f, setWeights = listOf(60f, 63f, 66f)),
        )

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))
        val exercise = adjusted.exercises.single()

        assertEquals(30f, exercise.weightPerCableKg)
        assertEquals(listOf(30f, 31.5f, 33f), exercise.setWeightsPerCableKg)
    }

    @Test
    fun `active recovery uses cross mode PR baseline like load time resolution`() = runTest {
        // PR exists only in Pump mode while the routine runs Old School: the shared resolver's
        // same-profile cross-mode fallback (used at load time) must also drive the modifier.
        prRepository.addRecord(
            PersonalRecord(
                exerciseId = "incline",
                exerciseName = "Incline DB Press",
                weightPerCableKg = 100f,
                reps = 1,
                oneRepMax = 100f,
                timestamp = 1L,
                workoutMode = "Pump",
                prType = PRType.MAX_WEIGHT,
                volume = 100f,
                phase = WorkoutPhase.CONCENTRIC,
            ),
        )
        val incline = Exercise(id = "incline", name = "Incline DB Press", muscleGroup = "Chest", equipment = "DUMBBELL")
        val routine = routineWith(routineExercise(exercise = incline, weight = 80f, setWeights = listOf(80f, 80f)))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50))

        assertEquals(50f, adjusted.exercises.single().weightPerCableKg)
        assertEquals(listOf(50f, 50f), adjusted.exercises.single().setWeightsPerCableKg)
    }

    @Test
    fun `active recovery never fabricates a floor weight for tiny loads`() = runTest {
        val routine = routineWith(routineExercise(weight = 0.6f))

        val adjusted = useCase(routine, AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 10))

        assertEquals(0f, adjusted.exercises.single().weightPerCableKg)
    }

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
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )
}
