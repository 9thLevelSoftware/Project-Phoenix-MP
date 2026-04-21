package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver

/**
 * Result of executing a single migration SQL statement during resilient fallback.
 */
internal data class MigrationStatementResult(
    val sql: String,
    val success: Boolean,
    val recoverable: Boolean = true,
    val error: String? = null,
)

/**
 * Execute all SQL statements for a migration version one-by-one, catching
 * "duplicate column" and "already exists" errors that indicate partially
 * applied migrations.
 *
 * This is the common-code replacement for the Android-only `applyMigrationResilient`
 * that was previously in `DriverFactory.android.kt`. Both Android and iOS can now
 * share the same resilient fallback logic.
 */
internal fun applyMigrationResilient(
    driver: SqlDriver,
    version: Int,
): List<MigrationStatementResult> {
    val statements = getMigrationStatements(version)
    return statements.map { sql ->
        try {
            driver.execute(identifier = null, sql = sql, parameters = 0)
            MigrationStatementResult(sql = sql, success = true)
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            val isRecoverable = msg.contains("duplicate column") ||
                msg.contains("already exists") ||
                "table .* already exists".toRegex().containsMatchIn(msg)
            MigrationStatementResult(
                sql = sql,
                success = false,
                recoverable = isRecoverable,
                error = e.message,
            )
        }
    }
}

/**
 * Get the SQL statements for a specific migration version.
 *
 * These mirror the .sqm files exactly, split into individual statements so
 * the resilient executor can apply them one-by-one. Every version from 1-25
 * is covered. Version 18 is intentionally empty (NOOP).
 */
internal fun getMigrationStatements(version: Int): List<String> = when (version) {
    // Migration 1: Add 1RM column to Exercise
    1 -> listOf(
        "ALTER TABLE Exercise ADD COLUMN one_rep_max_kg REAL DEFAULT NULL",
    )

    // Migration 2: UserProfile table
    2 -> listOf(
        """CREATE TABLE IF NOT EXISTS UserProfile (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            colorIndex INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL,
            isActive INTEGER NOT NULL DEFAULT 0
        )""",
    )

    // Migration 3: Superset columns on RoutineExercise
    3 -> listOf(
        "ALTER TABLE RoutineExercise ADD COLUMN supersetGroupId TEXT",
        "ALTER TABLE RoutineExercise ADD COLUMN supersetOrder INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE RoutineExercise ADD COLUMN supersetRestSeconds INTEGER NOT NULL DEFAULT 10",
    )

    // Migration 4: Superset table + RoutineExercise superset columns
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
        "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)",
    )

    // Migration 5: WorkoutSession biomechanics + analytics columns
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
        "ALTER TABLE WorkoutSession ADD COLUMN rpe INTEGER",
    )

    // Migration 6: Training cycle tables + indexes + CycleDay/CycleProgress fallback columns
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
        "ALTER TABLE CycleDay ADD COLUMN echo_level TEXT",
        "ALTER TABLE CycleDay ADD COLUMN eccentric_load_percent INTEGER",
        "ALTER TABLE CycleDay ADD COLUMN weight_progression_percent REAL",
        "ALTER TABLE CycleDay ADD COLUMN rep_modifier INTEGER",
        "ALTER TABLE CycleDay ADD COLUMN rest_time_override_seconds INTEGER",
        // Fallback: Add missing columns to CycleProgress if table existed without them
        "ALTER TABLE CycleProgress ADD COLUMN last_advanced_at INTEGER",
        "ALTER TABLE CycleProgress ADD COLUMN completed_days TEXT",
        "ALTER TABLE CycleProgress ADD COLUMN missed_days TEXT",
        "ALTER TABLE CycleProgress ADD COLUMN rotation_count INTEGER NOT NULL DEFAULT 0",
    )

    // Migration 7: PR percentage scaling columns for RoutineExercise
    7 -> listOf(
        "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80",
        "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'",
        "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT",
    )

    // Migration 8: Schema healing + index fix + orphan cleanup
    8 -> listOf(
        """CREATE TABLE IF NOT EXISTS Superset (
            id TEXT PRIMARY KEY NOT NULL,
            routineId TEXT NOT NULL,
            name TEXT NOT NULL,
            colorIndex INTEGER NOT NULL DEFAULT 0,
            restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
            orderIndex INTEGER NOT NULL
        )""",
        "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId = ''",
        "DELETE FROM Superset WHERE id = ''",
        "DROP INDEX IF EXISTS idx_progression_event_exercise",
        "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)",
        "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)",
    )

    // Migration 9: Final orphan cleanup after composite ID regeneration
    9 -> listOf(
        """CREATE TABLE IF NOT EXISTS Superset (
            id TEXT PRIMARY KEY NOT NULL,
            routineId TEXT NOT NULL,
            name TEXT NOT NULL,
            colorIndex INTEGER NOT NULL DEFAULT 0,
            restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
            orderIndex INTEGER NOT NULL
        )""",
        "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)",
    )

    // Migration 10: Comprehensive schema fix with table recreation
    10 -> listOf(
        // Pre-flight columns needed for INSERT...SELECT
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
        "UPDATE RoutineExercise SET supersetId = NULL WHERE supersetId IS NOT NULL AND supersetId NOT IN (SELECT id FROM Superset)",
    )

    // Migration 11: Sync columns on multiple tables
    11 -> listOf(
        "ALTER TABLE WorkoutSession ADD COLUMN updatedAt INTEGER",
        "ALTER TABLE WorkoutSession ADD COLUMN serverId TEXT",
        "ALTER TABLE WorkoutSession ADD COLUMN deletedAt INTEGER",
        "ALTER TABLE PersonalRecord ADD COLUMN updatedAt INTEGER",
        "ALTER TABLE PersonalRecord ADD COLUMN serverId TEXT",
        "ALTER TABLE PersonalRecord ADD COLUMN deletedAt INTEGER",
        "ALTER TABLE Routine ADD COLUMN updatedAt INTEGER",
        "ALTER TABLE Routine ADD COLUMN serverId TEXT",
        "ALTER TABLE Routine ADD COLUMN deletedAt INTEGER",
        "ALTER TABLE Exercise ADD COLUMN updatedAt INTEGER",
        "ALTER TABLE Exercise ADD COLUMN serverId TEXT",
        "ALTER TABLE Exercise ADD COLUMN deletedAt INTEGER",
        "ALTER TABLE EarnedBadge ADD COLUMN updatedAt INTEGER",
        "ALTER TABLE EarnedBadge ADD COLUMN serverId TEXT",
        "ALTER TABLE EarnedBadge ADD COLUMN deletedAt INTEGER",
        "ALTER TABLE GamificationStats ADD COLUMN updatedAt INTEGER",
        "ALTER TABLE GamificationStats ADD COLUMN serverId TEXT",
    )

    // Migration 12: RepMetric table + routineId column on WorkoutSession
    12 -> listOf(
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
        "CREATE INDEX IF NOT EXISTS idx_rep_metric_session ON RepMetric(sessionId)",
        "CREATE INDEX IF NOT EXISTS idx_rep_metric_session_rep ON RepMetric(sessionId, repNumber)",
        "ALTER TABLE WorkoutSession ADD COLUMN routineId TEXT",
    )

    // Migration 13: MetricSample performance index
    13 -> listOf(
        "CREATE INDEX IF NOT EXISTS idx_metric_sample_session ON MetricSample(sessionId)",
    )

    // Migration 14: ExerciseSignature and AssessmentResult tables
    14 -> listOf(
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
        "CREATE INDEX IF NOT EXISTS idx_exercise_signature_exercise ON ExerciseSignature(exerciseId)",
        """CREATE TABLE IF NOT EXISTS AssessmentResult (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            exerciseId TEXT NOT NULL,
            estimatedOneRepMaxKg REAL NOT NULL,
            loadVelocityData TEXT NOT NULL,
            assessmentSessionId TEXT,
            userOverrideKg REAL,
            createdAt INTEGER NOT NULL,
            FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE,
            FOREIGN KEY (assessmentSessionId) REFERENCES WorkoutSession(id) ON DELETE SET NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_assessment_result_exercise ON AssessmentResult(exerciseId)",
    )

    // Migration 15: RepBiomechanics table + WorkoutSession summary columns
    15 -> listOf(
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
        "CREATE INDEX IF NOT EXISTS idx_rep_biomechanics_session ON RepBiomechanics(sessionId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_rep_biomechanics_session_rep ON RepBiomechanics(sessionId, repNumber)",
        "ALTER TABLE WorkoutSession ADD COLUMN avgMcvMmS REAL",
        "ALTER TABLE WorkoutSession ADD COLUMN avgAsymmetryPercent REAL",
        "ALTER TABLE WorkoutSession ADD COLUMN totalVelocityLossPercent REAL",
        "ALTER TABLE WorkoutSession ADD COLUMN dominantSide TEXT",
        "ALTER TABLE WorkoutSession ADD COLUMN strengthProfile TEXT",
    )

    // Migration 16: Form Check score persistence
    16 -> listOf(
        "ALTER TABLE WorkoutSession ADD COLUMN formScore INTEGER",
    )

    // Migration 17: RPG Attributes table
    17 -> listOf(
        """CREATE TABLE IF NOT EXISTS RpgAttributes (
            id INTEGER PRIMARY KEY DEFAULT 1,
            strength INTEGER NOT NULL DEFAULT 0,
            power INTEGER NOT NULL DEFAULT 0,
            stamina INTEGER NOT NULL DEFAULT 0,
            consistency INTEGER NOT NULL DEFAULT 0,
            mastery INTEGER NOT NULL DEFAULT 0,
            characterClass TEXT NOT NULL DEFAULT 'Phoenix',
            lastComputed INTEGER NOT NULL DEFAULT 0
        )""",
    )

    // Migration 18: NOOP (columns healed outside numbered migrations)
    18 -> emptyList()

    // Migration 19: Phase-specific PR tracking
    19 -> listOf(
        "ALTER TABLE PersonalRecord ADD COLUMN phase TEXT NOT NULL DEFAULT 'COMBINED'",
        "DROP INDEX IF EXISTS idx_pr_unique",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType, phase)",
    )

    // Migration 20: Performance index on WorkoutSession.timestamp
    20 -> listOf(
        "CREATE INDEX IF NOT EXISTS idx_workout_session_timestamp ON WorkoutSession(timestamp)",
    )

    // Migration 21: profile_id indexes + PR unique index update
    21 -> listOf(
        "CREATE INDEX IF NOT EXISTS idx_session_profile ON WorkoutSession(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_pr_profile ON PersonalRecord(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_routine_profile ON Routine(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_cycle_profile ON TrainingCycle(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_assessment_profile ON AssessmentResult(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_progression_profile ON ProgressionEvent(profile_id)",
        "DROP INDEX IF EXISTS idx_pr_unique",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType, phase, profile_id)",
    )

    // Migration 22: Gamification table profile_id indexes
    22 -> listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_earned_badge_profile ON EarnedBadge(badgeId, profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_streak_history_profile ON StreakHistory(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_rpg_attributes_profile ON RpgAttributes(profile_id)",
        "CREATE INDEX IF NOT EXISTS idx_gamification_stats_profile ON GamificationStats(profile_id)",
    )

    // Migration 23: ExternalActivity + IntegrationStatus tables for third-party integrations
    23 -> listOf(
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
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_activity_dedup ON ExternalActivity(externalId, provider)",
        "CREATE INDEX IF NOT EXISTS idx_external_activity_profile ON ExternalActivity(profileId)",
        "CREATE INDEX IF NOT EXISTS idx_external_activity_provider ON ExternalActivity(provider)",
        "CREATE INDEX IF NOT EXISTS idx_external_activity_started ON ExternalActivity(startedAt DESC)",
        """CREATE TABLE IF NOT EXISTS IntegrationStatus (
            provider TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'disconnected',
            lastSyncAt INTEGER,
            errorMessage TEXT,
            profileId TEXT NOT NULL DEFAULT 'default',
            PRIMARY KEY(provider, profileId)
        )""",
    )

    // Migration 24: Rebuild EarnedBadge to remove legacy inline UNIQUE(badgeId) constraint
    24 -> listOf(
        """CREATE TABLE IF NOT EXISTS EarnedBadge_rebuild (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            badgeId TEXT NOT NULL,
            earnedAt INTEGER NOT NULL,
            celebratedAt INTEGER,
            updatedAt INTEGER,
            serverId TEXT,
            deletedAt INTEGER,
            profile_id TEXT NOT NULL DEFAULT 'default'
        )""",
        """INSERT OR IGNORE INTO EarnedBadge_rebuild (id, badgeId, earnedAt, celebratedAt, updatedAt, serverId, deletedAt, profile_id)
SELECT id, badgeId, earnedAt, celebratedAt, updatedAt, serverId, deletedAt, profile_id
FROM EarnedBadge""",
        "DROP TABLE IF EXISTS EarnedBadge",
        "ALTER TABLE EarnedBadge_rebuild RENAME TO EarnedBadge",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_earned_badge_profile ON EarnedBadge(badgeId, profile_id)",
    )

    // Migration 25: Deduplicate GamificationStats rows and enforce one-row-per-profile
    25 -> listOf(
        "DROP INDEX IF EXISTS idx_gamification_stats_profile",
        """CREATE TABLE IF NOT EXISTS GamificationStats_rebuild (
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
        """INSERT INTO GamificationStats_rebuild (
            id,
            totalWorkouts,
            totalReps,
            totalVolumeKg,
            longestStreak,
            currentStreak,
            uniqueExercisesUsed,
            prsAchieved,
            lastWorkoutDate,
            streakStartDate,
            lastUpdated,
            updatedAt,
            serverId,
            profile_id
        )
SELECT
    gs.id,
    gs.totalWorkouts,
    gs.totalReps,
    gs.totalVolumeKg,
    gs.longestStreak,
    gs.currentStreak,
    gs.uniqueExercisesUsed,
    gs.prsAchieved,
    gs.lastWorkoutDate,
    gs.streakStartDate,
    gs.lastUpdated,
    gs.updatedAt,
    gs.serverId,
    gs.profile_id
FROM GamificationStats gs
WHERE gs.rowid = (
    SELECT candidate.rowid
    FROM GamificationStats candidate
    WHERE candidate.profile_id = gs.profile_id
    ORDER BY COALESCE(candidate.lastUpdated, 0) DESC, COALESCE(candidate.updatedAt, 0) DESC, candidate.id DESC
    LIMIT 1
)""",
        "DROP TABLE IF EXISTS GamificationStats",
        "ALTER TABLE GamificationStats_rebuild RENAME TO GamificationStats",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_gamification_stats_profile ON GamificationStats(profile_id)",
    )

    // Migration 26: SessionNotes side-table for session-level notes persistence
    26 -> listOf(
        """CREATE TABLE IF NOT EXISTS SessionNotes (
            routineSessionId TEXT NOT NULL PRIMARY KEY,
            notes TEXT,
            updatedAt INTEGER
        )""",
        "CREATE INDEX IF NOT EXISTS idx_session_notes_updated_at ON SessionNotes(updatedAt)",
    )

    // Migration 27: Routine Groups (local-only organizational buckets)
    27 -> listOf(
        """CREATE TABLE IF NOT EXISTS RoutineGroup (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            orderIndex INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL,
            profile_id TEXT NOT NULL DEFAULT 'default'
        )""",
        "CREATE INDEX IF NOT EXISTS idx_routine_group_profile ON RoutineGroup(profile_id)",
        "ALTER TABLE Routine ADD COLUMN groupId TEXT REFERENCES RoutineGroup(id) ON DELETE SET NULL",
    )

    else -> emptyList()
}
