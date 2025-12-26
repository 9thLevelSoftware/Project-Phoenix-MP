package com.devil.phoenixproject.data.migration

import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.CycleDayTemplate
import com.devil.phoenixproject.domain.model.RoutineTemplate
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.domain.model.ProgressionRule
import com.devil.phoenixproject.domain.model.ProgramMode
/**
 * Preset cycle templates for quick creation.
 */
object CycleTemplates {

    /**
     * 3-Day Full Body template.
     */
    fun threeDay(): CycleTemplate {
        val fullBodyA = RoutineTemplate(
            name = "Full Body A",
            exercises = listOf(
                TemplateExercise("Squat", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Bench Press", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )
        val fullBodyB = RoutineTemplate(
            name = "Full Body B",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 3, 5, ProgramMode.OldSchool),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT),
                TemplateExercise("Plank", 3, null, null)  // null reps = timed, null mode = bodyweight
            )
        )
        val fullBodyC = RoutineTemplate(
            name = "Full Body C",
            exercises = listOf(
                TemplateExercise("Front Squat", 3, 8, ProgramMode.OldSchool),
                TemplateExercise("Bench Press - Wide Grip", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Shrug", 3, 12, ProgramMode.TUT)
            )
        )

        return CycleTemplate(
            id = "template_3day_fullbody",
            name = "3-Day Full Body",
            description = "Full body workout 3 times per week. Great for beginners or those with limited training time.",
            days = listOf(
                CycleDayTemplate.training(1, "Full Body A", fullBodyA),
                CycleDayTemplate.rest(2),
                CycleDayTemplate.training(3, "Full Body B", fullBodyB),
                CycleDayTemplate.rest(4),
                CycleDayTemplate.training(5, "Full Body C", fullBodyC),
                CycleDayTemplate.rest(6),
                CycleDayTemplate.rest(7)
            ),
            progressionRule = ProgressionRule.percentage(2.5f)
        )
    }

    /**
     * Push/Pull/Legs 6-day template.
     */
    fun pushPullLegs(): CycleTemplate {
        val pushA = RoutineTemplate(
            name = "Push A",
            exercises = listOf(
                TemplateExercise("Bench Press", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT)
            )
        )
        val pullA = RoutineTemplate(
            name = "Pull A",
            exercises = listOf(
                TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT)
            )
        )
        val legsA = RoutineTemplate(
            name = "Legs A",
            exercises = listOf(
                TemplateExercise("Squat", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lunge", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lying Leg Extension", 3, 12, ProgramMode.TUT),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )
        val pushB = RoutineTemplate(
            name = "Push B",
            exercises = listOf(
                TemplateExercise("Shoulder Press", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT)
            )
        )
        val pullB = RoutineTemplate(
            name = "Pull B",
            exercises = listOf(
                TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT)
            )
        )
        val legsB = RoutineTemplate(
            name = "Legs B",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 5, 5, ProgramMode.OldSchool),
                TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lying Hamstring Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )

        return CycleTemplate(
            id = "template_ppl",
            name = "Push/Pull/Legs",
            description = "6-day split focusing on push, pull, and leg movements. Ideal for intermediate lifters seeking muscle growth.",
            days = listOf(
                CycleDayTemplate.training(1, "Push A", pushA),
                CycleDayTemplate.training(2, "Pull A", pullA),
                CycleDayTemplate.training(3, "Legs A", legsA),
                CycleDayTemplate.training(4, "Push B", pushB),
                CycleDayTemplate.training(5, "Pull B", pullB),
                CycleDayTemplate.training(6, "Legs B", legsB)
            ),
            progressionRule = ProgressionRule.percentage(2.5f)
        )
    }

    /**
     * Upper/Lower 5-day template with rest day.
     */
    fun upperLower(): CycleTemplate {
        val upperA = RoutineTemplate(
            name = "Upper A",
            exercises = listOf(
                TemplateExercise("Bench Press", 4, 6, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row", 4, 6, ProgramMode.OldSchool),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT)
            )
        )
        val lowerA = RoutineTemplate(
            name = "Lower A",
            exercises = listOf(
                TemplateExercise("Squat", 4, 6, ProgramMode.OldSchool),
                TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Lunge", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT)
            )
        )
        val upperB = RoutineTemplate(
            name = "Upper B",
            exercises = listOf(
                TemplateExercise("Incline Bench Press", 4, 8, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row - Wide Grip", 4, 8, ProgramMode.OldSchool),
                TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT),
                TemplateExercise("Skull Crusher", 3, 12, ProgramMode.TUT)
            )
        )
        val lowerB = RoutineTemplate(
            name = "Lower B",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 4, 5, ProgramMode.OldSchool),
                TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Glute Kickbacks", 3, 12, ProgramMode.TUT)
            )
        )

        return CycleTemplate(
            id = "template_upper_lower",
            name = "Upper/Lower",
            description = "5-day split alternating between upper and lower body. Balanced approach for strength and hypertrophy.",
            days = listOf(
                CycleDayTemplate.training(1, "Upper A", upperA),
                CycleDayTemplate.training(2, "Lower A", lowerA),
                CycleDayTemplate.rest(3),
                CycleDayTemplate.training(4, "Upper B", upperB),
                CycleDayTemplate.training(5, "Lower B", lowerB)
            ),
            progressionRule = ProgressionRule.percentage(2.5f)
        )
    }

    /**
     * 5/3/1 (Wendler) 4-day template with percentage-based main lifts.
     */
    fun fiveThreeOne(): CycleTemplate {
        val benchDay = RoutineTemplate(
            name = "Bench Day",
            exercises = listOf(
                TemplateExercise("Bench Press", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Bent Over Row", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Plank", 3, null, null)  // null mode = bodyweight
            )
        )
        val squatDay = RoutineTemplate(
            name = "Squat Day",
            exercises = listOf(
                TemplateExercise("Squat", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT),
                TemplateExercise("Lunge", 3, 10, ProgramMode.OldSchool)
            )
        )
        val pressDay = RoutineTemplate(
            name = "Press Day",
            exercises = listOf(
                TemplateExercise("Shoulder Press", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT),
                TemplateExercise("Bent Over Row", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Crunch", 3, 15, null)  // null mode = bodyweight
            )
        )
        val deadliftDay = RoutineTemplate(
            name = "Deadlift Day",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
                TemplateExercise("Good Morning", 3, 12, ProgramMode.OldSchool)
            )
        )

        return CycleTemplate(
            id = "template_531",
            name = "5/3/1 (Wendler)",
            description = "Strength-focused 4-day program with percentage-based main lifts. Runs in 4-week cycles with progressive weight increases.",
            days = listOf(
                CycleDayTemplate.training(1, "Bench", benchDay),
                CycleDayTemplate.training(2, "Squat", squatDay),
                CycleDayTemplate.training(3, "Press", pressDay),
                CycleDayTemplate.training(4, "Deadlift", deadliftDay)
            ),
            progressionRule = ProgressionRule.fiveThreeOne(),
            requiresOneRepMax = true,
            mainLifts = listOf("Bench Press", "Squat", "Shoulder Press", "Conventional Deadlift")
        )
    }

    /**
     * Get all available templates.
     */
    fun all(): List<CycleTemplate> = listOf(
        threeDay(),
        pushPullLegs(),
        upperLower(),
        fiveThreeOne()
    )
}
