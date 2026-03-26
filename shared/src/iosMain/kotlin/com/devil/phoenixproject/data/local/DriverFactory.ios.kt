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
 * Custom exception thrown when a migration step fails with a non-recoverable error.
 * This propagates out of [SavepointMigratingSchema.migrate] to signal to
 * [NativeSqliteDriver] that migration did NOT complete successfully, preventing
 * the driver from advancing PRAGMA user_version to the target version.
 */
private class MigrationFailedException(
    val stoppedAtVersion: Long,
    val targetVersion: Long,
    cause: Exception
) : RuntimeException(
    "Migration stopped at version $stoppedAtVersion (target $targetVersion): ${cause.message}",
    cause
)

/**
 * iOS DriverFactory - delegates schema creation to SQLDelight and wraps migrations
 * with SAVEPOINT-based rollback protection and pre-flight safety checks.
 *
 * SQLDelight's [VitruvianDatabase.Schema] handles:
 * - Fresh installs: runs `create()` which executes the full schema from VitruvianDatabase.sq
 * - Upgrades: each migration step (.sqm) is wrapped in a SAVEPOINT so that a failure
 *   rolls back only the failed step
 * - Version tracking: reads/writes `PRAGMA user_version` automatically
 *
 * Recovery strategy (layered):
 * 1. Pre-flight: before each migration, ensure required tables/columns exist so the
 *    migration's DDL doesn't fail on missing dependencies (matches Android's approach).
 * 2. SAVEPOINT rollback: a failed migration rolls back to the pre-migration state.
 *    On failure, migrate() THROWS to prevent NativeSqliteDriver from advancing
 *    user_version to the target — this avoids silent schema corruption.
 * 3. Nuclear delete: the thrown exception triggers database deletion and recreation
 *    from scratch. Data can be recovered via Supabase sync.
 */
actual class DriverFactory {

    companion object {
        private const val DATABASE_NAME = "vitruvian.db"
    }

    /**
     * Creates the SQLite driver using SQLDelight's generated schema, wrapped with
     * SAVEPOINT-based migration protection and pre-flight safety checks.
     *
     * On a fresh install, SQLDelight runs VitruvianDatabase.Schema.create() which
     * executes the full CREATE TABLE/INDEX schema from VitruvianDatabase.sq.
     *
     * On upgrade, [SavepointMigratingSchema] applies each .sqm migration step inside
     * a SAVEPOINT transaction. Pre-flight checks run BEFORE each step to ensure
     * required tables and columns exist (matching Android's preflightMigration pattern).
     *
     * CRITICAL: If any migration fails, [SavepointMigratingSchema.migrate] throws
     * [MigrationFailedException]. This prevents NativeSqliteDriver from silently
     * advancing PRAGMA user_version to the target version, which would make the
     * database appear fully migrated when it isn't (causing column-not-found crashes
     * on every subsequent launch). The thrown exception triggers nuclear recovery.
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
        } catch (e: MigrationFailedException) {
            // A migration step failed and was rolled back. The SAVEPOINT set user_version
            // to the last successful step, but NativeSqliteDriver would overwrite it to
            // targetVersion if we returned normally. By throwing, we land here instead.
            NSLog("iOS DB: Migration failed at version ${e.stoppedAtVersion} (target ${e.targetVersion})")
            NSLog("iOS DB: Falling back to nuclear recovery (database delete + recreate)")
            NSLog("iOS DB: Data will be recovered via Supabase sync on next login")
            recoverByDeletingDatabase()
        } catch (e: Exception) {
            // Something more fundamental went wrong (corrupt DB, disk I/O, etc.)
            NSLog("iOS DB: Driver creation failed (${e::class.simpleName}: ${e.message?.take(200)})")
            NSLog("iOS DB: Falling back to nuclear recovery (database delete + recreate)")
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
 * Wraps a [SqlSchema] to apply each migration step inside a SAVEPOINT transaction,
 * with pre-flight safety checks matching Android's DriverFactory patterns.
 *
 * When NativeSqliteDriver calls [migrate], this wrapper:
 * 1. Loops from [oldVersion] to [newVersion], one step at a time
 * 2. Runs pre-flight checks (ensure required tables/columns exist) OUTSIDE the savepoint
 * 3. Creates a SAVEPOINT before each step
 * 4. Delegates to the real schema's migrate() for that single step
 * 5. On success: RELEASE the savepoint and update PRAGMA user_version
 * 6. On failure: ROLLBACK TO SAVEPOINT, then THROW [MigrationFailedException]
 *
 * CRITICAL: On failure, this wrapper THROWS rather than returning normally.
 * NativeSqliteDriver advances PRAGMA user_version to [newVersion] after migrate()
 * returns. If we returned normally after stopping early, the DB would be marked as
 * fully migrated when it isn't — causing column-not-found crashes on every subsequent
 * launch. Throwing prevents this silent corruption.
 *
 * The thrown [MigrationFailedException] is caught by [DriverFactory.createDriver],
 * which triggers nuclear recovery (delete DB + recreate from schema + Supabase sync).
 */
private class SavepointMigratingSchema(
    private val delegate: SqlSchema<QueryResult.Value<Unit>>
) : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long get() = delegate.version

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

            // Pre-flight: ensure tables/columns exist before this migration runs.
            // This runs OUTSIDE the savepoint so failures don't affect migration state.
            // Mirrors Android's preflightMigration() and ensureGamificationTablesExist().
            runPreFlight(driver, stepTo)

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
                    NSLog("iOS DB: ROLLBACK FAILED for version $stepTo: ${rollbackError.message?.take(200)}")
                }

                // Set user_version to the last successful step
                try {
                    driver.execute(null, "PRAGMA user_version = $stepFrom", 0)
                } catch (pragmaError: Exception) {
                    NSLog("iOS DB: Warning - could not set user_version to $stepFrom: ${pragmaError.message?.take(100)}")
                }

                // CRITICAL: Throw to prevent NativeSqliteDriver from overwriting
                // user_version to newVersion. Without this throw, the driver treats
                // a normal return as "all migrations succeeded" and advances the version,
                // causing silent schema corruption.
                throw MigrationFailedException(stepFrom, newVersion, e)
            }
        }

        NSLog("iOS DB: All migrations completed successfully (now at version $newVersion)")
        return QueryResult.Value(Unit)
    }

    // ==================== Pre-Flight Safety Checks ====================

    /**
     * Run pre-flight checks before a specific migration step.
     * This mirrors Android's [preflightMigration] and [ensureGamificationTablesExist].
     *
     * Pre-flight ensures required tables and columns exist BEFORE the migration's
     * .sqm SQL runs. This handles two iOS-specific problems:
     * 1. ALTER TABLE ADD COLUMN fails with "duplicate column" on replay (no IF NOT EXISTS)
     * 2. Gamification tables were never created via migration — only via Android's onOpen()
     *    bootstrap or fresh-install create(). iOS upgrade paths never hit either path.
     *
     * Errors in pre-flight are caught and logged, not propagated. The intent is to
     * ensure dependencies exist; if they already exist, that's fine.
     */
    private fun runPreFlight(driver: SqlDriver, targetStep: Long) {
        when (targetStep) {
            10L -> preFlightMigration10(driver)
            11L -> ensureGamificationTablesExist(driver)
            22L -> ensureGamificationTablesExist(driver)
        }
    }

    /**
     * Migration 10 STEP 0 adds columns to RoutineExercise that the INSERT...SELECT
     * in STEP 5 needs. On iOS, ALTER TABLE ADD COLUMN fails with "duplicate column"
     * if the column already exists (from migrations 4 or 7). We handle this here
     * by catching and ignoring duplicate column errors, matching Android's
     * preflightMigration(10) behavior.
     *
     * These columns are removed from 10.sqm STEP 0 to avoid the .sqm failing;
     * they are now ONLY added via this pre-flight.
     */
    private fun preFlightMigration10(driver: SqlDriver) {
        val columns = listOf(
            "ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT",
            "ALTER TABLE RoutineExercise ADD COLUMN orderInSuperset INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80",
            "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'",
            "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT",
        )
        for (sql in columns) {
            try {
                driver.execute(null, sql, 0)
                NSLog("iOS DB: Pre-flight column added: ${sql.substringAfter("ADD COLUMN ").take(30)}")
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("duplicate column")) {
                    // Expected on replay — column already exists from prior migration 4 or 7
                } else {
                    NSLog("iOS DB: Pre-flight ALTER warning: ${e.message?.take(80)}")
                }
            }
        }
    }

    /**
     * Gamification tables (EarnedBadge, StreakHistory, GamificationStats) were never
     * given a creation migration — they only exist via:
     * - Android: ensureGamificationTablesExist() in onOpen() callback
     * - Fresh installs: VitruvianDatabase.sq create()
     *
     * iOS users upgrading through migrations never hit either path, so these tables
     * don't exist when migration 11 or 22 tries to ALTER them.
     *
     * This pre-flight creates them with IF NOT EXISTS, matching the schema shape
     * that migrations 11 and 22 expect (the columns they ALTER TABLE ADD).
     *
     * Note: The column definitions here are the BASE shape before migration 11/22
     * adds sync/profile columns. They intentionally do NOT include updatedAt,
     * serverId, deletedAt, profile_id — those are added by the migrations.
     */
    private fun ensureGamificationTablesExist(driver: SqlDriver) {
        val tables = listOf(
            // Note: badgeId is intentionally NOT UNIQUE here. The composite uniqueness
            // constraint (badgeId, profile_id) is added by migration 22 via a named index.
            // Android's ensureGamificationTablesExist() has an inline UNIQUE on badgeId
            // which is a legacy bug — migration 24 rebuilds the table to remove it.
            """CREATE TABLE IF NOT EXISTS EarnedBadge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                badgeId TEXT NOT NULL,
                earnedAt INTEGER NOT NULL,
                celebratedAt INTEGER
            )""",
            """CREATE TABLE IF NOT EXISTS StreakHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                length INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS GamificationStats (
                id INTEGER PRIMARY KEY,
                totalWorkouts INTEGER NOT NULL DEFAULT 0,
                totalReps INTEGER NOT NULL DEFAULT 0,
                totalVolumeKg INTEGER NOT NULL DEFAULT 0,
                longestStreak INTEGER NOT NULL DEFAULT 0,
                currentStreak INTEGER NOT NULL DEFAULT 0,
                uniqueExercisesUsed INTEGER NOT NULL DEFAULT 0,
                prsAchieved INTEGER NOT NULL DEFAULT 0,
                lastWorkoutDate INTEGER,
                streakStartDate INTEGER,
                lastUpdated INTEGER NOT NULL
            )""",
        )
        for (sql in tables) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                NSLog("iOS DB: Pre-flight table creation warning: ${e.message?.take(80)}")
            }
        }
        NSLog("iOS DB: Pre-flight gamification tables verified")
    }
}
