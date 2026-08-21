package com.devil.phoenixproject.domain.model

/**
 * Stable exercise IDs from the bundled free-exercise-db catalogue.
 * Used by templates for reliable ID-based lookup with name fallback.
 */
private object ExerciseIds {
    const val SQUAT = "Barbell_Squat"
    const val BENCH_PRESS = "Barbell_Bench_Press_-_Medium_Grip"
    const val BENT_OVER_ROW = "Bent_Over_Barbell_Row"
    const val SHOULDER_PRESS = "Barbell_Shoulder_Press"
    const val BICEP_CURL = "Barbell_Curl"
    const val CALF_RAISE = "Standing_Calf_Raises"
    const val CONVENTIONAL_DEADLIFT = "Barbell_Deadlift"
    const val INCLINE_BENCH_PRESS = "Barbell_Incline_Bench_Press_-_Medium_Grip"
    const val BENT_OVER_ROW_REVERSE_GRIP = "Reverse_Grip_Bent-Over_Rows"
    const val LATERAL_RAISE = "Side_Lateral_Raise"
    const val OVERHEAD_TRICEP_EXTENSION = "Standing_Overhead_Barbell_Triceps_Extension"
    const val PLANK = "Plank"
    const val FRONT_SQUAT = "Front_Barbell_Squat"
    const val BENCH_PRESS_WIDE_GRIP = "Wide-Grip_Barbell_Bench_Press"
    const val UPRIGHT_ROW = "Upright_Barbell_Row"
    const val ARNOLD_PRESS = "Arnold_Dumbbell_Press"
    const val HAMMER_CURL = "Hammer_Curls"
    const val SHRUG = "Barbell_Shrug"
    const val FACE_PULL = "Face_Pull"
    const val ROMANIAN_DEADLIFT = "Romanian_Deadlift"
    const val LUNGE = "Barbell_Lunge"
    const val LYING_LEG_EXTENSION = "Leg_Extensions"
    const val BULGARIAN_SPLIT_SQUAT = "One_Leg_Barbell_Squat"
    const val LYING_HAMSTRING_CURL = "Lying_Leg_Curls"
    const val SKULL_CRUSHER = "Lying_Triceps_Press"
    const val BENT_OVER_ROW_WIDE_GRIP = "Bent_Over_Two-Dumbbell_Row"
    const val GLUTE_KICKBACKS = "One-Legged_Cable_Kickback"
    const val CRUNCH = "Crunches"
    const val GOOD_MORNING = "Good_Morning"
}

/**
 * Preset cycle templates for quick creation.
 *
 * Normalization rules (every template follows these):
 * - Every exercise carries an explicit [TemplateExercise.percentOfOneRm] prescription
 *   derived from its rep range (5-6 reps → 75%, 8 → 70%, 10 → 65%, 12 → 60%, 15 → 55%),
 *   resolved live at workout start via the PR/1RM resolution chain.
 * - 5/3/1 main lifts carry explicit [PercentageSet] lists (week 1 by default; the
 *   converter selects the active week).
 * - Every template spans a full 7-day week unless its split is intentionally shorter,
 *   with rest days declared explicitly — days are never implied.
 * - Bodyweight/core exercises (Plank, Crunch) use suggestedMode = null; the converter
 *   maps them to OldSchool at a light fallback weight.
 */
object CycleTemplates {

    /**
     * 3-Day Full Body template (7-day week: train/rest/train/rest/train/rest/rest).
     */
    fun threeDay(): CycleTemplate {
        val fullBodyA = RoutineTemplate(
            name = "Full Body A",
            exercises = listOf(
                TemplateExercise("Squat", 3, 8, ProgramMode.OldSchool, exerciseId = ExerciseIds.SQUAT, percentOfOneRm = 70),
                TemplateExercise("Bench Press", 3, 8, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENCH_PRESS, percentOfOneRm = 70),
                TemplateExercise("Bent Over Row", 3, 8, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW, percentOfOneRm = 70),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHOULDER_PRESS, percentOfOneRm = 65),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.BICEP_CURL, percentOfOneRm = 60),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.CALF_RAISE, percentOfOneRm = 55),
            ),
        )
        val fullBodyB = RoutineTemplate(
            name = "Full Body B",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 3, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.CONVENTIONAL_DEADLIFT, percentOfOneRm = 75),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.INCLINE_BENCH_PRESS, percentOfOneRm = 65),
                TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW_REVERSE_GRIP, percentOfOneRm = 65),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.LATERAL_RAISE, percentOfOneRm = 60),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.OVERHEAD_TRICEP_EXTENSION, percentOfOneRm = 60),
                TemplateExercise("Plank", 3, null, null, exerciseId = ExerciseIds.PLANK),
            ),
        )
        val fullBodyC = RoutineTemplate(
            name = "Full Body C",
            exercises = listOf(
                TemplateExercise("Front Squat", 3, 8, ProgramMode.OldSchool, exerciseId = ExerciseIds.FRONT_SQUAT, percentOfOneRm = 70),
                TemplateExercise("Bench Press - Wide Grip", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENCH_PRESS_WIDE_GRIP, percentOfOneRm = 65),
                TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.UPRIGHT_ROW, percentOfOneRm = 65),
                TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.ARNOLD_PRESS, percentOfOneRm = 65),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.HAMMER_CURL, percentOfOneRm = 60),
                TemplateExercise("Shrug", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.SHRUG, percentOfOneRm = 60),
            ),
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
                CycleDayTemplate.rest(7),
            ),
            progressionRule = ProgressionRule.percentage(2.5f),
        )
    }

    /**
     * Push/Pull/Legs template (7-day week: 6 training days + 1 rest day).
     */
    fun pushPullLegs(): CycleTemplate {
        val pushA = RoutineTemplate(
            name = "Push A",
            exercises = listOf(
                TemplateExercise("Bench Press", 5, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENCH_PRESS, percentOfOneRm = 75),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.INCLINE_BENCH_PRESS, percentOfOneRm = 65),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHOULDER_PRESS, percentOfOneRm = 65),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.LATERAL_RAISE, percentOfOneRm = 60),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.OVERHEAD_TRICEP_EXTENSION, percentOfOneRm = 60),
            ),
        )
        val pullA = RoutineTemplate(
            name = "Pull A",
            exercises = listOf(
                TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW, percentOfOneRm = 75),
                TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW_REVERSE_GRIP, percentOfOneRm = 65),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.FACE_PULL, percentOfOneRm = 55),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHRUG, percentOfOneRm = 60),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.BICEP_CURL, percentOfOneRm = 60),
            ),
        )
        val legsA = RoutineTemplate(
            name = "Legs A",
            exercises = listOf(
                TemplateExercise("Squat", 5, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.SQUAT, percentOfOneRm = 75),
                TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.ROMANIAN_DEADLIFT, percentOfOneRm = 65),
                TemplateExercise("Lunge", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.LUNGE, percentOfOneRm = 65),
                TemplateExercise("Lying Leg Extension", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.LYING_LEG_EXTENSION, percentOfOneRm = 60),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.CALF_RAISE, percentOfOneRm = 55),
            ),
        )
        val pushB = RoutineTemplate(
            name = "Push B",
            exercises = listOf(
                TemplateExercise("Shoulder Press", 5, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHOULDER_PRESS, percentOfOneRm = 75),
                TemplateExercise("Bench Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENCH_PRESS, percentOfOneRm = 65),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.INCLINE_BENCH_PRESS, percentOfOneRm = 65),
                TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.LATERAL_RAISE, percentOfOneRm = 60),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.OVERHEAD_TRICEP_EXTENSION, percentOfOneRm = 60),
            ),
        )
        val pullB = RoutineTemplate(
            name = "Pull B",
            exercises = listOf(
                TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW, percentOfOneRm = 75),
                TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.UPRIGHT_ROW, percentOfOneRm = 65),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.FACE_PULL, percentOfOneRm = 55),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHRUG, percentOfOneRm = 60),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.HAMMER_CURL, percentOfOneRm = 60),
            ),
        )
        val legsB = RoutineTemplate(
            name = "Legs B",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 5, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.CONVENTIONAL_DEADLIFT, percentOfOneRm = 75),
                TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.FRONT_SQUAT, percentOfOneRm = 65),
                TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BULGARIAN_SPLIT_SQUAT, percentOfOneRm = 65),
                TemplateExercise("Lying Hamstring Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.LYING_HAMSTRING_CURL, percentOfOneRm = 60),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.CALF_RAISE, percentOfOneRm = 55),
            ),
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
                CycleDayTemplate.training(6, "Legs B", legsB),
                CycleDayTemplate.rest(7),
            ),
            progressionRule = ProgressionRule.percentage(2.5f),
        )
    }

    /**
     * Upper/Lower 5-day template with rest day.
     */
    fun upperLower(): CycleTemplate {
        val upperA = RoutineTemplate(
            name = "Upper A",
            exercises = listOf(
                TemplateExercise("Bench Press", 4, 6, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENCH_PRESS, percentOfOneRm = 75),
                TemplateExercise("Bent Over Row", 4, 6, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW, percentOfOneRm = 75),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHOULDER_PRESS, percentOfOneRm = 65),
                TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.BICEP_CURL, percentOfOneRm = 60),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.OVERHEAD_TRICEP_EXTENSION, percentOfOneRm = 60),
            ),
        )
        val lowerA = RoutineTemplate(
            name = "Lower A",
            exercises = listOf(
                TemplateExercise("Squat", 4, 6, ProgramMode.OldSchool, exerciseId = ExerciseIds.SQUAT, percentOfOneRm = 75),
                TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.ROMANIAN_DEADLIFT, percentOfOneRm = 65),
                TemplateExercise("Lunge", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.LUNGE, percentOfOneRm = 65),
                TemplateExercise("Calf Raise", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.CALF_RAISE, percentOfOneRm = 55),
            ),
        )
        val upperB = RoutineTemplate(
            name = "Upper B",
            exercises = listOf(
                TemplateExercise("Incline Bench Press", 4, 8, ProgramMode.OldSchool, exerciseId = ExerciseIds.INCLINE_BENCH_PRESS, percentOfOneRm = 70),
                TemplateExercise("Bent Over Row - Wide Grip", 4, 8, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW_WIDE_GRIP, percentOfOneRm = 70),
                TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.ARNOLD_PRESS, percentOfOneRm = 65),
                TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.HAMMER_CURL, percentOfOneRm = 60),
                TemplateExercise("Skull Crusher", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.SKULL_CRUSHER, percentOfOneRm = 60),
            ),
        )
        val lowerB = RoutineTemplate(
            name = "Lower B",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 4, 5, ProgramMode.OldSchool, exerciseId = ExerciseIds.CONVENTIONAL_DEADLIFT, percentOfOneRm = 75),
                TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.FRONT_SQUAT, percentOfOneRm = 65),
                TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BULGARIAN_SPLIT_SQUAT, percentOfOneRm = 65),
                TemplateExercise("Glute Kickbacks", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.GLUTE_KICKBACKS, percentOfOneRm = 60),
            ),
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
                CycleDayTemplate.training(5, "Lower B", lowerB),
            ),
            progressionRule = ProgressionRule.percentage(2.5f),
        )
    }

    /**
     * 5/3/1 (Wendler) template (7-day week: 4 training days + 3 rest days).
     *
     * Main lifts carry explicit week-1 [PercentageSet] prescriptions (65/75/85% of
     * training max, AMRAP last set). The converter selects the active week's sets
     * and folds Wendler's 90% training max into the stored percentages.
     */
    fun fiveThreeOne(): CycleTemplate {
        val benchDay = RoutineTemplate(
            name = "Bench Day",
            exercises = listOf(
                TemplateExercise("Bench Press", 3, null, ProgramMode.OldSchool, isPercentageBased = true, percentageSets = FiveThreeOneWeeks.WEEK_1, exerciseId = ExerciseIds.BENCH_PRESS),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.INCLINE_BENCH_PRESS, percentOfOneRm = 65),
                TemplateExercise("Bent Over Row", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW, percentOfOneRm = 65),
                TemplateExercise("Plank", 3, null, null, exerciseId = ExerciseIds.PLANK),
            ),
        )
        val squatDay = RoutineTemplate(
            name = "Squat Day",
            exercises = listOf(
                TemplateExercise("Squat", 3, null, ProgramMode.OldSchool, isPercentageBased = true, percentageSets = FiveThreeOneWeeks.WEEK_1, exerciseId = ExerciseIds.SQUAT),
                TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHOULDER_PRESS, percentOfOneRm = 65),
                TemplateExercise("Face Pull", 3, 15, ProgramMode.TUT, exerciseId = ExerciseIds.FACE_PULL, percentOfOneRm = 55),
                TemplateExercise("Lunge", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.LUNGE, percentOfOneRm = 65),
            ),
        )
        val pressDay = RoutineTemplate(
            name = "Press Day",
            exercises = listOf(
                TemplateExercise("Shoulder Press", 3, null, ProgramMode.OldSchool, isPercentageBased = true, percentageSets = FiveThreeOneWeeks.WEEK_1, exerciseId = ExerciseIds.SHOULDER_PRESS),
                TemplateExercise("Overhead Tricep Extension", 3, 12, ProgramMode.TUT, exerciseId = ExerciseIds.OVERHEAD_TRICEP_EXTENSION, percentOfOneRm = 60),
                TemplateExercise("Bent Over Row", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.BENT_OVER_ROW, percentOfOneRm = 65),
                TemplateExercise("Crunch", 3, 15, null, exerciseId = ExerciseIds.CRUNCH),
            ),
        )
        val deadliftDay = RoutineTemplate(
            name = "Deadlift Day",
            exercises = listOf(
                TemplateExercise("Conventional Deadlift", 3, null, ProgramMode.OldSchool, isPercentageBased = true, percentageSets = FiveThreeOneWeeks.WEEK_1, exerciseId = ExerciseIds.CONVENTIONAL_DEADLIFT),
                TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool, exerciseId = ExerciseIds.INCLINE_BENCH_PRESS, percentOfOneRm = 65),
                TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool, exerciseId = ExerciseIds.SHRUG, percentOfOneRm = 60),
                TemplateExercise("Good Morning", 3, 12, ProgramMode.OldSchool, exerciseId = ExerciseIds.GOOD_MORNING, percentOfOneRm = 60),
            ),
        )

        return CycleTemplate(
            id = "template_531",
            name = "5/3/1 (Wendler)",
            description = "Strength-focused 4-day program with percentage-based main lifts. Runs in 4-week cycles with progressive weight increases.",
            days = listOf(
                CycleDayTemplate.training(1, "Bench", benchDay),
                CycleDayTemplate.training(2, "Squat", squatDay),
                CycleDayTemplate.training(3, "Press", pressDay),
                CycleDayTemplate.training(4, "Deadlift", deadliftDay),
                CycleDayTemplate.rest(5),
                CycleDayTemplate.rest(6),
                CycleDayTemplate.rest(7),
            ),
            progressionRule = ProgressionRule.fiveThreeOne(),
            requiresOneRepMax = true,
            mainLifts = listOf("Bench Press", "Squat", "Shoulder Press", "Conventional Deadlift"),
        )
    }

    /**
     * Get all available templates.
     */
    fun all(): List<CycleTemplate> = listOf(
        threeDay(),
        pushPullLegs(),
        upperLower(),
        fiveThreeOne(),
    )
}
