package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary tests for BiomechanicsVelocityZone.fromMcv(), the shared classification that
 * BOTH mobile and portal display code must agree on (audit 02 + CLAUDE.md zone boundaries).
 *
 *   EXPLOSIVE: MCV ≥ 1.0 m/s  (mmPerSec ≥ 1000)
 *   FAST:      MCV ≥ 0.75 m/s (mmPerSec ≥ 750)
 *   MODERATE:  MCV ≥ 0.5 m/s  (mmPerSec ≥ 500)
 *   SLOW:      MCV ≥ 0.25 m/s (mmPerSec ≥ 250)
 *   GRIND:     MCV < 0.25 m/s (mmPerSec < 250)
 *
 * The production enum accepts mm/s (not m/s) so all test inputs are mm/s values.
 */
class VelocityZoneBoundaryTest {

    // ==================== Exact Boundary Values ====================

    @Test
    fun exactlyOneMeterPerSecondIsExplosive() {
        // 1.0 m/s = 1000 mm/s → >= 1000 → EXPLOSIVE
        assertEquals(
            BiomechanicsVelocityZone.EXPLOSIVE,
            BiomechanicsVelocityZone.fromMcv(1000f),
            "1.0 m/s boundary (inclusive) is EXPLOSIVE",
        )
    }

    @Test
    fun exactlyZeroPointSevenFiveIsFast() {
        assertEquals(
            BiomechanicsVelocityZone.FAST,
            BiomechanicsVelocityZone.fromMcv(750f),
            "0.75 m/s boundary (inclusive) is FAST",
        )
    }

    @Test
    fun exactlyZeroPointFiveIsModerate() {
        assertEquals(
            BiomechanicsVelocityZone.MODERATE,
            BiomechanicsVelocityZone.fromMcv(500f),
            "0.5 m/s boundary (inclusive) is MODERATE",
        )
    }

    @Test
    fun exactlyZeroPointTwoFiveIsSlow() {
        assertEquals(
            BiomechanicsVelocityZone.SLOW,
            BiomechanicsVelocityZone.fromMcv(250f),
            "0.25 m/s boundary (inclusive) is SLOW",
        )
    }

    @Test
    fun zeroIsGrind() {
        assertEquals(
            BiomechanicsVelocityZone.GRIND,
            BiomechanicsVelocityZone.fromMcv(0f),
        )
    }

    // ==================== Just-Below Boundaries ====================

    @Test
    fun justBelowOneMeterFallsToFast() {
        // 999 mm/s = 0.999 m/s → below 1.0 threshold → FAST
        assertEquals(
            BiomechanicsVelocityZone.FAST,
            BiomechanicsVelocityZone.fromMcv(999.9f),
            "Values just below 1.0 m/s must classify as FAST",
        )
    }

    @Test
    fun justBelowZeroPointSevenFiveFallsToModerate() {
        assertEquals(
            BiomechanicsVelocityZone.MODERATE,
            BiomechanicsVelocityZone.fromMcv(749.9f),
            "Values just below 0.75 m/s must classify as MODERATE",
        )
    }

    @Test
    fun justBelowZeroPointFiveFallsToSlow() {
        assertEquals(
            BiomechanicsVelocityZone.SLOW,
            BiomechanicsVelocityZone.fromMcv(499.9f),
            "Values just below 0.5 m/s must classify as SLOW",
        )
    }

    @Test
    fun justBelowZeroPointTwoFiveFallsToGrind() {
        assertEquals(
            BiomechanicsVelocityZone.GRIND,
            BiomechanicsVelocityZone.fromMcv(249.9f),
            "Values just below 0.25 m/s must classify as GRIND",
        )
    }

    // ==================== Mid-Range Values ====================

    @Test
    fun clearlyExplosiveValues() {
        assertEquals(BiomechanicsVelocityZone.EXPLOSIVE, BiomechanicsVelocityZone.fromMcv(1200f))
        assertEquals(BiomechanicsVelocityZone.EXPLOSIVE, BiomechanicsVelocityZone.fromMcv(1500f))
        assertEquals(BiomechanicsVelocityZone.EXPLOSIVE, BiomechanicsVelocityZone.fromMcv(3000f))
    }

    @Test
    fun clearlyFastValues() {
        assertEquals(BiomechanicsVelocityZone.FAST, BiomechanicsVelocityZone.fromMcv(800f))
        assertEquals(BiomechanicsVelocityZone.FAST, BiomechanicsVelocityZone.fromMcv(900f))
    }

    @Test
    fun clearlyModerateValues() {
        assertEquals(BiomechanicsVelocityZone.MODERATE, BiomechanicsVelocityZone.fromMcv(550f))
        assertEquals(BiomechanicsVelocityZone.MODERATE, BiomechanicsVelocityZone.fromMcv(650f))
    }

    @Test
    fun clearlySlowValues() {
        assertEquals(BiomechanicsVelocityZone.SLOW, BiomechanicsVelocityZone.fromMcv(300f))
        assertEquals(BiomechanicsVelocityZone.SLOW, BiomechanicsVelocityZone.fromMcv(400f))
    }

    @Test
    fun clearlyGrindValues() {
        assertEquals(BiomechanicsVelocityZone.GRIND, BiomechanicsVelocityZone.fromMcv(100f))
        assertEquals(BiomechanicsVelocityZone.GRIND, BiomechanicsVelocityZone.fromMcv(50f))
    }

    // ==================== Invariants ====================

    @Test
    fun classificationIsMonotonicallyNonIncreasingAsVelocityDecreases() {
        // Sanity check: walking down from very fast to very slow, the zone index must
        // never get "faster" (higher ordinal is slower).
        val stepsMmPerSec = listOf(2000f, 1000f, 999f, 750f, 749f, 500f, 499f, 250f, 249f, 0f)
        val zones = stepsMmPerSec.map { BiomechanicsVelocityZone.fromMcv(it) }

        val ordinals = zones.map { it.ordinal }
        for (i in 1 until ordinals.size) {
            assertEquals(
                true,
                ordinals[i] >= ordinals[i - 1],
                "As velocity decreases (${stepsMmPerSec[i - 1]} → ${stepsMmPerSec[i]}), " +
                    "zone must stay the same or move toward GRIND (${ordinals[i - 1]} → ${ordinals[i]})",
            )
        }
    }

    @Test
    fun negativeVelocityIsGrind() {
        // Defensive: negative velocities should fall through to GRIND (the final else branch).
        assertEquals(
            BiomechanicsVelocityZone.GRIND,
            BiomechanicsVelocityZone.fromMcv(-10f),
            "Negative velocity falls through to GRIND",
        )
    }
}
