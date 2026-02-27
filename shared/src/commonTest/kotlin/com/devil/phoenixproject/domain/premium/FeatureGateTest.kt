package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.premium.FeatureGate.Feature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for FeatureGate tier-based access gating.
 *
 * Verifies:
 * - FREE tier gets no premium features
 * - PHOENIX tier gets phoenix features but not elite-only features
 * - ELITE tier gets all features
 * - resolveEffectiveTier grace period logic
 */
class FeatureGateTest {

    // ========== isEnabled - FREE tier ==========

    @Test
    fun `FREE tier has no premium features enabled`() {
        Feature.entries.forEach { feature ->
            assertFalse(
                FeatureGate.isEnabled(feature, SubscriptionTier.FREE),
                "Feature $feature should NOT be enabled for FREE tier"
            )
        }
    }

    // ========== isEnabled - PHOENIX tier ==========

    @Test
    fun `PHOENIX tier has phoenix features enabled`() {
        val phoenixFeatures = listOf(
            Feature.FORCE_CURVES,
            Feature.PER_REP_METRICS,
            Feature.VBT_METRICS,
            Feature.PORTAL_SYNC,
            Feature.LED_BIOFEEDBACK,
            Feature.REP_QUALITY_SCORE,
            Feature.CV_FORM_CHECK,
            Feature.RPG_ATTRIBUTES,
            Feature.GHOST_RACING
        )

        phoenixFeatures.forEach { feature ->
            assertTrue(
                FeatureGate.isEnabled(feature, SubscriptionTier.PHOENIX),
                "Feature $feature should be enabled for PHOENIX tier"
            )
        }
    }

    @Test
    fun `PHOENIX tier does not have elite-only features`() {
        val eliteOnlyFeatures = listOf(
            Feature.ASYMMETRY_ANALYSIS,
            Feature.AUTO_REGULATION,
            Feature.SMART_SUGGESTIONS,
            Feature.WORKOUT_REPLAY,
            Feature.STRENGTH_ASSESSMENT,
            Feature.PORTAL_ADVANCED_ANALYTICS,
            Feature.READINESS_BRIEFING
        )

        eliteOnlyFeatures.forEach { feature ->
            assertFalse(
                FeatureGate.isEnabled(feature, SubscriptionTier.PHOENIX),
                "Feature $feature should NOT be enabled for PHOENIX tier"
            )
        }
    }

    // ========== isEnabled - ELITE tier ==========

    @Test
    fun `ELITE tier has all features enabled`() {
        Feature.entries.forEach { feature ->
            assertTrue(
                FeatureGate.isEnabled(feature, SubscriptionTier.ELITE),
                "Feature $feature should be enabled for ELITE tier"
            )
        }
    }

    // ========== isEnabled - v0.5.1 features ==========

    @Test
    fun `PHOENIX tier has v051 phoenix features`() {
        assertTrue(
            FeatureGate.isEnabled(Feature.CV_FORM_CHECK, SubscriptionTier.PHOENIX),
            "CV_FORM_CHECK should be enabled for PHOENIX tier"
        )
        assertTrue(
            FeatureGate.isEnabled(Feature.RPG_ATTRIBUTES, SubscriptionTier.PHOENIX),
            "RPG_ATTRIBUTES should be enabled for PHOENIX tier"
        )
        assertTrue(
            FeatureGate.isEnabled(Feature.GHOST_RACING, SubscriptionTier.PHOENIX),
            "GHOST_RACING should be enabled for PHOENIX tier"
        )
    }

    @Test
    fun `READINESS_BRIEFING is elite-only`() {
        assertFalse(
            FeatureGate.isEnabled(Feature.READINESS_BRIEFING, SubscriptionTier.PHOENIX),
            "READINESS_BRIEFING should NOT be enabled for PHOENIX tier"
        )
        assertTrue(
            FeatureGate.isEnabled(Feature.READINESS_BRIEFING, SubscriptionTier.ELITE),
            "READINESS_BRIEFING should be enabled for ELITE tier"
        )
    }

    // ========== resolveEffectiveTier ==========

    @Test
    fun `resolveEffectiveTier returns tier when subscription is valid`() {
        val now = 1_000_000_000L
        val expiresAt = now + 86_400_000L // 1 day in the future

        assertEquals(
            SubscriptionTier.PHOENIX,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.PHOENIX, expiresAt, now)
        )
        assertEquals(
            SubscriptionTier.ELITE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.ELITE, expiresAt, now)
        )
    }

    @Test
    fun `resolveEffectiveTier returns tier within 30-day grace period`() {
        val now = 1_000_000_000L
        val expiresAt = now - 86_400_000L // Expired 1 day ago (within 30-day grace)

        assertEquals(
            SubscriptionTier.PHOENIX,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.PHOENIX, expiresAt, now)
        )
        assertEquals(
            SubscriptionTier.ELITE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.ELITE, expiresAt, now)
        )
    }

    @Test
    fun `resolveEffectiveTier returns FREE when expired beyond grace period`() {
        val now = 1_000_000_000L
        val gracePeriodMs = 30L * 24 * 60 * 60 * 1000 // 30 days
        val expiresAt = now - gracePeriodMs - 1L // Expired just past 30-day grace

        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.PHOENIX, expiresAt, now)
        )
        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.ELITE, expiresAt, now)
        )
    }

    @Test
    fun `resolveEffectiveTier returns FREE for FREE tier regardless of expiry`() {
        val now = 1_000_000_000L
        val validExpiry = now + 86_400_000L

        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.FREE, validExpiry, now)
        )
        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.FREE, null, now)
        )
    }

    @Test
    fun `resolveEffectiveTier returns FREE when expiresAt is null for non-FREE tiers`() {
        val now = 1_000_000_000L

        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.PHOENIX, null, now)
        )
        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.ELITE, null, now)
        )
    }

    @Test
    fun `resolveEffectiveTier returns tier at exact expiry boundary`() {
        val now = 1_000_000_000L
        // At exact expiry moment, now == expiresAt, which means now < expiresAt is false
        // but now < expiresAt + grace is true -> grace period applies
        val expiresAt = now

        assertEquals(
            SubscriptionTier.PHOENIX,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.PHOENIX, expiresAt, now)
        )
    }

    @Test
    fun `resolveEffectiveTier returns FREE at exact grace period boundary`() {
        val now = 1_000_000_000L
        val gracePeriodMs = 30L * 24 * 60 * 60 * 1000
        // At exact grace boundary: now == expiresAt + grace -> grace condition fails
        val expiresAt = now - gracePeriodMs

        assertEquals(
            SubscriptionTier.FREE,
            FeatureGate.resolveEffectiveTier(SubscriptionTier.PHOENIX, expiresAt, now)
        )
    }
}
