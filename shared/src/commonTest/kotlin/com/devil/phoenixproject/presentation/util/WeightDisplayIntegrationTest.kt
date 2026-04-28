package com.devil.phoenixproject.presentation.util

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
        // Scenario: 55.5kg per-cable weight on a dual-cable exercise
        // Total weight = 55.5 * 2 = 111.0kg (uses 0.5kg increments to avoid Float truncation)
        val perCableKg = 55.5f
        val cableCount = 2
        val totalKg = perCableKg * cableCount

        // UnitConverter.formatWeight operates on total weight (not per-cable)
        val formatted = UnitConverter.formatWeight(totalKg, useLb = false)

        // 55.5 * 2 = 111.0 (whole number) — verify it shows the total,
        // not the per-cable value, and includes the unit suffix
        assertEquals("111 kg", formatted, "Total weight for dual cable should be 111 kg")

        // Also verify a fractional case with machine-safe 0.5kg granularity
        val fractionalPerCable = 27.5f
        val fractionalTotal = fractionalPerCable * cableCount // 55.0
        val fractionalFormatted = UnitConverter.formatWeight(fractionalTotal, useLb = false)
        assertEquals("55 kg", fractionalFormatted, "27.5kg per cable x 2 = 55kg total")
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
        // Scenario: 50kg per-cable + 10% bulk adjust = 55kg per-cable
        // Total = 55 * 2 = 110kg
        val basePerCable = 50f
        val adjustedPerCable = basePerCable * 1.10f // +10%
        val totalKg = adjustedPerCable * 2

        val formatted = UnitConverter.formatWeight(totalKg, useLb = false)
        assertEquals("110 kg", formatted, "50kg + 10% = 55kg per cable, total 110kg")
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
