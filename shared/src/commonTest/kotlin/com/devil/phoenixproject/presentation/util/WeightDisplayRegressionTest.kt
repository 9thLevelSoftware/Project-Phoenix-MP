package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive regression test suite for weight display across all surfaces.
 *
 * Tests cover the full matrix of:
 * - Cable configurations: single (1), dual (2), null (legacy)
 * - Weight units: KG, LB
 * - Boundary values: zero, minimum, maximum
 * - Edge cases: fractional weights, large values, cableCount=0
 *
 * These tests ensure that WeightDisplayFormatter produces consistent results
 * that match what every display surface in the app should show.
 */
class WeightDisplayRegressionTest {

    private companion object {
        const val KG_TO_LB = 2.20462f
        const val FLOAT_TOLERANCE = 0.01f

        // Hardware constants from Constants.kt
        const val MAX_WEIGHT_KG = 220f // Trainer+ max per cable
        const val MIN_WEIGHT_KG = 0.5f // Minimum weight increment
    }

    // ===== 1. Dual-cable session: KG =====

    @Test
    fun dualCable_kg_displaysDoubledWeight() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(160f, result, "80kg/cable x 2 cables = 160kg displayed")
    }

    @Test
    fun dualCable_kg_formatsCorrectly() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("160", result, "160kg should format as '160' (no decimals)")
    }

    // ===== 2. Dual-cable session: LB =====

    @Test
    fun dualCable_lb_displaysDoubledAndConverted() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        val expected = 160f * KG_TO_LB // 352.7392
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "80kg/cable x 2 cables = 160kg -> ${expected}lb, got $result",
        )
    }

    @Test
    fun dualCable_lb_formatsCorrectly() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        // 160kg * 2.20462 = 352.7392 -> "352.7"
        assertTrue(
            result.startsWith("352"),
            "160kg -> ~352.7lb, got '$result'",
        )
    }

    // ===== 3. Single-cable session: KG =====

    @Test
    fun singleCable_kg_displaysUnchanged() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 1,
            unit = WeightUnit.KG,
        )
        assertEquals(80f, result, "80kg/cable x 1 cable = 80kg displayed")
    }

    // ===== 4. Single-cable session: LB =====

    @Test
    fun singleCable_lb_convertsOnly() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 1,
            unit = WeightUnit.LB,
        )
        val expected = 80f * KG_TO_LB
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "80kg x 1 cable -> ${expected}lb, got $result",
        )
    }

    // ===== 5. Null cableCount (legacy data): defaults to 1 =====

    @Test
    fun nullCableCount_kg_defaultsToSingle() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = null,
            unit = WeightUnit.KG,
        )
        assertEquals(80f, result, "Null cableCount defaults to 1: 80kg unchanged")
    }

    @Test
    fun nullCableCount_lb_defaultsToSingleThenConverts() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = null,
            unit = WeightUnit.LB,
        )
        val expected = 80f * KG_TO_LB
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "Null cableCount + LB: 80kg -> ${expected}lb, got $result",
        )
    }

    // ===== 6. Zero weight boundary =====

    @Test
    fun zeroWeight_dualCable_kg_returnsZero() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 0f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(0f, result, "0kg x 2 = 0kg")
    }

    @Test
    fun zeroWeight_dualCable_lb_returnsZero() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 0f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        assertEquals(0f, result, "0kg x 2 = 0lb")
    }

    @Test
    fun zeroWeight_formatsAsZero() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 0f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("0", result, "0kg should display as '0'")
    }

    // ===== 7. Maximum weight boundary (220kg per cable on Trainer+) =====

    @Test
    fun maxWeight_dualCable_kg() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MAX_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(440f, result, "220kg/cable x 2 = 440kg max total")
    }

    @Test
    fun maxWeight_dualCable_lb() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MAX_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        val expected = 440f * KG_TO_LB // 970.0328
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "440kg -> ${expected}lb, got $result",
        )
    }

    @Test
    fun maxWeight_singleCable_kg() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MAX_WEIGHT_KG,
            cableCount = 1,
            unit = WeightUnit.KG,
        )
        assertEquals(220f, result, "220kg/cable x 1 = 220kg")
    }

    @Test
    fun maxWeight_formatsAsInteger() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = MAX_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("440", result, "440kg should format as '440'")
    }

    // ===== 8. PR weight display (PR lacks cableCount, passes null) =====

    @Test
    fun prWeight_nullCableCount_kg_showsPerCable() {
        // PersonalRecord does NOT have cableCount field.
        // All PR displays pass cableCount=null, which defaults to 1.
        // This means PRs show per-cable weight, which is the current behavior.
        val prWeightPerCableKg = 100f
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = prWeightPerCableKg,
            cableCount = null,
            unit = WeightUnit.KG,
        )
        assertEquals(100f, result, "PR with null cableCount shows per-cable weight: 100kg")
    }

    @Test
    fun prWeight_nullCableCount_lb_convertsFromPerCable() {
        val prWeightPerCableKg = 100f
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = prWeightPerCableKg,
            cableCount = null,
            unit = WeightUnit.LB,
        )
        val expected = 100f * KG_TO_LB
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "PR weight 100kg -> ${expected}lb, got $result",
        )
    }

    // ===== 9. Fractional weight (0.5kg increments) =====

    @Test
    fun fractionalWeight_dualCable_kg() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MIN_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(1f, result, "0.5kg/cable x 2 = 1.0kg")
    }

    @Test
    fun fractionalWeight_formatsCleanly() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = MIN_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("1", result, "1.0kg should format as '1' (no unnecessary decimals)")
    }

    @Test
    fun oddFractionalWeight_showsOneDecimal() {
        // 37.5kg per cable x 2 = 75.0kg -> "75"
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 37.5f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("75", result, "37.5 x 2 = 75.0 -> '75'")
    }

    @Test
    fun fractionalResult_showsOneDecimal() {
        // 37.25kg per cable x 2 = 74.5kg -> "74.5"
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 37.25f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("74.5", result, "37.25 x 2 = 74.5 -> '74.5'")
    }

    // ===== 10. Cross-surface consistency =====

    @Test
    fun sameInput_producesIdenticalOutput_acrossMultipleCalls() {
        // Ensures WeightDisplayFormatter is a pure function with no hidden state.
        // All display surfaces calling with the same inputs must get the same result.
        val input = Triple(80f, 2, WeightUnit.KG)
        val results = (1..5).map {
            WeightDisplayFormatter.toDisplayWeight(input.first, input.second, input.third)
        }
        assertTrue(
            results.all { it == results.first() },
            "Pure function: 5 calls with same input must produce identical output. Got: $results",
        )
    }

    @Test
    fun formatAndToDisplay_areConsistent() {
        // formatDisplayWeight should format the exact value from toDisplayWeight
        val weightPerCable = 80f
        val cableCount = 2
        val unit = WeightUnit.KG

        val numericResult = WeightDisplayFormatter.toDisplayWeight(weightPerCable, cableCount, unit)
        val stringResult = WeightDisplayFormatter.formatDisplayWeight(weightPerCable, cableCount, unit)

        // String representation should match numeric value
        assertEquals(
            numericResult.toInt().toString(),
            stringResult,
            "formatDisplayWeight('$stringResult') must match toDisplayWeight($numericResult)",
        )
    }

    // ===== 11. Edge case: cableCount = 0 =====

    @Test
    fun cableCountZero_resultsInZeroWeight() {
        // If cableCount is somehow 0 (data corruption), result should be 0.
        // This is defensive; normal values are 1 or 2.
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 0,
            unit = WeightUnit.KG,
        )
        assertEquals(0f, result, "80kg x 0 cables = 0kg (defensive for corrupt data)")
    }

    // ===== 12. Unit conversion accuracy =====

    @Test
    fun unitConversion_kg_isIdentity() {
        // KG display should be exactly the cable-multiplied value, no floating-point drift
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 100f,
            cableCount = 1,
            unit = WeightUnit.KG,
        )
        assertEquals(100f, result, "KG conversion should be identity (no floating-point drift)")
    }

    @Test
    fun unitConversion_lb_matchesStandardFactor() {
        // LB conversion must use the standard 2.20462 factor
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 1f,
            cableCount = 1,
            unit = WeightUnit.LB,
        )
        assertTrue(
            abs(result - KG_TO_LB) < FLOAT_TOLERANCE,
            "1kg -> ${KG_TO_LB}lb, got $result",
        )
    }

    // ===== 13. No double-multiplication: toDisplayWeight applied once =====

    @Test
    fun applyingFormatterTwice_producesWrongResult() {
        // Demonstrates that calling toDisplayWeight on an already-converted value
        // produces an incorrect result. This is why the formatter must only be
        // applied once at the display layer.
        val firstPass = WeightDisplayFormatter.toDisplayWeight(50f, 2, WeightUnit.KG) // 100
        val secondPass = WeightDisplayFormatter.toDisplayWeight(firstPass, 2, WeightUnit.KG) // 200 (WRONG)

        assertEquals(100f, firstPass, "First pass: 50 x 2 = 100")
        assertEquals(200f, secondPass, "Second pass: 100 x 2 = 200 (WRONG - double multiplication)")
        assertTrue(
            firstPass != secondPass,
            "Double-applying the formatter produces a different (wrong) result. " +
                "This is why each display surface must only call the formatter ONCE.",
        )
    }

    // ===== 14. Formatting: integer vs decimal display =====

    @Test
    fun format_integerResult_noTrailingZero() {
        val result = WeightDisplayFormatter.formatDisplayWeight(50f, 2, WeightUnit.KG)
        assertEquals("100", result, "100.0 should format as '100', not '100.0'")
    }

    @Test
    fun format_oneDecimalResult_showsDecimal() {
        // 50.25 x 2 = 100.5
        val result = WeightDisplayFormatter.formatDisplayWeight(50.25f, 2, WeightUnit.KG)
        assertEquals("100.5", result, "100.5 should format as '100.5'")
    }

    @Test
    fun format_lbConversion_showsOneDecimal() {
        // 100kg * 2.20462 = 220.462 -> "220.5" (rounded to 1 decimal)
        val result = WeightDisplayFormatter.formatDisplayWeight(100f, 1, WeightUnit.LB)
        assertTrue(
            result.startsWith("220"),
            "100kg -> ~220lb, got '$result'",
        )
        // Verify it has at most 1 decimal place
        val decimalPart = result.substringAfter(".", "")
        assertTrue(
            decimalPart.length <= 1,
            "Should have at most 1 decimal place, got '$result'",
        )
    }
}
