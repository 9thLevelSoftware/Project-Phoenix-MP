package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for WeightDisplayFormatter.
 *
 * Verifies cable-aware weight display logic:
 * - Dual cable exercises multiply per-cable weight by 2
 * - Single cable exercises pass through per-cable weight
 * - Null cableCount defaults to 1 (safe legacy default)
 * - Unit conversion (KG/LB) applied AFTER cable multiplication
 * - Formatting: integers show no decimals, fractional values show 1 decimal
 */
class WeightDisplayFormatterTest {

    private companion object {
        const val KG_TO_LB = 2.20462f
        const val FLOAT_TOLERANCE = 0.01f
    }

    // ===== toDisplayWeight: Dual cable, KG =====

    @Test
    fun toDisplayWeight_dualCable_kg_multipliesByTwo() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(100f, result, "50kg per cable x 2 cables = 100kg total")
    }

    // ===== toDisplayWeight: Dual cable, LB =====

    @Test
    fun toDisplayWeight_dualCable_lb_multipliesByTwoThenConverts() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        val expected = 100f * KG_TO_LB // 220.462
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "50kg per cable x 2 cables = 100kg → ${expected}lb, got $result",
        )
    }

    // ===== toDisplayWeight: Single cable, KG =====

    @Test
    fun toDisplayWeight_singleCable_kg_noMultiplication() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 1,
            unit = WeightUnit.KG,
        )
        assertEquals(50f, result, "50kg per cable x 1 cable = 50kg total")
    }

    // ===== toDisplayWeight: Single cable, LB =====

    @Test
    fun toDisplayWeight_singleCable_lb_convertsOnly() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 1,
            unit = WeightUnit.LB,
        )
        val expected = 50f * KG_TO_LB // 110.231
        assertTrue(
            abs(result - expected) < FLOAT_TOLERANCE,
            "50kg per cable x 1 cable = 50kg → ${expected}lb, got $result",
        )
    }

    // ===== toDisplayWeight: Null cable count (default single) =====

    @Test
    fun toDisplayWeight_nullCableCount_defaultsToSingle() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = null,
            unit = WeightUnit.KG,
        )
        assertEquals(50f, result, "Null cableCount defaults to 1: 50kg per cable = 50kg total")
    }

    // ===== toDisplayWeight: Zero weight =====

    @Test
    fun toDisplayWeight_zeroWeight_returnsZero_kg() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 0f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(0f, result, "0kg per cable x 2 = 0kg total")
    }

    @Test
    fun toDisplayWeight_zeroWeight_returnsZero_lb() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 0f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        assertEquals(0f, result, "0kg per cable in LB = 0lb")
    }

    // ===== toDisplayWeight: Max weight (220kg per cable for Trainer+) =====

    @Test
    fun toDisplayWeight_maxWeight_dualCable_kg() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 220f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(440f, result, "220kg per cable x 2 = 440kg total")
    }

    // ===== formatDisplayWeight: Integer values =====

    @Test
    fun formatDisplayWeight_integerResult_noDecimals() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("100", result, "100.0kg should display as '100'")
    }

    // ===== formatDisplayWeight: Decimal values =====

    @Test
    fun formatDisplayWeight_decimalResult_oneDecimal() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50.25f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("100.5", result, "50.25 x 2 = 100.5kg should display as '100.5'")
    }

    // ===== formatDisplayWeight: LB formatting =====

    @Test
    fun formatDisplayWeight_lb_showsConvertedValue() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )
        // 50 * 2 = 100kg * 2.20462 = 220.462 → rounded to 1 decimal = "220.5"
        assertEquals("220.5", result, "100kg → 220.462lb → formatted '220.5', got '$result'")
    }

    // ===== Pure function: per-cable value preserved =====

    @Test
    fun toDisplayWeight_doesNotMutateInput() {
        val perCable = 50f
        val cableCount = 2
        val unit = WeightUnit.KG

        // Call multiple times with same input
        val result1 = WeightDisplayFormatter.toDisplayWeight(perCable, cableCount, unit)
        val result2 = WeightDisplayFormatter.toDisplayWeight(perCable, cableCount, unit)

        assertEquals(result1, result2, "Pure function: same input produces same output")
        assertEquals(100f, result1, "Value should be 100")
    }

    // ===== Edge: Small fractional weight =====

    @Test
    fun toDisplayWeight_smallFractionalWeight_kg() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 0.5f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals(1f, result, "0.5kg per cable x 2 = 1kg")
    }

    @Test
    fun formatDisplayWeight_zeroWeight_showsZero() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 0f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("0", result, "0kg should display as '0'")
    }
}
