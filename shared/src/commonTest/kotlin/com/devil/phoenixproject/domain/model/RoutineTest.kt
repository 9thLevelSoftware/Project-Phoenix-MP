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

    // ===== resolveWeight / resolveSetWeights (Issue #357) =====

    @Test
    fun resolveWeight_percentOfPR_calculatesCorrectly() {
        val exercise = createTestRoutineExercise(
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            weightPerCableKg = 5.75f, // Old absolute value
        )
        // 80% of 50kg PR = 40kg
        assertEquals(40f, exercise.resolveWeight(50f))
    }

    @Test
    fun resolveWeight_noPR_fallsBackToAbsolute() {
        val exercise = createTestRoutineExercise(
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            weightPerCableKg = 5.75f,
        )
        assertEquals(5.75f, exercise.resolveWeight(null))
    }

    @Test
    fun resolveSetWeights_allSetsGetPRWeight() {
        val exercise = createTestRoutineExercise(
            setReps = listOf(10, 10, 10),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            weightPerCableKg = 5.75f,
        )
        // 80% of 50kg PR = 40kg for ALL 3 sets
        val weights = exercise.resolveSetWeights(50f)
        assertEquals(3, weights.size)
        weights.forEachIndexed { index, weight ->
            assertEquals(40f, weight, "Set $index should be 40kg (80% of 50kg)")
        }
    }

    @Test
    fun resolveSetWeights_withPerSetPercentages() {
        val exercise = createTestRoutineExercise(
            setReps = listOf(10, 8, 6),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(70, 80, 90),
            weightPerCableKg = 5.75f,
        )
        // Per-set: 70%, 80%, 90% of 100kg PR
        val weights = exercise.resolveSetWeights(100f)
        assertEquals(3, weights.size)
        assertEquals(70f, weights[0])
        assertEquals(80f, weights[1])
        assertEquals(90f, weights[2])
    }

    @Test
    fun resolveSetWeights_warmupSetsDoNotAffectWorkingSetCount() {
        // Issue #357: Warm-up sets should NOT affect the number of resolved working set weights
        val exercise = createTestRoutineExercise(
            setReps = listOf(10, 10, 10), // 3 working sets
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            weightPerCableKg = 5.75f,
            warmupSets = listOf(
                WarmupSet(reps = 12, percentOfWorking = 50),
                WarmupSet(reps = 8, percentOfWorking = 70),
                WarmupSet(reps = 4, percentOfWorking = 85),
            ),
        )
        // Warm-up sets should NOT inflate the resolved set weights list
        val weights = exercise.resolveSetWeights(50f)
        assertEquals(3, weights.size, "Should have 3 weights (matching 3 working sets, NOT 3+3 warm-ups)")
        weights.forEach { weight ->
            assertEquals(40f, weight, "Each working set should be 40kg (80% of 50kg PR)")
        }
    }

    @Test
    fun resolveSetWeights_fewerPercentagesThanSets_fallsBackToBase() {
        // Edge case: setWeightsPercentOfPR has fewer entries than sets
        val exercise = createTestRoutineExercise(
            setReps = listOf(10, 10, 10),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
            setWeightsPercentOfPR = listOf(70), // Only 1 entry for 3 sets
            weightPerCableKg = 5.75f,
        )
        val weights = exercise.resolveSetWeights(100f)
        assertEquals(3, weights.size)
        assertEquals(70f, weights[0]) // Explicit 70%
        assertEquals(80f, weights[1]) // Falls back to base 80%
        assertEquals(80f, weights[2]) // Falls back to base 80%
    }

    @Test
    fun normalizedForExerciseType_bodyweightNeutralizesHiddenCableBehavior() {
        val exercise = createTestRoutineExercise(
            equipment = "",
            stallDetectionEnabled = true,
            repCountTiming = RepCountTiming.BOTTOM,
            stopAtTop = true,
        )

        val normalized = exercise.normalizedForExerciseType()

        assertEquals(false, normalized.stallDetectionEnabled)
        assertEquals(RepCountTiming.TOP, normalized.repCountTiming)
        assertEquals(false, normalized.stopAtTop)
    }

    @Test
    fun normalizedForExerciseType_cablePreservesNonDefaultBehavior() {
        val exercise = createTestRoutineExercise(
            equipment = "BAR",
            stallDetectionEnabled = true,
            repCountTiming = RepCountTiming.BOTTOM,
            stopAtTop = true,
        )

        val normalized = exercise.normalizedForExerciseType()

        assertEquals(true, normalized.stallDetectionEnabled)
        assertEquals(RepCountTiming.BOTTOM, normalized.repCountTiming)
        assertEquals(true, normalized.stopAtTop)
    }

    // ===== Helper =====

    private fun createTestRoutineExercise(
        id: String = "test",
        orderIndex: Int = 0,
        setReps: List<Int?> = listOf(10, 10, 10),
        setRestSeconds: List<Int> = emptyList(),
        supersetId: String? = null,
        orderInSuperset: Int = 0,
        usePercentOfPR: Boolean = false,
        weightPercentOfPR: Int = 80,
        setWeightsPercentOfPR: List<Int> = emptyList(),
        weightPerCableKg: Float = 20f,
        warmupSets: List<WarmupSet> = emptyList(),
        equipment: String = "BAR",
        stallDetectionEnabled: Boolean = true,
        repCountTiming: RepCountTiming = RepCountTiming.TOP,
        stopAtTop: Boolean = false,
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = Exercise(
            name = "Test Exercise",
            muscleGroup = "Chest",
            equipment = equipment,
            id = "ex1",
        ),
        orderIndex = orderIndex,
        setReps = setReps,
        weightPerCableKg = weightPerCableKg,
        setRestSeconds = setRestSeconds,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
        usePercentOfPR = usePercentOfPR,
        weightPercentOfPR = weightPercentOfPR,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
        warmupSets = warmupSets,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTiming = repCountTiming,
        stopAtTop = stopAtTop,
    )
}
