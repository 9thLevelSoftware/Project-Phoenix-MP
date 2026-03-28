package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

actual class DriverFactory {

    companion object {
        private const val DATABASE_NAME = "vitruvian.db"
    }

    actual fun createDriver(): SqlDriver {
        val targetVersion = VitruvianDatabase.Schema.version
        NSLog("iOS DB: Initializing database (schema version $targetVersion)")

        val resilientSchema = ResilientMigratingSchema(VitruvianDatabase.Schema)

        val driver = NativeSqliteDriver(
            schema = resilientSchema,
            name = DATABASE_NAME,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = DatabaseConfiguration.Extended(
                        foreignKeyConstraints = false,
                    ),
                )
            },
        )

        // Authoritative reconciliation -- ensures ALL tables, columns, indexes exist
        val report = reconcileFullSchema(driver)
        val summary = report.logSummary()
        NSLog("iOS DB: $summary")
        if (report.hasFailures) {
            for (failure in report.failures) {
                NSLog("iOS DB: RECONCILIATION FAILURE: ${failure.target} -- ${failure.detail?.take(120)}")
            }
        }

        // Post-creation pragmas
        try {
            driver.execute(null, "PRAGMA journal_mode = WAL", 0)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        } catch (e: Exception) {
            NSLog("iOS DB: Warning -- pragma setup failed: ${e.message}")
        }

        // Exclude database files from iCloud backup
        excludeDatabaseFromBackup()

        NSLog("iOS DB: Initialization complete")
        return driver
    }

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    private fun excludeDatabaseFromBackup() {
        val dbPath = getDatabasePath()
        val filesToExclude = listOf(dbPath, "$dbPath-wal", "$dbPath-shm")
        val fileManager = NSFileManager.defaultManager

        for (path in filesToExclude) {
            if (!fileManager.fileExistsAtPath(path)) continue
            try {
                val url = NSURL.fileURLWithPath(path)
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    url.setResourceValue(
                        NSNumber(bool = true),
                        forKey = NSURLIsExcludedFromBackupKey,
                        error = errorPtr.ptr,
                    )
                }
            } catch (e: Exception) {
                NSLog("iOS DB: Warning -- could not exclude $path from backup: ${e.message}")
            }
        }
    }

    private fun getDatabasePath(): String {
        val fileManager = NSFileManager.defaultManager
        val urls = fileManager.URLsForDirectory(NSLibraryDirectory, NSUserDomainMask)
        @Suppress("UNCHECKED_CAST")
        val libraryUrl = (urls as List<NSURL>).firstOrNull()
        return "${libraryUrl?.path ?: ""}/$DATABASE_NAME"
    }
}

/**
 * Wraps SQLDelight's schema to apply each migration step with per-statement
 * resilient error recovery. Replaces the old SavepointMigratingSchema which
 * used nuclear recovery (database deletion) on any failure.
 *
 * On failure: catches the error, applies the step's SQL one statement at a
 * time (skipping duplicates), and continues to the next step. The post-migration
 * reconcileFullSchema() catches any remaining gaps.
 */
private class ResilientMigratingSchema(
    private val delegate: SqlSchema<QueryResult.Value<Unit>>,
) : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long get() = delegate.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = delegate.create(driver)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        if (oldVersion >= newVersion) return QueryResult.Value(Unit)

        NSLog("iOS DB: Migrating from version $oldVersion to $newVersion")

        for (version in oldVersion until newVersion) {
            val stepTo = version + 1
            try {
                delegate.migrate(driver, version, stepTo, *callbacks)
                driver.execute(null, "PRAGMA user_version = $stepTo", 0)
                NSLog("iOS DB: Migration to version $stepTo succeeded")
            } catch (e: Exception) {
                NSLog("iOS DB: Migration $stepTo failed (${e.message?.take(120)}), applying resilient fallback")
                val results = applyMigrationResilient(driver, stepTo.toInt())
                val failures = results.count { !it.success && !it.recoverable }
                if (failures > 0) {
                    NSLog("iOS DB: Migration $stepTo had $failures non-recoverable failures")
                }
                driver.execute(null, "PRAGMA user_version = $stepTo", 0)
                NSLog("iOS DB: Migration $stepTo completed via resilient fallback")
            }
        }

        NSLog("iOS DB: All migrations completed (now at version $newVersion)")
        return QueryResult.Value(Unit)
    }
}
