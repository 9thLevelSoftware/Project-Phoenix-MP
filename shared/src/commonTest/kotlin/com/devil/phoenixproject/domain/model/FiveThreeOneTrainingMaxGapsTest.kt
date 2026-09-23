package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Carryover R-24: which 5/3/1 main lifts would silently miss their weekly training-max bump. */
class FiveThreeOneTrainingMaxGapsTest {

    @Test
    fun lifts_without_a_positive_baseline_are_reported_in_day_order() {
        val cycle = fiveThreeOneCycle()
        val baselines = mapOf(BENCH to 100f, SQUAT to null, PRESS to 0f)

        val missing = missingFiveThreeOneTrainingMaxes(cycle, routines()) { baselines[it] }

        assertEquals(
            listOf(
                MissingFiveThreeOneTrainingMax(SQUAT, "Squat"),
                MissingFiveThreeOneTrainingMax(PRESS, "Shoulder Press"),
                MissingFiveThreeOneTrainingMax(DEADLIFT, "Deadlift"),
            ),
            missing,
        )
    }

    @Test
    fun a_fully_seeded_cycle_reports_nothing() {
        val missing = missingFiveThreeOneTrainingMaxes(fiveThreeOneCycle(), routines()) { 120f }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun a_cycle_that_is_not_five_three_one_never_reports() {
        // Only bench as a %-of-PR main lift, no 5/3/1 template: the weekly bump never runs.
        val cycle = fiveThreeOneCycle(templateId = null).let { cycle ->
            cycle.copy(days = cycle.days.take(1))
        }
        val missing = missingFiveThreeOneTrainingMaxes(cycle, routines()) { null }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun the_531_template_reports_even_before_every_lift_is_recognised() {
        val cycle = fiveThreeOneCycle(templateId = "template_531").let { cycle ->
            cycle.copy(days = cycle.days.take(1))
        }
        val missing = missingFiveThreeOneTrainingMaxes(cycle, routines()) { null }
        assertEquals(listOf(MissingFiveThreeOneTrainingMax(BENCH, "Bench Press")), missing)
    }

    @Test
    fun rest_days_and_missing_routines_are_skipped() {
        val base = fiveThreeOneCycle()
        val cycle = base.copy(
            days = base.days + CycleDay(
                id = "rest",
                cycleId = base.id,
                dayNumber = 9,
                name = null,
                routineId = "routine-bench",
                isRestDay = true,
            ) + CycleDay(
                id = "gone",
                cycleId = base.id,
                dayNumber = 10,
                name = null,
                routineId = "deleted-routine",
                isRestDay = false,
            ),
        )
        val missing = missingFiveThreeOneTrainingMaxes(cycle, routines()) { if (it == BENCH) null else 1f }
        assertEquals(listOf(MissingFiveThreeOneTrainingMax(BENCH, "Bench Press")), missing)
    }

    private fun fiveThreeOneCycle(templateId: String? = null): TrainingCycle = TrainingCycle(
        id = "cycle",
        name = "5/3/1",
        description = null,
        days = listOf(BENCH, SQUAT, PRESS, DEADLIFT).mapIndexed { index, liftId ->
            CycleDay(
                id = "day-$index",
                cycleId = "cycle",
                dayNumber = index + 1,
                name = null,
                routineId = routineIdFor(liftId),
                isRestDay = false,
            )
        },
        createdAt = 0L,
        isActive = true,
        profileId = "default",
        templateId = templateId,
    )

    private fun routines(): List<Routine> = listOf(
        BENCH to "Bench Press",
        SQUAT to "Squat",
        PRESS to "Shoulder Press",
        DEADLIFT to "Deadlift",
    ).map { (liftId, name) ->
        Routine(
            id = routineIdFor(liftId),
            name = "$name day",
            exercises = listOf(
                mainLift(liftId, name),
                accessory(),
            ),
        )
    }

    private fun routineIdFor(liftId: String) = when (liftId) {
        BENCH -> "routine-bench"
        SQUAT -> "routine-squat"
        PRESS -> "routine-press"
        else -> "routine-deadlift"
    }

    private fun mainLift(id: String, name: String) = RoutineExercise(
        id = "re-$id",
        exercise = Exercise(id = id, name = name, muscleGroup = "Strength", muscleGroups = "Strength", equipment = "BAR"),
        orderIndex = 0,
        setReps = listOf(5, 5, null),
        weightPerCableKg = 40f,
        programMode = ProgramMode.OldSchool,
        isAMRAP = true,
        usePercentOfPR = true,
        setWeightsPercentOfPR = listOf(59, 68, 77),
    )

    private fun accessory() = RoutineExercise(
        id = "re-row",
        exercise = Exercise(id = "row", name = "Row", muscleGroup = "Back", muscleGroups = "Back", equipment = "BAR"),
        orderIndex = 1,
        setReps = listOf(10, 10, 10),
        weightPerCableKg = 25f,
        programMode = ProgramMode.OldSchool,
        isAMRAP = false,
        usePercentOfPR = true,
        setWeightsPercentOfPR = listOf(65, 65, 65),
    )

    private companion object {
        const val BENCH = FiveThreeOneRoutineDetector.BENCH_ID
        const val SQUAT = FiveThreeOneRoutineDetector.SQUAT_ID
        const val PRESS = FiveThreeOneRoutineDetector.SHOULDER_PRESS_ID
        const val DEADLIFT = FiveThreeOneRoutineDetector.DEADLIFT_ID
    }
}
