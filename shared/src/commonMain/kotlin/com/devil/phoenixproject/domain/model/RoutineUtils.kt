package com.devil.phoenixproject.domain.model

/**
 * Normalize a routine after structural changes (reorder, superset membership changes).
 *
 * This function:
 * 1. Groups superset members contiguously (required by getNextStep() Phase C)
 * 2. Reindexes orderIndex for flat ordering
 * 3. Optionally recalculates orderInSuperset from orderIndex order
 *
 * @param routine The routine to normalize
 * @param preserveIntraSupersetOrder When true, preserves existing orderInSuperset values
 *   instead of recalculating them from orderIndex position. Use this after intra-superset
 *   reorder operations where orderInSuperset was explicitly set by the user.
 * @return A normalized copy of the routine
 */
fun normalizeRoutine(routine: Routine, preserveIntraSupersetOrder: Boolean = false): Routine {
    // Issue #334: Reorder exercises so superset members are contiguous.
    // When exercises are added to a superset they get appended to the end
    // of the flat list. getNextStep() Phase C assumes superset members are
    // contiguous, so we must group them before reindexing.
    val reorderedExercises = routine.getItems().flatMap { item ->
        when (item) {
            is RoutineItem.Single -> listOf(item.exercise)
            is RoutineItem.SupersetItem ->
                item.superset.exercises.sortedBy { it.orderInSuperset }
        }
    }

    val reindexedExercises = reorderedExercises.mapIndexed { index, ex ->
        ex.copy(orderIndex = index)
    }

    val normalizedExercises = if (preserveIntraSupersetOrder) {
        // Preserve user-set orderInSuperset values — only reindex orderIndex
        reindexedExercises
    } else {
        // Recalculate orderInSuperset from position within each superset group
        val exercisesBySuperset = reindexedExercises.groupBy { it.supersetId }
        reindexedExercises.map { ex ->
            val supersetId = ex.supersetId ?: return@map ex
            val ordered = exercisesBySuperset[supersetId].orEmpty().sortedBy { it.orderIndex }
            val newOrder = ordered.indexOfFirst { it.id == ex.id }
            ex.copy(orderInSuperset = if (newOrder >= 0) newOrder else ex.orderInSuperset)
        }
    }

    val normalizedSupersets = routine.supersets.mapNotNull { superset ->
        val minOrder = normalizedExercises
            .filter { it.supersetId == superset.id }
            .minOfOrNull { it.orderIndex }
        minOrder?.let { superset.copy(orderIndex = it) }
    }
    return routine.copy(
        exercises = normalizedExercises,
        supersets = normalizedSupersets,
    )
}

/**
 * Reorder top-level routine items (standalone exercises and whole supersets).
 *
 * Superset containers have their own orderIndex, separate from member exercise
 * orderIndex values. Updating both keeps normalizeRoutine() from sorting a
 * dragged superset back to its stale container position.
 */
fun reorderRoutineItems(
    routine: Routine,
    fromIndex: Int,
    toIndex: Int,
): Routine {
    if (fromIndex == toIndex) return routine

    val items = routine.getItems().toMutableList()
    if (fromIndex !in items.indices || toIndex !in items.indices) {
        return routine
    }

    val moved = items.removeAt(fromIndex)
    items.add(toIndex, moved)

    var nextOrderIndex = 0
    val reorderedExercises = mutableListOf<RoutineExercise>()
    val supersetOrderById = mutableMapOf<String, Int>()

    items.forEach { item ->
        when (item) {
            is RoutineItem.Single -> {
                reorderedExercises += item.exercise.copy(orderIndex = nextOrderIndex)
                nextOrderIndex += 1
            }

            is RoutineItem.SupersetItem -> {
                supersetOrderById[item.superset.id] = nextOrderIndex
                val supersetExercises = item.superset.exercises.sortedBy { it.orderInSuperset }
                supersetExercises.forEachIndexed { index, exercise ->
                    reorderedExercises += exercise.copy(
                        orderIndex = nextOrderIndex + index,
                        orderInSuperset = index,
                    )
                }
                nextOrderIndex += supersetExercises.size
            }
        }
    }

    val reorderedSupersets = routine.supersets.map { superset ->
        supersetOrderById[superset.id]?.let { orderIndex ->
            superset.copy(orderIndex = orderIndex)
        } ?: superset
    }

    return normalizeRoutine(
        routine.copy(
            exercises = reorderedExercises,
            supersets = reorderedSupersets,
        ),
        preserveIntraSupersetOrder = true,
    )
}

/**
 * Reorder exercises within a single superset by moving an exercise from one position to another.
 *
 * This updates only the orderInSuperset values for exercises in the target superset.
 * It does NOT change orderIndex or any other fields.
 *
 * @param routine The current routine
 * @param supersetId The ID of the superset whose exercises are being reordered
 * @param fromIndex The current orderInSuperset position of the exercise being moved
 * @param toIndex The target orderInSuperset position
 * @return A new Routine with updated orderInSuperset values, or the original if indices are invalid
 */
fun reorderExercisesInSuperset(
    routine: Routine,
    supersetId: String,
    fromIndex: Int,
    toIndex: Int,
): Routine {
    if (fromIndex == toIndex) return routine

    val supersetExercises = routine.exercises
        .filter { it.supersetId == supersetId }
        .sortedBy { it.orderInSuperset }

    if (fromIndex !in supersetExercises.indices || toIndex !in supersetExercises.indices) {
        return routine
    }

    // Perform the move in a mutable list
    val reordered = supersetExercises.toMutableList()
    val moved = reordered.removeAt(fromIndex)
    reordered.add(toIndex, moved)

    // Build ID-to-new-orderInSuperset map
    val newOrderMap = reordered.mapIndexed { index, ex -> ex.id to index }.toMap()

    // Apply new orderInSuperset to the full exercise list
    val updatedExercises = routine.exercises.map { ex ->
        val newOrder = newOrderMap[ex.id]
        if (newOrder != null) {
            ex.copy(orderInSuperset = newOrder)
        } else {
            ex
        }
    }

    return routine.copy(exercises = updatedExercises)
}
