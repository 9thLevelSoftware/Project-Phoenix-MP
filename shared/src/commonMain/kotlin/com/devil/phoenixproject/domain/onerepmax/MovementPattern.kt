package com.devil.phoenixproject.domain.onerepmax

/** Movement patterns with their default Minimum Velocity Threshold (m/s) for 1RM extrapolation. */
enum class MovementPattern(val defaultMvtMs: Float) {
    HORIZONTAL_PRESS(0.15f),
    VERTICAL_PRESS(0.20f),
    SQUAT(0.30f),
    HINGE(0.15f),
    OTHER(0.20f),
}

/**
 * Best-effort classification of an exercise into a movement pattern from its name and
 * muscle groups. Keyword order matters: more specific patterns are checked first.
 */
fun classifyMovementPattern(name: String, muscleGroups: String): MovementPattern {
    val haystack = "$name $muscleGroups".lowercase()
    return when {
        listOf("deadlift", "rdl", "hip thrust", "hinge", "good morning", "swing").any { it in haystack } -> MovementPattern.HINGE
        listOf("squat", "leg press", "lunge", "split squat").any { it in haystack } -> MovementPattern.SQUAT
        listOf("overhead", "ohp", "shoulder press", "military", "push press").any { it in haystack } -> MovementPattern.VERTICAL_PRESS
        listOf("bench", "chest press", "horizontal press", "floor press").any { it in haystack } -> MovementPattern.HORIZONTAL_PRESS
        else -> MovementPattern.OTHER
    }
}
