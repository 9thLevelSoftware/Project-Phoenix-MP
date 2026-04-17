package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.migration.MigrationManager
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(appModule, platformModule)
}

/**
 * Shared Koin bootstrap used by the iOS `doInitKoin` entrypoint (and tests).
 *
 * Swift calls `KoinInitIosKt.doInitKoin()` (declared in `shared/iosMain/.../KoinInitIos.kt`,
 * `@Throws(Throwable::class)` so failures bridge to `NSError`). That function thinly delegates
 * to this one. The Kotlin/Native export class name follows the source file name, so note the
 * `Ios` suffix — the iOS symbol is NOT `KoinInitKt.doInitKoin()`.
 */
internal fun doInitKoinInternal() {
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
