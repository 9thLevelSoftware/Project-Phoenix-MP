package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
