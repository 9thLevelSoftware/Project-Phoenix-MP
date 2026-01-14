package com.devil.phoenixproject.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase

actual class DriverFactory(private val context: Context) {

    companion object {
        private const val TAG = "DriverFactory"
        private const val DATABASE_NAME = "vitruvian.db"
    }

    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = VitruvianDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
            callback = object : AndroidSqliteDriver.Callback(VitruvianDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Enable foreign keys
                    db.execSQL("PRAGMA foreign_keys = ON;")
                    // Ensure gamification tables exist (no migration was ever added for them)
                    ensureGamificationTablesExist(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Custom migration handler that's resilient to partial migrations.
                    // SQLite ALTER TABLE ADD COLUMN fails if column exists - we catch and continue.
                    Log.i(TAG, "Upgrading database from version $oldVersion to $newVersion")

                    for (version in oldVersion until newVersion) {
                        // Pre-flight: ensure required columns exist before migration
                        preflightMigration(db, version + 1)

                        try {
                            // Let SQLDelight apply the migration
                            VitruvianDatabase.Schema.migrate(
                                driver = createTempDriver(db),
                                oldVersion = version.toLong(),
                                newVersion = (version + 1).toLong()
                            )
                            Log.i(TAG, "Successfully applied migration ${version + 1}")
                        } catch (e: SQLiteException) {
                            // Handle "duplicate column" errors from partially applied migrations
                            if (e.message?.contains("duplicate column") == true) {
                                Log.w(TAG, "Migration ${version + 1} partially applied (duplicate column), continuing...")
                                // Force run remaining statements in migration by executing directly
                                applyMigrationResilient(db, version + 1)
                            } else if (e.message?.contains("no such table") == true) {
                                // Table doesn't exist - try to create it
                                Log.w(TAG, "Migration ${version + 1} failed (no such table), attempting resilient apply...")
                                applyMigrationResilient(db, version + 1)
                            } else {
                                // Re-throw other errors
                                throw e
                            }
                        }
                    }
                }

                /**
                 * Apply migration statements one by one, ignoring duplicate column/table errors.
                 * This handles cases where a migration was partially applied.
                 */
                private fun applyMigrationResilient(db: SupportSQLiteDatabase, version: Int) {
                    val statements = getMigrationStatements(version)
                    for (sql in statements) {
                        try {
                            db.execSQL(sql)
                        } catch (e: SQLiteException) {
                            val msg = e.message ?: ""
                            if (msg.contains("duplicate column") ||
                                msg.contains("already exists") ||
                                msg.contains("table .* already exists".toRegex())) {
                                Log.w(TAG, "Skipping (already exists): ${sql.take(60)}...")
                            } else {
                                Log.e(TAG, "Migration statement failed: $sql", e)
                                // Continue with other statements
                            }
                        }
                    }
                }

                /**
                 * Get SQL statements for a specific migration version.
                 * Only includes migrations that need resilient handling.
                 */
                private fun getMigrationStatements(version: Int): List<String> {
                    return when (version) {
                        1 -> listOf(
                            "ALTER TABLE Exercise ADD COLUMN one_rep_max_kg REAL DEFAULT NULL"
                        )
                        2 -> listOf(
                            """CREATE TABLE IF NOT EXISTS UserProfile (
                                id TEXT PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL,
                                colorIndex INTEGER NOT NULL DEFAULT 0,
                                createdAt INTEGER NOT NULL,
                                isActive INTEGER NOT NULL DEFAULT 0
                            )"""
                        )
                        3 -> listOf(
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetGroupId TEXT",
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetOrder INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetRestSeconds INTEGER NOT NULL DEFAULT 10"
                        )
                        4 -> listOf(
                            """CREATE TABLE IF NOT EXISTS Superset (
                                id TEXT PRIMARY KEY NOT NULL,
                                routineId TEXT NOT NULL,
                                name TEXT NOT NULL,
                                colorIndex INTEGER NOT NULL DEFAULT 0,
                                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                                orderIndex INTEGER NOT NULL,
                                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
                            )""",
                            "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)",
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT",
                            "ALTER TABLE RoutineExercise ADD COLUMN orderInSuperset INTEGER NOT NULL DEFAULT 0",
                            "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)"
                        )
                        5 -> listOf(
                            "ALTER TABLE WorkoutSession ADD COLUMN peakForceConcentricA REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN peakForceConcentricB REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN peakForceEccentricA REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN peakForceEccentricB REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN avgForceConcentricA REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN avgForceConcentricB REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN avgForceEccentricA REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN avgForceEccentricB REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN heaviestLiftKg REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN totalVolumeKg REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN estimatedCalories REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN warmupAvgWeightKg REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN workingAvgWeightKg REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN burnoutAvgWeightKg REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN peakWeightKg REAL",
                            "ALTER TABLE WorkoutSession ADD COLUMN rpe INTEGER"
                        )
                        6 -> listOf(
                            """CREATE TABLE IF NOT EXISTS TrainingCycle (
                                id TEXT PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL,
                                description TEXT,
                                created_at INTEGER NOT NULL,
                                is_active INTEGER NOT NULL DEFAULT 0
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
                                FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
                            )""",
                            "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)",
                            "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)",
                            "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)",
                            "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)",
                            "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)",
                            // Fallback: Add missing columns to CycleDay if table existed without them
                            // CREATE TABLE IF NOT EXISTS won't add columns to existing tables
                            "ALTER TABLE CycleDay ADD COLUMN echo_level TEXT",
                            "ALTER TABLE CycleDay ADD COLUMN eccentric_load_percent INTEGER",
                            "ALTER TABLE CycleDay ADD COLUMN weight_progression_percent REAL",
                            "ALTER TABLE CycleDay ADD COLUMN rep_modifier INTEGER",
                            "ALTER TABLE CycleDay ADD COLUMN rest_time_override_seconds INTEGER",
                            // Fallback: Add missing columns to CycleProgress if table existed without them
                            // CREATE TABLE IF NOT EXISTS won't add columns to existing tables
                            "ALTER TABLE CycleProgress ADD COLUMN last_advanced_at INTEGER",
                            "ALTER TABLE CycleProgress ADD COLUMN completed_days TEXT",
                            "ALTER TABLE CycleProgress ADD COLUMN missed_days TEXT",
                            "ALTER TABLE CycleProgress ADD COLUMN rotation_count INTEGER NOT NULL DEFAULT 0"
                        )
                        7 -> listOf(
                            // PR percentage scaling columns for RoutineExercise (Issue #57)
                            "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80",
                            "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'",
                            "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT"
                        )
                        8 -> listOf(
                            // Create Superset if missing (so DELETE/SELECT below don't crash)
                            """CREATE TABLE IF NOT EXISTS Superset (
                                id TEXT PRIMARY KEY NOT NULL,
                                routineId TEXT NOT NULL,
                                name TEXT NOT NULL,
                                colorIndex INTEGER NOT NULL DEFAULT 0,
                                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                                orderIndex INTEGER NOT NULL
                            )""",
                            // Schema healing: clean up empty string artifacts from legacy data
                            "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId = ''",
                            "DELETE FROM Superset WHERE id = ''",
                            // Fix index name inconsistency (was idx_progression_event_exercise in some migrations)
                            "DROP INDEX IF EXISTS idx_progression_event_exercise",
                            "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)",
                            // Fix orphaned supersetId references from Migration 4 ID collision bug
                            "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)"
                        )
                        9 -> listOf(
                            // Create Superset if missing (so SELECT below doesn't crash)
                            """CREATE TABLE IF NOT EXISTS Superset (
                                id TEXT PRIMARY KEY NOT NULL,
                                routineId TEXT NOT NULL,
                                name TEXT NOT NULL,
                                colorIndex INTEGER NOT NULL DEFAULT 0,
                                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                                orderIndex INTEGER NOT NULL
                            )""",
                            // Final cleanup: Remove orphaned supersetId references after composite ID regeneration
                            // Migration 9.sqm converts Superset IDs to composite format (routineId_originalId)
                            // This catches any references that still point to non-existent Superset rows
                            "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)"
                        )
                        10 -> listOf(
                            // Migration 10: Comprehensive schema fix with table recreation
                            // Pre-flight columns (also in preflightMigration for early application)
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT",
                            "ALTER TABLE RoutineExercise ADD COLUMN orderInSuperset INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80",
                            "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'",
                            "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT",
                            // Cleanup temp tables
                            "DROP TABLE IF EXISTS RoutineExercise_new",
                            "DROP TABLE IF EXISTS RoutineExercise_v10",
                            "DROP TABLE IF EXISTS PlannedSet_temp",
                            "DROP TABLE IF EXISTS CompletedSet_temp",
                            "DROP TABLE IF EXISTS CycleDay_temp",
                            "DROP TABLE IF EXISTS CycleProgress_temp",
                            // Ensure Superset exists
                            """CREATE TABLE IF NOT EXISTS Superset (
                                id TEXT PRIMARY KEY NOT NULL,
                                routineId TEXT NOT NULL,
                                name TEXT NOT NULL,
                                colorIndex INTEGER NOT NULL DEFAULT 0,
                                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                                orderIndex INTEGER NOT NULL,
                                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
                            )""",
                            "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)",
                            // Ensure TrainingCycle tables exist
                            """CREATE TABLE IF NOT EXISTS TrainingCycle (
                                id TEXT PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL,
                                description TEXT,
                                created_at INTEGER NOT NULL,
                                is_active INTEGER NOT NULL DEFAULT 0
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
                            // Create indexes
                            "CREATE INDEX IF NOT EXISTS idx_routine_exercise_routine ON RoutineExercise(routineId)",
                            "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)",
                            "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)",
                            "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)",
                            "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)",
                            "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)",
                            // Cleanup orphaned references
                            "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)"
                        )
                        else -> emptyList()
                    }
                }

                /**
                 * Pre-flight migration setup.
                 * Ensures required columns exist BEFORE migration runs.
                 * This matches iOS behavior where fallbacks run before migrations.
                 */
                private fun preflightMigration(db: SupportSQLiteDatabase, version: Int) {
                    val statements = when (version) {
                        3 -> listOf(
                            // Migration 3 SELECT requires these columns to exist
                            // Add them if missing (will be overwritten by migration anyway)
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetGroupId TEXT",
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetOrder INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetRestSeconds INTEGER NOT NULL DEFAULT 10"
                        )
                        10 -> listOf(
                            // Migration 10 Step 0: Pre-flight columns needed for INSERT...SELECT
                            // Android SQLite (3.28-3.32) doesn't support ALTER TABLE ADD COLUMN IF NOT EXISTS
                            // (requires SQLite 3.35+), so we add them here with duplicate-column handling
                            "ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT",
                            "ALTER TABLE RoutineExercise ADD COLUMN orderInSuperset INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80",
                            "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'",
                            "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT"
                        )
                        else -> emptyList()
                    }

                    for (sql in statements) {
                        try {
                            db.execSQL(sql)
                            Log.d(TAG, "Preflight: executed ${sql.take(50)}...")
                        } catch (e: SQLiteException) {
                            // Column already exists - this is expected and OK
                            if (e.message?.contains("duplicate column") == true) {
                                Log.d(TAG, "Preflight: column already exists (OK)")
                            } else {
                                Log.w(TAG, "Preflight: ${e.message}")
                            }
                        }
                    }
                }

                /**
                 * Create a temporary SqlDriver wrapper for the SupportSQLiteDatabase.
                 * This allows SQLDelight's migrate() to work with the open database.
                 */
                private fun createTempDriver(db: SupportSQLiteDatabase): SqlDriver {
                    return object : SqlDriver {
                        override fun close() {}
                        override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {}
                        override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {}
                        override fun notifyListeners(vararg queryKeys: String) {}
                        override fun currentTransaction(): app.cash.sqldelight.Transacter.Transaction? = null
                        override fun newTransaction(): app.cash.sqldelight.db.QueryResult<app.cash.sqldelight.Transacter.Transaction> {
                            throw UnsupportedOperationException()
                        }
                        override fun execute(
                            identifier: Int?,
                            sql: String,
                            parameters: Int,
                            binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?
                        ): app.cash.sqldelight.db.QueryResult<Long> {
                            db.execSQL(sql)
                            return app.cash.sqldelight.db.QueryResult.Value(0L)
                        }
                        override fun <R> executeQuery(
                            identifier: Int?,
                            sql: String,
                            mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
                            parameters: Int,
                            binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?
                        ): app.cash.sqldelight.db.QueryResult<R> {
                            throw UnsupportedOperationException()
                        }
                    }
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "Database corruption detected")
                    // Let SQLite handle corruption - it will attempt recovery or recreate
                    super.onCorruption(db)
                }

                /**
                 * Ensure gamification tables exist.
                 * These tables were added to the schema but no migration was ever created for them.
                 * This ensures they exist for users who upgraded from older versions.
                 */
                private fun ensureGamificationTablesExist(db: SupportSQLiteDatabase) {
                    val statements = listOf(
                        """CREATE TABLE IF NOT EXISTS EarnedBadge (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            badgeId TEXT NOT NULL UNIQUE,
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
                        )"""
                    )

                    for (sql in statements) {
                        try {
                            db.execSQL(sql)
                        } catch (e: SQLiteException) {
                            // Table may already exist - that's fine
                            Log.d(TAG, "Gamification table note: ${e.message}")
                        }
                    }

                    Log.d(TAG, "Gamification tables verified/created")
                }
            }
        )
    }
}
