package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

// ============================================================
// SchemaManifest.kt -- Single source of truth for schema reconciliation
//
// This file replaces 5 fragmented mechanisms (pre-flights, bootstrap code,
// legacy heals, platform-specific fallbacks, ensureAllTablesExist) with one
// comprehensive manifest that is COMPLETE, IDEMPOTENT, and CROSS-PLATFORM.
//
// Every table that needs guaranteed existence (both bootstrap tables and
// migration-created tables vulnerable to branch-merge gaps), every column
// added after its table's initial CREATE, and every index from
// VitruvianDatabase.sq is declared here with provenance comments tracing
// back to migration numbers.
// ============================================================

// ==================== DATA CLASSES ====================

internal data class SchemaHealOperation(val table: String, val column: String, val sql: String) {
    val target: String get() = "$table.$column"
}

internal data class SchemaTableOperation(val table: String, val createSql: String)

internal data class SchemaIndexOperation(
    val name: String,
    val createSql: String,
    val preDropSql: String? = null,
)

internal enum class ReconciliationStatus { CREATED, ALREADY_PRESENT, TABLE_MISSING, FAILED }

internal data class ReconciliationResult(
    val category: String,
    val target: String,
    val status: ReconciliationStatus,
    val detail: String? = null,
)

internal class SchemaReconciliationReport {
    private val results = mutableListOf<ReconciliationResult>()
    fun add(result: ReconciliationResult) { results.add(result) }
    val created: Int get() = results.count { it.status == ReconciliationStatus.CREATED }
    val alreadyPresent: Int get() = results.count { it.status == ReconciliationStatus.ALREADY_PRESENT }
    val tableMissing: Int get() = results.count { it.status == ReconciliationStatus.TABLE_MISSING }
    val failed: Int get() = results.count { it.status == ReconciliationStatus.FAILED }
    val hasFailures: Boolean get() = failed > 0
    val failures: List<ReconciliationResult> get() = results.filter { it.status == ReconciliationStatus.FAILED }
    val total: Int get() = results.size

    fun logSummary(): String = buildString {
        append("SchemaReconciliation: ")
        append("$total ops — ")
        append("$created created, ")
        append("$alreadyPresent already present, ")
        append("$tableMissing table missing, ")
        append("$failed failed")
        if (hasFailures) {
            append("\nFailures:")
            for (f in failures) {
                append("\n  - [${f.category}] ${f.target}: ${f.detail}")
            }
        }
    }
}

// ==================== RECONCILIATION ENGINE ====================

internal fun tableExists(driver: SqlDriver, table: String): Boolean {
    var exists = false
    driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'",
        mapper = { cursor ->
            exists = cursor.next().value
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    return exists
}

internal fun indexExists(driver: SqlDriver, indexName: String): Boolean {
    var exists = false
    driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'",
        mapper = { cursor ->
            exists = cursor.next().value
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    return exists
}

internal fun applyTableCreate(driver: SqlDriver, op: SchemaTableOperation): ReconciliationResult {
    val alreadyExists = tableExists(driver, op.table)
    return try {
        driver.execute(identifier = null, sql = op.createSql, parameters = 0)
        if (alreadyExists) {
            ReconciliationResult("table", op.table, ReconciliationStatus.ALREADY_PRESENT)
        } else {
            ReconciliationResult("table", op.table, ReconciliationStatus.CREATED)
        }
    } catch (e: Exception) {
        ReconciliationResult("table", op.table, ReconciliationStatus.FAILED, e.message)
    }
}

/**
 * Blind ALTER TABLE ADD COLUMN -- no PRAGMA pre-check.
 *
 * We intentionally skip PRAGMA table_info() existence checks because iOS
 * NativeSqliteDriver can serve reads from a different connection pool than
 * DDL writes, making reader-backed existence checks stale. Blind ALTER +
 * duplicate-column error handling is the only cross-platform safe approach.
 */
internal fun applyColumnHeal(driver: SqlDriver, op: SchemaHealOperation): ReconciliationResult = try {
    driver.execute(identifier = null, sql = op.sql, parameters = 0)
    ReconciliationResult("column", op.target, ReconciliationStatus.CREATED)
} catch (e: Exception) {
    val normalized = e.message.orEmpty().lowercase()
    when {
        normalized.contains("duplicate column") || normalized.contains("already exists") ->
            ReconciliationResult("column", op.target, ReconciliationStatus.ALREADY_PRESENT, e.message)
        normalized.contains("no such table") ->
            ReconciliationResult("column", op.target, ReconciliationStatus.TABLE_MISSING, e.message)
        else ->
            ReconciliationResult("column", op.target, ReconciliationStatus.FAILED, e.message)
    }
}

internal fun applyIndexCreate(driver: SqlDriver, op: SchemaIndexOperation): ReconciliationResult {
    return try {
        if (op.preDropSql != null) {
            driver.execute(identifier = null, sql = op.preDropSql, parameters = 0)
        }
        val alreadyExists = indexExists(driver, op.name)
        driver.execute(identifier = null, sql = op.createSql, parameters = 0)
        if (alreadyExists && op.preDropSql == null) {
            ReconciliationResult("index", op.name, ReconciliationStatus.ALREADY_PRESENT)
        } else {
            ReconciliationResult("index", op.name, ReconciliationStatus.CREATED)
        }
    } catch (e: Exception) {
        ReconciliationResult("index", op.name, ReconciliationStatus.FAILED, e.message)
    }
}

// ==================== ENTRY POINT ====================

internal fun reconcileFullSchema(driver: SqlDriver): SchemaReconciliationReport {
    val report = SchemaReconciliationReport()
    for (op in manifestTables) { report.add(applyTableCreate(driver, op)) }
    for (op in manifestColumns) { report.add(applyColumnHeal(driver, op)) }
    for (op in manifestIndexes) { report.add(applyIndexCreate(driver, op)) }
    return report
}

// ============================================================
// TASK 3: manifestTables -- 13 reconciled tables
//
// Two categories of tables that need reconciliation on every open:
//
// A) Bootstrap tables (6): Originally created by ensureGamificationTablesExist(),
//    ensureAllTablesExist(), or platform-specific DriverFactory bootstrap code.
//    Declared with BASE shape (columns added by later migrations are in manifestColumns).
//
// B) Migration-created tables (7): Created by numbered .sqm migrations. Included
//    here because branch merging can cause migration version numbers to be "already
//    applied" on a device that never actually ran the SQL, leaving the table missing.
//    CREATE TABLE IF NOT EXISTS is idempotent and safe to run on every open.
// ============================================================

internal val manifestTables: List<SchemaTableOperation> = listOf(
    // EarnedBadge -- originally bootstrapped by ensureGamificationTablesExist()
    // Base shape: id, badgeId, earnedAt, celebratedAt (no sync/profile columns)
    SchemaTableOperation(
        table = "EarnedBadge",
        createSql = """
            CREATE TABLE IF NOT EXISTS EarnedBadge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                badgeId TEXT NOT NULL,
                earnedAt INTEGER NOT NULL,
                celebratedAt INTEGER
            )
        """.trimIndent(),
    ),

    // StreakHistory -- originally bootstrapped by ensureGamificationTablesExist()
    // Base shape: id, startDate, endDate, length (no profile_id column)
    SchemaTableOperation(
        table = "StreakHistory",
        createSql = """
            CREATE TABLE IF NOT EXISTS StreakHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                length INTEGER NOT NULL
            )
        """.trimIndent(),
    ),

    // GamificationStats -- originally bootstrapped by ensureGamificationTablesExist()
    // Base shape: all stat columns + lastUpdated (no sync/profile columns)
    SchemaTableOperation(
        table = "GamificationStats",
        createSql = """
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
        """.trimIndent(),
    ),

    // ConnectionLog -- originally bootstrapped by ensureAllTablesExist()
    // Full shape: all columns present from creation (no later migrations add columns)
    SchemaTableOperation(
        table = "ConnectionLog",
        createSql = """
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
        """.trimIndent(),
    ),

    // DiagnosticsHistory -- originally bootstrapped by ensureAllTablesExist()
    // Full shape: all columns present from creation (no later migrations add columns)
    SchemaTableOperation(
        table = "DiagnosticsHistory",
        createSql = """
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
        """.trimIndent(),
    ),

    // PhaseStatistics -- originally bootstrapped by ensureAllTablesExist()
    // Full shape: all columns present from creation (no later migrations add columns)
    SchemaTableOperation(
        table = "PhaseStatistics",
        createSql = """
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
        """.trimIndent(),
    ),

    // ── Migration-created tables ────────────────────────────────────
    // Tables below were created by numbered migrations. They are included
    // here because branch merging can cause migration version numbers to
    // be "already applied" on a device that never actually ran the SQL,
    // leaving the table missing. CREATE TABLE IF NOT EXISTS is idempotent
    // and safe to run on every open.

    // RepMetric -- migration 12 (per-rep force curve data for premium analytics)
    // Full shape: no later migrations add columns
    SchemaTableOperation(
        table = "RepMetric",
        createSql = """
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
        """.trimIndent(),
    ),

    // RepBiomechanics -- migration 15 (VBT, force curve, asymmetry per rep)
    // Full shape: no later migrations add columns
    SchemaTableOperation(
        table = "RepBiomechanics",
        createSql = """
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
        """.trimIndent(),
    ),

    // ExerciseSignature -- migration 14 (movement signatures for auto-detection)
    // Full shape: no later migrations add columns
    SchemaTableOperation(
        table = "ExerciseSignature",
        createSql = """
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
        """.trimIndent(),
    ),

    // AssessmentResult -- migration 14 (VBT strength assessment results)
    // Base shape: profile_id added by migration 21 (handled by manifestColumns)
    SchemaTableOperation(
        table = "AssessmentResult",
        createSql = """
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
        """.trimIndent(),
    ),

    // RpgAttributes -- migration 17 (RPG attribute scores)
    // Base shape: profile_id added by migration 22 (handled by manifestColumns)
    SchemaTableOperation(
        table = "RpgAttributes",
        createSql = """
            CREATE TABLE IF NOT EXISTS RpgAttributes (
                id INTEGER PRIMARY KEY DEFAULT 1,
                strength INTEGER NOT NULL DEFAULT 0,
                power INTEGER NOT NULL DEFAULT 0,
                stamina INTEGER NOT NULL DEFAULT 0,
                consistency INTEGER NOT NULL DEFAULT 0,
                mastery INTEGER NOT NULL DEFAULT 0,
                characterClass TEXT NOT NULL DEFAULT 'Phoenix',
                lastComputed INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(),
    ),

    // ExternalActivity -- migration 23 (third-party integration activities)
    // Full shape: all columns present at creation
    SchemaTableOperation(
        table = "ExternalActivity",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalActivity (
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
            )
        """.trimIndent(),
    ),

    // IntegrationStatus -- migration 23 (third-party integration connection state)
    // Full shape: all columns present at creation
    SchemaTableOperation(
        table = "IntegrationStatus",
        createSql = """
            CREATE TABLE IF NOT EXISTS IntegrationStatus (
                provider TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'disconnected',
                lastSyncAt INTEGER,
                errorMessage TEXT,
                profileId TEXT NOT NULL DEFAULT 'default',
                PRIMARY KEY(provider, profileId)
            )
        """.trimIndent(),
    ),
)

// ============================================================
// TASK 4: manifestColumns -- 71 column heal operations
//
// Every column added AFTER its table's initial creation. Each entry is a
// complete ALTER TABLE ADD COLUMN statement. Comments trace provenance to
// the migration number or "No migration" for columns that were added to
// the .sq schema without a corresponding .sqm file.
// ============================================================

internal val manifestColumns: List<SchemaHealOperation> = listOf(
    // ── Exercise (4 columns) ────────────────────────────────────────────

    // Migration 1: one_rep_max_kg
    SchemaHealOperation("Exercise", "one_rep_max_kg", "ALTER TABLE Exercise ADD COLUMN one_rep_max_kg REAL DEFAULT NULL"),
    // Migration 11: sync fields
    SchemaHealOperation("Exercise", "updatedAt", "ALTER TABLE Exercise ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("Exercise", "serverId", "ALTER TABLE Exercise ADD COLUMN serverId TEXT"),
    SchemaHealOperation("Exercise", "deletedAt", "ALTER TABLE Exercise ADD COLUMN deletedAt INTEGER"),

    // ── WorkoutSession (31 columns) ─────────────────────────────────────

    // Migration 5: set summary metrics
    SchemaHealOperation("WorkoutSession", "peakForceConcentricA", "ALTER TABLE WorkoutSession ADD COLUMN peakForceConcentricA REAL"),
    SchemaHealOperation("WorkoutSession", "peakForceConcentricB", "ALTER TABLE WorkoutSession ADD COLUMN peakForceConcentricB REAL"),
    SchemaHealOperation("WorkoutSession", "peakForceEccentricA", "ALTER TABLE WorkoutSession ADD COLUMN peakForceEccentricA REAL"),
    SchemaHealOperation("WorkoutSession", "peakForceEccentricB", "ALTER TABLE WorkoutSession ADD COLUMN peakForceEccentricB REAL"),
    SchemaHealOperation("WorkoutSession", "avgForceConcentricA", "ALTER TABLE WorkoutSession ADD COLUMN avgForceConcentricA REAL"),
    SchemaHealOperation("WorkoutSession", "avgForceConcentricB", "ALTER TABLE WorkoutSession ADD COLUMN avgForceConcentricB REAL"),
    SchemaHealOperation("WorkoutSession", "avgForceEccentricA", "ALTER TABLE WorkoutSession ADD COLUMN avgForceEccentricA REAL"),
    SchemaHealOperation("WorkoutSession", "avgForceEccentricB", "ALTER TABLE WorkoutSession ADD COLUMN avgForceEccentricB REAL"),
    SchemaHealOperation("WorkoutSession", "heaviestLiftKg", "ALTER TABLE WorkoutSession ADD COLUMN heaviestLiftKg REAL"),
    SchemaHealOperation("WorkoutSession", "totalVolumeKg", "ALTER TABLE WorkoutSession ADD COLUMN totalVolumeKg REAL"),
    SchemaHealOperation("WorkoutSession", "estimatedCalories", "ALTER TABLE WorkoutSession ADD COLUMN estimatedCalories REAL"),
    SchemaHealOperation("WorkoutSession", "warmupAvgWeightKg", "ALTER TABLE WorkoutSession ADD COLUMN warmupAvgWeightKg REAL"),
    SchemaHealOperation("WorkoutSession", "workingAvgWeightKg", "ALTER TABLE WorkoutSession ADD COLUMN workingAvgWeightKg REAL"),
    SchemaHealOperation("WorkoutSession", "burnoutAvgWeightKg", "ALTER TABLE WorkoutSession ADD COLUMN burnoutAvgWeightKg REAL"),
    SchemaHealOperation("WorkoutSession", "peakWeightKg", "ALTER TABLE WorkoutSession ADD COLUMN peakWeightKg REAL"),
    SchemaHealOperation("WorkoutSession", "rpe", "ALTER TABLE WorkoutSession ADD COLUMN rpe INTEGER"),
    // Migration 11: sync fields
    SchemaHealOperation("WorkoutSession", "updatedAt", "ALTER TABLE WorkoutSession ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("WorkoutSession", "serverId", "ALTER TABLE WorkoutSession ADD COLUMN serverId TEXT"),
    SchemaHealOperation("WorkoutSession", "deletedAt", "ALTER TABLE WorkoutSession ADD COLUMN deletedAt INTEGER"),
    // Migration 15: biomechanics summary
    SchemaHealOperation("WorkoutSession", "avgMcvMmS", "ALTER TABLE WorkoutSession ADD COLUMN avgMcvMmS REAL"),
    SchemaHealOperation("WorkoutSession", "avgAsymmetryPercent", "ALTER TABLE WorkoutSession ADD COLUMN avgAsymmetryPercent REAL"),
    SchemaHealOperation("WorkoutSession", "totalVelocityLossPercent", "ALTER TABLE WorkoutSession ADD COLUMN totalVelocityLossPercent REAL"),
    SchemaHealOperation("WorkoutSession", "dominantSide", "ALTER TABLE WorkoutSession ADD COLUMN dominantSide TEXT"),
    SchemaHealOperation("WorkoutSession", "strengthProfile", "ALTER TABLE WorkoutSession ADD COLUMN strengthProfile TEXT"),
    // Migration 16: form score
    SchemaHealOperation("WorkoutSession", "formScore", "ALTER TABLE WorkoutSession ADD COLUMN formScore INTEGER"),
    // No migration: safety tracking (added to .sq for "parity with parent v23", no .sqm)
    SchemaHealOperation("WorkoutSession", "safetyFlags", "ALTER TABLE WorkoutSession ADD COLUMN safetyFlags INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("WorkoutSession", "deloadWarningCount", "ALTER TABLE WorkoutSession ADD COLUMN deloadWarningCount INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("WorkoutSession", "romViolationCount", "ALTER TABLE WorkoutSession ADD COLUMN romViolationCount INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("WorkoutSession", "spotterActivations", "ALTER TABLE WorkoutSession ADD COLUMN spotterActivations INTEGER NOT NULL DEFAULT 0"),
    // Migration 13 (healed outside .sqm): cableCount
    SchemaHealOperation("WorkoutSession", "cableCount", "ALTER TABLE WorkoutSession ADD COLUMN cableCount INTEGER"),
    // Migration 21: multi-profile support
    SchemaHealOperation("WorkoutSession", "profile_id", "ALTER TABLE WorkoutSession ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── PersonalRecord (5 columns) ──────────────────────────────────────

    // Migration 11: sync fields
    SchemaHealOperation("PersonalRecord", "updatedAt", "ALTER TABLE PersonalRecord ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("PersonalRecord", "serverId", "ALTER TABLE PersonalRecord ADD COLUMN serverId TEXT"),
    SchemaHealOperation("PersonalRecord", "deletedAt", "ALTER TABLE PersonalRecord ADD COLUMN deletedAt INTEGER"),
    // Migration 19: phase-specific PR tracking
    SchemaHealOperation("PersonalRecord", "phase", "ALTER TABLE PersonalRecord ADD COLUMN phase TEXT NOT NULL DEFAULT 'COMBINED'"),
    // Migration 21: multi-profile support
    SchemaHealOperation("PersonalRecord", "profile_id", "ALTER TABLE PersonalRecord ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── Routine (4 columns) ─────────────────────────────────────────────

    // Migration 11: sync fields
    SchemaHealOperation("Routine", "updatedAt", "ALTER TABLE Routine ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("Routine", "serverId", "ALTER TABLE Routine ADD COLUMN serverId TEXT"),
    SchemaHealOperation("Routine", "deletedAt", "ALTER TABLE Routine ADD COLUMN deletedAt INTEGER"),
    // Migration 21: multi-profile support
    SchemaHealOperation("Routine", "profile_id", "ALTER TABLE Routine ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── RoutineExercise (11 columns) ────────────────────────────────────

    // Migration 4: superset container model
    SchemaHealOperation("RoutineExercise", "supersetId", "ALTER TABLE RoutineExercise ADD COLUMN supersetId TEXT"),
    SchemaHealOperation("RoutineExercise", "orderInSuperset", "ALTER TABLE RoutineExercise ADD COLUMN orderInSuperset INTEGER NOT NULL DEFAULT 0"),
    // Migration 7: PR percentage scaling
    SchemaHealOperation("RoutineExercise", "usePercentOfPR", "ALTER TABLE RoutineExercise ADD COLUMN usePercentOfPR INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("RoutineExercise", "weightPercentOfPR", "ALTER TABLE RoutineExercise ADD COLUMN weightPercentOfPR INTEGER NOT NULL DEFAULT 80"),
    SchemaHealOperation("RoutineExercise", "prTypeForScaling", "ALTER TABLE RoutineExercise ADD COLUMN prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT'"),
    SchemaHealOperation("RoutineExercise", "setWeightsPercentOfPR", "ALTER TABLE RoutineExercise ADD COLUMN setWeightsPercentOfPR TEXT"),
    // Migration 18 (healed outside .sqm): routine programming
    SchemaHealOperation("RoutineExercise", "setEchoLevels", "ALTER TABLE RoutineExercise ADD COLUMN setEchoLevels TEXT NOT NULL DEFAULT ''"),
    SchemaHealOperation("RoutineExercise", "warmupSets", "ALTER TABLE RoutineExercise ADD COLUMN warmupSets TEXT NOT NULL DEFAULT ''"),
    // Migration 20 (healed outside .sqm): per-exercise behavior overrides
    SchemaHealOperation("RoutineExercise", "stallDetectionEnabled", "ALTER TABLE RoutineExercise ADD COLUMN stallDetectionEnabled INTEGER NOT NULL DEFAULT 1"),
    SchemaHealOperation("RoutineExercise", "stopAtTop", "ALTER TABLE RoutineExercise ADD COLUMN stopAtTop INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("RoutineExercise", "repCountTiming", "ALTER TABLE RoutineExercise ADD COLUMN repCountTiming TEXT NOT NULL DEFAULT 'TOP'"),

    // ── UserProfile (4 columns) ─────────────────────────────────────────

    // Migration 5: subscription fields
    SchemaHealOperation("UserProfile", "supabase_user_id", "ALTER TABLE UserProfile ADD COLUMN supabase_user_id TEXT"),
    SchemaHealOperation("UserProfile", "subscription_status", "ALTER TABLE UserProfile ADD COLUMN subscription_status TEXT DEFAULT 'free'"),
    SchemaHealOperation("UserProfile", "subscription_expires_at", "ALTER TABLE UserProfile ADD COLUMN subscription_expires_at INTEGER"),
    SchemaHealOperation("UserProfile", "last_auth_at", "ALTER TABLE UserProfile ADD COLUMN last_auth_at INTEGER"),

    // ── TrainingCycle (1 column) ────────────────────────────────────────

    // Migration 21: multi-profile support
    SchemaHealOperation("TrainingCycle", "profile_id", "ALTER TABLE TrainingCycle ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── AssessmentResult (1 column) ─────────────────────────────────────

    // Migration 21: multi-profile support
    SchemaHealOperation("AssessmentResult", "profile_id", "ALTER TABLE AssessmentResult ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── ProgressionEvent (1 column) ─────────────────────────────────────

    // Migration 21: multi-profile support
    SchemaHealOperation("ProgressionEvent", "profile_id", "ALTER TABLE ProgressionEvent ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── EarnedBadge (4 columns) ─────────────────────────────────────────

    // Migration 11 (preflight): sync fields
    SchemaHealOperation("EarnedBadge", "updatedAt", "ALTER TABLE EarnedBadge ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("EarnedBadge", "serverId", "ALTER TABLE EarnedBadge ADD COLUMN serverId TEXT"),
    SchemaHealOperation("EarnedBadge", "deletedAt", "ALTER TABLE EarnedBadge ADD COLUMN deletedAt INTEGER"),
    // Migration 22: multi-profile support
    SchemaHealOperation("EarnedBadge", "profile_id", "ALTER TABLE EarnedBadge ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── StreakHistory (1 column) ────────────────────────────────────────

    // Migration 22: multi-profile support
    SchemaHealOperation("StreakHistory", "profile_id", "ALTER TABLE StreakHistory ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── GamificationStats (3 columns) ───────────────────────────────────

    // Migration 11 (preflight): sync fields
    SchemaHealOperation("GamificationStats", "updatedAt", "ALTER TABLE GamificationStats ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("GamificationStats", "serverId", "ALTER TABLE GamificationStats ADD COLUMN serverId TEXT"),
    // Migration 22: multi-profile support
    SchemaHealOperation("GamificationStats", "profile_id", "ALTER TABLE GamificationStats ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── RpgAttributes (1 column) ────────────────────────────────────────

    // Migration 22: multi-profile support
    SchemaHealOperation("RpgAttributes", "profile_id", "ALTER TABLE RpgAttributes ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),
)

// ============================================================
// TASK 5: manifestIndexes -- 36 index operations
//
// Every CREATE INDEX and CREATE UNIQUE INDEX from VitruvianDatabase.sq.
// All use IF NOT EXISTS. idx_pr_unique needs preDropSql because its
// shape changed across migrations 19 and 21.
// ============================================================

internal val manifestIndexes: List<SchemaIndexOperation> = listOf(
    // ── Exercise ─────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_exercise_popularity", "CREATE INDEX IF NOT EXISTS idx_exercise_popularity ON Exercise(popularity DESC, name ASC)"),
    SchemaIndexOperation("idx_exercise_last_performed", "CREATE INDEX IF NOT EXISTS idx_exercise_last_performed ON Exercise(lastPerformed DESC)"),

    // ── WorkoutSession ──────────────────────────────────────────────────
    SchemaIndexOperation("idx_workout_session_timestamp", "CREATE INDEX IF NOT EXISTS idx_workout_session_timestamp ON WorkoutSession(timestamp)"),
    SchemaIndexOperation("idx_session_profile", "CREATE INDEX IF NOT EXISTS idx_session_profile ON WorkoutSession(profile_id)"),

    // ── MetricSample ────────────────────────────────────────────────────
    SchemaIndexOperation("idx_metric_sample_session", "CREATE INDEX IF NOT EXISTS idx_metric_sample_session ON MetricSample(sessionId)"),

    // ── PersonalRecord ──────────────────────────────────────────────────
    // idx_pr_unique changed shape: migration 19 added phase, migration 21 added profile_id.
    // preDropSql ensures we replace any stale version with the canonical shape.
    SchemaIndexOperation(
        name = "idx_pr_unique",
        createSql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType, phase, profile_id)",
        preDropSql = "DROP INDEX IF EXISTS idx_pr_unique",
    ),
    SchemaIndexOperation("idx_pr_profile", "CREATE INDEX IF NOT EXISTS idx_pr_profile ON PersonalRecord(profile_id)"),

    // ── Routine ─────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_routine_profile", "CREATE INDEX IF NOT EXISTS idx_routine_profile ON Routine(profile_id)"),

    // ── Superset ────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_superset_routine", "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)"),

    // ── RoutineExercise ─────────────────────────────────────────────────
    SchemaIndexOperation("idx_routine_exercise_routine", "CREATE INDEX IF NOT EXISTS idx_routine_exercise_routine ON RoutineExercise(routineId)"),
    SchemaIndexOperation("idx_routine_exercise_superset", "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)"),

    // ── ConnectionLog ───────────────────────────────────────────────────
    SchemaIndexOperation("idx_connection_log_timestamp", "CREATE INDEX IF NOT EXISTS idx_connection_log_timestamp ON ConnectionLog(timestamp)"),
    SchemaIndexOperation("idx_connection_log_device", "CREATE INDEX IF NOT EXISTS idx_connection_log_device ON ConnectionLog(deviceAddress)"),

    // ── DiagnosticsHistory ──────────────────────────────────────────────
    SchemaIndexOperation("idx_diagnostics_timestamp", "CREATE INDEX IF NOT EXISTS idx_diagnostics_timestamp ON DiagnosticsHistory(timestamp)"),

    // ── PhaseStatistics ─────────────────────────────────────────────────
    SchemaIndexOperation("idx_phase_stats_session", "CREATE INDEX IF NOT EXISTS idx_phase_stats_session ON PhaseStatistics(sessionId)"),

    // ── RepMetric ───────────────────────────────────────────────────────
    SchemaIndexOperation("idx_rep_metric_session", "CREATE INDEX IF NOT EXISTS idx_rep_metric_session ON RepMetric(sessionId)"),
    SchemaIndexOperation("idx_rep_metric_session_rep", "CREATE INDEX IF NOT EXISTS idx_rep_metric_session_rep ON RepMetric(sessionId, repNumber)"),

    // ── RepBiomechanics ─────────────────────────────────────────────────
    SchemaIndexOperation("idx_rep_biomechanics_session", "CREATE INDEX IF NOT EXISTS idx_rep_biomechanics_session ON RepBiomechanics(sessionId)"),
    SchemaIndexOperation("idx_rep_biomechanics_session_rep", "CREATE UNIQUE INDEX IF NOT EXISTS idx_rep_biomechanics_session_rep ON RepBiomechanics(sessionId, repNumber)"),

    // ── ExerciseSignature ───────────────────────────────────────────────
    SchemaIndexOperation("idx_exercise_signature_exercise", "CREATE INDEX IF NOT EXISTS idx_exercise_signature_exercise ON ExerciseSignature(exerciseId)"),

    // ── AssessmentResult ────────────────────────────────────────────────
    SchemaIndexOperation("idx_assessment_result_exercise", "CREATE INDEX IF NOT EXISTS idx_assessment_result_exercise ON AssessmentResult(exerciseId)"),
    SchemaIndexOperation("idx_assessment_profile", "CREATE INDEX IF NOT EXISTS idx_assessment_profile ON AssessmentResult(profile_id)"),

    // ── EarnedBadge ─────────────────────────────────────────────────────
    SchemaIndexOperation("idx_earned_badge_profile", "CREATE UNIQUE INDEX IF NOT EXISTS idx_earned_badge_profile ON EarnedBadge(badgeId, profile_id)"),

    // ── StreakHistory ────────────────────────────────────────────────────
    SchemaIndexOperation("idx_streak_history_profile", "CREATE INDEX IF NOT EXISTS idx_streak_history_profile ON StreakHistory(profile_id)"),

    // ── GamificationStats ───────────────────────────────────────────────
    SchemaIndexOperation("idx_gamification_stats_profile", "CREATE INDEX IF NOT EXISTS idx_gamification_stats_profile ON GamificationStats(profile_id)"),

    // ── RpgAttributes ───────────────────────────────────────────────────
    SchemaIndexOperation("idx_rpg_attributes_profile", "CREATE INDEX IF NOT EXISTS idx_rpg_attributes_profile ON RpgAttributes(profile_id)"),

    // ── TrainingCycle ───────────────────────────────────────────────────
    SchemaIndexOperation("idx_cycle_profile", "CREATE INDEX IF NOT EXISTS idx_cycle_profile ON TrainingCycle(profile_id)"),

    // ── CycleDay ────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_cycle_day_cycle", "CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id)"),

    // ── CycleProgress ───────────────────────────────────────────────────
    SchemaIndexOperation("idx_cycle_progress_cycle", "CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id)"),

    // ── PlannedSet ──────────────────────────────────────────────────────
    SchemaIndexOperation("idx_planned_set_exercise", "CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id)"),

    // ── CompletedSet ────────────────────────────────────────────────────
    SchemaIndexOperation("idx_completed_set_session", "CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id)"),

    // ── ProgressionEvent ────────────────────────────────────────────────
    SchemaIndexOperation("idx_progression_exercise", "CREATE INDEX IF NOT EXISTS idx_progression_exercise ON ProgressionEvent(exercise_id)"),
    SchemaIndexOperation("idx_progression_profile", "CREATE INDEX IF NOT EXISTS idx_progression_profile ON ProgressionEvent(profile_id)"),

    // ── ExternalActivity ────────────────────────────────────────────────
    SchemaIndexOperation("idx_external_activity_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_activity_dedup ON ExternalActivity(externalId, provider)"),
    SchemaIndexOperation("idx_external_activity_profile", "CREATE INDEX IF NOT EXISTS idx_external_activity_profile ON ExternalActivity(profileId)"),
    SchemaIndexOperation("idx_external_activity_provider", "CREATE INDEX IF NOT EXISTS idx_external_activity_provider ON ExternalActivity(provider)"),
    SchemaIndexOperation("idx_external_activity_started", "CREATE INDEX IF NOT EXISTS idx_external_activity_started ON ExternalActivity(startedAt DESC)"),
)
