package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.alloc
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
import kotlinx.cinterop.ObjCObjectVar

/**
 * iOS DriverFactory - delegates all schema management to SQLDelight.
 *
 * SQLDelight's [VitruvianDatabase.Schema] handles:
 * - Fresh installs: runs `create()` which executes the full schema from VitruvianDatabase.sq
 * - Upgrades: runs `migrate()` which applies .sqm files for each version step
 * - Version tracking: reads/writes `PRAGMA user_version` automatically
 *
 * This replaces the previous 1100+ line manual schema approach that repeatedly drifted
 * from the canonical .sq schema, causing missing-column crashes and version-mismatch wipes.
 *
 * Recovery strategy: if migration fails (e.g., duplicate column from partial prior migration),
 * delete the database and let SQLDelight recreate it from scratch. Data loss is preferable
 * to an unbootable app -- the mobile app syncs to Supabase, so data can be recovered.
 */
actual class DriverFactory {

    companion object {
        private const val DATABASE_NAME = "vitruvian.db"
    }

    /**
     * Creates the SQLite driver using SQLDelight's generated schema.
     *
     * On a fresh install, SQLDelight runs VitruvianDatabase.Schema.create() which
     * executes the full CREATE TABLE/INDEX schema from VitruvianDatabase.sq.
     *
     * On upgrade, SQLDelight reads PRAGMA user_version, then applies .sqm migration
     * files sequentially until the schema version matches VitruvianDatabase.Schema.version.
     *
     * If anything fails, we delete the database and create a fresh one.
     */
    actual fun createDriver(): SqlDriver {
        NSLog("iOS DB: Initializing database (schema version ${VitruvianDatabase.Schema.version})")

        val driver = try {
            NativeSqliteDriver(
                schema = VitruvianDatabase.Schema,
                name = DATABASE_NAME,
                onConfiguration = { config ->
                    config.copy(
                        extendedConfig = DatabaseConfiguration.Extended(
                            foreignKeyConstraints = false  // Disable during create/migrate; enabled below
                        )
                    )
                }
            )
        } catch (e: Exception) {
            NSLog("iOS DB: Migration failed (${e::class.simpleName}: ${e.message?.take(200)}), recreating database")
            recoverByDeletingDatabase()
        }

        // Post-creation pragmas
        applyPragmas(driver)

        // Exclude database files from iCloud backup to prevent restoring stale schemas
        excludeDatabaseFromBackup()

        NSLog("iOS DB: Initialization complete")
        return driver
    }

    /**
     * Nuclear recovery: delete the database and all associated files, then create fresh.
     * This is the last resort when migration fails. Data can be recovered via Supabase sync.
     */
    private fun recoverByDeletingDatabase(): SqlDriver {
        NSLog("iOS DB: Deleting database for fresh creation")
        deleteAllDatabaseFiles()

        return try {
            NativeSqliteDriver(
                schema = VitruvianDatabase.Schema,
                name = DATABASE_NAME,
                onConfiguration = { config ->
                    config.copy(
                        extendedConfig = DatabaseConfiguration.Extended(
                            foreignKeyConstraints = false
                        )
                    )
                }
            )
        } catch (e: Exception) {
            // If fresh creation also fails, something is fundamentally wrong
            NSLog("iOS DB: FATAL - Fresh database creation failed: ${e.message?.take(300)}")
            throw e
        }
    }

    /**
     * Enable WAL mode and foreign key constraints for normal operation.
     * These are set after schema creation/migration to avoid FK interference during DDL.
     */
    private fun applyPragmas(driver: SqlDriver) {
        try {
            driver.execute(null, "PRAGMA journal_mode = WAL", 0)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        } catch (e: Exception) {
            NSLog("iOS DB: Warning - pragma setup failed: ${e.message}")
        }
    }

    // ==================== iCloud Backup Exclusion ====================

    /**
     * Exclude the database from iCloud backup using NSURLIsExcludedFromBackupKey.
     * Prevents iCloud from backing up and later restoring a database with a stale schema
     * version, which previously caused version-mismatch purge loops on reinstall.
     */
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
                    val success = url.setResourceValue(
                        NSNumber(bool = true),
                        forKey = NSURLIsExcludedFromBackupKey,
                        error = errorPtr.ptr
                    )
                    if (!success) {
                        val error = errorPtr.value
                        NSLog("iOS DB: Warning - could not exclude $path from backup: ${error?.localizedDescription}")
                    }
                }
            } catch (e: Exception) {
                NSLog("iOS DB: Warning - could not exclude $path from backup: ${e.message}")
            }
        }
    }

    // ==================== File Operations ====================

    /**
     * Delete all database files (main, WAL, SHM).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun deleteAllDatabaseFiles() {
        val dbPath = getDatabasePath()
        val fileManager = NSFileManager.defaultManager

        for (path in listOf(dbPath, "$dbPath-wal", "$dbPath-shm")) {
            if (fileManager.fileExistsAtPath(path)) {
                try {
                    fileManager.removeItemAtPath(path, null)
                    NSLog("iOS DB: Deleted $path")
                } catch (e: Exception) {
                    NSLog("iOS DB: Failed to delete $path: ${e.message}")
                }
            }
        }
    }

    /**
     * Get the filesystem path where SQLite stores the database.
     * Uses NSLibraryDirectory which is the standard location for app databases on iOS.
     */
    private fun getDatabasePath(): String {
        val fileManager = NSFileManager.defaultManager
        val urls = fileManager.URLsForDirectory(NSLibraryDirectory, NSUserDomainMask)
        @Suppress("UNCHECKED_CAST")
        val libraryUrl = (urls as List<NSURL>).firstOrNull()
        val libraryPath = libraryUrl?.path ?: ""
        return "$libraryPath/$DATABASE_NAME"
    }
}
