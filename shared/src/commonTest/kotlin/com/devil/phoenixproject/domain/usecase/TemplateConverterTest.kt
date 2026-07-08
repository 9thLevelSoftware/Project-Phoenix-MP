package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CycleDayTemplate
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.CycleTemplates
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.FiveThreeOneWeeks
import com.devil.phoenixproject.domain.model.PercentageSet
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineTemplate
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.domain.model.computeFiveThreeOneSetWeightsForWeek
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TemplateConverterTest {

    /** Seeds the fake repository with every exercise a template references. */
    private fun repositoryFor(vararg templates: CycleTemplate): FakeExerciseRepository {
        val repository = FakeExerciseRepository()
        templates
            .flatMap { it.days }
            .flatMap { it.routine?.exercises ?: emptyList() }
            .distinctBy { it.exerciseId ?: it.exerciseName }
            .forEach { te ->
                repository.addExercise(
                    Exercise(
                        id = te.exerciseId ?: te.exerciseName,
                        name = te.exerciseName,
                        muscleGroup = "Test",
                        muscleGroups = "Test",
                        equipment = "BAR",
                    ),
                )
            }
        return repository
    }

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
                    oneRepMaxKg = 120f,
                ),
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
                            TemplateExercise(exerciseName = "Missing Exercise", sets = 3, reps = 8),
                        ),
                    ),
                ),
                CycleDayTemplate.rest(dayNumber = 2),
            ),
            progressionRule = null,
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
                    oneRepMaxKg = 140f,
                ),
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
                                percentageSets = FiveThreeOneWeeks.WEEK_1,
                            ),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val configs = mapOf(
            "Squat" to ExerciseConfig(
                exerciseName = "Squat",
                mode = ProgramMode.Pump,
                weightPerCableKg = 42.5f,
                eccentricLoadPercent = 120,
                echoLevel = EchoLevel.EPIC,
            ),
        )

        val result = converter.convert(template, configs)
        val routineExercise = result.routines.first().exercises.first()

        assertEquals(ProgramMode.Pump, routineExercise.programMode)
        assertEquals(EccentricLoad.LOAD_120, routineExercise.eccentricLoad)
        assertEquals(EchoLevel.EPIC, routineExercise.echoLevel)
        assertTrue(routineExercise.setReps.contains(null))
        // Live %-resolution wiring: percentage-based lifts opt into per-set scaling
        assertTrue(routineExercise.usePercentOfPR)
        assertEquals(ScalingBasis.ESTIMATED_1RM, routineExercise.scalingBasis)
        // Week 1 folded through the 90% training max: 65/75/85% of TM → 59/68/77% of 1RM
        assertEquals(listOf(59, 68, 77), routineExercise.setWeightsPercentOfPR)
        assertTrue(routineExercise.isAMRAP)
        // Fallback weight snapshot (used only when the resolution chain misses):
        // 140kg 1RM × default 70% = 98kg
        assertEquals(98f, routineExercise.weightPerCableKg)
    }

    @Test
    fun `week one custom percentage sets drive per-set weight percentages`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "bench-001",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    muscleGroups = "Chest",
                    equipment = "BAR",
                    oneRepMaxKg = 100f,
                ),
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "custom-531",
            name = "Custom 531",
            description = "Custom week 1 loading",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Push",
                    routine = RoutineTemplate(
                        name = "Push",
                        exercises = listOf(
                            TemplateExercise(
                                exerciseName = "Bench Press",
                                sets = 3,
                                reps = null,
                                suggestedMode = ProgramMode.OldSchool,
                                isPercentageBased = true,
                                percentageSets = listOf(
                                    PercentageSet(0.50f, 5),
                                    PercentageSet(0.60f, 5),
                                    PercentageSet(0.70f, null, isAmrap = true),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val lift = converter.convert(template, weekNumber = 1)
            .routines
            .single()
            .exercises
            .single()

        assertEquals(listOf(45, 54, 63), lift.setWeightsPercentOfPR)
        assertEquals(listOf(5, 5, null), lift.setReps)
        assertTrue(lift.isAMRAP)
    }

    // ===== Production template regression suite =====

    @Test
    fun `all production templates convert without dropped days or warnings`() = runTest {
        val templates = CycleTemplates.all()
        val converter = TemplateConverter(repositoryFor(*templates.toTypedArray()))

        for (template in templates) {
            val result = converter.convert(template)

            assertEquals(
                template.days.size,
                result.cycle.days.size,
                "${template.name}: day count must be preserved",
            )
            assertTrue(
                result.warnings.isEmpty(),
                "${template.name}: no warnings expected, got ${result.warnings}",
            )

            // Every training day keeps its routine; every rest day stays a rest day
            template.days.forEach { dayTemplate ->
                val day = result.cycle.days.first { it.dayNumber == dayTemplate.dayNumber }
                assertEquals(dayTemplate.isRestDay, day.isRestDay, "${template.name} day ${dayTemplate.dayNumber}")
                if (!dayTemplate.isRestDay) {
                    val routine = result.routines.first { it.id == day.routineId }
                    assertEquals(
                        dayTemplate.routine!!.exercises.size,
                        routine.exercises.size,
                        "${template.name} '${dayTemplate.name}': every template exercise must resolve",
                    )
                }
            }
        }
    }

    @Test
    fun `all production template exercises use live percent resolution and non-zero fallback weights`() = runTest {
        val templates = CycleTemplates.all()
        val converter = TemplateConverter(repositoryFor(*templates.toTypedArray()))

        for (template in templates) {
            val bodyweightNames = template.days
                .flatMap { it.routine?.exercises ?: emptyList() }
                .filter { it.suggestedMode == null }
                .map { it.exerciseName }
                .toSet()

            val result = converter.convert(template)
            result.routines.flatMap { it.exercises }.forEach { exercise ->
                if (exercise.exercise.name in bodyweightNames) {
                    // Bodyweight/core exercises (Plank, Crunch) never scale from 1RM
                    assertFalse(
                        exercise.usePercentOfPR,
                        "${template.name}/${exercise.exercise.name}: bodyweight must not 1RM-scale",
                    )
                } else {
                    assertTrue(
                        exercise.usePercentOfPR,
                        "${template.name}/${exercise.exercise.name}: must opt into live %-of-1RM resolution",
                    )
                    assertEquals(ScalingBasis.ESTIMATED_1RM, exercise.scalingBasis)
                }
                assertTrue(
                    exercise.weightPerCableKg > 0f,
                    "${template.name}/${exercise.exercise.name}: fallback weight must never be 0kg",
                )
            }
        }
    }

    @Test
    fun `production template percent prescriptions match the rep-range normalization rule`() = runTest {
        CycleTemplates.all()
            .flatMap { it.days }
            .flatMap { it.routine?.exercises ?: emptyList() }
            .filter { !it.isPercentageBased && it.suggestedMode != null }
            .forEach { te ->
                assertEquals(
                    com.devil.phoenixproject.domain.model.defaultPercentOfOneRmForReps(te.reps),
                    te.percentOfOneRm,
                    "${te.exerciseName} (${te.reps} reps): declared % must match the normalization rule",
                )
            }
    }

    @Test
    fun `production 531 main lifts carry week one percentage prescriptions`() = runTest {
        val template = CycleTemplates.fiveThreeOne()
        val converter = TemplateConverter(repositoryFor(template))

        val result = converter.convert(template)
        // Filter to the percentage-based instances: Shoulder Press also appears as a
        // plain accessory on Squat Day and must not be counted here.
        val mainLifts = result.routines.flatMap { it.exercises }
            .filter { it.exercise.name in template.mainLifts && it.setWeightsPercentOfPR.isNotEmpty() }

        assertEquals(4, mainLifts.size, "All 4 main lifts must resolve")
        assertEquals(template.id, result.cycle.templateId)
        assertEquals(1, result.cycle.weekNumber)
        mainLifts.forEach { lift ->
            assertEquals(listOf(59, 68, 77), lift.setWeightsPercentOfPR, lift.exercise.name)
            assertEquals(listOf(5, 5, null), lift.setReps, lift.exercise.name)
            assertTrue(lift.isAMRAP, "${lift.exercise.name}: week 1-3 last set is AMRAP")
        }
    }

    @Test
    fun `computeFiveThreeOneSetWeightsForWeek returns canonical folded percentages`() {
        assertEquals(listOf(59, 68, 77), computeFiveThreeOneSetWeightsForWeek(1))
        assertEquals(listOf(68, 77, 86), computeFiveThreeOneSetWeightsForWeek(3))
    }

    @Test
    fun `week number selects the 531 percentage scheme`() = runTest {
        val template = CycleTemplates.fiveThreeOne()
        val converter = TemplateConverter(repositoryFor(template))

        val week2 = converter.convert(template, weekNumber = 2)
        val week2Lift = week2.routines.flatMap { it.exercises }
            .first { it.exercise.name == "Bench Press" && it.setWeightsPercentOfPR.isNotEmpty() }
        assertEquals(listOf(63, 72, 81), week2Lift.setWeightsPercentOfPR, "70/80/90% of TM → 63/72/81% of 1RM")
        assertEquals(listOf(3, 3, null), week2Lift.setReps)
        assertTrue(week2Lift.isAMRAP)
        assertEquals(2, week2.cycle.weekNumber)

        val week4 = converter.convert(template, weekNumber = 4)
        val week4Lift = week4.routines.flatMap { it.exercises }
            .first { it.exercise.name == "Bench Press" && it.setWeightsPercentOfPR.isNotEmpty() }
        assertEquals(listOf(36, 45, 54), week4Lift.setWeightsPercentOfPR)
        assertEquals(listOf(5, 5, 5), week4Lift.setReps)
        assertFalse(week4Lift.isAMRAP, "Deload week has no AMRAP set")
    }

    @Test
    fun `missing one rep max falls back to conservative default weight not zero`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "row-001",
                    name = "Bent Over Row",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "BAR",
                    oneRepMaxKg = null,
                ),
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "template-3",
            name = "Test",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Day 1",
                    routine = RoutineTemplate(
                        name = "Rows",
                        exercises = listOf(
                            TemplateExercise(exerciseName = "Bent Over Row", sets = 3, reps = 8),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val exercise = converter.convert(template).routines.first().exercises.first()
        assertEquals(TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG, exercise.weightPerCableKg)
        assertTrue(exercise.usePercentOfPR, "Live resolution stays enabled; fallback is only a safety net")
    }

    @Test
    fun `explicit configured weight pins absolute and disables live scaling`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "curl-001",
                    name = "Bicep Curl",
                    muscleGroup = "Biceps",
                    muscleGroups = "Biceps",
                    equipment = "SINGLE_HANDLE",
                    oneRepMaxKg = 40f,
                ),
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "template-4",
            name = "Test",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Day 1",
                    routine = RoutineTemplate(
                        name = "Arms",
                        exercises = listOf(
                            TemplateExercise(exerciseName = "Bicep Curl", sets = 3, reps = 12),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val configs = mapOf(
            "Bicep Curl" to ExerciseConfig(
                exerciseName = "Bicep Curl",
                mode = ProgramMode.OldSchool,
                weightPerCableKg = 12.5f,
                userEditedWeight = true,
            ),
        )

        val exercise = converter.convert(template, configs).routines.first().exercises.first()
        assertEquals(12.5f, exercise.weightPerCableKg)
        assertFalse(exercise.usePercentOfPR, "User-pinned weight must not be overridden by live resolution")

        // #633 review (P1): an auto-filled config weight (fromTemplate's 1RM×0.70,
        // userEditedWeight = false) must NOT pin the exercise — live scaling stays on.
        val autoConfigs = mapOf(
            "Bicep Curl" to ExerciseConfig(
                exerciseName = "Bicep Curl",
                mode = ProgramMode.OldSchool,
                weightPerCableKg = 28f, // auto-filled from 1RM, not user-edited
            ),
        )
        val autoExercise = converter.convert(template, autoConfigs).routines.first().exercises.first()
        assertTrue(
            autoExercise.usePercentOfPR,
            "Auto-filled 1RM default weights must not disable live %-of-1RM resolution",
        )
    }

    @Test
    fun `tiny one rep max never rounds fallback weight to zero`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "band-001",
                    name = "Band Pull Apart",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "SINGLE_HANDLE",
                    oneRepMaxKg = 0.3f, // 0.3 × 70% = 0.21 → would round to 0kg without the floor
                ),
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "template-6",
            name = "Test",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Day 1",
                    routine = RoutineTemplate(
                        name = "Bands",
                        exercises = listOf(
                            TemplateExercise(exerciseName = "Band Pull Apart", sets = 3, reps = 15),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val exercise = converter.convert(template).routines.first().exercises.first()
        assertTrue(
            exercise.weightPerCableKg >= 0.5f,
            "Fallback weight must floor at the 0.5kg machine increment, got ${exercise.weightPerCableKg}",
        )
    }

    @Test
    fun `bodyweight exercises get fixed light weight not one rep max scaling`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "plank-001",
                    name = "Plank",
                    muscleGroup = "Core",
                    muscleGroups = "Core",
                    equipment = "",
                    oneRepMaxKg = 100f, // even with (nonsense) 1RM data present
                ),
            )
        }
        val converter = TemplateConverter(repository)

        val template = CycleTemplate(
            id = "template-7",
            name = "Test",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Day 1",
                    routine = RoutineTemplate(
                        name = "Core",
                        exercises = listOf(
                            // suggestedMode = null is the bodyweight sentinel
                            TemplateExercise(exerciseName = "Plank", sets = 3, reps = null, suggestedMode = null),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val exercise = converter.convert(template).routines.first().exercises.first()
        assertFalse(exercise.usePercentOfPR, "Bodyweight exercises must not scale from 1RM")
        assertEquals(
            TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG,
            exercise.weightPerCableKg,
            "Bodyweight exercises use the light fixed default, not 70% of 1RM",
        )
    }

    @Test
    fun `day with no resolvable exercises is kept with empty routine and warning`() = runTest {
        val converter = TemplateConverter(FakeExerciseRepository())

        val template = CycleTemplate(
            id = "template-5",
            name = "Test",
            description = "Test",
            days = listOf(
                CycleDayTemplate.training(
                    dayNumber = 1,
                    name = "Ghost Day",
                    routine = RoutineTemplate(
                        name = "Ghost Routine",
                        exercises = listOf(
                            TemplateExercise(exerciseName = "Nonexistent A", sets = 3, reps = 8),
                            TemplateExercise(exerciseName = "Nonexistent B", sets = 3, reps = 8),
                        ),
                    ),
                ),
                CycleDayTemplate.training(
                    dayNumber = 2,
                    name = "Also Ghost",
                    routine = RoutineTemplate(
                        name = "Ghost Routine 2",
                        exercises = listOf(
                            TemplateExercise(exerciseName = "Nonexistent C", sets = 3, reps = 8),
                        ),
                    ),
                ),
            ),
            progressionRule = null,
        )

        val result = converter.convert(template)

        // BUG 2 regression: days must never silently vanish
        assertEquals(2, result.cycle.days.size, "Days with unresolvable exercises must be kept")
        // ...but they must be UNASSIGNED, not pointed at empty startable routines —
        // the cycle card treats any non-null routineId as startable and an empty
        // routine dead-ends at workout start (#633 review).
        assertTrue(
            result.cycle.days.all { it.routineId == null && !it.isRestDay },
            "Unresolvable days must be kept unassigned for the Assign Routine repair flow",
        )
        assertTrue(result.routines.isEmpty(), "No empty routines should be created")
        assertTrue(
            result.warnings.any { it.contains("Ghost Day") },
            "Empty-day warning expected, got ${result.warnings}",
        )
    }

    @Test
    fun `rest day normalization every template declares at least one rest day per week`() = runTest {
        CycleTemplates.all().forEach { template ->
            assertTrue(
                template.days.any { it.isRestDay },
                "${template.name}: templates must declare rest days explicitly",
            )
        }
    }
}
