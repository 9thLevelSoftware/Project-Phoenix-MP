package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.migration.MigrationManager
import co.touchlab.kermit.Logger
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(appModule, platformModule)
}

/**
 * Helper function for iOS that doesn't require lambda parameter.
 * Call this from Swift: KoinKt.doInitKoin()
 */
fun doInitKoin() {
    Logger.i { "iOS: ========== KOIN INITIALIZATION START ==========" }
    try {
        Logger.i { "iOS: Calling initKoin()..." }
        initKoin {}
        Logger.i { "iOS: initKoin() completed successfully" }
        Logger.i { "iOS: ========== KOIN INITIALIZATION SUCCESS ==========" }
    } catch (e: Exception) {
        Logger.e(e) { "iOS: ========== KOIN INITIALIZATION FAILED ==========" }
        Logger.e { "iOS: Exception: ${e::class.simpleName}" }
        Logger.e { "iOS: Message: ${e.message}" }
        throw e
    }
}

/**
 * Helper function for iOS to run migrations after Koin initialization.
 * Call this from Swift: KoinKt.runMigrations()
 * This mirrors Android's VitruvianApp.onCreate() migration call.
 */
fun runMigrations() {
    Logger.i { "iOS: Running migrations..." }
    try {
        val koin = KoinPlatform.getKoin()
        val migrationManager = koin.get<MigrationManager>()
        migrationManager.checkAndRunMigrations()
        Logger.i { "iOS: Migrations completed" }
    } catch (e: Exception) {
        // Log error but don't crash - migrations are best effort
        Logger.e(e) { "Failed to run migrations on iOS" }
    }
}
