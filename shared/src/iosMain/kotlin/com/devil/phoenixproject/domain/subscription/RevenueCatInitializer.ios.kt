package com.devil.phoenixproject.domain.subscription

/**
 * iOS RevenueCat initializer stub.
 *
 * NOTE: RevenueCat integration is DISABLED until properly configured.
 * This is a no-op stub to allow the code to compile without the RevenueCat dependency.
 *
 * To re-enable RevenueCat:
 * 1. Uncomment revenuecat-purchases-core in shared/build.gradle.kts
 * 2. Add RevenueCat framework to iOS Xcode project (via SPM or CocoaPods)
 * 3. Configure valid API keys in PlatformConfig.ios.kt
 * 4. Uncomment RevenueCat initialization in VitruvianPhoenixApp.swift
 * 5. Restore the original implementation that uses Purchases.configure()
 */
actual object RevenueCatInitializer {
    actual fun initialize() {
        // No-op: RevenueCat is disabled
        // Original implementation would call:
        // Purchases.configure(PurchasesConfiguration(apiKey = PlatformConfig.revenueCatApiKey))
    }
}
