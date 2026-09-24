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
// PhoenixDatabase.sq is declared here with provenance comments tracing
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
    val beforeCreateSql: List<String> = emptyList(),
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
    fun add(result: ReconciliationResult) {
        results.add(result)
    }
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

/** Table, uniqueness and ordered plain-column list of an index. */
internal data class IndexShape(val table: String, val unique: Boolean, val columns: List<String>)

private val CREATE_INDEX_SHAPE = Regex(
    """^\s*CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?"?\w+"?\s+ON\s+"?(\w+)"?\s*\(([^()]*)\)\s*;?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val PLAIN_INDEX_COLUMN = Regex("""^"?(\w+)"?$""")

/**
 * Shape declared by a `CREATE [UNIQUE] INDEX ... ON Table(col, ...)` statement, or null when
 * the statement uses anything this check cannot compare (expressions, collations, sort order,
 * a WHERE clause). A null shape means "unverifiable", and callers must treat that as a mismatch.
 */
internal fun parseIndexShape(createSql: String): IndexShape? {
    val match = CREATE_INDEX_SHAPE.matchEntire(createSql.trim()) ?: return null
    val columns = match.groupValues[3].split(',').map { part ->
        PLAIN_INDEX_COLUMN.matchEntire(part.trim())?.groupValues?.get(1) ?: return null
    }
    if (columns.isEmpty()) return null
    return IndexShape(
        table = match.groupValues[2],
        unique = match.groupValues[1].isNotBlank(),
        columns = columns,
    )
}

/**
 * Shape of the index as it exists in the database, or null if it is absent, partial, or has an
 * expression column, a non-BINARY collation or a DESC column (none of which the canonical shape uses).
 */
internal fun liveIndexShape(driver: SqlDriver, indexName: String): IndexShape? {
    var table: String? = null
    driver.executeQuery(
        identifier = null,
        sql = "SELECT tbl_name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'",
        mapper = { cursor ->
            if (cursor.next().value) table = cursor.getString(0)
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    val tableName = table ?: return null
    // Any NULL where SQLite documents a value (or a missing row) means the PRAGMA output is not
    // what this check understands. That is "unverifiable", never a coerced default, so the caller
    // rebuilds instead of trusting it.
    var listRowFound = false
    var unique: Long? = null
    var partial: Long? = null
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA index_list(\"$tableName\")",
        mapper = { cursor ->
            // index_list columns: seq, name, unique, origin, partial
            while (cursor.next().value) {
                if (cursor.getString(1) == indexName) {
                    listRowFound = true
                    unique = cursor.getLong(2)
                    partial = cursor.getLong(4)
                }
            }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    if (!listRowFound) return null
    val isUnique = when (unique) {
        1L -> true
        0L -> false
        else -> return null
    }
    if (partial != 0L) return null
    val rows = mutableListOf<IndexXinfoRow>()
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA index_xinfo(\"$indexName\")",
        mapper = { cursor ->
            // index_xinfo columns: seqno, cid, name (null for an expression column), desc, coll, key
            while (cursor.next().value) {
                rows += IndexXinfoRow(
                    seqno = cursor.getLong(0),
                    name = cursor.getString(2),
                    desc = cursor.getLong(3),
                    collation = cursor.getString(4),
                    key = cursor.getLong(5),
                )
            }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
    val names = canonicalKeyColumnNames(rows) ?: return null
    return IndexShape(table = tableName, unique = isUnique, columns = names)
}

/** One raw `PRAGMA index_xinfo` row, with every value nullable exactly as the driver returns it. */
internal data class IndexXinfoRow(
    val seqno: Long?,
    val name: String?,
    val desc: Long?,
    val collation: String?,
    val key: Long?,
)

/**
 * Ordered key-column names when every key column is a plain, ascending, BINARY-collated
 * column; otherwise null ("not verifiably canonical, rebuild").
 *
 * Rows with key = 0 are the rowid/auxiliary columns SQLite appends and are ignored. Any NULL
 * where SQLite documents a value, an unexpected key flag, or a seqno sequence that is not
 * exactly 0..n-1 is treated as unverifiable, never coerced to a default that could sort or
 * compare as a false match. A non-BINARY collation (e.g. NOCASE) or DESC column changes
 * uniqueness or lookup semantics under the same column name, so it is not canonical either.
 */
internal fun canonicalKeyColumnNames(rows: List<IndexXinfoRow>): List<String>? {
    val keyRows = mutableListOf<IndexXinfoRow>()
    for (row in rows) {
        when (row.key) {
            1L -> keyRows += row
            0L -> Unit
            else -> return null
        }
    }
    if (keyRows.isEmpty()) return null
    val seqnos = keyRows.map { it.seqno ?: return null }
    if (seqnos.sorted() != keyRows.indices.map { it.toLong() }) return null
    return keyRows.sortedBy { it.seqno }.map { row ->
        if (row.desc != 0L || !row.collation.equals("BINARY", ignoreCase = true)) return null
        row.name ?: return null
    }
}

/**
 * True only when the live index provably has the canonical shape of [op]: same table, same
 * uniqueness, same columns in the same order, BINARY collation and ascending order, and not partial. Anything unverifiable is false,
 * so the caller falls back to the old drop-and-rebuild.
 */
internal fun indexHasCanonicalShape(driver: SqlDriver, op: SchemaIndexOperation): Boolean {
    val expected = parseIndexShape(op.createSql) ?: return false
    val live = liveIndexShape(driver, op.name) ?: return false
    return live.table.equals(expected.table, ignoreCase = true) &&
        live.unique == expected.unique &&
        live.columns.map { it.lowercase() } == expected.columns.map { it.lowercase() }
}

internal fun applyIndexCreate(driver: SqlDriver, op: SchemaIndexOperation): ReconciliationResult {
    val alreadyExists = indexExists(driver, op.name)
    if (alreadyExists && op.preDropSql == null) {
        return ReconciliationResult("index", op.name, ReconciliationStatus.ALREADY_PRESENT)
    }
    // A preDropSql op replaces a stale shape. When the index already has the canonical
    // shape there is nothing to replace: skip the dedupe + drop + rebuild that would
    // otherwise run on every database open. The check reads the live index structure
    // (not a "done once" flag), so a stale or hand-made index is still rebuilt, and a
    // missing one (upgrade, restore) is still created below.
    if (alreadyExists && indexHasCanonicalShape(driver, op)) {
        return ReconciliationResult("index", op.name, ReconciliationStatus.ALREADY_PRESENT)
    }

    // Apply any data cleanup/backfill required before index creation in the same
    // SAVEPOINT as the create so a failed index build cannot leave the repair in
    // a partially-applied state.
    val savepoint = if (op.preDropSql == null) "idx_create_${op.name}" else "idx_replace_${op.name}"
    return try {
        driver.execute(identifier = null, sql = "SAVEPOINT \"$savepoint\"", parameters = 0)
        try {
            op.beforeCreateSql.forEach { sql ->
                driver.execute(identifier = null, sql = sql, parameters = 0)
            }
            op.preDropSql?.let { dropSql ->
                driver.execute(identifier = null, sql = dropSql, parameters = 0)
            }
            driver.execute(identifier = null, sql = op.createSql, parameters = 0)
            driver.execute(identifier = null, sql = "RELEASE \"$savepoint\"", parameters = 0)
            ReconciliationResult("index", op.name, ReconciliationStatus.CREATED)
        } catch (inner: Exception) {
            // Roll back any pre-create repair plus the drop so the prior
            // constraint/data survive together, then release.
            driver.execute(identifier = null, sql = "ROLLBACK TO \"$savepoint\"", parameters = 0)
            driver.execute(identifier = null, sql = "RELEASE \"$savepoint\"", parameters = 0)
            ReconciliationResult("index", op.name, ReconciliationStatus.FAILED, inner.message)
        }
    } catch (e: Exception) {
        ReconciliationResult("index", op.name, ReconciliationStatus.FAILED, e.message)
    }
}

// ==================== ENTRY POINT ====================

internal fun applyTableDrop(driver: SqlDriver, table: String): ReconciliationResult {
    val existed = tableExists(driver, table)
    return try {
        driver.execute(identifier = null, sql = "DROP TABLE IF EXISTS $table", parameters = 0)
        if (existed) {
            ReconciliationResult("drop", table, ReconciliationStatus.CREATED, "dropped")
        } else {
            ReconciliationResult("drop", table, ReconciliationStatus.ALREADY_PRESENT)
        }
    } catch (e: Exception) {
        ReconciliationResult("drop", table, ReconciliationStatus.FAILED, e.message)
    }
}

internal fun reconcileFullSchema(driver: SqlDriver): SchemaReconciliationReport {
    val report = SchemaReconciliationReport()
    for (table in manifestDroppedTables) {
        report.add(applyTableDrop(driver, table))
    }
    for (op in manifestTables) {
        report.add(applyTableCreate(driver, op))
    }
    for (op in manifestColumns) {
        report.add(applyColumnHeal(driver, op))
    }
    for (op in manifestIndexes) {
        report.add(applyIndexCreate(driver, op))
    }
    return report
}

// ============================================================
// TASK 3: manifestTables -- 29 reconciled tables
//
// Three categories of tables that need reconciliation on every open:
//
// A) Bootstrap tables (6): Originally created by ensureGamificationTablesExist(),
//    ensureAllTablesExist(), or platform-specific DriverFactory bootstrap code.
//    Declared with BASE shape (columns added by later migrations are in manifestColumns).
//
// B) Migration-created tables (7): Created by numbered .sqm migrations. Included
//    here because branch merging can cause migration version numbers to be "already
//    applied" on a device that never actually ran the SQL, leaving the table missing.
//    CREATE TABLE IF NOT EXISTS is idempotent and safe to run on every open.
//
// C) Initial-schema tables (16): Tables defined in PhoenixDatabase.sq from the
//    initial schema. Included with their FULL current shape (all columns including
//    those added by later migrations). applyColumnHeal in manifestColumns handles
//    "duplicate column" errors gracefully, so having ALL columns is safe and ensures
//    fresh installs get the complete schema immediately.
// ============================================================

internal val manifestDroppedTables: List<String> = listOf(
    // Migration 43: streamed demo URLs must not be re-created by schema heal.
    "ExerciseVideo",
)

internal val manifestTables: List<SchemaTableOperation> = listOf(
    // UserProfile -- initial schema, full current shape
    // Columns added by later migrations: subscription fields (m5)
    SchemaTableOperation(
        table = "UserProfile",
        createSql = """
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
        """.trimIndent(),
    ),

    // UserProfilePreferences -- migration 42, device-local profile preferences.
    SchemaTableOperation(
        table = "UserProfilePreferences",
        createSql = """
            CREATE TABLE IF NOT EXISTS UserProfilePreferences (
                profile_id TEXT PRIMARY KEY NOT NULL,
                schema_version INTEGER NOT NULL DEFAULT 1,
                legacy_migration_version INTEGER NOT NULL DEFAULT 0,
                body_weight_kg REAL NOT NULL DEFAULT 0 CHECK(body_weight_kg = 0 OR body_weight_kg BETWEEN 20 AND 300),
                weight_unit TEXT NOT NULL DEFAULT 'LB' CHECK(weight_unit IN ('KG', 'LB')),
                weight_increment REAL NOT NULL DEFAULT -1 CHECK(weight_increment = -1 OR weight_increment > 0),
                core_updated_at INTEGER NOT NULL DEFAULT 0,
                core_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(core_local_generation >= 0),
                core_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(core_server_revision >= 0),
                core_dirty INTEGER NOT NULL DEFAULT 1 CHECK(core_dirty IN (0, 1)),
                equipment_rack_json TEXT NOT NULL DEFAULT '{"version":1,"items":[]}',
                rack_updated_at INTEGER NOT NULL DEFAULT 0,
                rack_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(rack_local_generation >= 0),
                rack_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(rack_server_revision >= 0),
                rack_dirty INTEGER NOT NULL DEFAULT 1 CHECK(rack_dirty IN (0, 1)),
                workout_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
                workout_updated_at INTEGER NOT NULL DEFAULT 0,
                workout_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(workout_local_generation >= 0),
                workout_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(workout_server_revision >= 0),
                workout_dirty INTEGER NOT NULL DEFAULT 1 CHECK(workout_dirty IN (0, 1)),
                led_color_scheme_id INTEGER NOT NULL DEFAULT 0 CHECK(led_color_scheme_id >= 0),
                led_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
                led_updated_at INTEGER NOT NULL DEFAULT 0,
                led_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(led_local_generation >= 0),
                led_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(led_server_revision >= 0),
                led_dirty INTEGER NOT NULL DEFAULT 1 CHECK(led_dirty IN (0, 1)),
                vbt_enabled INTEGER NOT NULL DEFAULT 1 CHECK(vbt_enabled IN (0, 1)),
                vbt_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
                vbt_updated_at INTEGER NOT NULL DEFAULT 0,
                vbt_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(vbt_local_generation >= 0),
                vbt_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(vbt_server_revision >= 0),
                vbt_dirty INTEGER NOT NULL DEFAULT 1 CHECK(vbt_dirty IN (0, 1)),
                FOREIGN KEY (profile_id) REFERENCES UserProfile(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // PendingProfileContextRecovery -- migration 42, device-local transition journal.
    SchemaTableOperation(
        table = "PendingProfileContextRecovery",
        createSql = """
            CREATE TABLE IF NOT EXISTS PendingProfileContextRecovery (
                recovery_key TEXT PRIMARY KEY NOT NULL
                    DEFAULT 'active_profile_transition'
                    CHECK(recovery_key = 'active_profile_transition'),
                prior_profile_id TEXT NOT NULL,
                created_profile_id TEXT,
                enqueued_at INTEGER NOT NULL
            )
        """.trimIndent(),
    ),

    // PendingProfileLocalCleanup -- migration 42, device-local cleanup journal.
    SchemaTableOperation(
        table = "PendingProfileLocalCleanup",
        createSql = """
            CREATE TABLE IF NOT EXISTS PendingProfileLocalCleanup (
                profile_id TEXT PRIMARY KEY NOT NULL,
                enqueued_at INTEGER NOT NULL
            )
        """.trimIndent(),
    ),

    // ActiveWorkoutRuntime -- migration 45, device-local retry recovery state.
    SchemaTableOperation(
        table = "ActiveWorkoutRuntime",
        createSql = """
            CREATE TABLE IF NOT EXISTS ActiveWorkoutRuntime (
                profile_id TEXT NOT NULL,
                routine_session_id TEXT NOT NULL,
                document_version INTEGER NOT NULL,
                runtime_json TEXT NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL,
                PRIMARY KEY (profile_id, routine_session_id)
            )
        """.trimIndent(),
    ),

    // MachineSafetyHazard -- migration 47, trainer-keyed load uncertainty.
    SchemaTableOperation(
        table = "MachineSafetyHazard",
        createSql = """
            CREATE TABLE IF NOT EXISTS MachineSafetyHazard (
                trainer_address TEXT NOT NULL PRIMARY KEY,
                generation INTEGER NOT NULL,
                document_version INTEGER NOT NULL,
                hazard_json TEXT NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL
            )
        """.trimIndent(),
    ),

    // EarnedBadge -- originally bootstrapped by ensureGamificationTablesExist()
    // Full current shape: sync fields (m11), profile_id (m22)
    SchemaTableOperation(
        table = "EarnedBadge",
        createSql = """
            CREATE TABLE IF NOT EXISTS EarnedBadge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                badgeId TEXT NOT NULL,
                earnedAt INTEGER NOT NULL,
                celebratedAt INTEGER,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )
        """.trimIndent(),
    ),

    // StreakHistory -- originally bootstrapped by ensureGamificationTablesExist()
    // Full current shape: profile_id (m22)
    SchemaTableOperation(
        table = "StreakHistory",
        createSql = """
            CREATE TABLE IF NOT EXISTS StreakHistory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                length INTEGER NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )
        """.trimIndent(),
    ),

    // GamificationStats -- originally bootstrapped by ensureGamificationTablesExist()
    // Full current shape: sync fields (m11), profile_id (m22)
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
                lastUpdated INTEGER NOT NULL,
                updatedAt INTEGER,
                serverId TEXT,
                profile_id TEXT NOT NULL DEFAULT 'default'
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
    // Full current shape: profile_id (m21)
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
                profile_id TEXT NOT NULL DEFAULT 'default',
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE,
                FOREIGN KEY (assessmentSessionId) REFERENCES WorkoutSession(id) ON DELETE SET NULL
            )
        """.trimIndent(),
    ),

    // RpgAttributes -- migration 17 (RPG attribute scores)
    // Full current shape: profile_id (m22)
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
                lastComputed INTEGER NOT NULL DEFAULT 0,
                profile_id TEXT NOT NULL DEFAULT 'default'
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
                needsSync INTEGER NOT NULL DEFAULT 1,
                deletedAt INTEGER
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

    // ExternalRoutine -- migration 31 (expanded third-party integration routines)
    SchemaTableOperation(
        table = "ExternalRoutine",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalRoutine (
                id TEXT NOT NULL PRIMARY KEY,
                externalId TEXT NOT NULL,
                provider TEXT NOT NULL,
                title TEXT NOT NULL,
                folderExternalId TEXT,
                folderName TEXT,
                updatedAt INTEGER,
                syncedAt INTEGER NOT NULL,
                rawData TEXT,
                profileId TEXT NOT NULL DEFAULT 'default',
                needsSync INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalRoutineExercise",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalRoutineExercise (
                id TEXT NOT NULL PRIMARY KEY,
                externalRoutineId TEXT NOT NULL REFERENCES ExternalRoutine(id) ON DELETE CASCADE,
                externalExerciseTemplateId TEXT,
                title TEXT NOT NULL,
                exerciseType TEXT,
                primaryMuscleGroups TEXT NOT NULL DEFAULT '',
                secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
                orderIndex INTEGER NOT NULL DEFAULT 0,
                rawData TEXT
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalRoutineSet",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalRoutineSet (
                id TEXT NOT NULL PRIMARY KEY,
                externalRoutineExerciseId TEXT NOT NULL REFERENCES ExternalRoutineExercise(id) ON DELETE CASCADE,
                setIndex INTEGER NOT NULL DEFAULT 0,
                setType TEXT,
                weightKg REAL,
                reps INTEGER,
                minReps INTEGER,
                maxReps INTEGER,
                restSeconds INTEGER,
                rpe REAL,
                durationSeconds INTEGER,
                distanceMeters REAL,
                rawData TEXT
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalRoutineFolder",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalRoutineFolder (
                id TEXT NOT NULL PRIMARY KEY,
                externalId TEXT NOT NULL,
                provider TEXT NOT NULL,
                title TEXT NOT NULL,
                folderIndex INTEGER,
                createdAt INTEGER,
                updatedAt INTEGER,
                profileId TEXT NOT NULL DEFAULT 'default',
                rawData TEXT
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalProgram",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalProgram (
                id TEXT NOT NULL PRIMARY KEY,
                externalId TEXT NOT NULL,
                provider TEXT NOT NULL,
                name TEXT NOT NULL,
                isCurrent INTEGER NOT NULL DEFAULT 0,
                scriptText TEXT,
                rawData TEXT,
                updatedAt INTEGER,
                syncedAt INTEGER NOT NULL,
                profileId TEXT NOT NULL DEFAULT 'default',
                needsSync INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalProgramStats",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalProgramStats (
                id TEXT NOT NULL PRIMARY KEY,
                externalProgramId TEXT NOT NULL REFERENCES ExternalProgram(id) ON DELETE CASCADE,
                days INTEGER,
                approximateMinutes INTEGER,
                setCount INTEGER,
                muscleGroupBreakdownJson TEXT,
                rawData TEXT,
                computedAt INTEGER
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalExerciseTemplate",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalExerciseTemplate (
                id TEXT NOT NULL PRIMARY KEY,
                externalId TEXT NOT NULL,
                provider TEXT NOT NULL,
                title TEXT NOT NULL,
                exerciseType TEXT,
                primaryMuscleGroups TEXT NOT NULL DEFAULT '',
                secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
                isCustom INTEGER NOT NULL DEFAULT 0,
                rawData TEXT,
                updatedAt INTEGER,
                profileId TEXT NOT NULL DEFAULT 'default'
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalExerciseTemplateMapping",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalExerciseTemplateMapping (
                id TEXT NOT NULL PRIMARY KEY,
                provider TEXT NOT NULL,
                externalTemplateId TEXT NOT NULL,
                localExerciseId TEXT NOT NULL,
                profileId TEXT NOT NULL DEFAULT 'default',
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                rawData TEXT
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "ExternalBodyMeasurement",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExternalBodyMeasurement (
                id TEXT NOT NULL PRIMARY KEY,
                externalId TEXT NOT NULL,
                provider TEXT NOT NULL,
                measurementType TEXT NOT NULL,
                value REAL NOT NULL,
                unit TEXT NOT NULL,
                measuredAt INTEGER NOT NULL,
                syncedAt INTEGER NOT NULL,
                rawData TEXT,
                profileId TEXT NOT NULL DEFAULT 'default'
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "IntegrationSyncCursor",
        createSql = """
            CREATE TABLE IF NOT EXISTS IntegrationSyncCursor (
                provider TEXT NOT NULL,
                profileId TEXT NOT NULL DEFAULT 'default',
                cursorType TEXT NOT NULL,
                cursorValue TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(provider, profileId, cursorType)
            )
        """.trimIndent(),
    ),

    // ── Initial-schema tables ──────────────────────────────────────────
    // Tables below are defined in PhoenixDatabase.sq from the initial schema.
    // They use the FULL current shape (all columns including migration-added ones)
    // because applyColumnHeal handles "duplicate column" errors gracefully.

    // Exercise -- initial schema, full current shape
    // Columns added by later migrations: one_rep_max_kg (m1), updatedAt/serverId/deletedAt (m11), displayName (m30), mvtOverrideMs (m37), isBodyweight (m39)
    SchemaTableOperation(
        table = "Exercise",
        createSql = """
            CREATE TABLE IF NOT EXISTS Exercise (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                displayName TEXT,
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
                deletedAt INTEGER,
                mvtOverrideMs REAL,
                isBodyweight INTEGER
            )
        """.trimIndent(),
    ),

    // ExerciseImage -- migration 43, still images for the replacement catalogue
    SchemaTableOperation(
        table = "ExerciseImage",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExerciseImage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                url TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // WorkoutSession -- initial schema, full current shape
    // Columns added by later migrations: set summary metrics (m5), sync fields (m11),
    // biomechanics summary (m15), formScore (m16), safety tracking (no migration),
    // cableCount (m13), profile_id (m21), display_multiplier (m29), rack context (m33),
    // portalOrigin (m48), local/synced_sync_generation (m49)
    SchemaTableOperation(
        table = "WorkoutSession",
        createSql = """
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
                profile_id TEXT NOT NULL DEFAULT 'default',
                display_multiplier INTEGER,
                externalAddedLoadKg REAL NOT NULL DEFAULT 0,
                counterweightKg REAL NOT NULL DEFAULT 0,
                rackItemsJson TEXT NOT NULL DEFAULT '[]',
                portalOrigin INTEGER NOT NULL DEFAULT 0 CHECK(portalOrigin IN (0, 1)),
                local_sync_generation INTEGER NOT NULL DEFAULT 1 CHECK(local_sync_generation >= 0),
                synced_sync_generation INTEGER NOT NULL DEFAULT 0
                    CHECK(synced_sync_generation >= 0 AND synced_sync_generation <= local_sync_generation)
            )
        """.trimIndent(),
    ),

    // MetricSample -- initial schema, full shape (no later migrations add columns)
    SchemaTableOperation(
        table = "MetricSample",
        createSql = """
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
        """.trimIndent(),
    ),

    // PersonalRecord -- initial schema, full current shape
    // Columns added by later migrations: sync fields (m11), phase (m19),
    // profile_id (m21), cable_count (m28), uuid (m40)
    SchemaTableOperation(
        table = "PersonalRecord",
        createSql = """
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
                phase TEXT NOT NULL DEFAULT 'COMBINED',
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default',
                cable_count INTEGER,
                uuid TEXT
            )
        """.trimIndent(),
    ),

    // Routine -- initial schema, full current shape
    // Columns added by later migrations: sync fields (m11), profile_id (m21), groupId (m27)
    SchemaTableOperation(
        table = "Routine",
        createSql = """
            CREATE TABLE IF NOT EXISTS Routine (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                lastUsed INTEGER,
                useCount INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                profile_id TEXT NOT NULL DEFAULT 'default',
                groupId TEXT REFERENCES RoutineGroup(id) ON DELETE SET NULL
            )
        """.trimIndent(),
    ),

    // RoutineExercise -- initial schema, full current shape
    // Columns added by later migrations: superset fields (m4), PR scaling (m7),
    // routine programming (m18), behavior overrides (m20), scalingBasis (m38), isBodyweight (m39),
    // drop-set offer (m46)
    SchemaTableOperation(
        table = "RoutineExercise",
        createSql = """
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
                scalingBasis TEXT,
                stallDetectionEnabled INTEGER NOT NULL DEFAULT 1,
                stopAtTop INTEGER NOT NULL DEFAULT 0,
                repCountTiming TEXT NOT NULL DEFAULT 'TOP',
                setEchoLevels TEXT NOT NULL DEFAULT '',
                warmupSets TEXT NOT NULL DEFAULT '',
                defaultRackItemIds TEXT NOT NULL DEFAULT '[]',
                rackBehaviorOverrides TEXT NOT NULL DEFAULT '{}',
                isBodyweight INTEGER,
                dropSetEnabled INTEGER NOT NULL DEFAULT 0,
                dropSetMinWeightKg REAL,
                durationSyncKnown INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE SET NULL,
                FOREIGN KEY (supersetId) REFERENCES Superset(id) ON DELETE SET NULL
            )
        """.trimIndent(),
    ),

    // Superset -- initial schema, full shape (no later migrations add columns)
    SchemaTableOperation(
        table = "Superset",
        createSql = """
            CREATE TABLE IF NOT EXISTS Superset (
                id TEXT PRIMARY KEY NOT NULL,
                routineId TEXT NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
                orderIndex INTEGER NOT NULL,
                FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // TrainingCycle -- migration 10, full current shape
    // Columns added by later migrations: profile_id (m21), deletedAt (m27),
    // template_id/week_number (m41), updatedAt (m50), server_updated_at (m52)
    SchemaTableOperation(
        table = "TrainingCycle",
        createSql = """
            CREATE TABLE IF NOT EXISTS TrainingCycle (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0,
                profile_id TEXT NOT NULL DEFAULT 'default',
                deletedAt INTEGER,
                template_id TEXT,
                week_number INTEGER NOT NULL DEFAULT 1,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                server_updated_at TEXT
            )
        """.trimIndent(),
    ),

    SchemaTableOperation(
        table = "CycleSyncState",
        createSql = """
            CREATE TABLE IF NOT EXISTS CycleSyncState (
                cycle_id TEXT PRIMARY KEY NOT NULL,
                profile_id TEXT NOT NULL,
                account_id TEXT,
                dirty_generation INTEGER NOT NULL DEFAULT 1,
                acknowledged_generation INTEGER NOT NULL DEFAULT 0,
                pending_delete_updated_at INTEGER,
                pending_delete_generation INTEGER,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),
    SchemaTableOperation(
        table = "CycleConflictDraft",
        createSql = """
            CREATE TABLE IF NOT EXISTS CycleConflictDraft (
                id TEXT PRIMARY KEY NOT NULL,
                cycle_id TEXT NOT NULL,
                original_profile_id TEXT NOT NULL,
                rejected_updated_at INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                resolution TEXT
            )
        """.trimIndent(),
    ),

    // CycleDay -- migration 10, full shape (no later migrations add columns)
    SchemaTableOperation(
        table = "CycleDay",
        createSql = """
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
    ),

    // CycleProgress -- migration 10, full shape (no later migrations add columns)
    SchemaTableOperation(
        table = "CycleProgress",
        createSql = """
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
    ),

    // CycleProgression -- migration 10, full shape (no later migrations add columns)
    SchemaTableOperation(
        table = "CycleProgression",
        createSql = """
            CREATE TABLE IF NOT EXISTS CycleProgression (
                cycle_id TEXT PRIMARY KEY NOT NULL,
                frequency_cycles INTEGER NOT NULL DEFAULT 2,
                weight_increase_percent REAL,
                echo_level_increase INTEGER NOT NULL DEFAULT 0,
                eccentric_load_increase_percent INTEGER,
                FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // PlannedSet -- migration 10, full shape (no later migrations add columns)
    SchemaTableOperation(
        table = "PlannedSet",
        createSql = """
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
    ),

    // CompletedSet -- migration 10, columns added later: set_end_reason (m44), attempt identity (m45)
    SchemaTableOperation(
        table = "CompletedSet",
        createSql = """
            CREATE TABLE IF NOT EXISTS CompletedSet (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                planned_set_id TEXT,
                routine_exercise_id TEXT,
                set_number INTEGER NOT NULL,
                set_type TEXT NOT NULL DEFAULT 'STANDARD',
                attempt_number INTEGER NOT NULL DEFAULT 1,
                actual_reps INTEGER NOT NULL,
                actual_weight_kg REAL NOT NULL,
                logged_rpe INTEGER,
                is_pr INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER NOT NULL,
                set_end_reason TEXT NOT NULL DEFAULT 'UNKNOWN',
                FOREIGN KEY (session_id) REFERENCES WorkoutSession(id) ON DELETE CASCADE,
                FOREIGN KEY (planned_set_id) REFERENCES PlannedSet(id) ON DELETE SET NULL
            )
        """.trimIndent(),
    ),

    // ProgressionEvent -- migration 10, full current shape
    // Columns added by later migrations: profile_id (m21)
    SchemaTableOperation(
        table = "ProgressionEvent",
        createSql = """
            CREATE TABLE IF NOT EXISTS ProgressionEvent (
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
            )
        """.trimIndent(),
    ),

    // SessionNotes -- introduced by migration 26.sqm (Phase 3.5).
    // Side-table for portal session-level notes keyed on routineSessionId.
    // Resolves the mobile persistence gap from audit item #2.
    SchemaTableOperation(
        table = "SessionNotes",
        createSql = """
            CREATE TABLE IF NOT EXISTS SessionNotes (
                routineSessionId TEXT NOT NULL PRIMARY KEY,
                notes TEXT,
                updatedAt INTEGER
            )
        """.trimIndent(),
    ),

    // RoutineGroup -- introduced by migration 27.sqm (Phase 39).
    // Parent grouping for daily routines. Local-only (not synced).
    SchemaTableOperation(
        table = "RoutineGroup",
        createSql = """
            CREATE TABLE IF NOT EXISTS RoutineGroup (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                orderIndex INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default'
            )
        """.trimIndent(),
    ),

    // VelocityOneRepMaxEstimate -- introduced by migration 36.sqm (issue #517).
    // Auto-computed velocity 1RM time-series. Separate from AssessmentResult (wizard)
    // and the legacy-recovery-only Exercise.one_rep_max_kg.
    SchemaTableOperation(
        table = "VelocityOneRepMaxEstimate",
        createSql = """
            CREATE TABLE IF NOT EXISTS VelocityOneRepMaxEstimate (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                estimatedPerCableKg REAL NOT NULL,
                mvtUsedMs REAL NOT NULL,
                r2 REAL NOT NULL,
                distinctLoads INTEGER NOT NULL,
                passedQualityGate INTEGER NOT NULL DEFAULT 0,
                computedAt INTEGER NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default',
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // ExerciseMvt -- introduced by migration 37.sqm (issue #517).
    // Personalized Minimum Velocity Threshold per exercise/profile.
    SchemaTableOperation(
        table = "ExerciseMvt",
        createSql = """
            CREATE TABLE IF NOT EXISTS ExerciseMvt (
                exerciseId TEXT NOT NULL,
                profile_id TEXT NOT NULL DEFAULT 'default',
                personalMvtMs REAL NOT NULL,
                sampleCount INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (exerciseId, profile_id),
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // ProfileExerciseBaseline -- migration 48, local profile-scoped training baseline.
    SchemaTableOperation(
        table = "ProfileExerciseBaseline",
        createSql = """
            CREATE TABLE IF NOT EXISTS ProfileExerciseBaseline (
                profile_id TEXT NOT NULL,
                exercise_id TEXT NOT NULL,
                one_rep_max_per_cable_kg REAL,
                updated_at INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
                PRIMARY KEY (profile_id, exercise_id),
                FOREIGN KEY (profile_id) REFERENCES UserProfile(id) ON DELETE CASCADE,
                FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
            )
        """.trimIndent(),
    ),

    // Durable startup recovery and account ownership operations -- migration 49.
    SchemaTableOperation(
        table = "AppliedDataRepair",
        createSql = """
            CREATE TABLE IF NOT EXISTS AppliedDataRepair (
                repair_key TEXT PRIMARY KEY NOT NULL,
                applied_at INTEGER NOT NULL
            )
        """.trimIndent(),
    ),
    SchemaTableOperation(
        table = "PendingProfileRecovery",
        createSql = """
            CREATE TABLE IF NOT EXISTS PendingProfileRecovery (
                recovery_id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN ('PROFILE_DATA', 'LEGACY_BASELINE')),
                source_key TEXT NOT NULL UNIQUE,
                source_profile_id TEXT,
                source_profile_name TEXT NOT NULL,
                owner_user_id TEXT,
                counts_json TEXT NOT NULL,
                discovered_at INTEGER NOT NULL,
                resolved_at INTEGER
            )
        """.trimIndent(),
    ),
    SchemaTableOperation(
        table = "OwnershipTransferOutbox",
        createSql = """
            CREATE TABLE IF NOT EXISTS OwnershipTransferOutbox (
                mutation_id TEXT PRIMARY KEY NOT NULL,
                owner_user_id TEXT NOT NULL,
                source_profile_id TEXT,
                target_profile_id TEXT NOT NULL,
                workout_session_ids_json TEXT NOT NULL,
                routine_ids_json TEXT NOT NULL,
                cycle_ids_json TEXT NOT NULL,
                personal_record_ids_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                acknowledged_at INTEGER,
                CHECK(
                    workout_session_ids_json <> '[]' OR
                    routine_ids_json <> '[]' OR
                    cycle_ids_json <> '[]' OR
                    personal_record_ids_json <> '[]'
                )
            )
        """.trimIndent(),
    ),
    SchemaTableOperation(
        table = "AppliedOwnershipEvent",
        createSql = """
            CREATE TABLE IF NOT EXISTS AppliedOwnershipEvent (
                owner_user_id TEXT NOT NULL,
                mutation_id TEXT NOT NULL,
                canonical_body_hash TEXT NOT NULL,
                applied_at INTEGER NOT NULL,
                PRIMARY KEY (owner_user_id, mutation_id)
            )
        """.trimIndent(),
    ),
    SchemaTableOperation(
        table = "LocalOwnershipClaim",
        createSql = """
            CREATE TABLE IF NOT EXISTS LocalOwnershipClaim (
                owner_user_id TEXT NOT NULL,
                entity_type TEXT NOT NULL CHECK(entity_type IN ('WORKOUT', 'ROUTINE', 'CYCLE', 'PERSONAL_RECORD')),
                entity_id TEXT NOT NULL,
                mutation_id TEXT NOT NULL,
                source_profile_id TEXT,
                target_profile_id TEXT NOT NULL,
                transferred_at INTEGER NOT NULL,
                PRIMARY KEY (owner_user_id, entity_type, entity_id)
            )
        """.trimIndent(),
    ),
    SchemaTableOperation(
        table = "WorkoutDeletion",
        createSql = """
            CREATE TABLE IF NOT EXISTS WorkoutDeletion (
                mutation_id TEXT NOT NULL PRIMARY KEY,
                owner_user_id TEXT,
                profile_id TEXT NOT NULL,
                scope TEXT NOT NULL CHECK(scope IN ('COMPONENT', 'WORKOUT')),
                portal_session_id TEXT NOT NULL,
                component_session_id TEXT,
                deleted_at INTEGER NOT NULL,
                acknowledged_at INTEGER,
                source TEXT NOT NULL CHECK(source IN ('LOCAL', 'REMOTE')),
                CHECK(
                    (scope = 'COMPONENT' AND component_session_id IS NOT NULL) OR
                    (scope = 'WORKOUT' AND component_session_id IS NULL)
                )
            )
        """.trimIndent(),
    ),

    // SyncExcludedEntity -- migration 54, account-switch upload exclusions.
    // Full shape: all columns present from creation (no later migrations add columns).
    SchemaTableOperation(
        table = "SyncExcludedEntity",
        createSql = """
            CREATE TABLE IF NOT EXISTS SyncExcludedEntity (
                portal_user_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                PRIMARY KEY (portal_user_id, entity_type, entity_id)
            )
        """.trimIndent(),
    ),
)

// ============================================================
// TASK 4: manifestColumns -- column heal operations
//
// Every column added AFTER its table's initial creation. Each entry is a
// complete ALTER TABLE ADD COLUMN statement. Comments trace provenance to
// the migration number or "No migration" for columns that were added to
// the .sq schema without a corresponding .sqm file.
// ============================================================

internal val manifestColumns: List<SchemaHealOperation> = listOf(
    // ── Exercise (7 columns) ────────────────────────────────────────────

    // Migration 1: one_rep_max_kg
    SchemaHealOperation("Exercise", "one_rep_max_kg", "ALTER TABLE Exercise ADD COLUMN one_rep_max_kg REAL DEFAULT NULL"),
    // Migration 11: sync fields
    SchemaHealOperation("Exercise", "updatedAt", "ALTER TABLE Exercise ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("Exercise", "serverId", "ALTER TABLE Exercise ADD COLUMN serverId TEXT"),
    SchemaHealOperation("Exercise", "deletedAt", "ALTER TABLE Exercise ADD COLUMN deletedAt INTEGER"),
    // Migration 30: display name for formatted exercise names
    SchemaHealOperation("Exercise", "displayName", "ALTER TABLE Exercise ADD COLUMN displayName TEXT"),
    // Migration 37: per-exercise MVT override for velocity 1RM (issue #517)
    SchemaHealOperation("Exercise", "mvtOverrideMs", "ALTER TABLE Exercise ADD COLUMN mvtOverrideMs REAL"),
    // Migration 39: explicit bodyweight classification (issue #635)
    SchemaHealOperation("Exercise", "isBodyweight", "ALTER TABLE Exercise ADD COLUMN isBodyweight INTEGER"),

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
    // Migration 29: display_multiplier for equipment-aware weight display
    SchemaHealOperation("WorkoutSession", "display_multiplier", "ALTER TABLE WorkoutSession ADD COLUMN display_multiplier INTEGER"),
    // Migration 33: local equipment rack context
    SchemaHealOperation("WorkoutSession", "externalAddedLoadKg", "ALTER TABLE WorkoutSession ADD COLUMN externalAddedLoadKg REAL NOT NULL DEFAULT 0"),
    SchemaHealOperation("WorkoutSession", "counterweightKg", "ALTER TABLE WorkoutSession ADD COLUMN counterweightKg REAL NOT NULL DEFAULT 0"),
    SchemaHealOperation("WorkoutSession", "rackItemsJson", "ALTER TABLE WorkoutSession ADD COLUMN rackItemsJson TEXT NOT NULL DEFAULT '[]'"),
    // Migration 48: conservative sync provenance; all preexisting rows are local-origin.
    SchemaHealOperation(
        "WorkoutSession",
        "portalOrigin",
        "ALTER TABLE WorkoutSession ADD COLUMN portalOrigin INTEGER NOT NULL DEFAULT 0 CHECK(portalOrigin IN (0, 1))",
    ),
    // Migration 49: local snapshot/ack generations. Legacy rows intentionally start dirty.
    SchemaHealOperation(
        "WorkoutSession",
        "local_sync_generation",
        "ALTER TABLE WorkoutSession ADD COLUMN local_sync_generation INTEGER NOT NULL DEFAULT 1 CHECK(local_sync_generation >= 0)",
    ),
    SchemaHealOperation(
        "WorkoutSession",
        "synced_sync_generation",
        "ALTER TABLE WorkoutSession ADD COLUMN synced_sync_generation INTEGER NOT NULL DEFAULT 0 CHECK(synced_sync_generation >= 0 AND synced_sync_generation <= local_sync_generation)",
    ),

    // ── PersonalRecord (7 columns) ──────────────────────────────────────

    // Migration 11: sync fields
    SchemaHealOperation("PersonalRecord", "updatedAt", "ALTER TABLE PersonalRecord ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("PersonalRecord", "serverId", "ALTER TABLE PersonalRecord ADD COLUMN serverId TEXT"),
    SchemaHealOperation("PersonalRecord", "deletedAt", "ALTER TABLE PersonalRecord ADD COLUMN deletedAt INTEGER"),
    // Migration 19: phase-specific PR tracking
    SchemaHealOperation("PersonalRecord", "phase", "ALTER TABLE PersonalRecord ADD COLUMN phase TEXT NOT NULL DEFAULT 'COMBINED'"),
    // Migration 21: multi-profile support
    SchemaHealOperation("PersonalRecord", "profile_id", "ALTER TABLE PersonalRecord ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),
    // Migration 28: cable-aware weight display
    SchemaHealOperation("PersonalRecord", "cable_count", "ALTER TABLE PersonalRecord ADD COLUMN cable_count INTEGER"),
    // Migration 40: stable UUID parity identity
    SchemaHealOperation("PersonalRecord", "uuid", "ALTER TABLE PersonalRecord ADD COLUMN uuid TEXT"),

    // ── Routine (4 columns) ─────────────────────────────────────────────

    // Migration 11: sync fields
    SchemaHealOperation("Routine", "updatedAt", "ALTER TABLE Routine ADD COLUMN updatedAt INTEGER"),
    SchemaHealOperation("Routine", "serverId", "ALTER TABLE Routine ADD COLUMN serverId TEXT"),
    SchemaHealOperation("Routine", "deletedAt", "ALTER TABLE Routine ADD COLUMN deletedAt INTEGER"),
    // Migration 21: multi-profile support
    SchemaHealOperation("Routine", "profile_id", "ALTER TABLE Routine ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),

    // ── RoutineExercise (15 columns) ────────────────────────────────────

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
    // Migration 34: equipment rack routine defaults
    SchemaHealOperation("RoutineExercise", "defaultRackItemIds", "ALTER TABLE RoutineExercise ADD COLUMN defaultRackItemIds TEXT NOT NULL DEFAULT '[]'"),
    // Migration 35: equipment rack behavior overrides
    SchemaHealOperation("RoutineExercise", "rackBehaviorOverrides", "ALTER TABLE RoutineExercise ADD COLUMN rackBehaviorOverrides TEXT NOT NULL DEFAULT '{}'"),
    // Migration 38: velocity-based 1RM scaling basis (issue #517 Phase 3)
    SchemaHealOperation("RoutineExercise", "scalingBasis", "ALTER TABLE RoutineExercise ADD COLUMN scalingBasis TEXT"),
    // Migration 39: explicit bodyweight classification (issue #635)
    SchemaHealOperation("RoutineExercise", "isBodyweight", "ALTER TABLE RoutineExercise ADD COLUMN isBodyweight INTEGER"),
    // Migration 20 (healed outside .sqm): rep detection behavior
    SchemaHealOperation("RoutineExercise", "stallDetectionEnabled", "ALTER TABLE RoutineExercise ADD COLUMN stallDetectionEnabled INTEGER NOT NULL DEFAULT 1"),
    SchemaHealOperation("RoutineExercise", "stopAtTop", "ALTER TABLE RoutineExercise ADD COLUMN stopAtTop INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("RoutineExercise", "repCountTiming", "ALTER TABLE RoutineExercise ADD COLUMN repCountTiming TEXT NOT NULL DEFAULT 'TOP'"),
    SchemaHealOperation("RoutineExercise", "dropSetEnabled", "ALTER TABLE RoutineExercise ADD COLUMN dropSetEnabled INTEGER NOT NULL DEFAULT 0"),
    SchemaHealOperation("RoutineExercise", "dropSetMinWeightKg", "ALTER TABLE RoutineExercise ADD COLUMN dropSetMinWeightKg REAL"),
    // Migration 53: tri-state duration sync upgrade marker.
    SchemaHealOperation("RoutineExercise", "durationSyncKnown", "ALTER TABLE RoutineExercise ADD COLUMN durationSyncKnown INTEGER NOT NULL DEFAULT 0"),

    // ── UserProfile (4 columns) ─────────────────────────────────────────

    // Migration 5: subscription fields
    SchemaHealOperation("UserProfile", "supabase_user_id", "ALTER TABLE UserProfile ADD COLUMN supabase_user_id TEXT"),
    SchemaHealOperation("UserProfile", "subscription_status", "ALTER TABLE UserProfile ADD COLUMN subscription_status TEXT DEFAULT 'free'"),
    SchemaHealOperation("UserProfile", "subscription_expires_at", "ALTER TABLE UserProfile ADD COLUMN subscription_expires_at INTEGER"),
    SchemaHealOperation("UserProfile", "last_auth_at", "ALTER TABLE UserProfile ADD COLUMN last_auth_at INTEGER"),

    // ── TrainingCycle (5 columns) ────────────────────────────────────────

    // Migration 21: multi-profile support
    SchemaHealOperation("TrainingCycle", "profile_id", "ALTER TABLE TrainingCycle ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'"),
    // Migration 27: soft-delete for sync tombstone propagation
    SchemaHealOperation("TrainingCycle", "deletedAt", "ALTER TABLE TrainingCycle ADD COLUMN deletedAt INTEGER"),
    // Migration 41: generated cycle template identity + persisted 5/3/1 week
    SchemaHealOperation("TrainingCycle", "template_id", "ALTER TABLE TrainingCycle ADD COLUMN template_id TEXT"),
    SchemaHealOperation("TrainingCycle", "week_number", "ALTER TABLE TrainingCycle ADD COLUMN week_number INTEGER NOT NULL DEFAULT 1"),
    // Migration 50: complete-cycle LWW clock. Migration backfills created_at.
    SchemaHealOperation("TrainingCycle", "updatedAt", "ALTER TABLE TrainingCycle ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"),
    // Migration 52: portal version of the cycle, sent back as baseUpdatedAt on push.
    SchemaHealOperation("TrainingCycle", "server_updated_at", "ALTER TABLE TrainingCycle ADD COLUMN server_updated_at TEXT"),

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

    // ── Routine (1 column, migration 27) ───────────────────────────────
    // Migration 27: routine grouping
    SchemaHealOperation("Routine", "groupId", "ALTER TABLE Routine ADD COLUMN groupId TEXT REFERENCES RoutineGroup(id) ON DELETE SET NULL"),

    // ── ExternalActivity (1 column, migration 31) ──────────────────────
    // Migration 31: provider tombstone handling
    SchemaHealOperation("ExternalActivity", "deletedAt", "ALTER TABLE ExternalActivity ADD COLUMN deletedAt INTEGER"),

    // ── CompletedSet (3 columns, migrations 43-44) ─────────────────────
    // Migration 43: set-end reason for workout history analytics (Issue #673 PR 1)
    SchemaHealOperation("CompletedSet", "set_end_reason", "ALTER TABLE CompletedSet ADD COLUMN set_end_reason TEXT NOT NULL DEFAULT 'UNKNOWN'"),
    // Migration 44: stable logical-set attempt identity (Issue #673 PR 2)
    SchemaHealOperation("CompletedSet", "routine_exercise_id", "ALTER TABLE CompletedSet ADD COLUMN routine_exercise_id TEXT"),
    SchemaHealOperation("CompletedSet", "attempt_number", "ALTER TABLE CompletedSet ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1"),
)

// ============================================================
// TASK 5: manifestIndexes -- 36 index operations
//
// Every CREATE INDEX and CREATE UNIQUE INDEX from PhoenixDatabase.sq.
// All use IF NOT EXISTS. idx_pr_unique needs preDropSql because its
// shape changed across migrations 19 and 21.
// ============================================================

internal val manifestIndexes: List<SchemaIndexOperation> = listOf(
    // ── Exercise ─────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_exercise_popularity", "CREATE INDEX IF NOT EXISTS idx_exercise_popularity ON Exercise(popularity DESC, name ASC)"),
    SchemaIndexOperation("idx_exercise_last_performed", "CREATE INDEX IF NOT EXISTS idx_exercise_last_performed ON Exercise(lastPerformed DESC)"),
    SchemaIndexOperation("idx_exercise_image_exercise", "CREATE INDEX IF NOT EXISTS idx_exercise_image_exercise ON ExerciseImage(exerciseId)"),
    SchemaIndexOperation(
        "idx_profile_exercise_baseline_exercise",
        "CREATE INDEX IF NOT EXISTS idx_profile_exercise_baseline_exercise ON ProfileExerciseBaseline(exercise_id)",
    ),
    SchemaIndexOperation(
        "idx_workout_deletion_pending",
        "CREATE INDEX IF NOT EXISTS idx_workout_deletion_pending ON WorkoutDeletion(owner_user_id, profile_id, source, acknowledged_at, deleted_at, mutation_id)",
    ),
    SchemaIndexOperation(
        "idx_workout_deletion_target",
        "CREATE INDEX IF NOT EXISTS idx_workout_deletion_target ON WorkoutDeletion(owner_user_id, portal_session_id, component_session_id, scope)",
    ),
    SchemaIndexOperation(
        "idx_local_ownership_claim_mutation",
        "CREATE INDEX IF NOT EXISTS idx_local_ownership_claim_mutation ON LocalOwnershipClaim(owner_user_id, mutation_id)",
    ),

    // ── WorkoutSession ──────────────────────────────────────────────────
    SchemaIndexOperation("idx_workout_session_timestamp", "CREATE INDEX IF NOT EXISTS idx_workout_session_timestamp ON WorkoutSession(timestamp)"),
    SchemaIndexOperation("idx_session_profile", "CREATE INDEX IF NOT EXISTS idx_session_profile ON WorkoutSession(profile_id)"),
    // Heal-only (no numbered migration): profile_id is itself a heal column, so these run
    // after manifestColumns has added it.
    SchemaIndexOperation(
        "idx_session_profile_ts",
        "CREATE INDEX IF NOT EXISTS idx_session_profile_ts ON WorkoutSession(profile_id, timestamp DESC)",
    ),
    SchemaIndexOperation(
        "idx_session_exercise",
        "CREATE INDEX IF NOT EXISTS idx_session_exercise ON WorkoutSession(exerciseId, profile_id)",
    ),
    SchemaIndexOperation(
        "idx_session_exercise_profile_ts",
        "CREATE INDEX IF NOT EXISTS idx_session_exercise_profile_ts ON WorkoutSession(exerciseId, profile_id, timestamp DESC)",
    ),
    // Migration 55.
    SchemaIndexOperation(
        "idx_session_routine_session",
        "CREATE INDEX IF NOT EXISTS idx_session_routine_session ON WorkoutSession(routineSessionId)",
    ),

    // ── MetricSample ────────────────────────────────────────────────────
    SchemaIndexOperation("idx_metric_sample_session", "CREATE INDEX IF NOT EXISTS idx_metric_sample_session ON MetricSample(sessionId)"),

    // ── PersonalRecord ──────────────────────────────────────────────────
    // idx_pr_unique changed shape: migration 19 added phase, migration 21 added profile_id.
    // preDropSql ensures we replace any stale version with the canonical shape.
    SchemaIndexOperation(
        name = "idx_pr_unique",
        createSql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType, phase, profile_id)",
        preDropSql = "DROP INDEX IF EXISTS idx_pr_unique",
        beforeCreateSql = listOf(
            """
            DELETE FROM PersonalRecord
            WHERE id IN (
                SELECT duplicate.id
                FROM PersonalRecord AS duplicate
                WHERE EXISTS (
                    SELECT 1
                    FROM PersonalRecord AS keeper
                    WHERE keeper.exerciseId = duplicate.exerciseId
                      AND keeper.workoutMode = duplicate.workoutMode
                      AND keeper.prType = duplicate.prType
                      AND keeper.phase = duplicate.phase
                      AND keeper.profile_id = duplicate.profile_id
                      AND (
                          keeper.achievedAt > duplicate.achievedAt
                          OR (keeper.achievedAt = duplicate.achievedAt AND keeper.id > duplicate.id)
                      )
                )
            )
            """.trimIndent(),
        ),
    ),
    SchemaIndexOperation("idx_pr_profile", "CREATE INDEX IF NOT EXISTS idx_pr_profile ON PersonalRecord(profile_id)"),
    SchemaIndexOperation(
        name = "idx_pr_uuid",
        createSql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_uuid ON PersonalRecord(uuid) WHERE uuid IS NOT NULL",
        beforeCreateSql = listOf(
            "DELETE FROM PersonalRecord WHERE uuid IS NULL AND workoutMode IN ('MAX_WEIGHT', 'MAX_VOLUME', '1RM')",
            """
            UPDATE PersonalRecord
            SET uuid = lower(hex(randomblob(4))) || '-' ||
                       lower(hex(randomblob(2))) || '-4' ||
                       substr(lower(hex(randomblob(2))), 2) || '-' ||
                       substr('89ab', abs(random()) % 4 + 1, 1) ||
                       substr(lower(hex(randomblob(2))), 2) || '-' ||
                       lower(hex(randomblob(6)))
            WHERE uuid IS NULL
            """.trimIndent(),
        ),
    ),

    // ── Routine ─────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_routine_profile", "CREATE INDEX IF NOT EXISTS idx_routine_profile ON Routine(profile_id)"),

    // ── Superset ────────────────────────────────────────────────────────
    SchemaIndexOperation("idx_superset_routine", "CREATE INDEX IF NOT EXISTS idx_superset_routine ON Superset(routineId)"),

    // ── RoutineExercise ─────────────────────────────────────────────────
    SchemaIndexOperation("idx_routine_exercise_routine", "CREATE INDEX IF NOT EXISTS idx_routine_exercise_routine ON RoutineExercise(routineId)"),
    SchemaIndexOperation("idx_routine_exercise_superset", "CREATE INDEX IF NOT EXISTS idx_routine_exercise_superset ON RoutineExercise(supersetId)"),
    // Migration 55.
    SchemaIndexOperation(
        "idx_routine_exercise_exercise",
        "CREATE INDEX IF NOT EXISTS idx_routine_exercise_exercise ON RoutineExercise(exerciseId)",
    ),

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
    SchemaIndexOperation(
        "idx_gamification_stats_profile",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_gamification_stats_profile ON GamificationStats(profile_id)",
        preDropSql = "DROP INDEX IF EXISTS idx_gamification_stats_profile",
        beforeCreateSql = listOf(
            """
            DELETE FROM GamificationStats
            WHERE id IN (
                SELECT duplicate.id
                FROM GamificationStats AS duplicate
                WHERE EXISTS (
                    SELECT 1
                    FROM GamificationStats AS keeper
                    WHERE keeper.profile_id = duplicate.profile_id
                      AND (
                          COALESCE(keeper.lastUpdated, 0) > COALESCE(duplicate.lastUpdated, 0)
                          OR (
                              COALESCE(keeper.lastUpdated, 0) = COALESCE(duplicate.lastUpdated, 0)
                              AND COALESCE(keeper.updatedAt, 0) > COALESCE(duplicate.updatedAt, 0)
                          )
                          OR (
                              COALESCE(keeper.lastUpdated, 0) = COALESCE(duplicate.lastUpdated, 0)
                              AND COALESCE(keeper.updatedAt, 0) = COALESCE(duplicate.updatedAt, 0)
                              AND keeper.id > duplicate.id
                          )
                      )
                )
            )
            """.trimIndent(),
        ),
    ),

    // ── RpgAttributes ───────────────────────────────────────────────────
    SchemaIndexOperation("idx_rpg_attributes_profile", "CREATE INDEX IF NOT EXISTS idx_rpg_attributes_profile ON RpgAttributes(profile_id)"),

    // ── TrainingCycle ───────────────────────────────────────────────────
    SchemaIndexOperation("idx_cycle_profile", "CREATE INDEX IF NOT EXISTS idx_cycle_profile ON TrainingCycle(profile_id)"),
    SchemaIndexOperation(
        "idx_cycle_conflict_draft_profile_cycle",
        "CREATE INDEX IF NOT EXISTS idx_cycle_conflict_draft_profile_cycle ON CycleConflictDraft(original_profile_id, cycle_id)",
    ),

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
    SchemaIndexOperation(
        name = "idx_external_activity_dedup",
        createSql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_activity_dedup ON ExternalActivity(provider, externalId, profileId)",
        preDropSql = "DROP INDEX IF EXISTS idx_external_activity_dedup",
        beforeCreateSql = listOf(
            """
            DELETE FROM ExternalActivity
            WHERE id IN (
                SELECT duplicate.id
                FROM ExternalActivity AS duplicate
                WHERE EXISTS (
                    SELECT 1
                    FROM ExternalActivity AS keeper
                    WHERE keeper.provider = duplicate.provider
                      AND keeper.externalId = duplicate.externalId
                      AND keeper.profileId = duplicate.profileId
                      AND (
                          keeper.syncedAt > duplicate.syncedAt
                          OR (
                              keeper.syncedAt = duplicate.syncedAt
                              AND keeper.startedAt > duplicate.startedAt
                          )
                          OR (
                              keeper.syncedAt = duplicate.syncedAt
                              AND keeper.startedAt = duplicate.startedAt
                              AND keeper.id > duplicate.id
                          )
                      )
                )
            )
            """.trimIndent(),
        ),
    ),
    SchemaIndexOperation("idx_external_activity_profile", "CREATE INDEX IF NOT EXISTS idx_external_activity_profile ON ExternalActivity(profileId)"),
    SchemaIndexOperation("idx_external_activity_provider", "CREATE INDEX IF NOT EXISTS idx_external_activity_provider ON ExternalActivity(provider)"),
    SchemaIndexOperation("idx_external_activity_started", "CREATE INDEX IF NOT EXISTS idx_external_activity_started ON ExternalActivity(startedAt DESC)"),

    // ── Expanded External Integration Entities ─────────────────────────
    SchemaIndexOperation("idx_external_routine_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_routine_dedup ON ExternalRoutine(provider, externalId, profileId)"),
    SchemaIndexOperation("idx_external_routine_profile_provider", "CREATE INDEX IF NOT EXISTS idx_external_routine_profile_provider ON ExternalRoutine(profileId, provider)"),
    SchemaIndexOperation("idx_external_routine_folder", "CREATE INDEX IF NOT EXISTS idx_external_routine_folder ON ExternalRoutine(profileId, provider, folderExternalId)"),
    SchemaIndexOperation("idx_external_routine_updated", "CREATE INDEX IF NOT EXISTS idx_external_routine_updated ON ExternalRoutine(updatedAt DESC)"),
    SchemaIndexOperation("idx_external_routine_exercise_routine", "CREATE INDEX IF NOT EXISTS idx_external_routine_exercise_routine ON ExternalRoutineExercise(externalRoutineId)"),
    SchemaIndexOperation("idx_external_routine_exercise_template", "CREATE INDEX IF NOT EXISTS idx_external_routine_exercise_template ON ExternalRoutineExercise(externalExerciseTemplateId)"),
    SchemaIndexOperation("idx_external_routine_set_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_routine_set_dedup ON ExternalRoutineSet(externalRoutineExerciseId, setIndex)"),
    SchemaIndexOperation("idx_external_routine_set_exercise", "CREATE INDEX IF NOT EXISTS idx_external_routine_set_exercise ON ExternalRoutineSet(externalRoutineExerciseId)"),
    SchemaIndexOperation("idx_external_routine_folder_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_routine_folder_dedup ON ExternalRoutineFolder(provider, externalId, profileId)"),
    SchemaIndexOperation("idx_external_routine_folder_profile_provider", "CREATE INDEX IF NOT EXISTS idx_external_routine_folder_profile_provider ON ExternalRoutineFolder(profileId, provider)"),
    SchemaIndexOperation("idx_external_program_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_program_dedup ON ExternalProgram(provider, externalId, profileId)"),
    SchemaIndexOperation("idx_external_program_profile_provider", "CREATE INDEX IF NOT EXISTS idx_external_program_profile_provider ON ExternalProgram(profileId, provider)"),
    SchemaIndexOperation("idx_external_program_current", "CREATE INDEX IF NOT EXISTS idx_external_program_current ON ExternalProgram(profileId, provider, isCurrent)"),
    SchemaIndexOperation("idx_external_program_updated", "CREATE INDEX IF NOT EXISTS idx_external_program_updated ON ExternalProgram(updatedAt DESC)"),
    SchemaIndexOperation("idx_external_program_stats_program", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_program_stats_program ON ExternalProgramStats(externalProgramId)"),
    SchemaIndexOperation("idx_external_program_stats_computed", "CREATE INDEX IF NOT EXISTS idx_external_program_stats_computed ON ExternalProgramStats(computedAt DESC)"),
    SchemaIndexOperation("idx_external_exercise_template_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_exercise_template_dedup ON ExternalExerciseTemplate(provider, externalId, profileId)"),
    SchemaIndexOperation("idx_external_exercise_template_profile_provider", "CREATE INDEX IF NOT EXISTS idx_external_exercise_template_profile_provider ON ExternalExerciseTemplate(profileId, provider)"),
    SchemaIndexOperation("idx_external_exercise_template_title", "CREATE INDEX IF NOT EXISTS idx_external_exercise_template_title ON ExternalExerciseTemplate(title)"),
    SchemaIndexOperation("idx_external_template_mapping_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_template_mapping_dedup ON ExternalExerciseTemplateMapping(provider, externalTemplateId, profileId)"),
    SchemaIndexOperation("idx_external_template_mapping_local", "CREATE INDEX IF NOT EXISTS idx_external_template_mapping_local ON ExternalExerciseTemplateMapping(localExerciseId)"),
    SchemaIndexOperation("idx_external_body_measurement_dedup", "CREATE UNIQUE INDEX IF NOT EXISTS idx_external_body_measurement_dedup ON ExternalBodyMeasurement(provider, externalId, profileId)"),
    SchemaIndexOperation("idx_external_body_measurement_profile_provider", "CREATE INDEX IF NOT EXISTS idx_external_body_measurement_profile_provider ON ExternalBodyMeasurement(profileId, provider)"),
    SchemaIndexOperation("idx_external_body_measurement_type_date", "CREATE INDEX IF NOT EXISTS idx_external_body_measurement_type_date ON ExternalBodyMeasurement(profileId, measurementType, measuredAt DESC)"),
    SchemaIndexOperation("idx_integration_sync_cursor_profile_provider", "CREATE INDEX IF NOT EXISTS idx_integration_sync_cursor_profile_provider ON IntegrationSyncCursor(profileId, provider)"),

    // ── SessionNotes (Phase 3.5, migration 26.sqm) ──────────────────────
    SchemaIndexOperation("idx_session_notes_updated_at", "CREATE INDEX IF NOT EXISTS idx_session_notes_updated_at ON SessionNotes(updatedAt)"),

    // ── RoutineGroup (Phase 39, migration 27.sqm) ──────────────────────
    SchemaIndexOperation("idx_routine_group_profile", "CREATE INDEX IF NOT EXISTS idx_routine_group_profile ON RoutineGroup(profile_id)"),

    // ── VelocityOneRepMaxEstimate (issue #517, migration 36.sqm) ──────
    SchemaIndexOperation("idx_velocity_1rm_exercise", "CREATE INDEX IF NOT EXISTS idx_velocity_1rm_exercise ON VelocityOneRepMaxEstimate(exerciseId, profile_id)"),
)
