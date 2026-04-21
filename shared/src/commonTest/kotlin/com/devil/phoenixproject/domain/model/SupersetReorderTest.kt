package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for superset exercise reorder functionality (Issue #365).
 *
 * Covers:
 * - reorderExercisesInSuperset: intra-superset drag-and-drop reorder
 * - normalizeRoutine with preserveIntraSupersetOrder: post-reorder normalization
 * - Boundary conditions: single-exercise superset, cross-superset isolation
 */
class SupersetReorderTest {

    // ── Helpers ─────────────────────────────────────────────────────

    private fun exercise(
        id: String,
        orderIndex: Int,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
    ) = RoutineExercise(
        id = id,
        exercise = Exercise(name = "Exercise $id", muscleGroup = "Chest", id = id),
        orderIndex = orderIndex,
        weightPerCableKg = 20f,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )

    private fun routineWithSuperset(
        ssId: String = "ss1",
        exercises: List<RoutineExercise>,
        supersets: List<Superset> = listOf(
            Superset(id = ssId, routineId = "r1", name = "Superset A", orderIndex = 0),
        ),
    ) = Routine(
        id = "r1",
        name = "Test Routine",
        exercises = exercises,
        supersets = supersets,
    )

    // ── reorderExercisesInSuperset ──────────────────────────────────

    @Test
    fun reorder_firstToLast_movesExerciseToEnd() {
        // Superset with [A(0), B(1), C(2)] -- move A to position 2
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ss1", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ss1", orderInSuperset = 2),
        )
        val routine = routineWithSuperset(exercises = exercises)

        val result = reorderExercisesInSuperset(routine, "ss1", fromIndex = 0, toIndex = 2)

        val reordered = result.exercises.sortedBy { it.orderInSuperset }
        assertEquals("B", reordered[0].id)
        assertEquals(0, reordered[0].orderInSuperset)
        assertEquals("C", reordered[1].id)
        assertEquals(1, reordered[1].orderInSuperset)
        assertEquals("A", reordered[2].id)
        assertEquals(2, reordered[2].orderInSuperset)
    }

    @Test
    fun reorder_lastToFirst_movesExerciseToStart() {
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ss1", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ss1", orderInSuperset = 2),
        )
        val routine = routineWithSuperset(exercises = exercises)

        val result = reorderExercisesInSuperset(routine, "ss1", fromIndex = 2, toIndex = 0)

        val reordered = result.exercises.sortedBy { it.orderInSuperset }
        assertEquals("C", reordered[0].id)
        assertEquals(0, reordered[0].orderInSuperset)
        assertEquals("A", reordered[1].id)
        assertEquals(1, reordered[1].orderInSuperset)
        assertEquals("B", reordered[2].id)
        assertEquals(2, reordered[2].orderInSuperset)
    }

    @Test
    fun reorder_middleToFirst_swapsCorrectly() {
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ss1", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ss1", orderInSuperset = 2),
        )
        val routine = routineWithSuperset(exercises = exercises)

        val result = reorderExercisesInSuperset(routine, "ss1", fromIndex = 1, toIndex = 0)

        val reordered = result.exercises.sortedBy { it.orderInSuperset }
        assertEquals("B", reordered[0].id)
        assertEquals(0, reordered[0].orderInSuperset)
        assertEquals("A", reordered[1].id)
        assertEquals(1, reordered[1].orderInSuperset)
        assertEquals("C", reordered[2].id)
        assertEquals(2, reordered[2].orderInSuperset)
    }

    @Test
    fun reorder_preservesOtherSupersets() {
        // Two supersets: X [A, B] and Y [C, D]. Reorder within X only.
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ssX", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ssX", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ssY", orderInSuperset = 0),
            exercise("D", orderIndex = 3, supersetId = "ssY", orderInSuperset = 1),
        )
        val supersets = listOf(
            Superset(id = "ssX", routineId = "r1", name = "Superset X", orderIndex = 0),
            Superset(id = "ssY", routineId = "r1", name = "Superset Y", orderIndex = 2),
        )
        val routine = routineWithSuperset(exercises = exercises, supersets = supersets)

        val result = reorderExercisesInSuperset(routine, "ssX", fromIndex = 0, toIndex = 1)

        // Y exercises must be unchanged
        val yExercises = result.exercises.filter { it.supersetId == "ssY" }.sortedBy { it.orderInSuperset }
        assertEquals("C", yExercises[0].id)
        assertEquals(0, yExercises[0].orderInSuperset)
        assertEquals("D", yExercises[1].id)
        assertEquals(1, yExercises[1].orderInSuperset)

        // X exercises should be swapped
        val xExercises = result.exercises.filter { it.supersetId == "ssX" }.sortedBy { it.orderInSuperset }
        assertEquals("B", xExercises[0].id)
        assertEquals(0, xExercises[0].orderInSuperset)
        assertEquals("A", xExercises[1].id)
        assertEquals(1, xExercises[1].orderInSuperset)
    }

    @Test
    fun reorder_singleExerciseSuperset_isNoOp() {
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
        )
        val routine = routineWithSuperset(exercises = exercises)

        // fromIndex == toIndex => no-op by contract
        val result = reorderExercisesInSuperset(routine, "ss1", fromIndex = 0, toIndex = 0)

        assertEquals(routine.exercises, result.exercises)
    }

    @Test
    fun normalizeRoutine_afterInnerReorder_preservesUserOrder() {
        // After reorderExercisesInSuperset, call normalizeRoutine with preserveIntraSupersetOrder=true
        // Verify normalizeRoutine does NOT clobber the intra-superset reorder
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ss1", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ss1", orderInSuperset = 2),
        )
        val routine = routineWithSuperset(exercises = exercises)

        // Step 1: Reorder A to last
        val reordered = reorderExercisesInSuperset(routine, "ss1", fromIndex = 0, toIndex = 2)

        // Step 2: Normalize with preservation flag
        val normalized = normalizeRoutine(reordered, preserveIntraSupersetOrder = true)

        // orderInSuperset should match the reorder, not be recalculated
        val ssExercises = normalized.exercises
            .filter { it.supersetId == "ss1" }
            .sortedBy { it.orderInSuperset }
        assertEquals("B", ssExercises[0].id)
        assertEquals(0, ssExercises[0].orderInSuperset)
        assertEquals("C", ssExercises[1].id)
        assertEquals(1, ssExercises[1].orderInSuperset)
        assertEquals("A", ssExercises[2].id)
        assertEquals(2, ssExercises[2].orderInSuperset)
    }

    @Test
    fun reorder_outOfBoundsIndex_returnsOriginalRoutine() {
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ss1", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ss1", orderInSuperset = 2),
        )
        val routine = routineWithSuperset(exercises = exercises)

        // fromIndex = 5 is out of bounds for a 3-element superset
        val result = reorderExercisesInSuperset(routine, "ss1", fromIndex = 5, toIndex = 0)

        assertEquals(routine.exercises, result.exercises, "Out-of-bounds fromIndex should return original routine unchanged")
    }

    @Test
    fun reorder_doesNotCrossSupersetBoundary() {
        // Verify exercises stay within their parent superset after reorder
        val exercises = listOf(
            exercise("A", orderIndex = 0, supersetId = "ss1", orderInSuperset = 0),
            exercise("B", orderIndex = 1, supersetId = "ss1", orderInSuperset = 1),
            exercise("C", orderIndex = 2, supersetId = "ss1", orderInSuperset = 2),
        )
        val routine = routineWithSuperset(exercises = exercises)

        val result = reorderExercisesInSuperset(routine, "ss1", fromIndex = 0, toIndex = 2)

        // All exercises must still belong to ss1
        result.exercises.forEach { ex ->
            assertEquals("ss1", ex.supersetId, "Exercise ${ex.id} should remain in ss1")
        }
    }
}
