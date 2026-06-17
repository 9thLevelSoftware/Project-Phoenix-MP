package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.usecase.BodyweightVolumeCalculator
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-phase weight display integration tests.
 *
 * Verifies consistency between:
 * - UnitConverter (Phase 38: weight increment system)
 * - BodyweightVolumeCalculator (Phase 41: bodyweight volume)
 * - WeightDisplayFormatter (Phase 37: cable-aware display)
 *
 * These tests catch regressions where a change in one weight subsystem
 * silently breaks display or calculation in another.
 */
class WeightDisplayIntegrationTest {

    private companion object {
        const val FLOAT_TOLERANCE = 0.05f
    }

    @Test
    fun formatterAndIncrementAlignmentDualCable() {
        val perCableKg = 55.5f
        val ordinaryFormatted = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = perCableKg,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("55.5", ordinaryFormatted, "Ordinary dual-cable display stays per-cable")

        val explicitTotalFormatted = WeightDisplayFormatter.formatTwoCableTotalWeight(
            weightPerCableKg = perCableKg,
            unit = WeightUnit.KG,
        )
        assertEquals("111", explicitTotalFormatted, "Explicit helper can show two-cable total")

        val fractionalPerCable = 27.5f
        assertEquals(
            "27.5",
            WeightDisplayFormatter.formatDisplayWeight(fractionalPerCable, cableCount = 2, unit = WeightUnit.KG),
        )
        assertEquals(
            "55",
            WeightDisplayFormatter.formatTwoCableTotalWeight(fractionalPerCable, WeightUnit.KG),
        )
    }

    @Test
    fun bodyweightVolumeAndUnitConversionNoDoubleConversion() {
        // Scenario: 80kg user doing push-ups
        // effectiveWeight returns kg (80 * 0.64 = 51.2kg)
        val bodyWeightKg = 80f
        val effectiveKg = BodyweightVolumeCalculator.effectiveWeight("push up", bodyWeightKg)
        assertTrue(effectiveKg > 0f, "Effective weight should be positive for known exercise")

        // Convert to lbs ONCE
        val effectiveLbs = UnitConverter.kgToLb(effectiveKg)

        // Convert back to kg to verify no double-conversion distortion
        val roundTripKg = UnitConverter.lbToKg(effectiveLbs)

        assertTrue(
            abs(roundTripKg - effectiveKg) < FLOAT_TOLERANCE,
            "Round-trip conversion should not distort weight: " +
                "original=${effectiveKg}kg, roundTrip=${roundTripKg}kg",
        )

        // Verify the effective weight is the expected percentage of body weight
        val expectedKg = bodyWeightKg * 0.64f // push-up = 64% body weight
        assertTrue(
            abs(effectiveKg - expectedKg) < FLOAT_TOLERANCE,
            "Push-up effective weight should be 64% of body weight: " +
                "expected=${expectedKg}kg, got=${effectiveKg}kg",
        )
    }

    @Test
    fun bulkAdjustWithFormatterConsistency() {
        val basePerCable = 50f
        val adjustedPerCable = basePerCable * 1.10f

        val ordinaryFormatted = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = adjustedPerCable,
            cableCount = 2,
            unit = WeightUnit.KG,
        )
        assertEquals("55", ordinaryFormatted, "Bulk adjust changes per-cable load")

        val explicitTotalFormatted = WeightDisplayFormatter.formatTwoCableTotalWeight(
            weightPerCableKg = adjustedPerCable,
            unit = WeightUnit.KG,
        )
        assertEquals("110", explicitTotalFormatted, "Explicit total helper doubles when requested")
    }

    @Test
    fun unitConsistencyLbSuffix() {
        // Verify multiple weights formatted with useLb=true all show "lbs" suffix
        val weights = listOf(10f, 50f, 100f, 0.5f, 220f)

        for (kg in weights) {
            val formatted = UnitConverter.formatWeight(kg, useLb = true)
            assertTrue(
                formatted.endsWith("lbs"),
                "Weight $kg formatted as lbs should end with 'lbs' suffix, got: '$formatted'",
            )
        }
    }
}
