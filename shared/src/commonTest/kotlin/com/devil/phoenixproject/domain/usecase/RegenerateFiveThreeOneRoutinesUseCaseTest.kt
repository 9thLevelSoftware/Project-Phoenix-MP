package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RegenerateFiveThreeOneRoutinesUseCaseTest {
    private lateinit var trainingCycleRepository: FakeTrainingCycleRepository
    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var exerciseRepository: FakeExerciseRepository
    private lateinit var useCase: RegenerateFiveThreeOneRoutinesUseCase

    @BeforeTest
    fun setUp() {
        trainingCycleRepository = FakeTrainingCycleRepository()
        workoutRepository = FakeWorkoutRepository()
        exerciseRepository = FakeExerciseRepository()
        useCase = RegenerateFiveThreeOneRoutinesUseCase(
            trainingCycleRepository = trainingCycleRepository,
            workoutRepository = workoutRepository,
            exerciseRepository = exerciseRepository,
        )
    }

    @Test
    fun `week 1 to 2 rewrites main lifts to week 2 percentages with last-set amrap`() = runTest {
        val cycle = seedFiveThreeOneCycle()

        useCase.execute(cycleId = cycle.id, targetWeek = 2, bumpTrainingMax = false)

        val benchRoutine = workoutRepository.getRoutineById("routine-bench")
        assertNotNull(benchRoutine)
        val benchMainLift = benchRoutine.exercises.first()
        assertEquals(listOf(63, 72, 81), benchMainLift.setWeightsPercentOfPR)
        assertEquals(listOf(3, 3, null), benchMainLift.setReps)
        assertEquals("routine-bench", benchRoutine.id)

        val updatedCycle = trainingCycleRepository.getCycleById(cycle.id)
        assertEquals(2, updatedCycle?.weekNumber)
    }

    @Test
    fun `reordered expected main lift not in slot zero still regenerates and gets tm bump`() = runTest {
        seedFiveThreeOneCycle(
            benchMainLiftPercentages = listOf(61, 70, 79),
            benchMainLiftPosition = 1,
        )

        useCase.execute(cycleId = "cycle-531", targetWeek = 1, bumpTrainingMax = true)

        val benchRoutine = workoutRepository.getRoutineById("routine-bench")
        assertNotNull(benchRoutine)
        val benchMainLift = benchRoutine.exercises[1]
        assertEquals(listOf(59, 68, 77), benchMainLift.setWeightsPercentOfPR)
        assertEquals(listOf(5, 5, null), benchMainLift.setReps)
        assertEquals(101.25f + (1.25f / 0.9f), exerciseRepository.getExerciseById(BENCH_ID)?.oneRepMaxKg)

        val slotZeroAccessory = benchRoutine.exercises.first()
        assertEquals("Incline Bench Press", slotZeroAccessory.exercise.name)
        assertEquals(listOf(10, 10, 10), slotZeroAccessory.setReps)
        assertEquals(listOf(65, 65, 65), slotZeroAccessory.setWeightsPercentOfPR)
    }

    @Test
    fun `same id accessory on the wrong day remains untouched`() = runTest {
        seedFiveThreeOneCycle(squatAccessoryPressPercentages = listOf(65, 65, 65))

        useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false)

        val squatRoutine = workoutRepository.getRoutineById("routine-squat")
        assertNotNull(squatRoutine)
        val accessoryShoulderPress = squatRoutine.exercises[1]
        assertEquals(listOf(10, 10, 10), accessoryShoulderPress.setReps)
        assertEquals(listOf(65, 65, 65), accessoryShoulderPress.setWeightsPercentOfPR)
        assertFalse(accessoryShoulderPress.isAMRAP)

        val pressRoutine = workoutRepository.getRoutineById("routine-press")
        assertNotNull(pressRoutine)
        val pressMainLift = pressRoutine.exercises.first()
        assertEquals(listOf(63, 72, 81), pressMainLift.setWeightsPercentOfPR)
        assertEquals(listOf(3, 3, null), pressMainLift.setReps)
    }

    @Test
    fun `custom day names still regenerate by inspecting routines`() = runTest {
        val cycle = seedFiveThreeOneCycle()
        trainingCycleRepository.updateCycle(
            cycle.copy(
                days = cycle.days.mapIndexed { index, day ->
                    day.copy(name = "Workout ${index + 1}")
                },
            ),
        )

        assertTrue(useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false))

        val benchRoutine = workoutRepository.getRoutineById("routine-bench")
        assertNotNull(benchRoutine)
        val benchMainLift = benchRoutine.exercises.first()
        assertEquals(listOf(63, 72, 81), benchMainLift.setWeightsPercentOfPR)
        assertEquals(listOf(3, 3, null), benchMainLift.setReps)
        assertEquals(2, trainingCycleRepository.getCycleById(cycle.id)?.weekNumber)
    }

    @Test
    fun `extra accessory day without a main lift does not abort regeneration`() = runTest {
        val cycle = seedFiveThreeOneCycle()
        val curl = accessoryExercise(id = "curl", name = "Curl")
        exerciseRepository.addExercise(curl)
        val armsRoutine = Routine(
            id = "routine-arms",
            name = "Arms",
            exercises = orderedExercises(
                accessoryRoutineExercise(id = "re-curl", exercise = curl, reps = listOf(12, 12, 12)),
            ),
        )
        workoutRepository.addRoutine(armsRoutine)
        trainingCycleRepository.updateCycle(
            cycle.copy(
                days = cycle.days + CycleDay.create(
                    id = "day-8",
                    cycleId = cycle.id,
                    dayNumber = 8,
                    name = "Arms",
                    routineId = armsRoutine.id,
                ),
            ),
        )

        assertTrue(useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false))

        assertEquals(2, trainingCycleRepository.getCycleById(cycle.id)?.weekNumber)
        val storedArmsRoutine = workoutRepository.getRoutineById("routine-arms")
        assertNotNull(storedArmsRoutine)
        assertEquals(listOf(12, 12, 12), storedArmsRoutine.exercises.single().setReps)
        assertEquals(listOf(65, 65, 65), storedArmsRoutine.exercises.single().setWeightsPercentOfPR)
    }

    @Test
    fun `deload week rewrites main lifts to 36 45 54 with fixed five reps and no amrap`() = runTest {
        seedFiveThreeOneCycle()

        useCase.execute(cycleId = "cycle-531", targetWeek = 4, bumpTrainingMax = false)

        val squatRoutine = workoutRepository.getRoutineById("routine-squat")
        assertNotNull(squatRoutine)
        val squatMainLift = squatRoutine.exercises.first()
        assertEquals(listOf(36, 45, 54), squatMainLift.setWeightsPercentOfPR)
        assertEquals(listOf(5, 5, 5), squatMainLift.setReps)
        assertFalse(squatMainLift.isAMRAP)
    }

    @Test
    fun `week 4 to 1 bumps upper and lower one rep max values without rounding`() = runTest {
        seedFiveThreeOneCycle()

        useCase.execute(cycleId = "cycle-531", targetWeek = 1, bumpTrainingMax = true)

        assertEquals(101.25f + (1.25f / 0.9f), exerciseRepository.getExerciseById(BENCH_ID)?.oneRepMaxKg)
        assertEquals(88.5f + (1.25f / 0.9f), exerciseRepository.getExerciseById(SHOULDER_PRESS_ID)?.oneRepMaxKg)
        assertEquals(140f + (2.5f / 0.9f), exerciseRepository.getExerciseById(SQUAT_ID)?.oneRepMaxKg)
        assertEquals(160.75f + (2.5f / 0.9f), exerciseRepository.getExerciseById(DEADLIFT_ID)?.oneRepMaxKg)
    }

    @Test
    fun `weeks two and three do not bump one rep max`() = runTest {
        seedFiveThreeOneCycle()

        useCase.execute(cycleId = "cycle-531", targetWeek = 3, bumpTrainingMax = false)

        assertEquals(101.25f, exerciseRepository.getExerciseById(BENCH_ID)?.oneRepMaxKg)
        assertEquals(140f, exerciseRepository.getExerciseById(SQUAT_ID)?.oneRepMaxKg)
    }

    @Test
    fun `null one rep max is skipped while other lifts still bump`() = runTest {
        seedFiveThreeOneCycle(includeNullShoulderPressOneRepMax = true)

        useCase.execute(cycleId = "cycle-531", targetWeek = 1, bumpTrainingMax = true)

        assertNull(exerciseRepository.getExerciseById(SHOULDER_PRESS_ID)?.oneRepMaxKg)
        assertEquals(101.25f + (1.25f / 0.9f), exerciseRepository.getExerciseById(BENCH_ID)?.oneRepMaxKg)
    }

    @Test
    fun `accessories stay untouched while routine ids remain stable`() = runTest {
        seedFiveThreeOneCycle()

        useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false)

        val benchRoutine = workoutRepository.getRoutineById("routine-bench")
        assertNotNull(benchRoutine)
        assertEquals("routine-bench", benchRoutine.id)

        val inclineBench = benchRoutine.exercises[1]
        assertEquals(listOf(10, 10, 10), inclineBench.setReps)
        assertEquals(listOf(65, 65, 65), inclineBench.setWeightsPercentOfPR)

        val plank = benchRoutine.exercises[3]
        assertEquals(listOf(null, null, null), plank.setReps)
        assertEquals(emptyList(), plank.setWeightsPercentOfPR)
    }

    @Test
    fun `week sentinel does not advance when no expected main lifts are matched`() = runTest {
        seedFiveThreeOneCycle(replaceExpectedMainLiftsWithAccessories = true)

        assertFalse(useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false))

        val cycle = trainingCycleRepository.getCycleById("cycle-531")
        assertNotNull(cycle)
        assertEquals(1, cycle.weekNumber)
    }

    @Test
    fun `week sentinel does not advance or partially rewrite when one expected main lift is missing`() = runTest {
        seedFiveThreeOneCycle(replaceBenchMainLiftWithAccessory = true)

        assertFalse(useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false))

        val cycle = trainingCycleRepository.getCycleById("cycle-531")
        assertNotNull(cycle)
        assertEquals(1, cycle.weekNumber)

        val squatRoutine = workoutRepository.getRoutineById("routine-squat")
        assertNotNull(squatRoutine)
        val squatMainLift = squatRoutine.exercises.first()
        assertEquals(listOf(59, 68, 77), squatMainLift.setWeightsPercentOfPR)
        assertEquals(listOf(5, 5, null), squatMainLift.setReps)
    }

    @Test
    fun `week sentinel does not advance when one routine has duplicate same-lift matches`() = runTest {
        val cycle = seedFiveThreeOneCycle(benchMainLiftPosition = 1)
        val bench = exerciseRepository.getExerciseById(BENCH_ID)
        assertNotNull(bench)
        val benchRoutine = workoutRepository.getRoutineById("routine-bench")
        assertNotNull(benchRoutine)
        workoutRepository.updateRoutine(
            benchRoutine.copy(
                exercises = orderedExercises(
                    accessoryRoutineExercise(id = "re-bench-accessory", exercise = bench),
                    benchRoutine.exercises[1],
                    benchRoutine.exercises[2],
                    benchRoutine.exercises[3],
                ),
            ),
        )

        assertFalse(useCase.execute(cycleId = "cycle-531", targetWeek = 2, bumpTrainingMax = false))

        val storedCycle = trainingCycleRepository.getCycleById(cycle.id)
        assertNotNull(storedCycle)
        assertEquals(1, storedCycle.weekNumber)
        val storedBenchRoutine = workoutRepository.getRoutineById("routine-bench")
        assertNotNull(storedBenchRoutine)
        assertEquals(listOf(65, 65, 65), storedBenchRoutine.exercises[0].setWeightsPercentOfPR)
        assertEquals(listOf(59, 68, 77), storedBenchRoutine.exercises[1].setWeightsPercentOfPR)
    }

    private fun seedFiveThreeOneCycle(
        includeNullShoulderPressOneRepMax: Boolean = false,
        benchMainLiftPercentages: List<Int> = listOf(59, 68, 77),
        squatAccessoryPressPercentages: List<Int> = listOf(65, 65, 65),
        benchMainLiftPosition: Int = 0,
        replaceExpectedMainLiftsWithAccessories: Boolean = false,
        replaceBenchMainLiftWithAccessory: Boolean = false,
    ): TrainingCycle {
        val bench = mainLiftExercise(
            id = BENCH_ID,
            name = "Bench Press",
            oneRepMaxKg = 101.25f,
        )
        val squat = mainLiftExercise(
            id = SQUAT_ID,
            name = "Squat",
            oneRepMaxKg = 140f,
        )
        val press = mainLiftExercise(
            id = SHOULDER_PRESS_ID,
            name = "Shoulder Press",
            oneRepMaxKg = if (includeNullShoulderPressOneRepMax) null else 88.5f,
        )
        val deadlift = mainLiftExercise(
            id = DEADLIFT_ID,
            name = "Conventional Deadlift",
            oneRepMaxKg = 160.75f,
        )
        val inclineBench = accessoryExercise(id = "incline", name = "Incline Bench Press")
        val row = accessoryExercise(id = "row", name = "Bent Over Row")
        val plank = Exercise(id = "plank", name = "Plank", muscleGroup = "Core", muscleGroups = "Core", equipment = "")
        val facePull = accessoryExercise(id = "face-pull", name = "Face Pull")
        val lunge = accessoryExercise(id = "lunge", name = "Lunge")
        val tricep = accessoryExercise(id = "tricep", name = "Overhead Tricep Extension")
        val crunch = Exercise(id = "crunch", name = "Crunch", muscleGroup = "Core", muscleGroups = "Core", equipment = "")
        val shrug = accessoryExercise(id = "shrug", name = "Shrug")
        val goodMorning = accessoryExercise(id = "good-morning", name = "Good Morning")

        listOf(bench, squat, press, deadlift, inclineBench, row, plank, facePull, lunge, tricep, crunch, shrug, goodMorning)
            .forEach(exerciseRepository::addExercise)

        val benchMainLift = if (replaceExpectedMainLiftsWithAccessories || replaceBenchMainLiftWithAccessory) {
            accessoryRoutineExercise(id = "re-bench-missing", exercise = inclineBench)
        } else {
            mainLiftRoutineExercise(id = "re-bench", exercise = bench, setWeightsPercentOfPR = benchMainLiftPercentages)
        }
        val benchSlotZero = accessoryRoutineExercise(id = "re-incline", exercise = inclineBench)
        val benchExercises = when (benchMainLiftPosition) {
            0 -> orderedExercises(
                benchMainLift,
                accessoryRoutineExercise(id = "re-incline-2", exercise = inclineBench),
                accessoryRoutineExercise(id = "re-row", exercise = row),
                accessoryRoutineExercise(
                    id = "re-plank",
                    exercise = plank,
                    reps = listOf(null, null, null),
                    usePercentOfPr = false,
                    setWeightsPercentOfPR = emptyList(),
                ),
            )
            1 -> orderedExercises(
                benchSlotZero,
                benchMainLift,
                accessoryRoutineExercise(id = "re-row", exercise = row),
                accessoryRoutineExercise(
                    id = "re-plank",
                    exercise = plank,
                    reps = listOf(null, null, null),
                    usePercentOfPr = false,
                    setWeightsPercentOfPR = emptyList(),
                ),
            )
            else -> error("Unsupported benchMainLiftPosition=$benchMainLiftPosition")
        }

        val squatMainLift = if (replaceExpectedMainLiftsWithAccessories) {
            accessoryRoutineExercise(id = "re-squat-missing", exercise = lunge)
        } else {
            mainLiftRoutineExercise(id = "re-squat", exercise = squat)
        }
        val pressMainLift = if (replaceExpectedMainLiftsWithAccessories) {
            accessoryRoutineExercise(id = "re-press-missing", exercise = tricep)
        } else {
            mainLiftRoutineExercise(id = "re-press", exercise = press)
        }
        val deadliftMainLift = if (replaceExpectedMainLiftsWithAccessories) {
            accessoryRoutineExercise(id = "re-deadlift-missing", exercise = goodMorning)
        } else {
            mainLiftRoutineExercise(id = "re-deadlift", exercise = deadlift)
        }

        val benchRoutine = Routine(
            id = "routine-bench",
            name = "Bench Day",
            exercises = benchExercises,
        )
        val squatRoutine = Routine(
            id = "routine-squat",
            name = "Squat Day",
            exercises = orderedExercises(
                squatMainLift,
                accessoryRoutineExercise(id = "re-press-accessory", exercise = press, setWeightsPercentOfPR = squatAccessoryPressPercentages),
                accessoryRoutineExercise(id = "re-face-pull", exercise = facePull, reps = listOf(15, 15, 15), setWeightsPercentOfPR = listOf(55, 55, 55)),
                accessoryRoutineExercise(id = "re-lunge", exercise = lunge),
            ),
        )
        val pressRoutine = Routine(
            id = "routine-press",
            name = "Press Day",
            exercises = orderedExercises(
                pressMainLift,
                accessoryRoutineExercise(id = "re-tricep", exercise = tricep, reps = listOf(12, 12, 12), setWeightsPercentOfPR = listOf(60, 60, 60)),
                accessoryRoutineExercise(id = "re-row-2", exercise = row),
                accessoryRoutineExercise(id = "re-crunch", exercise = crunch, reps = listOf(15, 15, 15), usePercentOfPr = false, setWeightsPercentOfPR = emptyList()),
            ),
        )
        val deadliftRoutine = Routine(
            id = "routine-deadlift",
            name = "Deadlift Day",
            exercises = orderedExercises(
                deadliftMainLift,
                accessoryRoutineExercise(id = "re-incline-2", exercise = inclineBench),
                accessoryRoutineExercise(id = "re-shrug", exercise = shrug, reps = listOf(12, 12, 12), setWeightsPercentOfPR = listOf(60, 60, 60)),
                accessoryRoutineExercise(id = "re-good-morning", exercise = goodMorning, reps = listOf(12, 12, 12), setWeightsPercentOfPR = listOf(60, 60, 60)),
            ),
        )

        listOf(benchRoutine, squatRoutine, pressRoutine, deadliftRoutine).forEach(workoutRepository::addRoutine)

        val cycle = TrainingCycle.create(
            id = "cycle-531",
            name = "5/3/1",
            weekNumber = 1,
            templateId = "template_531",
            days = listOf(
                CycleDay.create(id = "day-1", cycleId = "cycle-531", dayNumber = 1, name = "Bench", routineId = benchRoutine.id),
                CycleDay.create(id = "day-2", cycleId = "cycle-531", dayNumber = 2, name = "Squat", routineId = squatRoutine.id),
                CycleDay.create(id = "day-3", cycleId = "cycle-531", dayNumber = 3, name = "Press", routineId = pressRoutine.id),
                CycleDay.create(id = "day-4", cycleId = "cycle-531", dayNumber = 4, name = "Deadlift", routineId = deadliftRoutine.id),
                CycleDay.restDay(id = "day-5", cycleId = "cycle-531", dayNumber = 5),
                CycleDay.restDay(id = "day-6", cycleId = "cycle-531", dayNumber = 6),
                CycleDay.restDay(id = "day-7", cycleId = "cycle-531", dayNumber = 7),
            ),
        )
        trainingCycleRepository.addCycle(cycle)
        return cycle
    }

    private fun orderedExercises(vararg exercises: RoutineExercise): List<RoutineExercise> =
        exercises.mapIndexed { index, exercise -> exercise.copy(orderIndex = index) }

    private fun mainLiftExercise(id: String, name: String, oneRepMaxKg: Float?): Exercise = Exercise(
        id = id,
        name = name,
        muscleGroup = "Strength",
        muscleGroups = "Strength",
        equipment = "BAR",
        oneRepMaxKg = oneRepMaxKg,
    )

    private fun accessoryExercise(id: String, name: String): Exercise = Exercise(
        id = id,
        name = name,
        muscleGroup = "Accessory",
        muscleGroups = "Accessory",
        equipment = "BAR",
        oneRepMaxKg = 50f,
    )

    private fun mainLiftRoutineExercise(
        id: String,
        exercise: Exercise,
        setWeightsPercentOfPR: List<Int> = listOf(59, 68, 77),
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = exercise,
        orderIndex = 0,
        setReps = listOf(5, 5, null),
        weightPerCableKg = 40f,
        programMode = ProgramMode.OldSchool,
        isAMRAP = true,
        usePercentOfPR = true,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
    )

    private fun accessoryRoutineExercise(
        id: String,
        exercise: Exercise,
        reps: List<Int?> = listOf(10, 10, 10),
        usePercentOfPr: Boolean = true,
        setWeightsPercentOfPR: List<Int> = listOf(65, 65, 65),
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = exercise,
        orderIndex = 1,
        setReps = reps,
        weightPerCableKg = 25f,
        programMode = ProgramMode.OldSchool,
        isAMRAP = false,
        usePercentOfPR = usePercentOfPr,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
    )

    private companion object {
        const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
        const val SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
        const val SQUAT_ID = "UjIGHxCav-lS9B2I"
        const val DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"
    }
}
