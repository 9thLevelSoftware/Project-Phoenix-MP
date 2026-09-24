package com.devil.phoenixproject.domain.model

/**
 * Built-in "Essentials" set for the exercise picker (#770): 24 common movements from the bundled
 * catalogue, by stable catalogue id, so the full ~870-exercise library can be narrowed to a
 * familiar starting list. It is a filter only: favourites, custom exercises and the full
 * catalogue are unchanged.
 */
object EssentialsExercises {
    val ids: Set<String> = linkedSetOf(
        // Chest
        "Barbell_Bench_Press_-_Medium_Grip",
        "Barbell_Incline_Bench_Press_-_Medium_Grip",
        "Flat_Bench_Cable_Flyes",
        // Back
        "Bent_Over_Barbell_Row",
        "Seated_Cable_Rows",
        "Wide-Grip_Lat_Pulldown",
        "Barbell_Shrug",
        // Shoulders
        "Barbell_Shoulder_Press",
        "Side_Lateral_Raise",
        "Cable_Rear_Delt_Fly",
        "Face_Pull",
        // Arms
        "Barbell_Curl",
        "Hammer_Curls",
        "Standing_Overhead_Barbell_Triceps_Extension",
        "Triceps_Pushdown",
        // Legs
        "Barbell_Squat",
        "Barbell_Deadlift",
        "Romanian_Deadlift",
        "Barbell_Lunge",
        "Standing_Calf_Raises",
        "Barbell_Hip_Thrust",
        "One-Legged_Cable_Kickback",
        // Core
        "Cable_Crunch",
        "Standing_Cable_Wood_Chop",
    )

    fun contains(exercise: Exercise): Boolean = exercise.id?.trim() in ids
}
