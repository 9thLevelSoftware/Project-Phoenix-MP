package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.currentTimeMillis

/**
 * Domain utility for tier-based feature gating.
 *
 * Determines whether a premium feature is accessible for a given subscription tier.
 * This is a pure domain utility - it does NOT depend on UI, repositories, or DI.
 *
 * Data capture is ALWAYS enabled regardless of tier (see Spec 00 Section 2.3).
 * Only UI display, sync, and advanced computation are gated.
 */
object FeatureGate {

    /**
     * All premium features available in the app.
     *
     * Phoenix tier: Force curves, per-rep metrics, VBT, portal sync, LED biofeedback, rep quality, CV form check, RPG attributes, ghost racing
     * Elite tier: All Phoenix features + asymmetry, auto-regulation, smart suggestions, replay, assessment, advanced analytics, readiness briefing
     */
    enum class Feature {
        // Phoenix tier features
        FORCE_CURVES,
        PER_REP_METRICS,
        VBT_METRICS,
        PORTAL_SYNC,
        LED_BIOFEEDBACK,
        REP_QUALITY_SCORE,
        // New Phoenix tier features (v0.5.1)
        CV_FORM_CHECK,
        RPG_ATTRIBUTES,
        GHOST_RACING,

        // Elite tier features
        ASYMMETRY_ANALYSIS,
        AUTO_REGULATION,
        SMART_SUGGESTIONS,
        WORKOUT_REPLAY,
        STRENGTH_ASSESSMENT,
        PORTAL_ADVANCED_ANALYTICS,
        // New Elite tier feature (v0.5.1)
        READINESS_BRIEFING
    }

    private val phoenixFeatures = setOf(
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

    private val eliteFeatures = phoenixFeatures + setOf(
        Feature.ASYMMETRY_ANALYSIS,
        Feature.AUTO_REGULATION,
        Feature.SMART_SUGGESTIONS,
        Feature.WORKOUT_REPLAY,
        Feature.STRENGTH_ASSESSMENT,
        Feature.PORTAL_ADVANCED_ANALYTICS,
        Feature.READINESS_BRIEFING
    )

    /**
     * Check if a feature is enabled for the given subscription tier.
     *
     * @param feature The premium feature to check
     * @param tier The user's current subscription tier
     * @return true if the feature is accessible at this tier
     */
    fun isEnabled(feature: Feature, tier: SubscriptionTier): Boolean {
        return when (tier) {
            SubscriptionTier.FREE -> false  // No premium features
            SubscriptionTier.PHOENIX -> feature in phoenixFeatures
            SubscriptionTier.ELITE -> feature in eliteFeatures
        }
    }

    /**
     * Resolve the effective subscription tier considering offline grace period.
     *
     * If the subscription has expired but is within the 30-day offline grace period,
     * the original tier is preserved. After 30 days, the user degrades to FREE.
     *
     * @param subscriptionTier The stored subscription tier
     * @param expiresAt Subscription expiry timestamp in ms since epoch, or null if no expiry
     * @param nowMs Current time in ms since epoch (defaults to system time)
     * @return The effective tier after applying grace period logic
     */
    fun resolveEffectiveTier(
        subscriptionTier: SubscriptionTier,
        expiresAt: Long?,
        nowMs: Long = currentTimeMillis()
    ): SubscriptionTier {
        if (subscriptionTier == SubscriptionTier.FREE) return SubscriptionTier.FREE
        if (expiresAt == null) return SubscriptionTier.FREE

        val gracePeriodMs = 30L * 24 * 60 * 60 * 1000  // 30 days

        return when {
            nowMs < expiresAt -> subscriptionTier                    // Still valid
            nowMs < expiresAt + gracePeriodMs -> subscriptionTier    // Grace period
            else -> SubscriptionTier.FREE                            // Expired
        }
    }
}
