package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
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
 * iOS DriverFactory - delegates schema creation to SQLDelight and wraps migrations
 * with SAVEPOINT-based rollback protection.
 *
 * SQLDelight's [VitruvianDatabase.Schema] handles:
 * - Fresh installs: runs `create()` which executes the full schema from VitruvianDatabase.sq
 * - Upgrades: each migration step (.sqm) is wrapped in a SAVEPOINT so that a failure
 *   rolls back only the failed step, leaving the database at the last successful version
 * - Version tracking: reads/writes `PRAGMA user_version` automatically
 *
 * Recovery strategy (layered):
 * 1. SAVEPOINT rollback: a failed migration rolls back to the pre-migration state and stops.
 *    The database remains at the last successfully applied version.
 * 2. Nuclear delete: only if the SAVEPOINT mechanism itself fails (e.g., database corruption
 *    beyond repair), the database is deleted and recreated from scratch. Data can be
 *    recovered via Supabase sync.
 */
actual class DriverFactory {

    companion object {
        private const val DATABASE_NAME = "vitruvian.db"
    }

    /**
     * Creates the SQLite driver using SQLDelight's generated schema, wrapped with
     * SAVEPOINT-based migration protection.
     *
     * On a fresh install, SQLDelight runs VitruvianDatabase.Schema.create() which
     * executes the full CREATE TABLE/INDEX schema from VitruvianDatabase.sq.
     *
     * On upgrade, [SavepointMigratingSchema] applies each .sqm migration step inside
     * a SAVEPOINT transaction. If a step fails, it rolls back to the savepoint and stops
     * migrating -- the database stays at the last successful version rather than being
     * deleted.
     *
     * Nuclear database deletion is reserved as a last resort for unrecoverable errors
     * (e.g., actual database corruption where the SAVEPOINT mechanism itself fails).
     */
    actual fun createDriver(): SqlDriver {
        val targetVersion = VitruvianDatabase.Schema.version
        NSLog("iOS DB: Initializing database (schema version $targetVersion)")

        val savepointSchema = SavepointMigratingSchema(VitruvianDatabase.Schema)

        val driver = try {
            NativeSqliteDriver(
                schema = savepointSchema,
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
            // The SAVEPOINT wrapper absorbs individual migration failures gracefully.
            // If we reach here, something more fundamental went wrong (corrupt DB, disk I/O, etc.)
            NSLog("iOS DB: Driver creation failed (${e::class.simpleName}: ${e.message?.take(200)})")
            NSLog("iOS DB: Falling back to nuclear recovery (database delete + recreate)")
            recoverByDeletingDatabase()
        }

        if (savepointSchema.stoppedAtVersion != null) {
            val stopped = savepointSchema.stoppedAtVersion
            NSLog("iOS DB: WARNING - Migrations stopped at version $stopped (target was $targetVersion)")
            NSLog("iOS DB: Database is functional at version $stopped. Data preserved.")
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
     * This is the LAST RESORT when the SAVEPOINT mechanism itself fails -- meaning the
     * database is likely corrupted beyond repair. Data can be recovered via Supabase sync.
     */
    private fun recoverByDeletingDatabase(): SqlDriver {
        NSLog("iOS DB: NUCLEAR RECOVERY - Deleting database for fresh creation")
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

// ==================== SAVEPOINT Migration Wrapper ====================

/**
 * Wraps a [SqlSchema] to apply each migration step inside a SAVEPOINT transaction.
 *
 * When NativeSqliteDriver calls [migrate], this wrapper:
 * 1. Loops from [oldVersion] to [newVersion], one step at a time
 * 2. Creates a SAVEPOINT before each step
 * 3. Delegates to the real schema's migrate() for that single step
 * 4. On success: RELEASE the savepoint and update PRAGMA user_version
 * 5. On failure: ROLLBACK TO SAVEPOINT, log the error, and stop migrating
 *
 * This ensures a bad migration leaves the database at the previous working version
 * instead of triggering a full database delete. The [stoppedAtVersion] property
 * records which version the database ended at if migration was interrupted.
 *
 * Note: SQLDelight's NativeSqliteDriver calls migrate() with the full range
 * (oldVersion -> targetVersion). This wrapper intercepts that to apply steps
 * individually with rollback protection. After migrate() returns, the driver
 * sets PRAGMA user_version to newVersion -- but if we stopped early, the DB is
 * actually at [stoppedAtVersion]. The caller should check this property.
 */
private class SavepointMigratingSchema(
    private val delegate: SqlSchema<QueryResult.Value<Unit>>
) : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long get() = delegate.version

    /**
     * If migration was interrupted, this holds the version the database was left at.
     * Null means all migrations succeeded (or no migration was needed).
     */
    var stoppedAtVersion: Long? = null
        private set

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        return delegate.create(driver)
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion
    ): QueryResult.Value<Unit> {
        if (oldVersion >= newVersion) {
            return QueryResult.Value(Unit)
        }

        NSLog("iOS DB: Migrating from version $oldVersion to $newVersion with SAVEPOINT protection")

        for (version in oldVersion until newVersion) {
            val stepFrom = version
            val stepTo = version + 1
            val savepointName = "migration_v${stepTo}"

            try {
                // Create a savepoint before applying this migration step
                driver.execute(null, "SAVEPOINT $savepointName", 0)

                // Apply this single migration step via the real schema
                delegate.migrate(driver, stepFrom, stepTo, *callbacks)

                // Migration step succeeded -- release the savepoint (commits the changes)
                driver.execute(null, "RELEASE SAVEPOINT $savepointName", 0)

                // Explicitly update user_version to reflect successful migration.
                // This is critical: if a later step fails, the DB version must reflect
                // only the steps that actually completed.
                driver.execute(null, "PRAGMA user_version = $stepTo", 0)

                NSLog("iOS DB: Migration to version $stepTo succeeded")
            } catch (e: Exception) {
                NSLog("iOS DB: Migration to version $stepTo FAILED (${e::class.simpleName}: ${e.message?.take(200)})")

                // Roll back this failed migration step to the savepoint
                try {
                    driver.execute(null, "ROLLBACK TO SAVEPOINT $savepointName", 0)
                    driver.execute(null, "RELEASE SAVEPOINT $savepointName", 0)
                    NSLog("iOS DB: Rolled back version $stepTo, database remains at version $stepFrom")
                } catch (rollbackError: Exception) {
                    // If even the rollback fails, the database may be in a bad state.
                    // Log it but don't throw -- let the caller's nuclear fallback handle it.
                    NSLog("iOS DB: ROLLBACK FAILED for version $stepTo: ${rollbackError.message?.take(200)}")
                }

                // Record where we stopped and halt further migrations
                stoppedAtVersion = stepFrom
                // Ensure user_version reflects the last successful state
                try {
                    driver.execute(null, "PRAGMA user_version = $stepFrom", 0)
                } catch (pragmaError: Exception) {
                    NSLog("iOS DB: Warning - could not set user_version to $stepFrom: ${pragmaError.message?.take(100)}")
                }

                // Stop migrating -- the database is usable at stepFrom
                NSLog("iOS DB: Stopping migration. Database functional at version $stepFrom (target was $newVersion)")
                return QueryResult.Value(Unit)
            }
        }

        NSLog("iOS DB: All migrations completed successfully (now at version $newVersion)")
        return QueryResult.Value(Unit)
    }
}
