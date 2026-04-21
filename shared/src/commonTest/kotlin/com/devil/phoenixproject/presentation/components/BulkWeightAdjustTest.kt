package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.util.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for applyBulkAdjust — the pure logic behind BulkWeightAdjustDialog.
 *
 * Covers:
 * - Percentage mode (positive, negative, zero)
 * - Absolute mode (positive, negative, zero)
 * - Clamping to [0, MAX_WEIGHT_KG]
 * - Rounding to 0.5kg machine increment
 * - PR-scaled exercise skipping
 * - Per-set weight adjustment
 * - ID preservation (all non-weight fields unchanged)
 * - Empty list edge case
 * - progressionKg NOT scaled (intentional)
 */
class BulkWeightAdjustTest {

    // ── Test helpers ────────────────────────────────────────────────

    private fun exercise(
        id: String = "ex-1",
        name: String = "Bench Press",
        weightKg: Float = 50f,
        setWeightsKg: List<Float> = emptyList(),
        usePercentOfPR: Boolean = false,
        progressionKg: Float = 0.5f,
    ) = RoutineExercise(
        id = id,
        exercise = Exercise(name = name, muscleGroup = "Chest"),
        orderIndex = 0,
        weightPerCableKg = weightKg,
        setWeightsPerCableKg = setWeightsKg,
        usePercentOfPR = usePercentOfPR,
        progressionKg = progressionKg,
        programMode = ProgramMode.OldSchool,
    )

    // ── Percentage mode ─────────────────────────────────────────────

    @Test
    fun percentage_positiveTenPercent_increasesWeight() {
        val exercises = listOf(exercise(weightKg = 50f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(10f))
        assertEquals(55f, result[0].weightPerCableKg)
    }

    @Test
    fun percentage_negativeFivePercent_decreasesWeight() {
        val exercises = listOf(exercise(weightKg = 40f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(-5f))
        // 40 * 0.95 = 38.0
        assertEquals(38f, result[0].weightPerCableKg)
    }

    @Test
    fun percentage_zeroPercent_noChange() {
        val exercises = listOf(exercise(weightKg = 50f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(0f))
        assertEquals(50f, result[0].weightPerCableKg)
    }

    @Test
    fun percentage_roundsToHalfKg() {
        // 33 * 1.10 = 36.3 -> rounds to 36.5
        val exercises = listOf(exercise(weightKg = 33f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(10f))
        assertEquals(36.5f, result[0].weightPerCableKg)
    }

    @Test
    fun percentage_roundsDownToHalfKg() {
        // 33 * 1.05 = 34.65 -> rounds to 34.5
        val exercises = listOf(exercise(weightKg = 33f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(5f))
        assertEquals(34.5f, result[0].weightPerCableKg)
    }

    // ── Absolute mode ───────────────────────────────────────────────

    @Test
    fun absolute_positiveDelta_increasesWeight() {
        val exercises = listOf(exercise(weightKg = 50f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))
        assertEquals(55f, result[0].weightPerCableKg)
    }

    @Test
    fun absolute_negativeDelta_decreasesWeight() {
        val exercises = listOf(exercise(weightKg = 50f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(-5f))
        assertEquals(45f, result[0].weightPerCableKg)
    }

    @Test
    fun absolute_zeroDelta_noChange() {
        val exercises = listOf(exercise(weightKg = 50f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(0f))
        assertEquals(50f, result[0].weightPerCableKg)
    }

    @Test
    fun absolute_roundsToMachineIncrement() {
        // 50 + 1.3 = 51.3 -> rounds to 51.5
        val exercises = listOf(exercise(weightKg = 50f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(1.3f))
        assertEquals(51.5f, result[0].weightPerCableKg)
    }

    // ── Clamping ────────────────────────────────────────────────────

    @Test
    fun clamps_toMaxWeight() {
        val exercises = listOf(exercise(weightKg = 98f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(10f))
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].weightPerCableKg)
    }

    @Test
    fun clamps_toMinWeight() {
        val exercises = listOf(exercise(weightKg = 3f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(-10f))
        assertEquals(Constants.MIN_WEIGHT_KG, result[0].weightPerCableKg)
    }

    @Test
    fun clamps_percentageExceedingMax() {
        val exercises = listOf(exercise(weightKg = 80f))
        // 80 * 1.50 = 120 -> clamped to 100
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(50f))
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].weightPerCableKg)
    }

    @Test
    fun clamps_largeNegativePercentage() {
        val exercises = listOf(exercise(weightKg = 50f))
        // 50 * (1 + -200/100) = 50 * -1 = -50 -> clamped to 0
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(-200f))
        assertEquals(Constants.MIN_WEIGHT_KG, result[0].weightPerCableKg)
    }

    // ── PR-scaled exercises ─────────────────────────────────────────

    @Test
    fun skips_prScaledExercises() {
        val prExercise = exercise(
            id = "pr-ex",
            weightKg = 40f,
            usePercentOfPR = true,
        )
        val normalExercise = exercise(id = "normal-ex", weightKg = 50f)
        val exercises = listOf(prExercise, normalExercise)

        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))

        // PR exercise unchanged
        assertEquals(40f, result[0].weightPerCableKg)
        assertTrue(result[0].usePercentOfPR)
        // Normal exercise adjusted
        assertEquals(55f, result[1].weightPerCableKg)
    }

    // ── Per-set weights ─────────────────────────────────────────────

    @Test
    fun adjusts_perSetWeights_percentage() {
        val exercises = listOf(
            exercise(
                weightKg = 50f,
                setWeightsKg = listOf(45f, 50f, 55f),
            ),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(10f))
        assertEquals(55f, result[0].weightPerCableKg)
        assertEquals(listOf(49.5f, 55f, 60.5f), result[0].setWeightsPerCableKg)
    }

    @Test
    fun adjusts_perSetWeights_absolute() {
        val exercises = listOf(
            exercise(
                weightKg = 50f,
                setWeightsKg = listOf(45f, 50f, 55f),
            ),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(2.5f))
        assertEquals(52.5f, result[0].weightPerCableKg)
        assertEquals(listOf(47.5f, 52.5f, 57.5f), result[0].setWeightsPerCableKg)
    }

    @Test
    fun perSetWeights_emptyList_staysEmpty() {
        val exercises = listOf(exercise(weightKg = 50f, setWeightsKg = emptyList()))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))
        assertEquals(emptyList(), result[0].setWeightsPerCableKg)
    }

    @Test
    fun perSetWeights_clampedIndividually() {
        val exercises = listOf(
            exercise(
                weightKg = 95f,
                setWeightsKg = listOf(90f, 95f, 99f),
            ),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(10f))
        // All clamped to 100
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].weightPerCableKg)
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].setWeightsPerCableKg[0])
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].setWeightsPerCableKg[1])
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].setWeightsPerCableKg[2])
    }

    // ── ID and field preservation ───────────────────────────────────

    @Test
    fun preserves_allNonWeightFields() {
        val original = exercise(
            id = "preserve-test",
            name = "Squat",
            weightKg = 50f,
            progressionKg = 1.5f,
        ).copy(
            orderIndex = 3,
            setReps = listOf(8, 8, 6),
            echoLevel = com.devil.phoenixproject.domain.model.EchoLevel.HARDER,
            stallDetectionEnabled = false,
            supersetId = "ss-1",
            orderInSuperset = 2,
        )
        val exercises = listOf(original)
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))

        assertEquals("preserve-test", result[0].id)
        assertEquals("Squat", result[0].exercise.name)
        assertEquals(3, result[0].orderIndex)
        assertEquals(listOf(8, 8, 6), result[0].setReps)
        assertEquals(false, result[0].stallDetectionEnabled)
        assertEquals("ss-1", result[0].supersetId)
        assertEquals(2, result[0].orderInSuperset)
    }

    @Test
    fun preserves_ids_acrossAllExercises() {
        val exercises = listOf(
            exercise(id = "ex-1", weightKg = 30f),
            exercise(id = "ex-2", weightKg = 40f),
            exercise(id = "ex-3", weightKg = 50f),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(10f))
        assertEquals(listOf("ex-1", "ex-2", "ex-3"), result.map { it.id })
    }

    @Test
    fun preserves_listSize() {
        val exercises = listOf(
            exercise(id = "a", weightKg = 10f),
            exercise(id = "b", weightKg = 20f),
            exercise(id = "c", weightKg = 30f),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))
        assertEquals(3, result.size)
    }

    // ── progressionKg NOT scaled (intentional) ──────────────────────

    @Test
    fun progressionKg_notScaled() {
        val exercises = listOf(exercise(weightKg = 50f, progressionKg = 1.5f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(20f))
        assertEquals(1.5f, result[0].progressionKg)
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    fun emptyList_returnsEmptyList() {
        val result = applyBulkAdjust(emptyList(), BulkAdjustMode.Absolute(5f))
        assertTrue(result.isEmpty())
    }

    @Test
    fun allPrScaled_returnsUnchangedList() {
        val exercises = listOf(
            exercise(id = "pr-1", weightKg = 30f, usePercentOfPR = true),
            exercise(id = "pr-2", weightKg = 40f, usePercentOfPR = true),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(10f))
        assertEquals(30f, result[0].weightPerCableKg)
        assertEquals(40f, result[1].weightPerCableKg)
    }

    @Test
    fun mixedExercises_onlyAdjustsNonPrScaled() {
        val exercises = listOf(
            exercise(id = "normal", weightKg = 50f),
            exercise(id = "pr", weightKg = 50f, usePercentOfPR = true),
            exercise(id = "normal2", weightKg = 60f),
        )
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))
        assertEquals(55f, result[0].weightPerCableKg)    // adjusted
        assertEquals(50f, result[1].weightPerCableKg)    // skipped
        assertEquals(65f, result[2].weightPerCableKg)    // adjusted
    }

    @Test
    fun weightAtZero_positiveAdjust_works() {
        val exercises = listOf(exercise(weightKg = 0f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Absolute(5f))
        assertEquals(5f, result[0].weightPerCableKg)
    }

    @Test
    fun weightAtZero_percentageIncrease_staysZero() {
        // 0 * 1.10 = 0 — percentage of zero is still zero
        val exercises = listOf(exercise(weightKg = 0f))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(10f))
        assertEquals(Constants.MIN_WEIGHT_KG, result[0].weightPerCableKg)
    }

    @Test
    fun weightAtMax_percentageIncrease_staysAtMax() {
        val exercises = listOf(exercise(weightKg = Constants.MAX_WEIGHT_KG))
        val result = applyBulkAdjust(exercises, BulkAdjustMode.Percentage(10f))
        assertEquals(Constants.MAX_WEIGHT_KG, result[0].weightPerCableKg)
    }
}
