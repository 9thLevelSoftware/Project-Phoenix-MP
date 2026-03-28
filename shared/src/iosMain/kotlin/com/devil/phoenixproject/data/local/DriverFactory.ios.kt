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

/**
 * Custom exception thrown when a migration step fails with a non-recoverable error.
 * This propagates out of [SavepointMigratingSchema.migrate] to signal to
 * [NativeSqliteDriver] that migration did NOT complete successfully, preventing
 * the driver from advancing PRAGMA user_version to the target version.
 */
private class MigrationFailedException(val stoppedAtVersion: Long, val targetVersion: Long, cause: Exception) :
    RuntimeException(
        "Migration stopped at version $stoppedAtVersion (target $targetVersion): ${cause.message}",
        cause,
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
                            foreignKeyConstraints = false, // Disable during create/migrate; enabled below
                        ),
                    )
                },
            )
        } catch (e: MigrationFailedException) {
            // A migration step failed and was rolled back. The SAVEPOINT set user_version
            // to the last successful step, but NativeSqliteDriver would overwrite it to
            // targetVersion if we returned normally. By throwing, we land here instead.
            NSLog("iOS DB: Migration failed at version ${e.stoppedAtVersion} (target ${e.targetVersion})")
            NSLog("iOS DB: Falling back to nuclear recovery (backup + delete + recreate)")
            recoverByDeletingDatabase()
        } catch (e: Exception) {
            // Something more fundamental went wrong (corrupt DB, disk I/O, etc.)
            NSLog("iOS DB: Driver creation failed (${e::class.simpleName}: ${e.message?.take(200)})")
            NSLog("iOS DB: Falling back to nuclear recovery (backup + delete + recreate)")
            recoverByDeletingDatabase()
        }

        val readyDriver = reconcileKnownLegacySchemaDrift(driver)

        // Post-creation pragmas
        applyPragmas(readyDriver)

        // Verify schema integrity. If NativeSqliteDriver advanced user_version despite
        // a migration failure (crash-loop scenario), the schema is incomplete. Detect
        // this and force nuclear recovery so the app doesn't crash on every launch.
        if (!verifySchemaIntegrity(readyDriver)) {
            NSLog("iOS DB: Schema verification FAILED — forcing nuclear recovery")
            return recoverByDeletingDatabase()
        }

        // Exclude database files from iCloud backup to prevent restoring stale schemas
        excludeDatabaseFromBackup()

        NSLog("iOS DB: Initialization complete")
        return readyDriver
    }

    /**
     * Nuclear recovery: back up the database, then delete and recreate fresh.
     * This is the LAST RESORT when the SAVEPOINT mechanism itself fails -- meaning the
     * database is likely corrupted beyond repair.
     *
     * The backup is preserved at `vitruvian.db.backup` in the same directory so data
     * can be recovered manually or restored once cloud sync is available.
     */
    private fun recoverByDeletingDatabase(): SqlDriver {
        NSLog("iOS DB: NUCLEAR RECOVERY - Backing up database before fresh creation")
        backupDatabaseFiles()
        deleteAllDatabaseFiles()

        return try {
            NativeSqliteDriver(
                schema = VitruvianDatabase.Schema,
                name = DATABASE_NAME,
                onConfiguration = { config ->
                    config.copy(
                        extendedConfig = DatabaseConfiguration.Extended(
                            foreignKeyConstraints = false,
                        ),
                    )
                },
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

    /**
     * Reconcile all confirmed released schema drift before repository code touches the DB.
     *
     * This intentionally heals outside numbered migrations so both cohorts converge:
     * installs where a column is already present before its migration runs, and later-version
     * installs that never replay that migration but are still missing the column.
     */
    private fun reconcileKnownLegacySchemaDrift(driver: SqlDriver): SqlDriver {
        for (result in reconcileKnownLegacySchema(driver)) {
            when (result.status) {
                SchemaHealStatus.ADDED -> {
                    NSLog("iOS DB: Legacy repair added ${result.operation.target}")
                }

                SchemaHealStatus.ALREADY_PRESENT -> {
                    NSLog("iOS DB: Legacy repair confirmed ${result.operation.target}")
                }

                SchemaHealStatus.TABLE_MISSING -> {
                    NSLog("iOS DB: Legacy repair deferred for ${result.operation.target} (table missing)")
                }

                SchemaHealStatus.FAILED -> {
                    NSLog(
                        "iOS DB: Legacy repair warning for ${result.operation.target}: " +
                            (result.detail?.take(120) ?: "unknown"),
                    )
                }
            }
        }
        return driver
    }

    // ==================== Schema Integrity Verification ====================

    /**
     * Verify that critical columns exist in the database schema.
     *
     * This catches the crash-loop scenario where NativeSqliteDriver advances
     * PRAGMA user_version to the target despite a migration failure. The DB appears
     * "fully migrated" but is missing columns, causing every subsequent launch to
     * crash on the first query that references a missing column.
     *
     * Returns true if the schema looks healthy, false if nuclear recovery is needed.
     */
    private fun verifySchemaIntegrity(driver: SqlDriver): Boolean {
        val checks = listOf(
            "WorkoutSession" to "profile_id",
            "WorkoutSession" to "safetyFlags",
            "EarnedBadge" to "updatedAt",
            "PersonalRecord" to "profile_id",
        )
        for ((table, column) in checks) {
            if (!columnExists(driver, table, column)) {
                NSLog("iOS DB: Schema verification FAILED — $table.$column is missing")
                return false
            }
        }
        NSLog("iOS DB: Schema verification passed")
        return true
    }

    /**
     * Check if a column exists in a table using PRAGMA table_info.
     */
    private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean {
        return try {
            var found = false
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA table_info($table)",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val name = cursor.getString(1)
                        if (name == column) {
                            found = true
                        }
                    }
                    app.cash.sqldelight.db.QueryResult.Value(found)
                },
                parameters = 0,
            )
            found
        } catch (e: Exception) {
            NSLog("iOS DB: Schema check failed for $table.$column: ${e.message?.take(80)}")
            false
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
                        error = errorPtr.ptr,
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
     * Copy the main database file to a .backup suffix before nuclear recovery.
     * This preserves user workout data on disk even when the active database is
     * deleted and recreated. The backup can be restored manually or via a future
     * recovery feature once cloud sync is available.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun backupDatabaseFiles() {
        val dbPath = getDatabasePath()
        val backupPath = "$dbPath.backup"
        val fileManager = NSFileManager.defaultManager

        if (!fileManager.fileExistsAtPath(dbPath)) {
            NSLog("iOS DB: No database file to back up")
            return
        }

        try {
            // Remove any previous backup so copy doesn't fail with "file exists"
            if (fileManager.fileExistsAtPath(backupPath)) {
                fileManager.removeItemAtPath(backupPath, null)
            }
            fileManager.copyItemAtPath(dbPath, toPath = backupPath, error = null)
            NSLog("iOS DB: Database backed up to $backupPath")
        } catch (e: Exception) {
            NSLog("iOS DB: WARNING - Failed to back up database: ${e.message?.take(120)}")
            // Continue with nuclear recovery even if backup fails — a fresh DB
            // is better than a perpetually crashing app.
        }
    }

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
private class SavepointMigratingSchema(private val delegate: SqlSchema<QueryResult.Value<Unit>>) : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long get() = delegate.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = delegate.create(driver)

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        if (oldVersion >= newVersion) {
            return QueryResult.Value(Unit)
        }

        NSLog("iOS DB: Migrating from version $oldVersion to $newVersion with SAVEPOINT protection")

        for (version in oldVersion until newVersion) {
            val stepFrom = version
            val stepTo = version + 1
            val savepointName = "migration_v$stepTo"

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
        // NOTE: ensureAllTablesExist() was previously called here before every step.
        // This was REMOVED because it creates ALL tables with ALL columns from the
        // current schema, which poisons future ALTER TABLE ADD COLUMN migrations.
        // Tables that don't exist yet get created with columns that migrations
        // expect to ADD, causing "duplicate column" failures.
        // See: crash reports 7/8 (iPad7,11, build 20260327147).

        when (targetStep) {
            10L -> preFlightMigration10(driver)

            11L -> {
                // Gamification tables have no creation migration — bootstrap them
                // with the BASE shape (without sync/profile columns) so migration 11
                // can ALTER TABLE ADD COLUMN on them.
                ensureGamificationTablesExist(driver)
                preFlightMigration11(driver)
            }

            21L -> preFlightMigration21(driver)

            22L -> {
                ensureGamificationTablesExist(driver)
                preFlightMigration22(driver)
            }
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
     * Migration 11 adds sync columns (updatedAt, serverId, deletedAt) to gamification
     * tables. If these tables were bootstrapped by a previous build's
     * ensureGamificationTablesExist() or ensureAllTablesExist() with extra columns,
     * the ALTER TABLE ADD COLUMN will fail with "duplicate column".
     *
     * This preflight adds the sync columns defensively before migration 11 runs.
     */
    private fun preFlightMigration11(driver: SqlDriver) {
        val columns = listOf(
            // EarnedBadge sync columns (migration 11, step 6)
            "ALTER TABLE EarnedBadge ADD COLUMN updatedAt INTEGER",
            "ALTER TABLE EarnedBadge ADD COLUMN serverId TEXT",
            "ALTER TABLE EarnedBadge ADD COLUMN deletedAt INTEGER",
            // GamificationStats sync columns (migration 11, step 7)
            "ALTER TABLE GamificationStats ADD COLUMN updatedAt INTEGER",
            "ALTER TABLE GamificationStats ADD COLUMN serverId TEXT",
        )
        for (sql in columns) {
            try {
                driver.execute(null, sql, 0)
                NSLog("iOS DB: Pre-flight sync column added: ${sql.substringAfter("ADD COLUMN ").take(30)}")
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("duplicate column") || msg.contains("already exists")) {
                    // Expected — column present from prior bootstrap or healing
                } else if (msg.contains("no such table")) {
                    // Table doesn't exist yet — ensureGamificationTablesExist should have
                    // created it, but if it didn't, migration 11 will handle it
                    NSLog("iOS DB: Pre-flight migration 11: table missing (${e.message?.take(60)})")
                } else {
                    NSLog("iOS DB: Pre-flight ALTER warning (migration 11): ${e.message?.take(80)}")
                }
            }
        }
    }

    /**
     * Migration 21 creates indexes on profile_id for 6 tables, but the profile_id
     * columns themselves are added via schema healing which runs AFTER migrations.
     * For upgrade users whose DB is at version ≤20, the columns don't exist yet
     * when migration 21 runs, causing "no such column: profile_id" failures.
     *
     * This pre-flight adds the columns before migration 21's index creation,
     * matching the established pattern from preFlightMigration10.
     */
    private fun preFlightMigration21(driver: SqlDriver) {
        val columns = listOf(
            "ALTER TABLE WorkoutSession ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE PersonalRecord ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE Routine ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE TrainingCycle ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE AssessmentResult ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE ProgressionEvent ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
        )
        for (sql in columns) {
            try {
                driver.execute(null, sql, 0)
                NSLog("iOS DB: Pre-flight profile_id added: ${sql.substringAfter("ALTER TABLE ").substringBefore(" ADD")}")
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("duplicate column") || msg.contains("already exists")) {
                    // Expected — column already present from healing or fresh install
                } else {
                    NSLog("iOS DB: Pre-flight ALTER warning (migration 21): ${e.message?.take(80)}")
                }
            }
        }
    }

    /**
     * Migration 22 creates indexes on profile_id for gamification tables.
     * Same timing issue as migration 21 — the columns don't exist yet for
     * upgrade users. This pre-flight adds them before the index creation.
     */
    private fun preFlightMigration22(driver: SqlDriver) {
        val columns = listOf(
            "ALTER TABLE EarnedBadge ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE StreakHistory ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE RpgAttributes ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
            "ALTER TABLE GamificationStats ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
        )
        for (sql in columns) {
            try {
                driver.execute(null, sql, 0)
                NSLog("iOS DB: Pre-flight profile_id added: ${sql.substringAfter("ALTER TABLE ").substringBefore(" ADD")}")
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("duplicate column") || msg.contains("already exists")) {
                    // Expected — column already present from healing or fresh install
                } else {
                    NSLog("iOS DB: Pre-flight ALTER warning (migration 22): ${e.message?.take(80)}")
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

    /**
     * Layer 4 defense: ensure ALL tables from VitruvianDatabase.sq exist with their
     * complete column definitions. This is called during pre-flight to guarantee that
     * fresh iOS installs and upgrade paths both produce an identical schema.
     *
     * Each CREATE TABLE IF NOT EXISTS statement exactly mirrors the .sq file.
     * The CI validator (.github/scripts/validate-ios-schema.sh) enforces parity
     * between this function and VitruvianDatabase.sq — every table, every column,
     * same order.
     *
     * This does NOT create indexes — those are handled by SQLDelight migrations.
     */
    private fun ensureAllTablesExist(driver: SqlDriver) {
        val tables = listOf(
            """CREATE TABLE IF NOT EXISTS Exercise (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                created INTEGER NOT NULL DEFAULT 0,
                muscleGroup TEXT NOT NULL,
                muscleGroups TEXT NOT NULL,
                muscles TEXT,
                equipment TEXT NOT NULL,
                movement TEXT,
                sidedness TEXT,
                grip TEXT,
                gripWidth TEXT,
                minRepRange REAL,
                popularity REAL NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                isCustom INTEGER NOT NULL DEFAULT 0,
                timesPerformed INTEGER NOT NULL DEFAULT 0,
                lastPerformed INTEGER,
                aliases TEXT,
                defaultCableConfig TEXT NOT NULL,
                one_rep_max_kg REAL DEFAULT NULL,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER
            )""",
            """CREATE TABLE IF NOT EXISTS ExerciseVideo (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                angle TEXT NOT NULL,
                videoUrl TEXT NOT NULL,
                thumbnailUrl TEXT NOT NULL,
                isTutorial INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS WorkoutSession (
                id TEXT NOT NULL PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                mode TEXT NOT NULL,
                targetReps INTEGER NOT NULL,
                weightPerCableKg REAL NOT NULL,
                progressionKg REAL NOT NULL DEFAULT 0.0,
                duration INTEGER NOT NULL DEFAULT 0,
                totalReps INTEGER NOT NULL DEFAULT 0,
                warmupReps INTEGER NOT NULL DEFAULT 0,
                workingReps INTEGER NOT NULL DEFAULT 0,
                isJustLift INTEGER NOT NULL DEFAULT 0,
                stopAtTop INTEGER NOT NULL DEFAULT 0,
                eccentricLoad INTEGER NOT NULL DEFAULT 100,
                echoLevel INTEGER NOT NULL DEFAULT 1,
                exerciseId TEXT,
                exerciseName TEXT,
                routineSessionId TEXT,
                routineName TEXT,
                routineId TEXT,
                safetyFlags INTEGER NOT NULL DEFAULT 0,
                deloadWarningCount INTEGER NOT NULL DEFAULT 0,
                romViolationCount INTEGER NOT NULL DEFAULT 0,
                spotterActivations INTEGER NOT NULL DEFAULT 0,
                peakForceConcentricA REAL,
                peakForceConcentricB REAL,
                peakForceEccentricA REAL,
                peakForceEccentricB REAL,
                avgForceConcentricA REAL,
                avgForceConcentricB REAL,
                avgForceEccentricA REAL,
                avgForceEccentricB REAL,
                heaviestLiftKg REAL,
                totalVolumeKg REAL,
                cableCount INTEGER,
                estimatedCalories REAL,
                warmupAvgWeightKg REAL,
                workingAvgWeightKg REAL,
                burnoutAvgWeightKg REAL,
                peakWeightKg REAL,
                rpe INTEGER,
                avgMcvMmS REAL,
                avgAsymmetryPercent REAL,
                totalVelocityLossPercent REAL,
                dominantSide TEXT,
                strengthProfile TEXT,
                formScore INTEGER,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS MetricSample (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                position REAL,
                positionB REAL,
                velocity REAL,
                velocityB REAL,
                load REAL,
                loadB REAL,
                power REAL,
                status INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS PersonalRecord (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                exerciseName TEXT NOT NULL,
                weight REAL NOT NULL,
                reps INTEGER NOT NULL,
                oneRepMax REAL NOT NULL,
                achievedAt INTEGER NOT NULL,
                workoutMode TEXT NOT NULL,
                prType TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
                volume REAL NOT NULL DEFAULT 0.0,
                phase TEXT NOT NULL DEFAULT 'COMBINED',
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS Routine (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                lastUsed INTEGER,
                useCount INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS Superset (
                id TEXT PRIMARY KEY NOT NULL,
                routineId TEXT NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                orderIndex INTEGER NOT NULL,
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS RoutineExercise (
                id TEXT NOT NULL PRIMARY KEY,
                routineId TEXT NOT NULL,
                exerciseName TEXT NOT NULL,
                exerciseMuscleGroup TEXT NOT NULL DEFAULT '',
                exerciseEquipment TEXT NOT NULL DEFAULT '',
                exerciseDefaultCableConfig TEXT NOT NULL DEFAULT 'DOUBLE',
                exerciseId TEXT,
                cableConfig TEXT NOT NULL DEFAULT 'DOUBLE',
                orderIndex INTEGER NOT NULL,
                setReps TEXT NOT NULL DEFAULT '10,10,10',
                weightPerCableKg REAL NOT NULL DEFAULT 0.0,
                setWeights TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL DEFAULT 'OldSchool',
                eccentricLoad INTEGER NOT NULL DEFAULT 100,
                echoLevel INTEGER NOT NULL DEFAULT 1,
                progressionKg REAL NOT NULL DEFAULT 0.0,
                restSeconds INTEGER NOT NULL DEFAULT 60,
                duration INTEGER,
                setRestSeconds TEXT NOT NULL DEFAULT '[]',
                perSetRestTime INTEGER NOT NULL DEFAULT 0,
                isAMRAP INTEGER NOT NULL DEFAULT 0,
                supersetId TEXT,
                orderInSuperset INTEGER NOT NULL DEFAULT 0,
                usePercentOfPR INTEGER NOT NULL DEFAULT 0,
                weightPercentOfPR INTEGER NOT NULL DEFAULT 80,
                prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
                setWeightsPercentOfPR TEXT,
                stallDetectionEnabled INTEGER NOT NULL DEFAULT 1,
                stopAtTop INTEGER NOT NULL DEFAULT 0,
                repCountTiming TEXT NOT NULL DEFAULT 'TOP',
                setEchoLevels TEXT NOT NULL DEFAULT '',
                warmupSets TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE SET NULL,
                FOREIGN KEY (supersetId) REFERENCES Superset(id) ON DELETE SET NULL
            )""",
            """CREATE TABLE IF NOT EXISTS ConnectionLog (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                eventType TEXT NOT NULL,
                level TEXT NOT NULL,
                deviceAddress TEXT,
                deviceName TEXT,
                message TEXT NOT NULL,
                details TEXT,
                metadata TEXT
            )""",
            """CREATE TABLE IF NOT EXISTS DiagnosticsHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                runtimeSeconds INTEGER NOT NULL,
                faultMask INTEGER NOT NULL,
                temp1 INTEGER NOT NULL,
                temp2 INTEGER NOT NULL,
                temp3 INTEGER NOT NULL,
                temp4 INTEGER NOT NULL,
                temp5 INTEGER NOT NULL,
                temp6 INTEGER NOT NULL,
                temp7 INTEGER NOT NULL,
                temp8 INTEGER NOT NULL,
                containsFaults INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS PhaseStatistics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                concentricKgAvg REAL NOT NULL,
                concentricKgMax REAL NOT NULL,
                concentricVelAvg REAL NOT NULL,
                concentricVelMax REAL NOT NULL,
                concentricWattAvg REAL NOT NULL,
                concentricWattMax REAL NOT NULL,
                eccentricKgAvg REAL NOT NULL,
                eccentricKgMax REAL NOT NULL,
                eccentricVelAvg REAL NOT NULL,
                eccentricVelMax REAL NOT NULL,
                eccentricWattAvg REAL NOT NULL,
                eccentricWattMax REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS RepMetric (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                repNumber INTEGER NOT NULL,
                isWarmup INTEGER NOT NULL DEFAULT 0,
                startTimestamp INTEGER NOT NULL,
                endTimestamp INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                concentricDurationMs INTEGER NOT NULL,
                concentricPositions TEXT NOT NULL,
                concentricLoadsA TEXT NOT NULL,
                concentricLoadsB TEXT NOT NULL,
                concentricVelocities TEXT NOT NULL,
                concentricTimestamps TEXT NOT NULL,
                eccentricDurationMs INTEGER NOT NULL,
                eccentricPositions TEXT NOT NULL,
                eccentricLoadsA TEXT NOT NULL,
                eccentricLoadsB TEXT NOT NULL,
                eccentricVelocities TEXT NOT NULL,
                eccentricTimestamps TEXT NOT NULL,
                peakForceA REAL NOT NULL,
                peakForceB REAL NOT NULL,
                avgForceConcentricA REAL NOT NULL,
                avgForceConcentricB REAL NOT NULL,
                avgForceEccentricA REAL NOT NULL,
                avgForceEccentricB REAL NOT NULL,
                peakVelocity REAL NOT NULL,
                avgVelocityConcentric REAL NOT NULL,
                avgVelocityEccentric REAL NOT NULL,
                rangeOfMotionMm REAL NOT NULL,
                peakPowerWatts REAL NOT NULL,
                avgPowerWatts REAL NOT NULL,
                updatedAt INTEGER,
                serverId TEXT,
                FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS RepBiomechanics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                repNumber INTEGER NOT NULL,
                mcvMmS REAL NOT NULL,
                peakVelocityMmS REAL NOT NULL,
                velocityZone TEXT NOT NULL,
                velocityLossPercent REAL,
                estimatedRepsRemaining INTEGER,
                shouldStopSet INTEGER NOT NULL DEFAULT 0,
                normalizedForceN TEXT NOT NULL,
                normalizedPositionPct TEXT NOT NULL,
                stickingPointPct REAL,
                strengthProfile TEXT NOT NULL,
                asymmetryPercent REAL NOT NULL,
                dominantSide TEXT NOT NULL,
                avgLoadA REAL NOT NULL,
                avgLoadB REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS ExerciseSignature (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                romMm REAL NOT NULL,
                durationMs INTEGER NOT NULL,
                symmetryRatio REAL NOT NULL,
                velocityProfile TEXT NOT NULL,
                cableConfig TEXT NOT NULL,
                sampleCount INTEGER NOT NULL DEFAULT 1,
                confidence REAL NOT NULL DEFAULT 0.0,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS AssessmentResult (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                estimatedOneRepMaxKg REAL NOT NULL,
                loadVelocityData TEXT NOT NULL,
                assessmentSessionId TEXT,
                userOverrideKg REAL,
                createdAt INTEGER NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default',
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE,
                FOREIGN KEY (assessmentSessionId) REFERENCES WorkoutSession(id) ON DELETE SET NULL
            )""",
            """CREATE TABLE IF NOT EXISTS EarnedBadge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                badgeId TEXT NOT NULL,
                earnedAt INTEGER NOT NULL,
                celebratedAt INTEGER,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS StreakHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                length INTEGER NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default'
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
                lastUpdated INTEGER NOT NULL,
                updatedAt INTEGER,
                serverId TEXT,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS RpgAttributes (
                id INTEGER PRIMARY KEY DEFAULT 1,
                strength INTEGER NOT NULL DEFAULT 0,
                power INTEGER NOT NULL DEFAULT 0,
                stamina INTEGER NOT NULL DEFAULT 0,
                consistency INTEGER NOT NULL DEFAULT 0,
                mastery INTEGER NOT NULL DEFAULT 0,
                characterClass TEXT NOT NULL DEFAULT 'Phoenix',
                lastComputed INTEGER NOT NULL DEFAULT 0,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS TrainingCycle (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )""",
            """CREATE TABLE IF NOT EXISTS CycleDay (
                id TEXT PRIMARY KEY NOT NULL,
                cycle_id TEXT NOT NULL,
                day_number INTEGER NOT NULL,
                name TEXT,
                routine_id TEXT,
                is_rest_day INTEGER NOT NULL DEFAULT 0,
                echo_level TEXT,
                eccentric_load_percent INTEGER,
                weight_progression_percent REAL,
                rep_modifier INTEGER,
                rest_time_override_seconds INTEGER,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE,
                FOREIGN KEY (routine_id) REFERENCES Routine(id) ON DELETE SET NULL
            )""",
            """CREATE TABLE IF NOT EXISTS CycleProgress (
                id TEXT PRIMARY KEY NOT NULL,
                cycle_id TEXT NOT NULL UNIQUE,
                current_day_number INTEGER NOT NULL DEFAULT 1,
                last_completed_date INTEGER,
                cycle_start_date INTEGER NOT NULL,
                last_advanced_at INTEGER,
                completed_days TEXT,
                missed_days TEXT,
                rotation_count INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS CycleProgression (
                cycle_id TEXT PRIMARY KEY NOT NULL,
                frequency_cycles INTEGER NOT NULL DEFAULT 2,
                weight_increase_percent REAL,
                echo_level_increase INTEGER NOT NULL DEFAULT 0,
                eccentric_load_increase_percent INTEGER,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS PlannedSet (
                id TEXT PRIMARY KEY NOT NULL,
                routine_exercise_id TEXT NOT NULL,
                set_number INTEGER NOT NULL,
                set_type TEXT NOT NULL DEFAULT 'STANDARD',
                target_reps INTEGER,
                target_weight_kg REAL,
                target_rpe INTEGER,
                rest_seconds INTEGER,
                FOREIGN KEY (routine_exercise_id) REFERENCES RoutineExercise(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS CompletedSet (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                planned_set_id TEXT,
                set_number INTEGER NOT NULL,
                set_type TEXT NOT NULL DEFAULT 'STANDARD',
                actual_reps INTEGER NOT NULL,
                actual_weight_kg REAL NOT NULL,
                logged_rpe INTEGER,
                is_pr INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES WorkoutSession(id) ON DELETE CASCADE,
                FOREIGN KEY (planned_set_id) REFERENCES PlannedSet(id) ON DELETE SET NULL
            )""",
            """CREATE TABLE IF NOT EXISTS ProgressionEvent (
                id TEXT PRIMARY KEY NOT NULL,
                exercise_id TEXT NOT NULL,
                suggested_weight_kg REAL NOT NULL,
                previous_weight_kg REAL NOT NULL,
                reason TEXT NOT NULL,
                user_response TEXT,
                actual_weight_kg REAL,
                timestamp INTEGER NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default',
                FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
            )""",
            """CREATE TABLE IF NOT EXISTS UserProfile (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                isActive INTEGER NOT NULL DEFAULT 0,
                supabase_user_id TEXT,
                subscription_status TEXT DEFAULT 'free',
                subscription_expires_at INTEGER,
                last_auth_at INTEGER
            )""",
            // ExternalActivity and IntegrationStatus use CREATE TABLE IF NOT EXISTS
            // in VitruvianDatabase.sq itself, so SQLDelight handles them as idempotent
            // creates. They are included here for completeness on iOS upgrade paths.
            """CREATE TABLE IF NOT EXISTS ExternalActivity (
                id TEXT NOT NULL PRIMARY KEY,
                externalId TEXT NOT NULL,
                provider TEXT NOT NULL,
                name TEXT NOT NULL,
                activityType TEXT NOT NULL DEFAULT 'strength',
                startedAt INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL DEFAULT 0,
                distanceMeters REAL,
                calories INTEGER,
                avgHeartRate INTEGER,
                maxHeartRate INTEGER,
                elevationGainMeters REAL,
                rawData TEXT,
                syncedAt INTEGER NOT NULL,
                profileId TEXT NOT NULL DEFAULT 'default',
                needsSync INTEGER NOT NULL DEFAULT 1
            )""",
            """CREATE TABLE IF NOT EXISTS IntegrationStatus (
                provider TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'disconnected',
                lastSyncAt INTEGER,
                errorMessage TEXT,
                profileId TEXT NOT NULL DEFAULT 'default',
                PRIMARY KEY(provider, profileId)
            )""",
        )
        for (sql in tables) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                NSLog("iOS DB: Pre-flight table creation warning: ${e.message?.take(120)}")
            }
        }
        NSLog("iOS DB: Pre-flight all ${tables.size} tables verified")
    }
}
