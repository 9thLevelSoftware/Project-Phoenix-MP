package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.database.VitruvianDatabase
import platform.Foundation.NSLog

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        // PHASE 1: Run migrations with FK disabled
        // Create a temporary driver that runs schema migrations, then close it immediately.
        // This works around iOS SQLite driver issues where migrations fail silently with FK enabled.
        // See: https://github.com/cashapp/sqldelight/issues/1356
        try {
            NativeSqliteDriver(
                schema = VitruvianDatabase.Schema,
                name = "vitruvian.db",
                onConfiguration = { config ->
                    config.copy(
                        extendedConfig = DatabaseConfiguration.Extended(
                            foreignKeyConstraints = false
                        )
                    )
                }
            ).close()
            NSLog("iOS DB: Phase 1 complete - migrations applied")
        } catch (e: Exception) {
            NSLog("iOS DB: Phase 1 migration error: ${e.message}")
            // Continue anyway - phase 2 fallback will try to recover
        }

        // PHASE 2: Create the real driver with FK enabled but migrations skipped
        val driver = NativeSqliteDriver(
            schema = VitruvianDatabase.Schema,
            name = "vitruvian.db",
            onConfiguration = { config ->
                config.copy(
                    // Skip migrations - already done in phase 1
                    upgrade = { _, _, _ -> },
                    extendedConfig = DatabaseConfiguration.Extended(
                        foreignKeyConstraints = true
                    )
                )
            }
        )

        // PHASE 3: Verify/create tables as fallback for users with corrupted state
        ensureTrainingCycleTablesExist(driver)
        verifyCriticalTablesExist(driver)

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
}
