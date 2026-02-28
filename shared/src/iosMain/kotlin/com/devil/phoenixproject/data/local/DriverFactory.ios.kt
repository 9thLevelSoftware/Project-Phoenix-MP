package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
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
 * iOS DriverFactory - COMPREHENSIVE 4-LAYER DEFENSE AGAINST MIGRATION CRASHES
 *
 * ## Background
 *
 * iOS SQLite migrations run on worker threads. Exceptions propagate through Kotlin/Native's
 * coroutine machinery to terminateWithUnhandledException, BYPASSING ALL try-catch blocks.
 * This makes iOS migrations fundamentally unsafe for complex schema changes.
 *
 * Previous fixes used NSUserDefaults markers to track "already purged" state, but iCloud
 * backs up NSUserDefaults. When users reinstall, both the corrupted database AND the marker
 * are restored, defeating the purpose.
 *
 * ## The 4-Layer Defense
 *
 * **Layer 1: No-Op Schema** - SQLDelight NEVER runs migrations on iOS. We use a schema that
 * reports the target version but has empty create() and migrate() methods.
 *
 * **Layer 2: Always Verify** - Check database health on EVERY launch. No markers that could
 * be restored by iCloud. If version < 12 or integrity fails, purge and recreate.
 *
 * **Layer 3: Exclude from Backup** - Mark the database with NSURLIsExcludedFromBackupKey
 * so iCloud doesn't back it up. Preserves data locally but prevents restore issues.
 *
 * **Layer 4: Manual Schema** - All tables, columns, and indexes are created/verified manually
 * using IF NOT EXISTS and ALTER TABLE ADD COLUMN with duplicate detection.
 *
 * @author Claude - Jan 2026
 */
actual class DriverFactory {

    companion object {
        /** Current schema version - must match SQLDelight (1 + number of .sqm files) */
        private const val CURRENT_SCHEMA_VERSION = 17L
    }

    /**
     * Database health states for Layer 2 verification.
     */
    private enum class DatabaseHealth {
        HEALTHY,    // Version >= CURRENT_SCHEMA_VERSION, integrity OK
        CORRUPTED,  // Integrity check failed
        OUTDATED,   // Version < CURRENT_SCHEMA_VERSION (needs migration = purge on iOS)
        MISSING     // Database file doesn't exist (fresh install)
    }

    /**
     * Creates the SQLite driver with 4-layer defense.
     */
    actual fun createDriver(): SqlDriver {
        NSLog("iOS DB: ========== DATABASE INITIALIZATION START ==========")
        NSLog("iOS DB: Target schema version: $CURRENT_SCHEMA_VERSION")

        try {
            // LAYER 2: Always check health - NO MARKERS
            NSLog("iOS DB: [STEP 1/6] Checking database health...")
            val health = checkDatabaseHealth()
            NSLog("iOS DB: [STEP 1/6] Health check result: $health")

            when (health) {
                DatabaseHealth.MISSING -> {
                    NSLog("iOS DB: Fresh install - will create new database")
                }
                DatabaseHealth.HEALTHY -> {
                    NSLog("iOS DB: Database healthy - preserving user data")
                }
                DatabaseHealth.OUTDATED, DatabaseHealth.CORRUPTED -> {
                    NSLog("iOS DB: Database not healthy ($health) - purging...")
                    deleteAllDatabaseFiles()
                    NSLog("iOS DB: Purge complete")
                }
            }

            // LAYER 1: Use no-op schema - SQLDelight NEVER runs migrations
            NSLog("iOS DB: [STEP 2/6] Creating NativeSqliteDriver...")
            val driver: SqlDriver
            try {
                driver = NativeSqliteDriver(
                    schema = noOpSchema,
                    name = "vitruvian.db",
                    onConfiguration = { config ->
                        config.copy(
                            create = { _ -> },  // Override to prevent schema.create() from being called
                            upgrade = { _, _, _ -> },  // Override to prevent schema.migrate() from being called
                            extendedConfig = DatabaseConfiguration.Extended(
                                foreignKeyConstraints = false  // Disable during schema setup
                            )
                        )
                    }
                )
                NSLog("iOS DB: [STEP 2/6] NativeSqliteDriver created successfully")
            } catch (e: Exception) {
                NSLog("iOS DB: [STEP 2/6] FATAL - NativeSqliteDriver creation failed: ${e::class.simpleName}")
                NSLog("iOS DB: [STEP 2/6] Error message: ${e.message}")
                NSLog("iOS DB: [STEP 2/6] Stack trace: ${e.stackTraceToString().take(500)}")
                throw e
            }

            // LAYER 4: Manual schema management
            NSLog("iOS DB: [STEP 3/6] Ensuring schema complete...")
            try {
                ensureSchemaComplete(driver)
                NSLog("iOS DB: [STEP 3/6] Schema complete")
            } catch (e: Exception) {
                NSLog("iOS DB: [STEP 3/6] FATAL - Schema creation failed: ${e::class.simpleName}")
                NSLog("iOS DB: [STEP 3/6] Error message: ${e.message}")
                NSLog("iOS DB: [STEP 3/6] Stack trace: ${e.stackTraceToString().take(500)}")
                throw e
            }

            // LAYER 3: Exclude from iCloud backup
            NSLog("iOS DB: [STEP 4/6] Excluding from iCloud backup...")
            excludeDatabaseFromBackup()
            NSLog("iOS DB: [STEP 4/6] Backup exclusion complete")

            // Enable foreign keys and WAL for normal operation
            NSLog("iOS DB: [STEP 5/6] Enabling pragmas...")
            try {
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                driver.execute(null, "PRAGMA journal_mode = WAL", 0)
                NSLog("iOS DB: [STEP 5/6] Pragmas enabled (foreign_keys, WAL)")
            } catch (e: Exception) {
                NSLog("iOS DB: [STEP 5/6] Warning - could not enable pragmas: ${e.message}")
            }

            NSLog("iOS DB: [STEP 6/6] Initialization complete")
            NSLog("iOS DB: ========== DATABASE INITIALIZATION SUCCESS ==========")
            return driver

        } catch (e: Exception) {
            NSLog("iOS DB: ========== DATABASE INITIALIZATION FAILED ==========")
            NSLog("iOS DB: FATAL Exception: ${e::class.simpleName}")
            NSLog("iOS DB: Message: ${e.message}")
            NSLog("iOS DB: Stack: ${e.stackTraceToString().take(1000)}")
            throw e
        }
    }

    // ==================== LAYER 1: No-Op Schema ====================

    /**
     * A schema that reports the target version but does NOTHING.
     * SQLDelight's generated migration code NEVER runs on iOS.
     */
    private val noOpSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = CURRENT_SCHEMA_VERSION

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            // NEVER let SQLDelight create the schema - we do it manually
            NSLog("iOS DB: noOpSchema.create() called - handled manually")
            return QueryResult.Value(Unit)
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion
        ): QueryResult.Value<Unit> {
            // NEVER let SQLDelight run migrations - they crash on iOS
            NSLog("iOS DB: noOpSchema.migrate($oldVersion -> $newVersion) called - skipped")
            return QueryResult.Value(Unit)
        }
    }

    // ==================== LAYER 2: Always Verify ====================

    /**
     * Check database health on EVERY launch. No markers, no one-time logic.
     * This cannot be defeated by iCloud restore.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun checkDatabaseHealth(): DatabaseHealth {
        val dbPath = getDatabasePath()
        val fileManager = NSFileManager.defaultManager

        // Check 1: Does database file exist?
        if (!fileManager.fileExistsAtPath(dbPath)) {
            return DatabaseHealth.MISSING
        }

        // Check 2: Can we read the schema version?
        val version = tryGetSchemaVersion()
        if (version == null) {
            NSLog("iOS DB: Cannot read schema version - CORRUPTED")
            return DatabaseHealth.CORRUPTED
        }

        // Check 3: Is version current? (Old versions would need migration = crash)
        if (version < CURRENT_SCHEMA_VERSION) {
            NSLog("iOS DB: Version $version < $CURRENT_SCHEMA_VERSION - OUTDATED")
            return DatabaseHealth.OUTDATED
        }

        // Check 4: Does integrity check pass?
        if (!passesIntegrityCheck()) {
            NSLog("iOS DB: Integrity check failed - CORRUPTED")
            return DatabaseHealth.CORRUPTED
        }

        return DatabaseHealth.HEALTHY
    }

    /**
     * Try to read the schema version from the database.
     * Returns null if we can't read it (corruption or missing).
     */
    private fun tryGetSchemaVersion(): Long? {
        val validationSchema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 1L
            override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
        }

        var testDriver: SqlDriver? = null
        return try {
            testDriver = NativeSqliteDriver(
                schema = validationSchema,
                name = "vitruvian.db",
                onConfiguration = { config ->
                    config.copy(
                        create = { _ -> },
                        upgrade = { _, _, _ -> },
                        extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = false)
                    )
                }
            )

            var version: Long? = null
            testDriver.executeQuery(
                null,
                "PRAGMA user_version",
                { cursor ->
                    if (cursor.next().value) {
                        version = cursor.getLong(0)
                    }
                    QueryResult.Value(Unit)
                },
                0
            )
            version
        } catch (e: Exception) {
            NSLog("iOS DB: Failed to read version: ${e.message}")
            null
        } finally {
            try { testDriver?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Run SQLite integrity check on the database.
     */
    private fun passesIntegrityCheck(): Boolean {
        val validationSchema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 1L
            override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
        }

        var testDriver: SqlDriver? = null
        return try {
            testDriver = NativeSqliteDriver(
                schema = validationSchema,
                name = "vitruvian.db",
                onConfiguration = { config ->
                    config.copy(
                        create = { _ -> },
                        upgrade = { _, _, _ -> },
                        extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = false)
                    )
                }
            )

            var integrityOk = false
            testDriver.executeQuery(
                null,
                "PRAGMA integrity_check",
                { cursor ->
                    if (cursor.next().value) {
                        val result = cursor.getString(0)
                        integrityOk = result == "ok"
                        if (!integrityOk) {
                            NSLog("iOS DB: Integrity check result: $result")
                        }
                    }
                    QueryResult.Value(Unit)
                },
                0
            )
            integrityOk
        } catch (e: Exception) {
            NSLog("iOS DB: Integrity check failed: ${e.message}")
            false
        } finally {
            try { testDriver?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Delete all database files (main, WAL, SHM).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun deleteAllDatabaseFiles() {
        val dbPath = getDatabasePath()
        val fileManager = NSFileManager.defaultManager

        val filesToDelete = listOf(dbPath, "$dbPath-wal", "$dbPath-shm")
        for (path in filesToDelete) {
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

    // ==================== LAYER 3: Exclude from Backup ====================

    /**
     * Exclude the database from iCloud backup using NSURLIsExcludedFromBackupKey.
     * This prevents iCloud from restoring corrupted databases after reinstall.
     */
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    private fun excludeDatabaseFromBackup() {
        val dbPath = getDatabasePath()
        val filesToExclude = listOf(dbPath, "$dbPath-wal", "$dbPath-shm")
        val fileManager = NSFileManager.defaultManager

        for (path in filesToExclude) {
            // Only try to exclude files that exist
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
        NSLog("iOS DB: Database files excluded from iCloud backup")
    }

    // ==================== LAYER 4: Manual Schema Management ====================

    /**
     * Ensure the complete schema exists, regardless of migration state.
     * Uses IF NOT EXISTS and duplicate column detection for idempotency.
     */
    private fun ensureSchemaComplete(driver: SqlDriver) {
        NSLog("iOS DB: Ensuring schema complete...")

        // Create all tables
        createAllTables(driver)

        // Add all columns that might be missing
        ensureAllColumnsExist(driver)

        // Create all indexes
        createAllIndexes(driver)

        // Set version to current
        try {
            driver.execute(null, "PRAGMA user_version = $CURRENT_SCHEMA_VERSION", 0)
            NSLog("iOS DB: Schema version set to $CURRENT_SCHEMA_VERSION")
        } catch (e: Exception) {
            NSLog("iOS DB: Warning - could not set version: ${e.message}")
        }

        NSLog("iOS DB: Schema verification complete")
    }

    /**
     * Create all tables using IF NOT EXISTS for idempotency.
     * CRITICAL: These table definitions MUST exactly match VitruvianDatabase.sq
     * Any mismatch will cause SQLDelight queries to fail with missing column errors.
     */
    private fun createAllTables(driver: SqlDriver) {
        val tables = listOf(
            // ==================== Exercise Library ====================
            // Must match VitruvianDatabase.sq exactly
            """
            CREATE TABLE IF NOT EXISTS Exercise (
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
            )
            """,
            // Exercise Videos
            """
            CREATE TABLE IF NOT EXISTS ExerciseVideo (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                angle TEXT NOT NULL,
                videoUrl TEXT NOT NULL,
                thumbnailUrl TEXT NOT NULL,
                isTutorial INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )
            """,
            // ==================== Workout Sessions ====================
            """
            CREATE TABLE IF NOT EXISTS WorkoutSession (
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
                estimatedCalories REAL,
                warmupAvgWeightKg REAL,
                workingAvgWeightKg REAL,
                burnoutAvgWeightKg REAL,
                peakWeightKg REAL,
                rpe INTEGER,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER
            )
            """,
            // ==================== Metric Samples ====================
            """
            CREATE TABLE IF NOT EXISTS MetricSample (
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
            )
            """,
            // ==================== Personal Records ====================
            """
            CREATE TABLE IF NOT EXISTS PersonalRecord (
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
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER
            )
            """,
            // ==================== Routines ====================
            """
            CREATE TABLE IF NOT EXISTS Routine (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                lastUsed INTEGER,
                useCount INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER
            )
            """,
            // ==================== Supersets ====================
            """
            CREATE TABLE IF NOT EXISTS Superset (
                id TEXT PRIMARY KEY NOT NULL,
                routineId TEXT NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                orderIndex INTEGER NOT NULL,
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
            )
            """,
            // ==================== Routine Exercises ====================
            """
            CREATE TABLE IF NOT EXISTS RoutineExercise (
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
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE SET NULL,
                FOREIGN KEY (supersetId) REFERENCES Superset(id) ON DELETE SET NULL
            )
            """,
            // ==================== Connection Logs ====================
            """
            CREATE TABLE IF NOT EXISTS ConnectionLog (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                eventType TEXT NOT NULL,
                level TEXT NOT NULL,
                deviceAddress TEXT,
                deviceName TEXT,
                message TEXT NOT NULL,
                details TEXT,
                metadata TEXT
            )
            """,
            // ==================== Diagnostics History ====================
            """
            CREATE TABLE IF NOT EXISTS DiagnosticsHistory (
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
            )
            """,
            // ==================== Phase Statistics ====================
            """
            CREATE TABLE IF NOT EXISTS PhaseStatistics (
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
            )
            """,
            // ==================== Per-Rep Metrics ====================
            """
            CREATE TABLE IF NOT EXISTS RepMetric (
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
            )
            """,
            // ==================== Per-Rep Biomechanics ====================
            """
            CREATE TABLE IF NOT EXISTS RepBiomechanics (
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
            )
            """,
            // ==================== Exercise Signatures ====================
            """
            CREATE TABLE IF NOT EXISTS ExerciseSignature (
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
            )
            """,
            // ==================== Assessment Results ====================
            """
            CREATE TABLE IF NOT EXISTS AssessmentResult (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                estimatedOneRepMaxKg REAL NOT NULL,
                loadVelocityData TEXT NOT NULL,
                assessmentSessionId TEXT,
                userOverrideKg REAL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE,
                FOREIGN KEY (assessmentSessionId) REFERENCES WorkoutSession(id) ON DELETE SET NULL
            )
            """,
            // ==================== Gamification Tables ====================
            """
            CREATE TABLE IF NOT EXISTS EarnedBadge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                badgeId TEXT NOT NULL UNIQUE,
                earnedAt INTEGER NOT NULL,
                celebratedAt INTEGER,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS StreakHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                length INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS GamificationStats (
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
                serverId TEXT
            )
            """,
            // ==================== Training Cycles ====================
            """
            CREATE TABLE IF NOT EXISTS TrainingCycle (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS CycleDay (
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
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS CycleProgress (
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
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS CycleProgression (
                cycle_id TEXT PRIMARY KEY NOT NULL,
                frequency_cycles INTEGER NOT NULL DEFAULT 2,
                weight_increase_percent REAL,
                echo_level_increase INTEGER NOT NULL DEFAULT 0,
                eccentric_load_increase_percent INTEGER,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS PlannedSet (
                id TEXT PRIMARY KEY NOT NULL,
                routine_exercise_id TEXT NOT NULL,
                set_number INTEGER NOT NULL,
                set_type TEXT NOT NULL DEFAULT 'STANDARD',
                target_reps INTEGER,
                target_weight_kg REAL,
                target_rpe INTEGER,
                rest_seconds INTEGER,
                FOREIGN KEY (routine_exercise_id) REFERENCES RoutineExercise(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS CompletedSet (
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
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS ProgressionEvent (
                id TEXT PRIMARY KEY NOT NULL,
                exercise_id TEXT NOT NULL,
                suggested_weight_kg REAL NOT NULL,
                previous_weight_kg REAL NOT NULL,
                reason TEXT NOT NULL,
                user_response TEXT,
                actual_weight_kg REAL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
            )
            """,
            // ==================== User Profiles ====================
            """
            CREATE TABLE IF NOT EXISTS UserProfile (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                isActive INTEGER NOT NULL DEFAULT 0,
                supabase_user_id TEXT,
                subscription_status TEXT DEFAULT 'free',
                subscription_expires_at INTEGER,
                last_auth_at INTEGER
            )
            """
        )

        for (sql in tables) {
            try {
                driver.execute(null, sql.trimIndent(), 0)
            } catch (e: Exception) {
                val tableName = sql.substringAfter("CREATE TABLE IF NOT EXISTS ").substringBefore(" (").trim()
                NSLog("iOS DB: Table '$tableName' note: ${e.message}")
            }
        }
    }

    /**
     * Ensure all columns exist that might be missing from older schemas.
     * Uses duplicate detection to handle already-existing columns.
     */
    private fun ensureAllColumnsExist(driver: SqlDriver) {
        // Exercise columns
        safeAddColumn(driver, "Exercise", "one_rep_max_kg", "REAL")
        safeAddColumn(driver, "Exercise", "updatedAt", "INTEGER")
        safeAddColumn(driver, "Exercise", "serverId", "TEXT")
        safeAddColumn(driver, "Exercise", "deletedAt", "INTEGER")

        // Routine columns
        safeAddColumn(driver, "Routine", "updatedAt", "INTEGER")
        safeAddColumn(driver, "Routine", "serverId", "TEXT")
        safeAddColumn(driver, "Routine", "deletedAt", "INTEGER")

        // RoutineExercise columns
        safeAddColumn(driver, "RoutineExercise", "supersetId", "TEXT")
        safeAddColumn(driver, "RoutineExercise", "orderInSuperset", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(driver, "RoutineExercise", "usePercentOfPR", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(driver, "RoutineExercise", "weightPercentOfPR", "INTEGER NOT NULL DEFAULT 80")
        safeAddColumn(driver, "RoutineExercise", "prTypeForScaling", "TEXT NOT NULL DEFAULT 'MAX_WEIGHT'")
        safeAddColumn(driver, "RoutineExercise", "setWeightsPercentOfPR", "TEXT")

        // WorkoutSession columns (migration 5 + 11)
        safeAddColumn(driver, "WorkoutSession", "peakForceConcentricA", "REAL")
        safeAddColumn(driver, "WorkoutSession", "peakForceConcentricB", "REAL")
        safeAddColumn(driver, "WorkoutSession", "peakForceEccentricA", "REAL")
        safeAddColumn(driver, "WorkoutSession", "peakForceEccentricB", "REAL")
        safeAddColumn(driver, "WorkoutSession", "avgForceConcentricA", "REAL")
        safeAddColumn(driver, "WorkoutSession", "avgForceConcentricB", "REAL")
        safeAddColumn(driver, "WorkoutSession", "avgForceEccentricA", "REAL")
        safeAddColumn(driver, "WorkoutSession", "avgForceEccentricB", "REAL")
        safeAddColumn(driver, "WorkoutSession", "heaviestLiftKg", "REAL")
        safeAddColumn(driver, "WorkoutSession", "totalVolumeKg", "REAL")
        safeAddColumn(driver, "WorkoutSession", "estimatedCalories", "REAL")
        safeAddColumn(driver, "WorkoutSession", "warmupAvgWeightKg", "REAL")
        safeAddColumn(driver, "WorkoutSession", "workingAvgWeightKg", "REAL")
        safeAddColumn(driver, "WorkoutSession", "burnoutAvgWeightKg", "REAL")
        safeAddColumn(driver, "WorkoutSession", "peakWeightKg", "REAL")
        safeAddColumn(driver, "WorkoutSession", "rpe", "INTEGER")
        // WorkoutSession biomechanics summary columns (migration 15 - Phase 13 v16)
        safeAddColumn(driver, "WorkoutSession", "avgMcvMmS", "REAL")
        safeAddColumn(driver, "WorkoutSession", "avgAsymmetryPercent", "REAL")
        safeAddColumn(driver, "WorkoutSession", "totalVelocityLossPercent", "REAL")
        safeAddColumn(driver, "WorkoutSession", "dominantSide", "TEXT")
        safeAddColumn(driver, "WorkoutSession", "strengthProfile", "TEXT")
        safeAddColumn(driver, "WorkoutSession", "updatedAt", "INTEGER")
        safeAddColumn(driver, "WorkoutSession", "serverId", "TEXT")
        safeAddColumn(driver, "WorkoutSession", "deletedAt", "INTEGER")

        // PersonalRecord columns
        safeAddColumn(driver, "PersonalRecord", "updatedAt", "INTEGER")
        safeAddColumn(driver, "PersonalRecord", "serverId", "TEXT")
        safeAddColumn(driver, "PersonalRecord", "deletedAt", "INTEGER")

        // UserProfile columns
        safeAddColumn(driver, "UserProfile", "supabase_user_id", "TEXT")
        safeAddColumn(driver, "UserProfile", "subscription_status", "TEXT DEFAULT 'free'")
        safeAddColumn(driver, "UserProfile", "subscription_expires_at", "INTEGER")
        safeAddColumn(driver, "UserProfile", "last_auth_at", "INTEGER")

        // CycleDay columns
        safeAddColumn(driver, "CycleDay", "echo_level", "TEXT")
        safeAddColumn(driver, "CycleDay", "eccentric_load_percent", "INTEGER")
        safeAddColumn(driver, "CycleDay", "weight_progression_percent", "REAL")
        safeAddColumn(driver, "CycleDay", "rep_modifier", "INTEGER")
        safeAddColumn(driver, "CycleDay", "rest_time_override_seconds", "INTEGER")

        // CycleProgress columns
        safeAddColumn(driver, "CycleProgress", "last_advanced_at", "INTEGER")
        safeAddColumn(driver, "CycleProgress", "completed_days", "TEXT")
        safeAddColumn(driver, "CycleProgress", "missed_days", "TEXT")
        safeAddColumn(driver, "CycleProgress", "rotation_count", "INTEGER NOT NULL DEFAULT 0")

        // EarnedBadge columns
        safeAddColumn(driver, "EarnedBadge", "updatedAt", "INTEGER")
        safeAddColumn(driver, "EarnedBadge", "serverId", "TEXT")
        safeAddColumn(driver, "EarnedBadge", "deletedAt", "INTEGER")

        // GamificationStats columns
        safeAddColumn(driver, "GamificationStats", "updatedAt", "INTEGER")
        safeAddColumn(driver, "GamificationStats", "serverId", "TEXT")
    }

    /**
     * Create all indexes using IF NOT EXISTS for idempotency.
     * CRITICAL: Column names must match VitruvianDatabase.sq exactly.
     */
    private fun createAllIndexes(driver: SqlDriver) {
        val indexes = listOf(
            // Exercise indexes
            "CREATE INDEX IF NOT EXISTS idx_exercise_popularity ON Exercise(popularity DESC, name ASC)",
            "CREATE INDEX IF NOT EXISTS idx_exercise_last_performed ON Exercise(lastPerformed DESC)",
            // RoutineExercise indexes
            "CREATE INDEX IF NOT EXISTS idx_routine_exercise_routine ON RoutineExercise(routineId)",
            "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)",
            // WorkoutSession indexes
            "CREATE INDEX IF NOT EXISTS idx_workout_session_exercise ON WorkoutSession(exerciseId)",
            "CREATE INDEX IF NOT EXISTS idx_workout_session_started ON WorkoutSession(timestamp)",
            // MetricSample indexes
            "CREATE INDEX IF NOT EXISTS idx_metric_sample_session ON MetricSample(sessionId)",
            // PersonalRecord indexes
            "CREATE INDEX IF NOT EXISTS idx_personal_record_exercise ON PersonalRecord(exerciseId)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType)",
            // Superset indexes
            "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)",
            // ConnectionLog indexes
            "CREATE INDEX IF NOT EXISTS idx_connection_log_timestamp ON ConnectionLog(timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_connection_log_device ON ConnectionLog(deviceAddress)",
            // DiagnosticsHistory indexes
            "CREATE INDEX IF NOT EXISTS idx_diagnostics_timestamp ON DiagnosticsHistory(timestamp)",
            // PhaseStatistics indexes
            "CREATE INDEX IF NOT EXISTS idx_phase_stats_session ON PhaseStatistics(sessionId)",
            // RepMetric indexes
            "CREATE INDEX IF NOT EXISTS idx_rep_metric_session ON RepMetric(sessionId)",
            "CREATE INDEX IF NOT EXISTS idx_rep_metric_session_rep ON RepMetric(sessionId, repNumber)",
            // RepBiomechanics indexes
            "CREATE INDEX IF NOT EXISTS idx_rep_biomechanics_session ON RepBiomechanics(sessionId)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_rep_biomechanics_session_rep ON RepBiomechanics(sessionId, repNumber)",
            // ExerciseSignature indexes
            "CREATE INDEX IF NOT EXISTS idx_exercise_signature_exercise ON ExerciseSignature(exerciseId)",
            // AssessmentResult indexes
            "CREATE INDEX IF NOT EXISTS idx_assessment_result_exercise ON AssessmentResult(exerciseId)",
            // Training Cycle indexes
            "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)",
            "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)"
        )

        for (sql in indexes) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                // Index may already exist or be created with incompatible definition - that's fine
            }
        }
    }

    /**
     * Safely add a column to a table, handling "duplicate column" gracefully.
     */
    private fun safeAddColumn(driver: SqlDriver, table: String, column: String, type: String) {
        try {
            // First check if column exists
            if (checkColumnExists(driver, table, column)) {
                return  // Column already exists
            }

            driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $type", 0)
            NSLog("iOS DB: Added column $table.$column")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("duplicate column", ignoreCase = true) &&
                !msg.contains("no such table", ignoreCase = true)) {
                NSLog("iOS DB: Column $table.$column note: $msg")
            }
        }
    }

    /**
     * Check if a column exists in a table using PRAGMA table_info.
     */
    private fun checkColumnExists(driver: SqlDriver, tableName: String, columnName: String): Boolean {
        return try {
            var exists = false
            driver.executeQuery(
                null,
                "PRAGMA table_info($tableName)",
                { cursor ->
                    while (cursor.next().value) {
                        val name = cursor.getString(1)
                        if (name == columnName) {
                            exists = true
                            break
                        }
                    }
                    QueryResult.Value(Unit)
                },
                0
            )
            exists
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Utility Functions ====================

    /**
     * Get the path where SQLite database is stored.
     */
    private fun getDatabasePath(): String {
        val fileManager = NSFileManager.defaultManager
        val urls = fileManager.URLsForDirectory(NSLibraryDirectory, NSUserDomainMask)
        @Suppress("UNCHECKED_CAST")
        val libraryUrl = (urls as List<platform.Foundation.NSURL>).firstOrNull()
        val libraryPath = libraryUrl?.path ?: ""
        return "$libraryPath/vitruvian.db"
    }
}
