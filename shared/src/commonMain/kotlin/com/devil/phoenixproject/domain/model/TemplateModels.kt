package com.devil.phoenixproject.domain.model

import kotlin.math.roundToInt

/**
 * A single set defined by percentage of 1RM (for 5/3/1).
 */
data class PercentageSet(val percent: Float, val targetReps: Int?, val isAmrap: Boolean = false)

/**
 * 5/3/1 week definitions with percentage-based sets.
 */
object FiveThreeOneWeeks {
    val WEEK_1 = listOf(
        PercentageSet(0.65f, 5),
        PercentageSet(0.75f, 5),
        PercentageSet(0.85f, null, isAmrap = true),
    )
    val WEEK_2 = listOf(
        PercentageSet(0.70f, 3),
        PercentageSet(0.80f, 3),
        PercentageSet(0.90f, null, isAmrap = true),
    )
    val WEEK_3 = listOf(
        PercentageSet(0.75f, 5),
        PercentageSet(0.85f, 3),
        PercentageSet(0.95f, null, isAmrap = true),
    )
    val WEEK_4_DELOAD = listOf(
        PercentageSet(0.40f, 5),
        PercentageSet(0.50f, 5),
        PercentageSet(0.60f, 5),
    )

    fun forWeek(weekNumber: Int): List<PercentageSet> = when (weekNumber) {
        1 -> WEEK_1
        2 -> WEEK_2
        3 -> WEEK_3
        4 -> WEEK_4_DELOAD
        else -> WEEK_1
    }
}

private const val FIVE_THREE_ONE_TRAINING_MAX_FACTOR = 0.9

fun computeFiveThreeOneSetWeightsForWeek(weekNumber: Int): List<Int> = FiveThreeOneWeeks.forWeek(weekNumber).map { set ->
    val percentOfOneRepMax = (set.percent * 100).roundToInt()
    (percentOfOneRepMax * FIVE_THREE_ONE_TRAINING_MAX_FACTOR).roundToInt()
}

/**
 * Default %-of-1RM working-weight prescription for a rep count, per the template
 * normalization rules (see CycleTemplates): 5-6 reps → 75%, 8 → 70%, 10 → 65%,
 * 12 → 60%, 15+ → 55%. Timed/unspecified reps default to 70%.
 *
 * Used to seed [TemplateExercise.percentOfOneRm] and to keep it consistent when
 * the user edits reps in the template preview.
 */
fun defaultPercentOfOneRmForReps(reps: Int?): Int = when {
    reps == null -> 70
    reps <= 6 -> 75
    reps <= 8 -> 70
    reps <= 10 -> 65
    reps <= 12 -> 60
    else -> 55
}

/**
 * Calculate weight for a percentage-based set.
 * Uses 90% of 1RM as "training max" per Wendler's method.
 */
fun calculateSetWeight(oneRepMaxKg: Float, percentageSet: PercentageSet): Float {
    val trainingMax = oneRepMaxKg * 0.9f
    val rawWeight = trainingMax * percentageSet.percent
    // Round to nearest 0.5kg
    return (rawWeight * 2).toInt() / 2f
}

/**
 * An exercise within a template, before being resolved to actual Exercise.
 *
 * @param exerciseName Name to look up in exercise library (fallback)
 * @param sets Number of sets
 * @param reps Target reps per set (null for timed exercises)
 * @param suggestedMode Workout mode for cable exercises, null for bodyweight exercises
 * @param isPercentageBased Whether this uses percentage-based loading (5/3/1)
 * @param percentageSets Percentage sets for 5/3/1 programming
 * @param exerciseId Stable ID from exercise library for reliable lookup
 * @param percentOfOneRm Working-weight prescription as % of estimated 1RM for
 *   non-percentage-based exercises. Resolved live at workout start through
 *   ResolveRoutineWeightsUseCase (VBT 1RM → stored 1RM → PRs), so weights grow
 *   with the user. Ignored when [isPercentageBased] is true (per-set percentages
 *   from [percentageSets] apply instead).
 */
data class TemplateExercise(
    val exerciseName: String,
    val sets: Int,
    val reps: Int?,
    val suggestedMode: ProgramMode? = ProgramMode.OldSchool,
    val isPercentageBased: Boolean = false,
    val percentageSets: List<PercentageSet>? = null,
    val exerciseId: String? = null,
    val percentOfOneRm: Int = 70,
)

/**
 * A routine template containing multiple exercises.
 */
data class RoutineTemplate(val name: String, val exercises: List<TemplateExercise>)

/**
 * A single day in a cycle template.
 */
data class CycleDayTemplate(val dayNumber: Int, val name: String, val routine: RoutineTemplate?, val isRestDay: Boolean = false) {
    companion object {
        fun training(dayNumber: Int, name: String, routine: RoutineTemplate) = CycleDayTemplate(dayNumber, name, routine, isRestDay = false)

        fun rest(dayNumber: Int, name: String = "Rest") = CycleDayTemplate(dayNumber, name, null, isRestDay = true)
    }
}

/**
 * Complete cycle template with all days and progression rules.
 */
data class CycleTemplate(
    val id: String,
    val name: String,
    val description: String,
    val days: List<CycleDayTemplate>,
    val progressionRule: ProgressionRule?,
    val requiresOneRepMax: Boolean = false,
    val mainLifts: List<String> = emptyList(),
)
