package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.SqlDelightPersonalRecordRepository
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                equipment = ""
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 0f
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value }
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
                equipment = "BAR"
            ),
            orderIndex = 0,
            setReps = listOf(10, 8),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f),
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER,
            setRestSeconds = listOf(60, 60),
            perSetRestTime = true
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value }
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
                equipment = "BAR"
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 20f,
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
            echoLevel = EchoLevel.HARDER
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
            profile_id = "default"
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
            profile_id = "profile-b"
        )

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "default"
        )
        waitForCondition { viewModel.currentExercisePR.value != null }
        assertNotNull(viewModel.currentExercisePR.value)
        assertEquals(55f, viewModel.currentExercisePR.value?.weightPerCableKg)

        viewModel.initialize(
            exercise = exercise,
            unit = WeightUnit.KG,
            toDisplay = { value, _ -> value },
            toKg = { value, _ -> value },
            profileId = "profile-b"
        )
        waitForCondition { viewModel.currentExercisePR.value?.weightPerCableKg == 72.5f }
        assertEquals(72.5f, viewModel.currentExercisePR.value?.weightPerCableKg)

        viewModel.loadPRForExercise("bench-1", "Pump")
        waitForCondition { viewModel.currentExercisePR.value == null }
        assertNull(viewModel.currentExercisePR.value)
    }

    private fun insertExercise(
        queries: com.devil.phoenixproject.database.VitruvianDatabaseQueries,
        id: String,
        name: String
    ) {
        queries.insertExercise(
            id = id,
            name = name,
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
            one_rep_max_kg = null
        )
    }

    private fun waitForCondition(
        timeoutMs: Long = 1_000L,
        pollMs: Long = 25L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMs)
        }
        assertTrue(condition(), "Timed out waiting for view model state update")
    }
}
