package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [highestKnownTier] — the pure precedence helper that
 * [PortalApiClient.getActiveSubscriptionTier] uses to collapse the subscriptions
 * REST response into a single tier string.
 *
 * Precedence contract (per Phoenix Portal subscription matrix):
 *   INFERNO > FLAME > EMBER
 *
 * Unknown tier strings must be ignored so the addition of a future tier
 * ("CINDER", "BLAZE", ...) cannot accidentally grant Inferno-only features
 * (e.g., 50 Hz telemetry sync) to a user who does not actually own Inferno.
 */
class SubscriptionTierPrecedenceTest {

    private fun sub(tier: String, status: String = "active") = SubscriptionCheckDto(
        tier = tier,
        status = status,
    )

    @Test
    fun returnsNullForEmptyList() {
        assertNull(
            highestKnownTier(emptyList()),
            "No active subscription rows → no tier",
        )
    }

    @Test
    fun returnsEmberWhenOnlyEmber() {
        assertEquals("EMBER", highestKnownTier(listOf(sub("EMBER"))))
    }

    @Test
    fun returnsFlameWhenOnlyFlame() {
        assertEquals("FLAME", highestKnownTier(listOf(sub("FLAME"))))
    }

    @Test
    fun returnsInfernoWhenOnlyInferno() {
        assertEquals("INFERNO", highestKnownTier(listOf(sub("INFERNO"))))
    }

    @Test
    fun prefersInfernoOverFlame() {
        assertEquals(
            "INFERNO",
            highestKnownTier(listOf(sub("FLAME"), sub("INFERNO"))),
            "Inferno must win when both are active (upgrade window, stacked comp)",
        )
    }

    @Test
    fun prefersFlameOverEmber() {
        assertEquals(
            "FLAME",
            highestKnownTier(listOf(sub("EMBER"), sub("FLAME"))),
        )
    }

    @Test
    fun prefersInfernoOverAll() {
        assertEquals(
            "INFERNO",
            highestKnownTier(listOf(sub("EMBER"), sub("FLAME"), sub("INFERNO"))),
        )
    }

    @Test
    fun orderingIsIndependentOfInputOrder() {
        assertEquals(
            "INFERNO",
            highestKnownTier(listOf(sub("INFERNO"), sub("EMBER"), sub("FLAME"))),
            "Precedence must hold regardless of response ordering",
        )
    }

    @Test
    fun ignoresUnknownTierStrings() {
        assertEquals(
            "EMBER",
            highestKnownTier(listOf(sub("CINDER"), sub("EMBER"))),
            "Unknown tier is dropped; fall back to the highest known tier in the list",
        )
    }

    @Test
    fun returnsNullWhenAllTiersAreUnknown() {
        assertNull(
            highestKnownTier(listOf(sub("CINDER"), sub("BLAZE"))),
            "A future unknown tier must NOT be granted entitlement; treat as no tier",
        )
    }

    @Test
    fun isCaseSensitive() {
        // Supabase stores tiers in uppercase per PortalApiClient.checkPremiumStatus().
        // If the server ever returns mixed case, we want the mismatch to surface as
        // "no active tier" rather than silently granting entitlement on a typo.
        assertNull(
            highestKnownTier(listOf(sub("inferno"))),
            "Lowercase 'inferno' must NOT match — fail closed on tier casing mismatch",
        )
    }
}
