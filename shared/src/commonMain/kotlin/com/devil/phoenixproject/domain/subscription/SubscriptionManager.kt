package com.devil.phoenixproject.domain.subscription

import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.UserProfileRepository
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
 * NOTE: RevenueCat integration is DISABLED until properly configured.
 * This stub version uses local profile subscription status only.
 *
 * To re-enable RevenueCat:
 * 1. Uncomment revenuecat-purchases-core in shared/build.gradle.kts
 * 2. Add RevenueCat framework to iOS Xcode project (via SPM or CocoaPods)
 * 3. Configure valid API keys in PlatformConfig (Android/iOS)
 * 4. Uncomment RevenueCat initialization in VitruvianApp.kt and VitruvianPhoenixApp.swift
 * 5. Restore the original SubscriptionManager implementation
 */
class SubscriptionManager(
    private val userProfileRepository: UserProfileRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isProSubscriber = MutableStateFlow(false)
    val hasProAccess: StateFlow<Boolean> = _isProSubscriber.asStateFlow()

    // Feature access flows - all tied to Pro for now
    val canUseAIRoutines: StateFlow<Boolean> = hasProAccess
    val canAccessCommunityLibrary: StateFlow<Boolean> = hasProAccess
    val canSyncToCloud: StateFlow<Boolean> = hasProAccess
    val canUseHealthIntegrations: StateFlow<Boolean> = hasProAccess

    init {
        // Watch local profile subscription status
        scope.launch {
            userProfileRepository.getActiveProfileSubscriptionStatus().collect { status ->
                _isProSubscriber.value = status == SubscriptionStatus.ACTIVE
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
