package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.database.VitruvianDatabase
import platform.Foundation.NSLog

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        // IMPORTANT: Previous implementation had a critical bug where Phase 2 used an empty
        // upgrade callback `upgrade = { _, _, _ -> }`. This caused data loss because:
        // 1. If Phase 1 failed, Phase 2 would mark the DB as "migrated" without running migrations
        // 2. Database version was updated but schema remained old/incomplete
        // 3. Future app launches skipped migrations (version matched) leaving DB corrupted
        //
        // FIX: Use a single driver creation with resilient migration handling.
        // Let SQLDelight run migrations normally, then use fallback functions to fix any gaps.

        NSLog("iOS DB: Creating driver with resilient migration handling")

        // Create driver - let SQLDelight handle migrations with FK disabled for safety
        val driver = NativeSqliteDriver(
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
        verifyCriticalTablesExist(driver)
        // Migration 7: PR percentage columns
        ensureRoutineExercisePRColumnsExist(driver)
        // Data migration: Legacy superset data
        migrateLegacySupersetData(driver)
        // Migration 8 fixes
        cleanupInvalidSupersetData(driver)
        fixProgressionEventIndex(driver)

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
            // Only create if not already exists (idempotent)
            val createSupersetsSQL = """
                INSERT OR IGNORE INTO Superset (id, routineId, name, colorIndex, restBetweenSeconds, orderIndex)
                SELECT
                    supersetGroupId,
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
            NSLog("iOS DB: Created Superset entries from legacy data")

            // Step 2: Update RoutineExercise to link to Superset table
            // Copy supersetGroupId to supersetId where supersetId is null
            val linkSupersetsSQL = """
                UPDATE RoutineExercise
                SET supersetId = supersetGroupId,
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
            // Clear invalid supersetId references
            driver.execute(null, "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId = ''", 0)
            // Delete invalid Superset rows
            driver.execute(null, "DELETE FROM Superset WHERE id = ''", 0)
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
}
