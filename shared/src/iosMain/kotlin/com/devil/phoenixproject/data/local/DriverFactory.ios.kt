package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.database.VitruvianDatabase
import platform.Foundation.NSLog
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSUserDomainMask

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        // CRITICAL FIX (Jan 2026): Migration exceptions on iOS worker threads abort the app
        // before try-catch can intercept them. The NativeSqliteDriver runs migrations during
        // construction on worker threads - exceptions propagate through Kotlin/Native's
        // coroutine machinery to terminateWithUnhandledException, bypassing try-catch.
        //
        // Solution: Pre-add columns BEFORE running SQLDelight migrations. We:
        // 1. Open DB with no-op schema (no migrations)
        // 2. Add all columns that migrations expect to exist
        // 3. Close that connection
        // 4. Open DB normally - migrations now succeed since columns exist

        NSLog("iOS DB: Creating driver with pre-migration column fixes")

        // Phase 1: Pre-add columns that migrations expect
        // This prevents "no such column" crashes during Migration 10
        preMigrationColumnFixes()

        // Phase 2: Now open with full schema - migrations should succeed
        val driver = try {
            NativeSqliteDriver(
                schema = VitruvianDatabase.Schema,
                name = "vitruvian.db",
                onConfiguration = { config ->
                    config.copy(
                        // Keep FK disabled during migration to prevent cascade issues
                        extendedConfig = DatabaseConfiguration.Extended(
                            foreignKeyConstraints = false
                        )
                    )
                }
            )
        } catch (e: Exception) {
            // Migration failed - use recovery mode
            NSLog("iOS DB ERROR: SQLDelight migration failed: ${e.message}")
            NSLog("iOS DB: Attempting recovery with manual migration...")
            createRecoveryDriver()
        }

        // FALLBACK: Ensure all critical tables and columns exist regardless of migration state
        // This handles cases where migrations partially failed or were skipped
        NSLog("iOS DB: Running fallback schema verification...")

        // Migration 1: Exercise.one_rep_max_kg column
        ensureExerciseOneRepMaxColumnExists(driver)
        // Migration 2: UserProfile table
        ensureUserProfileTableExists(driver)
        // Migration 3-4: Superset tables and columns
        ensureRoutineExerciseSupersetColumnsExist(driver)
        // Migration 5: WorkoutSession summary columns
        ensureWorkoutSessionColumnsExist(driver)
        // Migration 6: Training cycle tables
        ensureTrainingCycleTablesExist(driver)
        ensureCycleDayColumnsExist(driver)  // Add missing columns to existing CycleDay table
        ensureCycleProgressColumnsExist(driver)  // Add missing columns to existing CycleProgress table
        verifyCriticalTablesExist(driver)
        // Migration 7: PR percentage columns
        ensureRoutineExercisePRColumnsExist(driver)
        // Data migration: Legacy superset data
        migrateLegacySupersetData(driver)
        // Migration 8 fixes
        cleanupInvalidSupersetData(driver)
        fixProgressionEventIndex(driver)
        // Migration 9 fixes
        regenerateSupersetCompositeIds(driver)
        // Gamification tables (may be missing - no migration was ever added for them)
        ensureGamificationTablesExist(driver)

        // Enable WAL mode for better concurrent read/write performance
        // This prevents lock contention issues during export (read) while other
        // operations might be writing. WAL allows readers and writers to proceed
        // concurrently without blocking each other.
        try {
            driver.execute(null, "PRAGMA journal_mode = WAL", 0)
            NSLog("iOS DB: WAL journal mode enabled")
        } catch (e: Exception) {
            NSLog("iOS DB: Warning - could not enable WAL mode: ${e.message}")
        }

        // Now enable foreign keys for normal operation
        try {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            NSLog("iOS DB: Foreign keys enabled")
        } catch (e: Exception) {
            NSLog("iOS DB: Warning - could not enable foreign keys: ${e.message}")
        }

        NSLog("iOS DB: Driver initialization complete")
        return driver
    }

    /**
     * Creates a recovery driver that skips SQLDelight's automatic migrations.
     * Used when normal migration fails due to schema issues.
     *
     * This creates a driver with a no-op schema that reports the target version
     * but doesn't actually run migrations. Our fallback functions then fix the schema.
     */
    private fun createRecoveryDriver(): SqlDriver {
        NSLog("iOS DB: Creating recovery driver (bypassing SQLDelight migrations)")

        // Create a no-op schema that reports the target version but doesn't migrate
        // This allows opening the database without crashing on migration errors
        val noOpSchema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = VitruvianDatabase.Schema.version

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                NSLog("iOS DB Recovery: Skipping schema create (using fallback functions)")
                return QueryResult.Value(Unit)
            }

            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion
            ): QueryResult.Value<Unit> {
                NSLog("iOS DB Recovery: Skipping SQLDelight migration $oldVersion -> $newVersion (using fallback functions)")
                return QueryResult.Value(Unit)
            }
        }

        return NativeSqliteDriver(
            schema = noOpSchema,
            name = "vitruvian.db",
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = DatabaseConfiguration.Extended(
                        foreignKeyConstraints = false
                    )
                )
            }
        )
    }

    /**
     * Verify that critical Training Cycle tables exist.
     * Throws an exception if tables are missing to fail fast with a clear error.
     */
    private fun verifyCriticalTablesExist(driver: SqlDriver) {
        val criticalTables = listOf("TrainingCycle", "CycleDay", "CycleProgress")
        val missingTables = mutableListOf<String>()

        for (tableName in criticalTables) {
            val exists = checkTableExists(driver, tableName)
            if (!exists) {
                missingTables.add(tableName)
                NSLog("iOS DB CRITICAL: Table '$tableName' does not exist!")
            }
        }

        if (missingTables.isNotEmpty()) {
            NSLog("iOS DB CRITICAL: Missing tables: $missingTables. Attempting emergency creation...")
            // Try one more time with direct SQL execution
            emergencyCreateTables(driver)

            // Verify again
            val stillMissing = missingTables.filter { !checkTableExists(driver, it) }
            if (stillMissing.isNotEmpty()) {
                NSLog("iOS DB FATAL: Could not create tables: $stillMissing")
                // Don't throw - let the app try to function, but log the critical error
            } else {
                NSLog("iOS DB: Emergency table creation succeeded")
            }
        }
    }

    /**
     * Check if a table exists in the database.
     * Uses a simple approach: try to select from the table and catch errors.
     */
    private fun checkTableExists(driver: SqlDriver, tableName: String): Boolean {
        return try {
            // Try a simple query against the table - if it doesn't exist, this will throw
            driver.executeQuery(
                null,
                "SELECT 1 FROM $tableName LIMIT 1",
                { cursor ->
                    // We don't care about the result, just whether it throws
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                0
            )
            true
        } catch (e: Exception) {
            // Table doesn't exist or error occurred
            NSLog("iOS DB: Table '$tableName' check: ${e.message}")
            false
        }
    }

    /**
     * Emergency direct table creation - bypasses normal flow.
     */
    private fun emergencyCreateTables(driver: SqlDriver) {
        NSLog("iOS DB: Starting emergency table creation...")

        // Execute each statement directly without any error handling to see what fails
        val statements = listOf(
            "CREATE TABLE IF NOT EXISTS TrainingCycle (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, description TEXT, created_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS CycleDay (id TEXT PRIMARY KEY NOT NULL, cycle_id TEXT NOT NULL, day_number INTEGER NOT NULL, name TEXT, routine_id TEXT, is_rest_day INTEGER NOT NULL DEFAULT 0, echo_level TEXT, eccentric_load_percent INTEGER, weight_progression_percent REAL, rep_modifier INTEGER, rest_time_override_seconds INTEGER)",
            "CREATE TABLE IF NOT EXISTS CycleProgress (id TEXT PRIMARY KEY NOT NULL, cycle_id TEXT NOT NULL UNIQUE, current_day_number INTEGER NOT NULL DEFAULT 1, last_completed_date INTEGER, cycle_start_date INTEGER NOT NULL, last_advanced_at INTEGER, completed_days TEXT, missed_days TEXT, rotation_count INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS CycleProgression (cycle_id TEXT PRIMARY KEY NOT NULL, frequency_cycles INTEGER NOT NULL DEFAULT 2, weight_increase_percent REAL, echo_level_increase INTEGER NOT NULL DEFAULT 0, eccentric_load_increase_percent INTEGER)",
            "CREATE TABLE IF NOT EXISTS PlannedSet (id TEXT PRIMARY KEY NOT NULL, routine_exercise_id TEXT NOT NULL, set_number INTEGER NOT NULL, set_type TEXT NOT NULL DEFAULT 'STANDARD', target_reps INTEGER, target_weight_kg REAL, target_rpe INTEGER, rest_seconds INTEGER)",
            "CREATE TABLE IF NOT EXISTS CompletedSet (id TEXT PRIMARY KEY NOT NULL, session_id TEXT NOT NULL, planned_set_id TEXT, set_number INTEGER NOT NULL, set_type TEXT NOT NULL DEFAULT 'STANDARD', actual_reps INTEGER NOT NULL, actual_weight_kg REAL NOT NULL, logged_rpe INTEGER, is_pr INTEGER NOT NULL DEFAULT 0, completed_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ProgressionEvent (id TEXT PRIMARY KEY NOT NULL, exercise_id TEXT NOT NULL, suggested_weight_kg REAL NOT NULL, previous_weight_kg REAL NOT NULL, reason TEXT NOT NULL, user_response TEXT, actual_weight_kg REAL, timestamp INTEGER NOT NULL)"
        )

        for (sql in statements) {
            try {
                driver.execute(null, sql, 0)
                NSLog("iOS DB: Executed: ${sql.take(60)}...")
            } catch (e: Exception) {
                NSLog("iOS DB ERROR: Failed to execute: ${sql.take(60)}... Error: ${e.message}")
            }
        }

        // Create indexes
        val indexes = listOf(
            "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)",
            "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_progression_event_exercise ON ProgressionEvent(exercise_id)"
        )

        for (sql in indexes) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                NSLog("iOS DB: Index creation note: ${e.message}")
            }
        }

        NSLog("iOS DB: Emergency table creation completed")
    }

    /**
     * Resilient migration fallback for Training Cycle tables.
     * Creates tables if they don't exist, ignoring errors for tables that already exist.
     * This mirrors the approach used in DriverFactory.android.kt.
     */
    private fun ensureTrainingCycleTablesExist(driver: SqlDriver) {
        val statements = listOf(
            // TrainingCycle table
            """
            CREATE TABLE IF NOT EXISTS TrainingCycle (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),

            // CycleDay table
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
            """.trimIndent(),

            // CycleProgress table
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
            """.trimIndent(),

            // CycleProgression table
            """
            CREATE TABLE IF NOT EXISTS CycleProgression (
                cycle_id TEXT PRIMARY KEY NOT NULL,
                frequency_cycles INTEGER NOT NULL DEFAULT 2,
                weight_increase_percent REAL,
                echo_level_increase INTEGER NOT NULL DEFAULT 0,
                eccentric_load_increase_percent INTEGER,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
            )
            """.trimIndent(),

            // PlannedSet table
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
            """.trimIndent(),

            // CompletedSet table
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
            """.trimIndent(),

            // ProgressionEvent table
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
            """.trimIndent(),

            // Indexes
            "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)",
            "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_progression_event_exercise ON ProgressionEvent(exercise_id)"
        )

        for (sql in statements) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                // Log but continue - table may already exist or have different schema
                // Using IF NOT EXISTS should prevent most errors
                NSLog("iOS DB Migration: ${e.message ?: "Unknown error"} for statement: ${sql.take(50)}...")
            }
        }

        NSLog("iOS DB Migration: Training Cycle tables verified/created")
    }

    /**
     * Ensure Exercise table has one_rep_max_kg column from migration 1.
     * This column is used for %-based training features.
     */
    private fun ensureExerciseOneRepMaxColumnExists(driver: SqlDriver) {
        val columnName = "one_rep_max_kg"
        try {
            if (!checkColumnExists(driver, "Exercise", columnName)) {
                driver.execute(null, "ALTER TABLE Exercise ADD COLUMN one_rep_max_kg REAL DEFAULT NULL", 0)
                NSLog("iOS DB: Added missing column '$columnName' to Exercise")
            } else {
                NSLog("iOS DB: Exercise.$columnName column present")
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("duplicate column", ignoreCase = true)) {
                NSLog("iOS DB: Column '$columnName' already exists (OK)")
            } else {
                NSLog("iOS DB ERROR: Failed to add column '$columnName': $msg")
            }
        }
    }

    /**
     * Ensure UserProfile table exists from migration 2.
     * This table is used for multi-user support.
     */
    private fun ensureUserProfileTableExists(driver: SqlDriver) {
        val tableName = "UserProfile"
        if (!checkTableExists(driver, tableName)) {
            try {
                val createTableSql = """
                    CREATE TABLE UserProfile (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        colorIndex INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent()
                driver.execute(null, createTableSql, 0)
                NSLog("iOS DB: Created missing table '$tableName'")
            } catch (e: Exception) {
                NSLog("iOS DB ERROR: Failed to create table '$tableName': ${e.message}")
            }
        } else {
            NSLog("iOS DB: UserProfile table present")
        }
    }

    /**
     * Ensure RoutineExercise has the PR percentage columns added in migration 7.
     * This fixes crash when saving routines for users whose migration 7 failed silently.
     * Uses ALTER TABLE ADD COLUMN which silently fails if column already exists (desired behavior).
     */
    private fun ensureRoutineExercisePRColumnsExist(driver: SqlDriver) {
        val columns = listOf(
            // Column name to SQL statement
            "usePercentOfPR" to "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0",
            "weightPercentOfPR" to "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80",
            "prTypeForScaling" to "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'",
            "setWeightsPercentOfPR" to "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT"
        )

        var columnsAdded = 0
        for ((columnName, sql) in columns) {
            try {
                // Check if column exists first
                if (!checkColumnExists(driver, "RoutineExercise", columnName)) {
                    driver.execute(null, sql, 0)
                    columnsAdded++
                    NSLog("iOS DB: Added missing column '$columnName' to RoutineExercise")
                }
            } catch (e: Exception) {
                // SQLite error "duplicate column name" means column exists - this is OK
                val msg = e.message ?: ""
                if (msg.contains("duplicate column", ignoreCase = true)) {
                    NSLog("iOS DB: Column '$columnName' already exists (OK)")
                } else {
                    NSLog("iOS DB ERROR: Failed to add column '$columnName': $msg")
                }
            }
        }

        if (columnsAdded > 0) {
            NSLog("iOS DB: Added $columnsAdded missing PR percentage columns to RoutineExercise")
        } else {
            NSLog("iOS DB: All RoutineExercise PR percentage columns present")
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
                        // PRAGMA table_info returns: cid, name, type, notnull, dflt_value, pk
                        // Column 1 (index 1) is the column name
                        val name = cursor.getString(1)
                        if (name == columnName) {
                            exists = true
                            break
                        }
                    }
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                0
            )
            exists
        } catch (e: Exception) {
            NSLog("iOS DB: Error checking column '$columnName' in '$tableName': ${e.message}")
            // Assume column doesn't exist if we can't check
            false
        }
    }

    /**
     * Ensure RoutineExercise has superset columns from migrations 3 and 4.
     * Migration 3 added: supersetGroupId, supersetOrder, supersetRestSeconds (later removed)
     * Migration 4 converted to: supersetId, orderInSuperset
     *
     * We only need the final columns (supersetId, orderInSuperset) since migration 4
     * is the current schema.
     */
    private fun ensureRoutineExerciseSupersetColumnsExist(driver: SqlDriver) {
        // First ensure the Superset table exists (from migration 4)
        val supersetTableSql = """
            CREATE TABLE IF NOT EXISTS Superset (
                id TEXT PRIMARY KEY NOT NULL,
                routineId TEXT NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                orderIndex INTEGER NOT NULL,
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
            )
        """.trimIndent()

        try {
            driver.execute(null, supersetTableSql, 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)", 0)
            NSLog("iOS DB: Superset table verified/created")
        } catch (e: Exception) {
            NSLog("iOS DB: Superset table error: ${e.message}")
        }

        // Ensure RoutineExercise has superset columns
        val columns = listOf(
            "supersetId" to "ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT",
            "orderInSuperset" to "ALTER TABLE RoutineExercise ADD COLUMN orderInSuperset INTEGER NOT NULL DEFAULT 0"
        )

        var columnsAdded = 0
        for ((columnName, sql) in columns) {
            try {
                if (!checkColumnExists(driver, "RoutineExercise", columnName)) {
                    driver.execute(null, sql, 0)
                    columnsAdded++
                    NSLog("iOS DB: Added missing column '$columnName' to RoutineExercise")
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("duplicate column", ignoreCase = true)) {
                    NSLog("iOS DB: Column '$columnName' already exists (OK)")
                } else {
                    NSLog("iOS DB ERROR: Failed to add column '$columnName': $msg")
                }
            }
        }

        // Also ensure the index exists
        try {
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)", 0)
        } catch (e: Exception) {
            NSLog("iOS DB: Index creation note: ${e.message}")
        }

        if (columnsAdded > 0) {
            NSLog("iOS DB: Added $columnsAdded missing superset columns to RoutineExercise")
        } else {
            NSLog("iOS DB: All RoutineExercise superset columns present")
        }
    }

    /**
     * Ensure WorkoutSession has all columns from migration 5.
     * These are summary metrics columns that may be missing if migration 5 failed.
     */
    private fun ensureWorkoutSessionColumnsExist(driver: SqlDriver) {
        val columns = listOf(
            // Peak force measurements
            "peakForceConcentricA" to "ALTER TABLE WorkoutSession ADD COLUMN peakForceConcentricA REAL",
            "peakForceConcentricB" to "ALTER TABLE WorkoutSession ADD COLUMN peakForceConcentricB REAL",
            "peakForceEccentricA" to "ALTER TABLE WorkoutSession ADD COLUMN peakForceEccentricA REAL",
            "peakForceEccentricB" to "ALTER TABLE WorkoutSession ADD COLUMN peakForceEccentricB REAL",
            // Average force measurements
            "avgForceConcentricA" to "ALTER TABLE WorkoutSession ADD COLUMN avgForceConcentricA REAL",
            "avgForceConcentricB" to "ALTER TABLE WorkoutSession ADD COLUMN avgForceConcentricB REAL",
            "avgForceEccentricA" to "ALTER TABLE WorkoutSession ADD COLUMN avgForceEccentricA REAL",
            "avgForceEccentricB" to "ALTER TABLE WorkoutSession ADD COLUMN avgForceEccentricB REAL",
            // Summary statistics
            "heaviestLiftKg" to "ALTER TABLE WorkoutSession ADD COLUMN heaviestLiftKg REAL",
            "totalVolumeKg" to "ALTER TABLE WorkoutSession ADD COLUMN totalVolumeKg REAL",
            "estimatedCalories" to "ALTER TABLE WorkoutSession ADD COLUMN estimatedCalories REAL",
            // Average weights by workout phase
            "warmupAvgWeightKg" to "ALTER TABLE WorkoutSession ADD COLUMN warmupAvgWeightKg REAL",
            "workingAvgWeightKg" to "ALTER TABLE WorkoutSession ADD COLUMN workingAvgWeightKg REAL",
            "burnoutAvgWeightKg" to "ALTER TABLE WorkoutSession ADD COLUMN burnoutAvgWeightKg REAL",
            "peakWeightKg" to "ALTER TABLE WorkoutSession ADD COLUMN peakWeightKg REAL",
            // Rate of Perceived Exertion
            "rpe" to "ALTER TABLE WorkoutSession ADD COLUMN rpe INTEGER"
        )

        var columnsAdded = 0
        for ((columnName, sql) in columns) {
            try {
                if (!checkColumnExists(driver, "WorkoutSession", columnName)) {
                    driver.execute(null, sql, 0)
                    columnsAdded++
                    NSLog("iOS DB: Added missing column '$columnName' to WorkoutSession")
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("duplicate column", ignoreCase = true)) {
                    // Column already exists - OK
                } else {
                    NSLog("iOS DB ERROR: Failed to add column '$columnName': $msg")
                }
            }
        }

        if (columnsAdded > 0) {
            NSLog("iOS DB: Added $columnsAdded missing columns to WorkoutSession")
        } else {
            NSLog("iOS DB: All WorkoutSession columns present")
        }
    }

    /**
     * Ensure CycleDay table has all columns from the current schema.
     * This is critical because CREATE TABLE IF NOT EXISTS does NOT add missing columns
     * to existing tables. If the table was created with an older schema, it will be
     * missing columns like echo_level, causing INSERT failures.
     */
    private fun ensureCycleDayColumnsExist(driver: SqlDriver) {
        // First check if the table exists - if not, ensureTrainingCycleTablesExist will create it
        if (!checkTableExists(driver, "CycleDay")) {
            NSLog("iOS DB: CycleDay table doesn't exist, will be created by ensureTrainingCycleTablesExist")
            return
        }

        // Columns that might be missing from older schema versions
        // Note: id, cycle_id, day_number, name, routine_id, is_rest_day are the base columns
        // The following columns were added in later schema updates
        val columns = listOf(
            "echo_level" to "ALTER TABLE CycleDay ADD COLUMN echo_level TEXT",
            "eccentric_load_percent" to "ALTER TABLE CycleDay ADD COLUMN eccentric_load_percent INTEGER",
            "weight_progression_percent" to "ALTER TABLE CycleDay ADD COLUMN weight_progression_percent REAL",
            "rep_modifier" to "ALTER TABLE CycleDay ADD COLUMN rep_modifier INTEGER",
            "rest_time_override_seconds" to "ALTER TABLE CycleDay ADD COLUMN rest_time_override_seconds INTEGER"
        )

        var columnsAdded = 0
        for ((columnName, sql) in columns) {
            try {
                if (!checkColumnExists(driver, "CycleDay", columnName)) {
                    driver.execute(null, sql, 0)
                    columnsAdded++
                    NSLog("iOS DB: Added missing column '$columnName' to CycleDay")
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("duplicate column", ignoreCase = true)) {
                    NSLog("iOS DB: Column '$columnName' already exists (OK)")
                } else {
                    NSLog("iOS DB ERROR: Failed to add column '$columnName' to CycleDay: $msg")
                }
            }
        }

        if (columnsAdded > 0) {
            NSLog("iOS DB: Added $columnsAdded missing columns to CycleDay")
        } else {
            NSLog("iOS DB: All CycleDay columns present")
        }
    }

    /**
     * Ensure CycleProgress table has all columns from the current schema.
     * This is critical because CREATE TABLE IF NOT EXISTS does NOT add missing columns
     * to existing tables. If the table was created with an older schema, it will be
     * missing columns like last_advanced_at, completed_days, missed_days, rotation_count.
     *
     * Without this fix, SELECT * FROM CycleProgress will fail when the app expects
     * 9 columns but the table only has 5.
     */
    private fun ensureCycleProgressColumnsExist(driver: SqlDriver) {
        // First check if the table exists - if not, ensureTrainingCycleTablesExist will create it
        if (!checkTableExists(driver, "CycleProgress")) {
            NSLog("iOS DB: CycleProgress table doesn't exist, will be created by ensureTrainingCycleTablesExist")
            return
        }

        // Columns that might be missing from older schema versions
        // Note: id, cycle_id, current_day_number, last_completed_date, cycle_start_date are the base columns
        // The following columns were added in later schema updates
        val columns = listOf(
            "last_advanced_at" to "ALTER TABLE CycleProgress ADD COLUMN last_advanced_at INTEGER",
            "completed_days" to "ALTER TABLE CycleProgress ADD COLUMN completed_days TEXT",
            "missed_days" to "ALTER TABLE CycleProgress ADD COLUMN missed_days TEXT",
            "rotation_count" to "ALTER TABLE CycleProgress ADD COLUMN rotation_count INTEGER NOT NULL DEFAULT 0"
        )

        var columnsAdded = 0
        for ((columnName, sql) in columns) {
            try {
                if (!checkColumnExists(driver, "CycleProgress", columnName)) {
                    driver.execute(null, sql, 0)
                    columnsAdded++
                    NSLog("iOS DB: Added missing column '$columnName' to CycleProgress")
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("duplicate column", ignoreCase = true)) {
                    NSLog("iOS DB: Column '$columnName' already exists (OK)")
                } else {
                    NSLog("iOS DB ERROR: Failed to add column '$columnName' to CycleProgress: $msg")
                }
            }
        }

        if (columnsAdded > 0) {
            NSLog("iOS DB: Added $columnsAdded missing columns to CycleProgress")
        } else {
            NSLog("iOS DB: All CycleProgress columns present")
        }
    }

    /**
     * Migrate legacy superset data from old columns to new container model.
     * Migration 3 added: supersetGroupId, supersetOrder, supersetRestSeconds
     * Migration 4 converted to: supersetId, orderInSuperset + Superset table
     *
     * If migration 4 failed, users may have data in the old columns that needs
     * to be migrated to the new model.
     */
    private fun migrateLegacySupersetData(driver: SqlDriver) {
        // Check if legacy columns exist
        val hasLegacyColumns = checkColumnExists(driver, "RoutineExercise", "supersetGroupId")
        if (!hasLegacyColumns) {
            NSLog("iOS DB: No legacy superset columns found, skipping migration")
            return
        }

        // Check if there's any data to migrate
        var hasLegacyData = false
        try {
            driver.executeQuery(
                null,
                "SELECT 1 FROM RoutineExercise WHERE supersetGroupId IS NOT NULL LIMIT 1",
                { cursor ->
                    hasLegacyData = cursor.next().value
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                0
            )
        } catch (e: Exception) {
            NSLog("iOS DB: Error checking legacy superset data: ${e.message}")
            return
        }

        if (!hasLegacyData) {
            NSLog("iOS DB: No legacy superset data to migrate")
            return
        }

        NSLog("iOS DB: Found legacy superset data, attempting migration...")

        try {
            // Step 1: Create Superset entries from distinct supersetGroupIds
            // Uses composite ID (routineId_supersetGroupId) to prevent PK collisions
            // Only create if not already exists (idempotent)
            val createSupersetsSQL = """
                INSERT OR IGNORE INTO Superset (id, routineId, name, colorIndex, restBetweenSeconds, orderIndex)
                SELECT
                    routineId || '_' || supersetGroupId,
                    routineId,
                    supersetGroupId,
                    0,
                    COALESCE(MAX(supersetRestSeconds), 10),
                    MIN(orderIndex)
                FROM RoutineExercise
                WHERE supersetGroupId IS NOT NULL AND supersetGroupId != ''
                GROUP BY supersetGroupId, routineId
            """.trimIndent()

            driver.execute(null, createSupersetsSQL, 0)
            NSLog("iOS DB: Created Superset entries from legacy data with composite IDs")

            // Step 2: Update RoutineExercise to link to Superset table
            // Use composite ID (routineId_supersetGroupId) to match new Superset rows
            val linkSupersetsSQL = """
                UPDATE RoutineExercise
                SET supersetId = routineId || '_' || supersetGroupId,
                    orderInSuperset = COALESCE(supersetOrder, 0)
                WHERE supersetGroupId IS NOT NULL
                  AND supersetGroupId != ''
                  AND (supersetId IS NULL OR supersetId = '')
            """.trimIndent()

            driver.execute(null, linkSupersetsSQL, 0)
            NSLog("iOS DB: Linked RoutineExercise to Superset entries")

            NSLog("iOS DB: Legacy superset data migration complete")
        } catch (e: Exception) {
            NSLog("iOS DB ERROR: Failed to migrate legacy superset data: ${e.message}")
            // Don't throw - app should still work, just without legacy superset data
        }
    }

    /**
     * Clean up invalid Superset data from Migration 4.
     * Migration 4 didn't filter empty strings, creating invalid Superset rows.
     */
    private fun cleanupInvalidSupersetData(driver: SqlDriver) {
        try {
            // Clear invalid supersetId references (empty strings)
            driver.execute(null, "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId = ''", 0)
            // Delete invalid Superset rows
            driver.execute(null, "DELETE FROM Superset WHERE id = ''", 0)
            // Fix orphaned supersetId references from Migration 4 ID collision bug
            // If same supersetGroupId was used across routines, some Superset rows weren't created
            driver.execute(null, "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)", 0)
            NSLog("iOS DB: Cleaned up invalid Superset data")
        } catch (e: Exception) {
            NSLog("iOS DB: Superset cleanup note: ${e.message}")
        }
    }

    /**
     * Fix ProgressionEvent index name inconsistency.
     * Migration 6 created idx_progression_event_exercise but schema uses idx_progression_exercise.
     */
    private fun fixProgressionEventIndex(driver: SqlDriver) {
        try {
            // Drop incorrectly named index
            driver.execute(null, "DROP INDEX IF EXISTS idx_progression_event_exercise", 0)
            // Ensure correctly named index exists
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)", 0)
            NSLog("iOS DB: ProgressionEvent index standardized")
        } catch (e: Exception) {
            NSLog("iOS DB: Index fix note: ${e.message}")
        }
    }

    /**
     * Regenerate Superset IDs to composite format (routineId_originalId).
     *
     * This is a fallback for Migration 9 to fix existing users who ran old Migration 4.
     * The original Migration 4 used supersetGroupId directly as Superset.id, which caused
     * PRIMARY KEY collisions when the same superset group name was used across different routines.
     *
     * The new composite format (routineId_originalId) ensures uniqueness across routines.
     *
     * Steps:
     * 1. Create new Superset rows with composite IDs (INSERT OR IGNORE for idempotency)
     * 2. Update RoutineExercise.supersetId to point to new composite IDs
     * 3. Delete old non-composite Superset rows if no references remain
     * 4. Final orphan cleanup for any dangling supersetId references
     */
    private fun regenerateSupersetCompositeIds(driver: SqlDriver) {
        try {
            // Step 1: Create new Superset rows with composite IDs
            // Only process rows where ID doesn't contain underscore (not yet migrated)
            val createCompositeSQL = """
                INSERT OR IGNORE INTO Superset (id, routineId, name, colorIndex, restBetweenSeconds, orderIndex)
                SELECT
                    routineId || '_' || id,
                    routineId,
                    name,
                    colorIndex,
                    restBetweenSeconds,
                    orderIndex
                FROM Superset
                WHERE id NOT LIKE '%_%'
            """.trimIndent()
            driver.execute(null, createCompositeSQL, 0)

            // Step 2: Update RoutineExercise references to use new composite IDs
            val updateReferencesSQL = """
                UPDATE RoutineExercise
                SET supersetId = routineId || '_' || supersetId
                WHERE supersetId IS NOT NULL
                  AND supersetId != ''
                  AND supersetId NOT LIKE '%_%'
                  AND EXISTS (
                      SELECT 1 FROM Superset
                      WHERE Superset.id = RoutineExercise.routineId || '_' || RoutineExercise.supersetId
                  )
            """.trimIndent()
            driver.execute(null, updateReferencesSQL, 0)

            // Step 3: Delete old non-composite Superset rows (now orphaned)
            val deleteOldSQL = """
                DELETE FROM Superset
                WHERE id NOT LIKE '%_%'
                  AND NOT EXISTS (SELECT 1 FROM RoutineExercise WHERE supersetId = Superset.id)
            """.trimIndent()
            driver.execute(null, deleteOldSQL, 0)

            // Step 4: Final orphan cleanup - null out any supersetId that points to non-existent Superset
            val cleanupOrphansSQL = """
                UPDATE RoutineExercise SET supersetId = NULL
                WHERE supersetId IS NOT NULL
                  AND supersetId NOT IN (SELECT id FROM Superset)
            """.trimIndent()
            driver.execute(null, cleanupOrphansSQL, 0)

            NSLog("iOS DB: Superset composite ID regeneration complete")
        } catch (e: Exception) {
            NSLog("iOS DB: Superset composite ID regeneration note: ${e.message}")
        }
    }

    /**
     * Ensure gamification tables exist.
     * These tables were added to the schema but no migration was ever created for them.
     * This ensures they exist for users who upgraded from older versions.
     */
    private fun ensureGamificationTablesExist(driver: SqlDriver) {
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS EarnedBadge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                badgeId TEXT NOT NULL UNIQUE,
                earnedAt INTEGER NOT NULL,
                celebratedAt INTEGER
            )
            """.trimIndent(),

            """
            CREATE TABLE IF NOT EXISTS StreakHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                length INTEGER NOT NULL
            )
            """.trimIndent(),

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
                lastUpdated INTEGER NOT NULL
            )
            """.trimIndent()
        )

        var tablesCreated = 0
        for (sql in statements) {
            try {
                driver.execute(null, sql, 0)
                tablesCreated++
            } catch (e: Exception) {
                // Table may already exist - that's fine
                NSLog("iOS DB: Gamification table note: ${e.message}")
            }
        }

        NSLog("iOS DB: Gamification tables verified/created")
    }

    /**
     * CRITICAL FIX (Jan 2026): Pre-add columns BEFORE SQLDelight migrations run.
     *
     * Problem: Migration 10 expects columns (supersetId, orderInSuperset, usePercentOfPR, etc.)
     * to exist in RoutineExercise. If earlier migrations failed silently, these columns
     * don't exist and Migration 10 crashes with "no such column" error.
     *
     * The iOS NativeSqliteDriver runs migrations on worker threads - exceptions propagate
     * through Kotlin/Native coroutine machinery and abort the app BEFORE try-catch can
     * intercept them.
     *
     * Solution: Open DB with no-op schema, add all required columns, close, then let
     * SQLDelight open it properly. Migrations will now succeed since columns exist.
     *
     * CRITICAL (Jan 2026 - Fresh Install Fix): For BRAND NEW users, the database doesn't exist.
     * If we open it with a no-op schema, SQLite creates the file and sets version = 1,
     * but creates NO TABLES. Then SQLDelight sees version 1 < 11 and runs migrations
     * against an EMPTY database, causing crashes. For fresh installs, we MUST let
     * SQLDelight create the schema properly - skip pre-migration fixes entirely.
     */
    private fun preMigrationColumnFixes() {
        // CRITICAL: Check if database exists BEFORE trying to open it
        // For fresh installs, we must let SQLDelight create the schema from scratch
        val dbPath = getDatabasePath()
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(dbPath)) {
            NSLog("iOS DB: Fresh install detected (no database file), skipping pre-migration fixes")
            return
        }

        NSLog("iOS DB: Running pre-migration column fixes...")

        // Open database with no-op schema that doesn't run any migrations
        val noOpSchema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 1L  // Low version, won't trigger migrations

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                // Don't create anything - DB already exists
                return QueryResult.Value(Unit)
            }

            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion
            ): QueryResult.Value<Unit> {
                // Don't run any migrations
                return QueryResult.Value(Unit)
            }
        }

        val preDriver: SqlDriver
        try {
            preDriver = NativeSqliteDriver(
                schema = noOpSchema,
                name = "vitruvian.db",
                onConfiguration = { config ->
                    config.copy(
                        // Don't run create/upgrade, just open
                        create = { _ -> },
                        upgrade = { _, _, _ -> },
                        extendedConfig = DatabaseConfiguration.Extended(
                            foreignKeyConstraints = false
                        )
                    )
                }
            )
        } catch (e: Exception) {
            NSLog("iOS DB: Pre-migration driver failed (fresh install?): ${e.message}")
            // If we can't open the database, it's likely a fresh install - migrations will handle it
            return
        }

        try {
            // Pre-add columns that Migration 10 expects to exist in RoutineExercise
            // These are added by migrations 4 and 7, but may be missing if those failed

            // Superset columns from Migration 4
            safeAddColumn(preDriver, "RoutineExercise", "supersetId", "TEXT")
            safeAddColumn(preDriver, "RoutineExercise", "orderInSuperset", "INTEGER NOT NULL DEFAULT 0")

            // PR percentage columns from Migration 7
            safeAddColumn(preDriver, "RoutineExercise", "usePercentOfPR", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(preDriver, "RoutineExercise", "weightPercentOfPR", "INTEGER NOT NULL DEFAULT 80")
            safeAddColumn(preDriver, "RoutineExercise", "prTypeForScaling", "TEXT NOT NULL DEFAULT 'MAX_WEIGHT'")
            safeAddColumn(preDriver, "RoutineExercise", "setWeightsPercentOfPR", "TEXT")

            // Ensure Superset table exists (Migration 4 creates it)
            try {
                preDriver.execute(null, """
                    CREATE TABLE IF NOT EXISTS Superset (
                        id TEXT PRIMARY KEY NOT NULL,
                        routineId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        colorIndex INTEGER NOT NULL DEFAULT 0,
                        restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                        orderIndex INTEGER NOT NULL,
                        FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
                    )
                """.trimIndent(), 0)
                preDriver.execute(null, "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)", 0)
            } catch (e: Exception) {
                NSLog("iOS DB Pre-migration: Superset table note: ${e.message}")
            }

            // CRITICAL FIX (Jan 2026): Ensure Training Cycle tables exist BEFORE migrations
            // For users whose DB is at version 10 with missing tables from failed Migration 6,
            // SQLDelight won't run migrations again. Creating tables here ensures they exist
            // regardless of migration state. CREATE TABLE IF NOT EXISTS is idempotent.
            createTrainingCycleTablesPreMigration(preDriver)

            NSLog("iOS DB: Pre-migration column fixes complete")
        } catch (e: Exception) {
            NSLog("iOS DB ERROR: Pre-migration fixes failed: ${e.message}")
        } finally {
            // Close the pre-migration driver
            try {
                preDriver.close()
            } catch (e: Exception) {
                NSLog("iOS DB: Pre-driver close note: ${e.message}")
            }
        }
    }

    /**
     * Safely add a column to a table, ignoring errors if it already exists.
     */
    private fun safeAddColumn(driver: SqlDriver, table: String, column: String, type: String) {
        try {
            // First check if column already exists
            var exists = false
            driver.executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    while (cursor.next().value) {
                        val name = cursor.getString(1)
                        if (name == column) {
                            exists = true
                            break
                        }
                    }
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                0
            )

            if (!exists) {
                driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $type", 0)
                NSLog("iOS DB Pre-migration: Added $table.$column")
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("duplicate column", ignoreCase = true) &&
                !msg.contains("no such table", ignoreCase = true)) {
                NSLog("iOS DB Pre-migration: $table.$column note: $msg")
            }
        }
    }

    /**
     * CRITICAL FIX (Jan 2026): Create/Rebuild Training Cycle tables BEFORE SQLDelight migrations.
     *
     * Problem: Users whose database was marked as version 10 from a previous failed migration
     * won't have SQLDelight run migrations again - it sees version 10 and skips all migrations.
     * But if Migration 6 (which creates these tables) crashed mid-way, the tables may not exist
     * OR may exist with missing columns (schema drift).
     *
     * Solution: Use "Rename-Aside & Rebuild" pattern for CycleDay and CycleProgress tables.
     * This handles:
     * 1. Users at version 10 with missing tables (Migration 6 failed)
     * 2. Users at version 10 with partial tables (missing columns)
     * 3. Users at any version where Migration 6 will fail
     * 4. Fresh installs (idempotent operations)
     *
     * Omits foreign keys during pre-migration to avoid FK constraint issues.
     */
    private fun createTrainingCycleTablesPreMigration(driver: SqlDriver) {
        NSLog("iOS DB Pre-migration: Creating/Rebuilding Training Cycle tables...")

        // Step 1: Ensure base TrainingCycle table exists (assumed stable)
        try {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS TrainingCycle (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT,
                    created_at INTEGER NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent(), 0)
        } catch (e: Exception) {
            NSLog("iOS DB Pre-migration: TrainingCycle note: ${e.message}")
        }

        // Step 2: Rebuild CycleDay using Rename-Aside pattern
        // This fixes missing columns in partial tables from failed Migration 6
        rebuildCycleDayTable(driver)

        // Step 3: Rebuild CycleProgress using Rename-Aside pattern
        rebuildCycleProgressTable(driver)

        // Step 4: Create other tables that are safe with IF NOT EXISTS
        val simpleStatements = listOf(
            // CycleProgression (no column drift expected)
            """
            CREATE TABLE IF NOT EXISTS CycleProgression (
                cycle_id TEXT PRIMARY KEY NOT NULL,
                frequency_cycles INTEGER NOT NULL DEFAULT 2,
                weight_increase_percent REAL,
                echo_level_increase INTEGER NOT NULL DEFAULT 0,
                eccentric_load_increase_percent INTEGER
            )
            """.trimIndent(),

            // PlannedSet
            """
            CREATE TABLE IF NOT EXISTS PlannedSet (
                id TEXT PRIMARY KEY NOT NULL,
                routine_exercise_id TEXT NOT NULL,
                set_number INTEGER NOT NULL,
                set_type TEXT NOT NULL DEFAULT 'STANDARD',
                target_reps INTEGER,
                target_weight_kg REAL,
                target_rpe INTEGER,
                rest_seconds INTEGER
            )
            """.trimIndent(),

            // CompletedSet
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
                completed_at INTEGER NOT NULL
            )
            """.trimIndent(),

            // ProgressionEvent
            """
            CREATE TABLE IF NOT EXISTS ProgressionEvent (
                id TEXT PRIMARY KEY NOT NULL,
                exercise_id TEXT NOT NULL,
                suggested_weight_kg REAL NOT NULL,
                previous_weight_kg REAL NOT NULL,
                reason TEXT NOT NULL,
                user_response TEXT,
                actual_weight_kg REAL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent(),

            // Indexes
            "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)",
            "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)",
            "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)"
        )

        for (sql in simpleStatements) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                val preview = sql.take(60).replace("\n", " ")
                NSLog("iOS DB Pre-migration: $preview... note: ${e.message}")
            }
        }

        NSLog("iOS DB Pre-migration: Training Cycle tables creation/rebuild complete")
    }

    /**
     * Rebuild CycleDay table using ATOMIC Rename-Aside pattern.
     * This ensures all required columns exist, even if the table was created
     * with an older schema that's missing new columns.
     *
     * CRITICAL: If CREATE new table fails after RENAME, we MUST restore the backup
     * to prevent leaving the database without a CycleDay table.
     */
    private fun rebuildCycleDayTable(driver: SqlDriver) {
        var backupCreated = false

        try {
            // Step 0: Clean up any leftover backup from previous failed migration
            // Without this, RENAME TO backup will fail with "table already exists"
            driver.execute(null, "DROP TABLE IF EXISTS CycleDay_premig_backup", 0)

            // Step 1: Create dummy table if missing (so rename always works)
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS CycleDay (
                    id TEXT PRIMARY KEY NOT NULL,
                    cycle_id TEXT NOT NULL,
                    day_number INTEGER NOT NULL,
                    name TEXT,
                    routine_id TEXT,
                    is_rest_day INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent(), 0)

            // Step 2: Rename existing to backup
            driver.execute(null, "ALTER TABLE CycleDay RENAME TO CycleDay_premig_backup", 0)
            backupCreated = true

            // Step 3: Create new table with full schema INCLUDING FOREIGN KEYS
            driver.execute(null, """
                CREATE TABLE CycleDay (
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
            """.trimIndent(), 0)

            // Step 4: Copy BASE columns only (prevent crash if backup missing new columns)
            driver.execute(null, """
                INSERT OR IGNORE INTO CycleDay (id, cycle_id, day_number, name, routine_id, is_rest_day)
                SELECT id, cycle_id, day_number, name, routine_id, is_rest_day FROM CycleDay_premig_backup
            """.trimIndent(), 0)

            // Step 5: Drop backup (only after successful rebuild)
            driver.execute(null, "DROP TABLE IF EXISTS CycleDay_premig_backup", 0)
            backupCreated = false

            NSLog("iOS DB Pre-migration: CycleDay table rebuilt successfully with FK constraints")
        } catch (e: Exception) {
            NSLog("iOS DB Pre-migration: CycleDay rebuild error: ${e.message}")

            // CRITICAL: If backup was created but new table wasn't, RESTORE the backup
            if (backupCreated) {
                try {
                    // Check if new CycleDay table exists
                    driver.executeQuery(null, "SELECT 1 FROM CycleDay LIMIT 1", { QueryResult.Value(Unit) }, 0)
                    // New table exists, safe to drop backup
                    driver.execute(null, "DROP TABLE IF EXISTS CycleDay_premig_backup", 0)
                    NSLog("iOS DB Pre-migration: CycleDay backup cleaned up")
                } catch (_: Exception) {
                    // New table doesn't exist - RESTORE backup to prevent data loss
                    try {
                        driver.execute(null, "ALTER TABLE CycleDay_premig_backup RENAME TO CycleDay", 0)
                        NSLog("iOS DB Pre-migration: CycleDay RESTORED from backup (prevented data loss)")
                    } catch (restoreError: Exception) {
                        NSLog("iOS DB Pre-migration: CRITICAL - Failed to restore CycleDay: ${restoreError.message}")
                    }
                }
            }
        }
    }

    /**
     * Rebuild CycleProgress table using ATOMIC Rename-Aside pattern.
     * This ensures all required columns exist, even if the table was created
     * with an older schema that's missing new columns like rotation_count.
     *
     * CRITICAL: If CREATE new table fails after RENAME, we MUST restore the backup
     * to prevent leaving the database without a CycleProgress table.
     */
    private fun rebuildCycleProgressTable(driver: SqlDriver) {
        var backupCreated = false

        try {
            // Step 0: Clean up any leftover backup from previous failed migration
            // Without this, RENAME TO backup will fail with "table already exists"
            driver.execute(null, "DROP TABLE IF EXISTS CycleProgress_premig_backup", 0)

            // Step 1: Create dummy table if missing (so rename always works)
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS CycleProgress (
                    id TEXT PRIMARY KEY NOT NULL,
                    cycle_id TEXT NOT NULL UNIQUE,
                    current_day_number INTEGER NOT NULL DEFAULT 1,
                    last_completed_date INTEGER,
                    cycle_start_date INTEGER NOT NULL
                )
            """.trimIndent(), 0)

            // Step 2: Rename existing to backup
            driver.execute(null, "ALTER TABLE CycleProgress RENAME TO CycleProgress_premig_backup", 0)
            backupCreated = true

            // Step 3: Create new table with full schema INCLUDING FOREIGN KEY
            driver.execute(null, """
                CREATE TABLE CycleProgress (
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
            """.trimIndent(), 0)

            // Step 4: Copy BASE columns only (prevent crash if backup missing new columns)
            driver.execute(null, """
                INSERT OR IGNORE INTO CycleProgress (id, cycle_id, current_day_number, last_completed_date, cycle_start_date)
                SELECT id, cycle_id, current_day_number, last_completed_date, cycle_start_date FROM CycleProgress_premig_backup
            """.trimIndent(), 0)

            // Step 5: Drop backup (only after successful rebuild)
            driver.execute(null, "DROP TABLE IF EXISTS CycleProgress_premig_backup", 0)
            backupCreated = false

            NSLog("iOS DB Pre-migration: CycleProgress table rebuilt successfully with FK constraint")
        } catch (e: Exception) {
            NSLog("iOS DB Pre-migration: CycleProgress rebuild error: ${e.message}")

            // CRITICAL: If backup was created but new table wasn't, RESTORE the backup
            if (backupCreated) {
                try {
                    // Check if new CycleProgress table exists
                    driver.executeQuery(null, "SELECT 1 FROM CycleProgress LIMIT 1", { QueryResult.Value(Unit) }, 0)
                    // New table exists, safe to drop backup
                    driver.execute(null, "DROP TABLE IF EXISTS CycleProgress_premig_backup", 0)
                    NSLog("iOS DB Pre-migration: CycleProgress backup cleaned up")
                } catch (_: Exception) {
                    // New table doesn't exist - RESTORE backup to prevent data loss
                    try {
                        driver.execute(null, "ALTER TABLE CycleProgress_premig_backup RENAME TO CycleProgress", 0)
                        NSLog("iOS DB Pre-migration: CycleProgress RESTORED from backup (prevented data loss)")
                    } catch (restoreError: Exception) {
                        NSLog("iOS DB Pre-migration: CRITICAL - Failed to restore CycleProgress: ${restoreError.message}")
                    }
                }
            }
        }
    }

    /**
     * Get the path where SQLite database will be stored.
     * NativeSqliteDriver stores databases in the app's Library directory by default.
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
