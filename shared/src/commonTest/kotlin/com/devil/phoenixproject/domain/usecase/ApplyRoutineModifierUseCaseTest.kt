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
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.test.runTest

class ApplyRoutineModifierUseCaseTest {
    private lateinit var prRepository: FakePersonalRecordRepository
    private lateinit var exerciseRepository: FakeExerciseRepository
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
        exerciseRepository = FakeExerciseRepository()
        useCase = ApplyRoutineModifierUseCase(prRepository, exerciseRepository)
    }

    @Test
    fun `active recovery scales weights from stored baseline and keeps working reps`() = runTest {
        exerciseRepository.addExercise(cableExercise.copy(oneRepMaxKg = 100f))
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
        exerciseRepository.addExercise(cableExercise.copy(oneRepMaxKg = 120f))
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
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )
}
