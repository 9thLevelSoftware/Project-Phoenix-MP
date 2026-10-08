package com.devil.phoenixproject.domain.model

/**
 * Minimum portal subscription tier allowed to generate AI routines (issue #1223).
 *
 * Mirrors the portal's `requireSubscription(..., "FLAME")` gate. Signoff can raise
 * or lower this in one place on each side; never gate this feature on `isPremium`
 * (that would include Ember).
 */
const val AI_ROUTINE_MINIMUM_TIER = "FLAME"

/**
 * Portal subscription tier ladder, low to high: FREE < EMBER < FLAME < INFERNO.
 * Same ladder as the portal `TIER_LEVEL` order and [com.devil.phoenixproject.data.sync]
 * `TIER_PRECEDENCE` (plus the free rung, which is not an entitlement).
 */
private val AI_ROUTINE_TIER_LADDER = listOf("FREE", "EMBER", "FLAME", "INFERNO")

/**
 * True when [tier] meets [AI_ROUTINE_MINIMUM_TIER] on the portal ladder.
 *
 * The tier is read as a string ([com.devil.phoenixproject.data.sync.PortalTokenStorage]
 * `getSubscriptionTier()`). Null — signed out, or never refreshed — is below Flame.
 * Unknown tier strings are below Flame too: fail closed, the server re-checks anyway.
 */
fun meetsAiRoutineTier(tier: String?): Boolean {
    val normalized = tier?.trim()?.uppercase() ?: return false
    val rank = AI_ROUTINE_TIER_LADDER.indexOf(normalized)
    val minimumRank = AI_ROUTINE_TIER_LADDER.indexOf(AI_ROUTINE_MINIMUM_TIER)
    return rank >= 0 && rank >= minimumRank
}
