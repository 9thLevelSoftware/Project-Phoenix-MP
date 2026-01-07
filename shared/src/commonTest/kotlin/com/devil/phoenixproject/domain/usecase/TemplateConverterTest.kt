package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CycleDayTemplate
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineTemplate
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.domain.model.FiveThreeOneWeeks
import com.devil.phoenixproject.domain.model.CableConfiguration
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateConverterTest {

    @Test
    fun `convert builds routines and warnings for missing exercises`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "bench-001",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    muscleGroups = "Chest",
                    equipment = "BAR",
                    defaultCableConfig = CableConfiguration.DOUBLE,
                    oneRepMaxKg = 120f
                )
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "template-1",
            name = "Test Cycle",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Day 1",
                    routine = RoutineTemplate(
                        name = "Strength A",
                        exercises = listOf(
                            TemplateExercise(exerciseName = "Bench Press", sets = 3, reps = 5),
                            TemplateExercise(exerciseName = "Missing Exercise", sets = 3, reps = 8)
                        )
                    )
                ),
                CycleDayTemplate.rest(dayNumber = 2)
            ),
            progressionRule = null
        )

        val result = converter.convert(template)

        assertEquals(1, result.routines.size)
        assertEquals(1, result.warnings.size)
        assertEquals("Missing Exercise", result.warnings.first())
    }

    @Test
    fun `convert applies exercise config overrides and percentage sets`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "squat-001",
                    name = "Squat",
                    muscleGroup = "Legs",
                    muscleGroups = "Legs",
                    equipment = "BAR",
                    defaultCableConfig = CableConfiguration.DOUBLE,
                    oneRepMaxKg = 140f
                )
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "template-2",
            name = "531",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Day 1",
                    routine = RoutineTemplate(
                        name = "Squat Day",
                        exercises = listOf(
                            TemplateExercise(
                                exerciseName = "Squat",
                                sets = 3,
                                reps = 5,
                                suggestedMode = ProgramMode.Echo,
                                isPercentageBased = true,
                                percentageSets = FiveThreeOneWeeks.WEEK_1
                            )
                        )
                    )
                )
            ),
            progressionRule = null
        )

        val configs = mapOf(
            "Squat" to ExerciseConfig(
                exerciseName = "Squat",
                mode = ProgramMode.Pump,
                weightPerCableKg = 42.5f,
                eccentricLoadPercent = 120,
                echoLevel = EchoLevel.EPIC
            )
        )

        val result = converter.convert(template, configs)
        val routineExercise = result.routines.first().exercises.first()

        assertEquals(ProgramMode.Pump, routineExercise.programMode)
        assertEquals(EccentricLoad.LOAD_120, routineExercise.eccentricLoad)
        assertEquals(EchoLevel.EPIC, routineExercise.echoLevel)
        assertTrue(routineExercise.setReps.contains(null))
        assertEquals(0f, routineExercise.weightPerCableKg)
    }
}
