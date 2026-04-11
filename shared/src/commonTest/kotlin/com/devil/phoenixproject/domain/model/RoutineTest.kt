package com.devil.phoenixproject.domain.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Routine domain model enhancements
 */
class RoutineTest {

    // ===== roundToIncrement extension (Issue #266) =====

    @Test
    fun roundToIncrement_halfKg() {
        assertEquals(10.0f, 10.0f.roundToIncrement(0.5f))
        assertEquals(10.5f, 10.3f.roundToIncrement(0.5f))
        assertEquals(10.0f, 10.2f.roundToIncrement(0.5f))
    }

    @Test
    fun roundToIncrement_oneTenthLb() {
        val result = 10.14f.roundToIncrement(0.1f)
        assertTrue(abs(result - 10.1f) < 0.01f, "Expected ~10.1, got $result")
    }

    @Test
    fun roundToIncrement_fiveKg() {
        assertEquals(10.0f, 12.0f.roundToIncrement(5.0f))
        assertEquals(15.0f, 13.0f.roundToIncrement(5.0f))
    }

    @Test
    fun roundToIncrement_zeroIncrement_returnsOriginal() {
        assertEquals(10.3f, 10.3f.roundToIncrement(0.0f))
    }

    // ===== RoutineExercise.getRestForSet =====

    @Test
    fun getRestForSet_returnsConfiguredRest() {
        val exercise = createTestRoutineExercise(
            setRestSeconds = listOf(30, 60, 90),
        )
        assertEquals(30, exercise.getRestForSet(0))
        assertEquals(60, exercise.getRestForSet(1))
        assertEquals(90, exercise.getRestForSet(2))
    }

    @Test
    fun getRestForSet_fallsBackTo60WhenMissing() {
        val exercise = createTestRoutineExercise(
            setRestSeconds = listOf(30),
        )
        assertEquals(30, exercise.getRestForSet(0))
        assertEquals(60, exercise.getRestForSet(1)) // Falls back
        assertEquals(60, exercise.getRestForSet(5)) // Falls back
    }

    @Test
    fun withNormalizedRestTimes_padsToMatchSets() {
        val exercise = createTestRoutineExercise(
            setReps = listOf(10, 10, 10),
            setRestSeconds = listOf(30),
        )
        val normalized = exercise.withNormalizedRestTimes()
        assertEquals(3, normalized.setRestSeconds.size)
        assertEquals(30, normalized.setRestSeconds[0])
        assertEquals(60, normalized.setRestSeconds[1])
        assertEquals(60, normalized.setRestSeconds[2])
    }

    @Test
    fun withNormalizedRestTimes_trimsExcess() {
        val exercise = createTestRoutineExercise(
            setReps = listOf(10, 10),
            setRestSeconds = listOf(30, 60, 90, 120),
        )
        val normalized = exercise.withNormalizedRestTimes()
        assertEquals(2, normalized.setRestSeconds.size)
    }

    // ===== Superset ordering =====

    @Test
    fun routine_getItems_ordersSupersetAndStandalone() {
        val ssId = "ss1"
        val exercises = listOf(
            createTestRoutineExercise(
                id = "e1",
                orderIndex = 0,
                supersetId = ssId,
                orderInSuperset = 0,
            ),
            createTestRoutineExercise(
                id = "e2",
                orderIndex = 0,
                supersetId = ssId,
                orderInSuperset = 1,
            ),
            createTestRoutineExercise(id = "e3", orderIndex = 1),
        )
        val supersets = listOf(
            Superset(id = ssId, routineId = "r1", name = "SS A", orderIndex = 0),
        )
        val routine =
            Routine(id = "r1", name = "Test", exercises = exercises, supersets = supersets)
        val items = routine.getItems()

        assertEquals(2, items.size)
        assertTrue(items[0] is RoutineItem.SupersetItem)
        assertTrue(items[1] is RoutineItem.Single)
    }

    // ===== Helper =====

    private fun createTestRoutineExercise(
        id: String = "test",
        orderIndex: Int = 0,
        setReps: List<Int?> = listOf(10, 10, 10),
        setRestSeconds: List<Int> = emptyList(),
        supersetId: String? = null,
        orderInSuperset: Int = 0,
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = Exercise(
            name = "Test Exercise",
            muscleGroup = "Chest",
            equipment = "Double Cable",
            id = "ex1",
        ),
        orderIndex = orderIndex,
        setReps = setReps,
        weightPerCableKg = 20f,
        setRestSeconds = setRestSeconds,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )
}
