package com.devil.phoenixproject.domain.subscription

import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.premium.SubscriptionTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Subscription manager that handles premium feature access.
 *
 * Determines tier from the UserProfile.subscription_status field which stores
 * tier strings ("free", "phoenix", "elite") mapped via [SubscriptionTier.fromDbString].
 *
 * Access levels:
 * - FREE: No premium features
 * - PHOENIX: Pro access (force curves, LED biofeedback, rep quality, etc.)
 * - ELITE: Pro + Elite access (smart suggestions, auto-regulation, etc.)
 *
 * NOTE: RevenueCat integration is DISABLED until properly configured.
 * This stub version uses local profile subscription status only.
 */
class SubscriptionManager(
    private val userProfileRepository: UserProfileRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isProSubscriber = MutableStateFlow(false)
    val hasProAccess: StateFlow<Boolean> = _isProSubscriber.asStateFlow()

    private val _isEliteSubscriber = MutableStateFlow(false)
    /**
     * True only for ELITE tier subscribers.
     * Used for gating Elite-only features like smart suggestions (GATE-03/SUGG-06).
     */
    val hasEliteAccess: StateFlow<Boolean> = _isEliteSubscriber.asStateFlow()

    // Feature access flows - all tied to Pro for now
    val canUseAIRoutines: StateFlow<Boolean> = hasProAccess
    val canAccessCommunityLibrary: StateFlow<Boolean> = hasProAccess
    val canSyncToCloud: StateFlow<Boolean> = hasProAccess
    val canUseHealthIntegrations: StateFlow<Boolean> = hasProAccess

    init {
        // Watch active profile tier from the DB subscription_status field.
        // The column stores tier strings ("free", "phoenix", "elite") mapped via SubscriptionTier.
        // Phoenix tier grants Pro access; Elite tier grants both Pro and Elite access.
        scope.launch {
            userProfileRepository.getActiveProfileTier().collect { tier ->
                _isProSubscriber.value = tier == SubscriptionTier.PHOENIX || tier == SubscriptionTier.ELITE
                _isEliteSubscriber.value = tier == SubscriptionTier.ELITE
            }
        }
    }

    /**
     * Stub: RevenueCat delegate setup is disabled.
     */
    fun setupDelegate() {
        // No-op: RevenueCat is disabled
    }

    /**
     * Stub: Returns failure since RevenueCat is disabled.
     */
    suspend fun refreshCustomerInfo(): Result<Unit> {
        return Result.failure(Exception("RevenueCat is not configured"))
    }

    /**
     * Stub: Returns failure since RevenueCat is disabled.
     */
    suspend fun restorePurchases(): Result<Unit> {
        return Result.failure(Exception("RevenueCat is not configured"))
    }

    /**
     * Stub: RevenueCat login is disabled.
     */
    fun loginToRevenueCat(userId: String) {
        // No-op: RevenueCat is disabled
    }

    /**
     * Stub: RevenueCat logout is disabled.
     */
    fun logoutFromRevenueCat() {
        // No-op: RevenueCat is disabled
    }
}
