package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.SqlDelightPersonalRecordRepository
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExerciseConfigViewModelTest {

    @Test
    fun `initialize detects bodyweight exercise and forces duration mode`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-1",
            exercise = Exercise(
                id = "bw-1",
                name = "Plank",
                muscleGroup = "Core",
                muscleGroups = "Core",
                equipment = "",
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 0f,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        assertEquals(ExerciseType.BODYWEIGHT, viewModel.exerciseType.value)
        assertEquals(SetMode.DURATION, viewModel.setMode.value)
    }

    @Test
    fun `onSave applies uniform rest time when per-set rest disabled`() = runTest {
        val viewModel = ExerciseConfigViewModel()
        val exercise = RoutineExercise(
            id = "rex-2",
            exercise = Exercise(
                id = "bench-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
            orderIndex = 0,
            setReps = listOf(10, 8),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setRestSeconds = listOf(60, 60),
            perSetRestTime = true,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        viewModel.onRestChange(90)
        viewModel.onPerSetRestTimeChange(false)

        val firstSetId = viewModel.sets.value.firstOrNull()?.id
        assertNotNull(firstSetId)
        viewModel.updateReps(firstSetId, 12)

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(12, saved.setReps.first())
        assertEquals(listOf(90, 90), saved.setRestSeconds)
    }

    @Test
    fun `percent of PR syncs visible set weights and saves resolved snapshots`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-sync",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f, 5f),
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        insertWeightPR(queries, weight = 50.0)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        assertEquals(5f, viewModel.sets.value.first().weightPerCable)

        waitForCondition { viewModel.currentExercisePR.value?.weightPerCableKg == 50f }
        viewModel.onUsePercentOfPRChange(true)

        assertEquals(listOf(40f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })
        viewModel.addSet()
        assertEquals(listOf(40f, 40f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })
        viewModel.deleteSet(3)
        assertEquals(listOf(40f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertTrue(saved.usePercentOfPR)
        assertEquals(40f, saved.weightPerCableKg)
        assertEquals(listOf(40f, 40f, 40f), saved.setWeightsPerCableKg)
        assertEquals(listOf(80, 80, 80), saved.setWeightsPercentOfPR)
    }

    @Test
    fun `percent of PR uses nearest half kg rounding when syncing set weights`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-rounding",
            setReps = listOf(10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        insertWeightPR(queries, weight = 47.0)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )

        waitForCondition { viewModel.sets.value.first().weightPerCable == 37.5f }
        assertEquals(37.5f, viewModel.calculateResolvedWeight())
        assertEquals(37.5f, viewModel.sets.value.first().weightPerCable)
    }

    @Test
    fun `global PR percent preserves custom per-set percentages`() = runTest {
        val repository = FakePersonalRecordRepository()
        repository.addRecord(weightPR(weight = 50f))
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-custom",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f, 5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(80, 80, 80),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        advanceUntilIdle()
        waitForCondition { viewModel.sets.value.map { it.weightPerCable } == listOf(40f, 40f, 40f) }

        viewModel.updateWeight(viewModel.sets.value.first().id, 45f)
        assertEquals(listOf(45f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })

        viewModel.onWeightPercentOfPRChange(85)
        assertEquals(listOf(45f, 40f, 40f), viewModel.sets.value.map { it.weightPerCable })

        viewModel.addSet()
        assertEquals(listOf(45f, 40f, 40f, 42.5f), viewModel.sets.value.map { it.weightPerCable })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(85, saved.weightPercentOfPR)
        assertEquals(listOf(90, 80, 80, 85), saved.setWeightsPercentOfPR)
        assertEquals(listOf(45f, 40f, 40f, 42.5f), saved.setWeightsPerCableKg)
    }

    @Test
    fun `manual PR percent weight edit before PR load converts when PR becomes available`() = runTest {
        val repository = FakePersonalRecordRepository()
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-pending",
            setReps = listOf(10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(80, 80),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        advanceUntilIdle()
        assertNull(viewModel.currentExercisePR.value)

        viewModel.updateWeight(viewModel.sets.value.first().id, 25f)
        assertEquals(listOf(25f, 5f), viewModel.sets.value.map { it.weightPerCable })

        repository.addRecord(weightPR(weight = 50f))
        viewModel.loadPRForExercise("bench-1", "Old School")
        advanceUntilIdle()

        waitForCondition { viewModel.sets.value.map { it.weightPerCable } == listOf(25f, 40f) }

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(listOf(50, 80), saved.setWeightsPercentOfPR)
        assertEquals(listOf(25f, 40f), saved.setWeightsPerCableKg)
    }

    @Test
    fun `delete set while PR percent disabled keeps percentages aligned when re-enabled`() = runTest {
        val repository = FakePersonalRecordRepository()
        repository.addRecord(weightPR(weight = 50f))
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = benchRoutineExercise(
            id = "rex-pr-delete-disabled",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 5f,
            setWeightsPerCableKg = listOf(5f, 5f, 5f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(80, 90, 100),
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
        )
        advanceUntilIdle()
        waitForCondition { viewModel.sets.value.map { it.weightPerCable } == listOf(40f, 45f, 50f) }

        viewModel.onUsePercentOfPRChange(false)
        viewModel.deleteSet(0)
        assertEquals(listOf(45f, 50f), viewModel.sets.value.map { it.weightPerCable })

        viewModel.onUsePercentOfPRChange(true)
        assertEquals(listOf(45f, 50f), viewModel.sets.value.map { it.weightPerCable })

        var saved: RoutineExercise? = null
        viewModel.onSave { updated -> saved = updated }

        assertNotNull(saved)
        assertEquals(listOf(90, 100), saved.setWeightsPercentOfPR)
        assertEquals(listOf(45f, 50f), saved.setWeightsPerCableKg)
    }

    @Test
    fun `initialize reloads PR lookup when active profile changes`() = runTest {
        val database = createTestDatabase()
        val queries = database.vitruvianDatabaseQueries
        val repository = SqlDelightPersonalRecordRepository(database)
        val viewModel = ExerciseConfigViewModel(repository)
        val exercise = RoutineExercise(
            id = "rex-3",
            exercise = Exercise(
                id = "bench-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 20f,
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
        )

        insertExercise(queries, id = "bench-1", name = "Bench Press")
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = 55.0,
            reps = 6,
            oneRepMax = 66.0,
            achievedAt = 1_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 330.0,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = null,
        )
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = 72.5,
            reps = 5,
            oneRepMax = 84.5,
            achievedAt = 2_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 362.5,
            phase = "COMBINED",
            profile_id = "profile-b",
            cable_count = null,
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "default",
        )
        waitForCondition { viewModel.currentExercisePR.value != null }
        assertNotNull(viewModel.currentExercisePR.value)
        assertEquals(55f, viewModel.currentExercisePR.value?.weightPerCableKg)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "profile-b",
        )
        waitForCondition { viewModel.currentExercisePR.value?.weightPerCableKg == 72.5f }
        assertEquals(72.5f, viewModel.currentExercisePR.value?.weightPerCableKg)

        viewModel.loadPRForExercise("bench-1", "Pump")
        waitForCondition { viewModel.currentExercisePR.value == null }
        assertNull(viewModel.currentExercisePR.value)
    }

    private fun benchRoutineExercise(
        id: String,
        setReps: List<Int?>,
        weightPerCableKg: Float,
        setWeightsPerCableKg: List<Float> = emptyList(),
        usePercentOfPR: Boolean = false,
        weightPercentOfPR: Int = 80,
        setWeightsPercentOfPR: List<Int> = emptyList(),
    ) = RoutineExercise(
        id = id,
        exercise = Exercise(
            id = "bench-1",
            name = "Bench Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            equipment = "BAR",
        ),
        orderIndex = 0,
        setReps = setReps,
        weightPerCableKg = weightPerCableKg,
        setWeightsPerCableKg = setWeightsPerCableKg,
        programMode = ProgramMode.OldSchool,
        eccentricLoad = EccentricLoad.LOAD_100,
        echoLevel = EchoLevel.HARDER,
        usePercentOfPR = usePercentOfPR,
        weightPercentOfPR = weightPercentOfPR,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
    )

    private fun weightPR(weight: Float) = PersonalRecord(
        id = 1,
        exerciseId = "bench-1",
        exerciseName = "Bench Press",
        weightPerCableKg = weight,
        reps = 6,
        oneRepMax = weight * 1.2f,
        timestamp = 1_000L,
        workoutMode = "Old School",
        prType = PRType.MAX_WEIGHT,
        volume = weight * 6,
    )

    private fun insertExercise(queries: com.devil.phoenixproject.database.VitruvianDatabaseQueries, id: String, name: String) {
        queries.insertExercise(
            id = id,
            name = name,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
        )
    }

    private fun insertWeightPR(queries: com.devil.phoenixproject.database.VitruvianDatabaseQueries, weight: Double) {
        queries.insertRecord(
            exerciseId = "bench-1",
            exerciseName = "Bench Press",
            weight = weight,
            reps = 6,
            oneRepMax = weight * 1.2,
            achievedAt = 1_000L,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = weight * 6,
            phase = "COMBINED",
            profile_id = "default",
            cable_count = null,
        )
    }

    private fun waitForCondition(timeoutMs: Long = 1_000L, pollMs: Long = 25L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMs)
        }
        assertTrue(condition(), "Timed out waiting for view model state update")
    }
}
