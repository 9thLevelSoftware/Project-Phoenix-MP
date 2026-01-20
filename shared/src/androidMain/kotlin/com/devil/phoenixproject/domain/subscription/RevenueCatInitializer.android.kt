package com.devil.phoenixproject.domain.subscription

import android.app.Application

/**
 * Android RevenueCat initializer stub.
 *
 * NOTE: RevenueCat integration is DISABLED until properly configured.
 * This is a no-op stub to allow the code to compile without the RevenueCat dependency.
 *
 * To re-enable RevenueCat:
 * 1. Uncomment revenuecat-purchases-core in shared/build.gradle.kts
 * 2. Configure valid API keys in PlatformConfig.android.kt
 * 3. Uncomment RevenueCat initialization in VitruvianApp.kt
 * 4. Restore the original implementation that uses Purchases.configure()
 */
actual object RevenueCatInitializer {
    private var application: Application? = null

    fun setApplication(app: Application) {
        application = app
    }

    actual fun initialize() {
        // No-op: RevenueCat is disabled
        // Original implementation would call:
        // val app = application ?: throw IllegalStateException("Application not set")
        // Purchases.configure(PurchasesConfiguration(apiKey = PlatformConfig.revenueCatApiKey))
    }
}
