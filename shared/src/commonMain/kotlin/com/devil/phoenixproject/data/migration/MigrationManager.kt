package com.devil.phoenixproject.data.migration

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages data migrations on app startup.
 * Call [checkAndRunMigrations] after Koin is initialized.
 * Call [close] when done to prevent memory leaks.
 */
class MigrationManager {
    private val log = Logger.withTag("MigrationManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Check for and run any pending migrations.
     * This should be called once on app startup.
     */
    fun checkAndRunMigrations() {
        scope.launch {
            try {
                runMigrations()
            } catch (e: Exception) {
                log.e(e) { "Migration failed" }
            }
        }
    }

    private suspend fun runMigrations() {
        // No migrations currently.
    }

    /**
     * Cancels the coroutine scope to prevent memory leaks.
     * Should be called when the MigrationManager is no longer needed.
     */
    fun close() {
        scope.cancel()
        log.d { "MigrationManager scope cancelled" }
    }
}
