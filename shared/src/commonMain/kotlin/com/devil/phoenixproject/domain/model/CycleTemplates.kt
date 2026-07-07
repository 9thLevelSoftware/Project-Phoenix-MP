package com.devil.phoenixproject.domain.model

/**
 * Stable exercise IDs from the bundled exercise_dump.json library.
 * Used by templates for reliable ID-based lookup with name fallback.
 */
private object ExerciseIds {
    const val SQUAT = "UjIGHxCav-lS9B2I"
    const val BENCH_PRESS = "ZZ92N8QsBdp6HCh3"
    const val BENT_OVER_ROW = "cJt26IdtckFcJsq1"
    const val SHOULDER_PRESS = "0040d53f-85c7-4564-b14e-9b38c979b461"
    const val BICEP_CURL = "k-PGXPztgc5uS42S"
    const val CALF_RAISE = "j3Y1MpvaeGPy0o99"
    const val CONVENTIONAL_DEADLIFT = "e64c7837-52e2-4b97-b771-cf08ab861af1"
    const val INCLINE_BENCH_PRESS = "aR4mXWcgsqNaxw4C"
    const val BENT_OVER_ROW_REVERSE_GRIP = "cc7f2c3a-20bd-4a3e-8d5a-393420386c23"
    const val LATERAL_RAISE = "8WHxwWifeoVP8vLq"
    const val OVERHEAD_TRICEP_EXTENSION = "_i1E704BS8bngWrv"
    const val PLANK = "U9nn8f-vcAltrR-E"
    const val FRONT_SQUAT = "rwTxzKiYAl8UENGp"
    const val BENCH_PRESS_WIDE_GRIP = "IAcN1MX1kIiF9wdo"
    const val UPRIGHT_ROW = "513b8b5b-5315-4510-87c6-04df95a51053"
    const val ARNOLD_PRESS = "3cd0a0cb-8e56-4b0e-83a0-d88ff369749f"
    const val HAMMER_CURL = "05wiA4Eqtrj388Ui"
    const val SHRUG = "1cAPp9FYOqgEFDfm"
    const val FACE_PULL = "lKxWrGuEzVcxLYqG"
    const val ROMANIAN_DEADLIFT = "WAB_Z7EUGeUxF9ce"
    const val LUNGE = "vvG84utDyVrhhcJB"
    const val LYING_LEG_EXTENSION = "IIglddaLiD3aFW9a"
    const val BULGARIAN_SPLIT_SQUAT = "-YjRuMgOttzv0yZW"
    const val LYING_HAMSTRING_CURL = "xh7phUUawthAuF41"
    const val SKULL_CRUSHER = "cOaTQ1ljsuUom_cn"
    const val BENT_OVER_ROW_WIDE_GRIP = "useRdaf9DVqyjBD8"
    const val GLUTE_KICKBACKS = "e280829c-aa17-4812-b8fa-bcd0d89ad815"
    const val CRUNCH = "FLyfmJWYyxLus7e8"
    const val GOOD_MORNING = "enuJ_FgAzXDLAweK"
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
