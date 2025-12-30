package com.devil.phoenixproject.domain.model

/**
 * Domain model for a workout routine
 */
data class Routine(
    val id: String,
    val name: String,
    val description: String = "",
    val exercises: List<RoutineExercise> = emptyList(),
    val createdAt: Long = currentTimeMillis(),
    val lastUsed: Long? = null,
    val useCount: Int = 0
)

/**
 * Domain model for an exercise within a routine
 *
 * @param cableConfig User's cable configuration choice (SINGLE or DOUBLE)
 *                    Should be set based on exercise's defaultCableConfig
 *                    If exercise allows EITHER, defaults to DOUBLE
 * @param weightPerCableKg Weight in kg per cable (machine tracks each cable independently)
 *                         For SINGLE: weight on the one active cable
 *                         For DOUBLE: weight per cable (total load = 2x this value)
 * @param supersetId Reference to parent Superset container (null if not in a superset)
 * @param orderInSuperset Position within the superset (0-based)
 */
data class RoutineExercise(
    val id: String,
    val exercise: Exercise,
    val cableConfig: CableConfiguration,
    val orderIndex: Int,
    val setReps: List<Int?> = listOf(10, 10, 10),
    val weightPerCableKg: Float,
    // Optional per-set weights in kg per cable; when empty, fall back to weightPerCableKg
    val setWeightsPerCableKg: List<Float> = emptyList(),
    // Selected workout type for this exercise in routines
    val workoutType: WorkoutType = WorkoutType.Program(ProgramMode.OldSchool),
    // Echo-specific configuration
    val eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
    val echoLevel: EchoLevel = EchoLevel.HARDER,
    val progressionKg: Float = 0f,
    val setRestSeconds: List<Int> = emptyList(), // NEW: per-set rest times
    // Optional duration in seconds for duration-based sets
    val duration: Int? = null,
    // AMRAP (As Many Reps As Possible) flag - when true, setReps should be null for that set
    val isAMRAP: Boolean = false,
    // Per Set Rest Time toggle - when true, each set has its own rest time; when false, single rest time applies to all sets
    val perSetRestTime: Boolean = false,
    // Stall detection toggle - when true, auto-stops set if user hesitates too long (applies to AMRAP/Just Lift modes)
    val stallDetectionEnabled: Boolean = true,
    // Superset configuration (container model)
    val supersetId: String? = null,       // Reference to parent Superset container
    val orderInSuperset: Int = 0          // Position within the superset
) {
    /** Returns true if this exercise is part of a superset */
    val isInSuperset: Boolean get() = supersetId != null
    // Computed property for backwards compatibility
    val sets: Int get() = setReps.size
    val reps: Int get() = setReps.firstOrNull() ?: 10

    // Helper to get rest time for specific set (with fallback to 60s default)
    fun getRestForSet(setIndex: Int): Int {
        return setRestSeconds.getOrNull(setIndex) ?: 60
    }

    // Helper to ensure rest times array matches number of sets
    fun withNormalizedRestTimes(): RoutineExercise {
        val numSets = setReps.size
        val normalizedRest = if (setRestSeconds.isEmpty()) {
            List(numSets) { 60 } // Default to 60s for all sets
        } else if (setRestSeconds.size < numSets) {
            setRestSeconds + List(numSets - setRestSeconds.size) { 60 } // Pad with 60s
        } else {
            setRestSeconds.take(numSets) // Trim to match sets
        }
        return copy(setRestSeconds = normalizedRest)
    }
}

/**
 * Helper function to determine the appropriate cable configuration for an exercise
 * If exercise allows EITHER, defaults to DOUBLE
 */
fun Exercise.resolveDefaultCableConfig(): CableConfiguration {
    return when (defaultCableConfig) {
        CableConfiguration.EITHER -> CableConfiguration.DOUBLE
        else -> defaultCableConfig
    }
}

// ==================== SUPERSET SUPPORT ====================

/**
 * Superset colors for visual distinction
 */
object SupersetColors {
    const val INDIGO = 0   // #6366F1
    const val PINK = 1     // #EC4899
    const val GREEN = 2    // #10B981
    const val AMBER = 3    // #F59E0B

    fun next(existingIndices: Set<Int>): Int {
        for (i in 0..3) {
            if (i !in existingIndices) return i
        }
        return existingIndices.size % 4
    }
}

/**
 * First-class superset container entity.
 * Represents a group of exercises performed back-to-back.
 */
data class Superset(
    val id: String,
    val routineId: String,
    val name: String,
    val colorIndex: Int = SupersetColors.INDIGO,
    val restBetweenSeconds: Int = 10,
    val orderIndex: Int = 0,
    val exercises: List<RoutineExercise> = emptyList(),
    val isCollapsed: Boolean = false  // UI state only, not persisted
) {
    val isEmpty: Boolean get() = exercises.isEmpty()
    val exerciseCount: Int get() = exercises.size

    /** Total number of sets (minimum sets among all exercises) */
    val sets: Int get() = exercises.minOfOrNull { it.sets } ?: 0
}

/**
 * Generate a unique superset ID
 */
fun generateSupersetId(): String = "superset_${generateUUID()}"

/**
 * Sealed class representing an item in a routine - either a single exercise or a superset group.
 * Used for UI display and workout execution.
 */
sealed class RoutineItem {
    /** A single exercise not part of any superset */
    data class SingleExercise(val exercise: RoutineExercise) : RoutineItem() {
        val orderIndex: Int get() = exercise.orderIndex
    }

    /** A group of exercises forming a superset */
    data class Superset(val group: SupersetGroup) : RoutineItem() {
        val orderIndex: Int get() = group.exercises.minOfOrNull { it.orderIndex } ?: 0
    }
}

/**
 * Extension to get exercises grouped by supersets.
 * Returns a list of RoutineItems (either SingleExercise or Superset) sorted by order.
 */
fun Routine.getGroupedExercises(): List<RoutineItem> {
    // Group exercises by superset ID
    val supersetGroups = exercises
        .filter { it.supersetGroupId != null }
        .groupBy { it.supersetGroupId!! }

    // Create superset items
    val supersetItems = supersetGroups.map { (groupId, groupExercises) ->
        val sortedExercises = groupExercises.sortedBy { it.supersetOrder }
        val groupIndex = ('A'.code + supersetGroups.keys.toList().indexOf(groupId)).toChar()
        RoutineItem.Superset(
            SupersetGroup(
                id = groupId,
                name = "Superset $groupIndex",
                exercises = sortedExercises,
                restBetweenExercises = sortedExercises.first().supersetRestSeconds
            )
        )
    }

    // Create single exercise items (not in any superset)
    val singleItems = exercises
        .filter { it.supersetGroupId == null }
        .map { RoutineItem.SingleExercise(it) }

    // Combine and sort by order index
    return (supersetItems + singleItems).sortedBy { item ->
        when (item) {
            is RoutineItem.SingleExercise -> item.orderIndex
            is RoutineItem.Superset -> item.orderIndex
        }
    }
}

/**
 * Extension to check if a routine contains any supersets
 */
fun Routine.hasSupersets(): Boolean {
    return exercises.any { it.supersetGroupId != null }
}

/**
 * Extension to get all superset group IDs in a routine
 */
fun Routine.getSupersetGroupIds(): Set<String> {
    return exercises.mapNotNull { it.supersetGroupId }.toSet()
}

/**
 * Extension to get exercises in a specific superset group
 */
fun Routine.getExercisesInSuperset(groupId: String): List<RoutineExercise> {
    return exercises
        .filter { it.supersetGroupId == groupId }
        .sortedBy { it.supersetOrder }
}

