package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SchemaManifestTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun columnNames(driver: SqlDriver, table: String): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    names += cursor.getString(1).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return names
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean {
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

    private fun indexExists(driver: SqlDriver, indexName: String): Boolean {
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

    private fun indexSql(driver: SqlDriver, indexName: String): String? {
        var sql: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$indexName'",
            mapper = { cursor ->
                if (cursor.next().value) {
                    sql = cursor.getString(0)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return sql
    }

    // ── Column Heal Tests ───────────────────────────────────────────────

    @Test
    fun `applyColumnHeal adds missing column`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE TestTable (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            0,
        )

        val op = SchemaHealOperation(
            table = "TestTable",
            column = "newCol",
            sql = "ALTER TABLE TestTable ADD COLUMN newCol TEXT DEFAULT 'hello'",
        )
        val result = applyColumnHeal(driver, op)

        assertEquals(ReconciliationStatus.CREATED, result.status)
        assertTrue(columnNames(driver, "TestTable").contains("newCol"))
    }

    @Test
    fun `applyColumnHeal returns ALREADY_PRESENT for duplicate column`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE TestTable (id INTEGER PRIMARY KEY, existingCol TEXT)",
            0,
        )

        val op = SchemaHealOperation(
            table = "TestTable",
            column = "existingCol",
            sql = "ALTER TABLE TestTable ADD COLUMN existingCol TEXT",
        )
        val result = applyColumnHeal(driver, op)

        assertEquals(ReconciliationStatus.ALREADY_PRESENT, result.status)
    }

    @Test
    fun `applyColumnHeal returns TABLE_MISSING when table does not exist`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        val op = SchemaHealOperation(
            table = "NonexistentTable",
            column = "someCol",
            sql = "ALTER TABLE NonexistentTable ADD COLUMN someCol TEXT",
        )
        val result = applyColumnHeal(driver, op)

        assertEquals(ReconciliationStatus.TABLE_MISSING, result.status)
    }

    // ── Table Create Tests ──────────────────────────────────────────────

    @Test
    fun `applyTableCreate creates missing table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        val op = SchemaTableOperation(
            table = "NewTable",
            createSql = "CREATE TABLE IF NOT EXISTS NewTable (id INTEGER PRIMARY KEY, value TEXT)",
        )
        val result = applyTableCreate(driver, op)

        assertEquals(ReconciliationStatus.CREATED, result.status)
        assertTrue(tableExists(driver, "NewTable"))
    }

    @Test
    fun `applyTableCreate returns ALREADY_PRESENT for existing table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE ExistingTable (id INTEGER PRIMARY KEY)",
            0,
        )

        val op = SchemaTableOperation(
            table = "ExistingTable",
            createSql = "CREATE TABLE IF NOT EXISTS ExistingTable (id INTEGER PRIMARY KEY)",
        )
        val result = applyTableCreate(driver, op)

        assertEquals(ReconciliationStatus.ALREADY_PRESENT, result.status)
    }

    // ── Index Create Tests ──────────────────────────────────────────────

    @Test
    fun `applyIndexCreate creates missing index`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE TestTable (id INTEGER PRIMARY KEY, col1 TEXT)",
            0,
        )

        val op = SchemaIndexOperation(
            name = "idx_test_col1",
            createSql = "CREATE INDEX IF NOT EXISTS idx_test_col1 ON TestTable(col1)",
        )
        val result = applyIndexCreate(driver, op)

        assertEquals(ReconciliationStatus.CREATED, result.status)
        assertTrue(indexExists(driver, "idx_test_col1"))
    }

    @Test
    fun `applyIndexCreate with preDropSql replaces existing index`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE TestTable (id INTEGER PRIMARY KEY, col1 TEXT, col2 TEXT)",
            0,
        )
        // Create an old-shape index
        driver.execute(
            null,
            "CREATE UNIQUE INDEX idx_test_unique ON TestTable(col1)",
            0,
        )
        assertTrue(indexExists(driver, "idx_test_unique"))
        // Verify old shape does NOT include col2
        val oldSql = indexSql(driver, "idx_test_unique").orEmpty()
        assertFalse(oldSql.contains("col2"))

        // Now replace it with a new-shape index via preDropSql
        val op = SchemaIndexOperation(
            name = "idx_test_unique",
            createSql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_test_unique ON TestTable(col1, col2)",
            preDropSql = "DROP INDEX IF EXISTS idx_test_unique",
        )
        val result = applyIndexCreate(driver, op)

        assertEquals(ReconciliationStatus.CREATED, result.status)
        assertTrue(indexExists(driver, "idx_test_unique"))
        // Verify new shape includes col2
        val newSql = indexSql(driver, "idx_test_unique").orEmpty()
        assertTrue(newSql.contains("col2"))
    }

    // ── Full Reconciliation Test ────────────────────────────────────────

    @Test
    fun `reconcileFullSchema report tracks all results`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // Create the minimum set of tables that manifestColumns references
        // (tables NOT in manifestTables, i.e. they have creation migrations)
        driver.execute(null, "CREATE TABLE Exercise (id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, created INTEGER NOT NULL DEFAULT 0, muscleGroup TEXT NOT NULL, muscleGroups TEXT NOT NULL, muscles TEXT, equipment TEXT NOT NULL, movement TEXT, sidedness TEXT, grip TEXT, gripWidth TEXT, minRepRange REAL, popularity REAL NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, isFavorite INTEGER NOT NULL DEFAULT 0, isCustom INTEGER NOT NULL DEFAULT 0, timesPerformed INTEGER NOT NULL DEFAULT 0, lastPerformed INTEGER, aliases TEXT, defaultCableConfig TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE WorkoutSession (id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, mode TEXT NOT NULL, targetReps INTEGER NOT NULL, weightPerCableKg REAL NOT NULL, progressionKg REAL NOT NULL DEFAULT 0.0, duration INTEGER NOT NULL DEFAULT 0, totalReps INTEGER NOT NULL DEFAULT 0, warmupReps INTEGER NOT NULL DEFAULT 0, workingReps INTEGER NOT NULL DEFAULT 0, isJustLift INTEGER NOT NULL DEFAULT 0, stopAtTop INTEGER NOT NULL DEFAULT 0, eccentricLoad INTEGER NOT NULL DEFAULT 100, echoLevel INTEGER NOT NULL DEFAULT 1, exerciseId TEXT, exerciseName TEXT, routineSessionId TEXT, routineName TEXT, routineId TEXT)", 0)
        driver.execute(null, "CREATE TABLE PersonalRecord (id INTEGER PRIMARY KEY AUTOINCREMENT, exerciseId TEXT NOT NULL, exerciseName TEXT NOT NULL, weight REAL NOT NULL, reps INTEGER NOT NULL, oneRepMax REAL NOT NULL, achievedAt INTEGER NOT NULL, workoutMode TEXT NOT NULL, prType TEXT NOT NULL DEFAULT 'MAX_WEIGHT', volume REAL NOT NULL DEFAULT 0.0)", 0)
        driver.execute(null, "CREATE TABLE Routine (id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, lastUsed INTEGER, useCount INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE RoutineExercise (id TEXT PRIMARY KEY, routineId TEXT NOT NULL, exerciseName TEXT NOT NULL, exerciseMuscleGroup TEXT NOT NULL DEFAULT '', exerciseEquipment TEXT NOT NULL DEFAULT '', exerciseDefaultCableConfig TEXT NOT NULL DEFAULT 'DOUBLE', exerciseId TEXT, cableConfig TEXT NOT NULL DEFAULT 'DOUBLE', orderIndex INTEGER NOT NULL, setReps TEXT NOT NULL DEFAULT '10,10,10', weightPerCableKg REAL NOT NULL DEFAULT 0.0, setWeights TEXT NOT NULL DEFAULT '', mode TEXT NOT NULL DEFAULT 'OldSchool', eccentricLoad INTEGER NOT NULL DEFAULT 100, echoLevel INTEGER NOT NULL DEFAULT 1, progressionKg REAL NOT NULL DEFAULT 0.0, restSeconds INTEGER NOT NULL DEFAULT 60, duration INTEGER, setRestSeconds TEXT NOT NULL DEFAULT '[]', perSetRestTime INTEGER NOT NULL DEFAULT 0, isAMRAP INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE UserProfile (id TEXT PRIMARY KEY, name TEXT NOT NULL, colorIndex INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, isActive INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE TrainingCycle (id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, created_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE AssessmentResult (id INTEGER PRIMARY KEY AUTOINCREMENT, exerciseId TEXT NOT NULL, estimatedOneRepMaxKg REAL NOT NULL, loadVelocityData TEXT NOT NULL, assessmentSessionId TEXT, userOverrideKg REAL, createdAt INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE ProgressionEvent (id TEXT PRIMARY KEY, exercise_id TEXT NOT NULL, suggested_weight_kg REAL NOT NULL, previous_weight_kg REAL NOT NULL, reason TEXT NOT NULL, user_response TEXT, actual_weight_kg REAL, timestamp INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE RpgAttributes (id INTEGER PRIMARY KEY DEFAULT 1, strength INTEGER NOT NULL DEFAULT 0, power INTEGER NOT NULL DEFAULT 0, stamina INTEGER NOT NULL DEFAULT 0, consistency INTEGER NOT NULL DEFAULT 0, mastery INTEGER NOT NULL DEFAULT 0, characterClass TEXT NOT NULL DEFAULT 'Phoenix', lastComputed INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE MetricSample (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId TEXT NOT NULL, timestamp INTEGER NOT NULL, position REAL, positionB REAL, velocity REAL, velocityB REAL, load REAL, loadB REAL, power REAL, status INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE Superset (id TEXT PRIMARY KEY, routineId TEXT NOT NULL, name TEXT NOT NULL, colorIndex INTEGER NOT NULL DEFAULT 0, restBetweenSeconds INTEGER NOT NULL DEFAULT 10, orderIndex INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE RepMetric (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId TEXT NOT NULL, repNumber INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE RepBiomechanics (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId TEXT NOT NULL, repNumber INTEGER NOT NULL, mcvMmS REAL NOT NULL, peakVelocityMmS REAL NOT NULL, velocityZone TEXT NOT NULL, velocityLossPercent REAL, estimatedRepsRemaining INTEGER, shouldStopSet INTEGER NOT NULL DEFAULT 0, normalizedForceN TEXT NOT NULL, normalizedPositionPct TEXT NOT NULL, stickingPointPct REAL, strengthProfile TEXT NOT NULL, asymmetryPercent REAL NOT NULL, dominantSide TEXT NOT NULL, avgLoadA REAL NOT NULL, avgLoadB REAL NOT NULL, timestamp INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE ExerciseSignature (id INTEGER PRIMARY KEY AUTOINCREMENT, exerciseId TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE CycleDay (id TEXT PRIMARY KEY, cycle_id TEXT NOT NULL, day_number INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE CycleProgress (id TEXT PRIMARY KEY, cycle_id TEXT NOT NULL UNIQUE, current_day_number INTEGER NOT NULL DEFAULT 1)", 0)
        driver.execute(null, "CREATE TABLE PlannedSet (id TEXT PRIMARY KEY, routine_exercise_id TEXT NOT NULL, set_number INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE CompletedSet (id TEXT PRIMARY KEY, session_id TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE ExternalActivity (id TEXT PRIMARY KEY, externalId TEXT NOT NULL, provider TEXT NOT NULL, name TEXT NOT NULL, startedAt INTEGER NOT NULL, syncedAt INTEGER NOT NULL, profileId TEXT NOT NULL DEFAULT 'default')", 0)

        val report = reconcileFullSchema(driver)

        // Verify the report has entries for all manifest items
        val expectedTotal = manifestTables.size + manifestColumns.size + manifestIndexes.size
        assertEquals(expectedTotal, report.total)

        // No failures should occur (all prerequisite tables exist)
        assertFalse(report.hasFailures, "Unexpected failures: ${report.failures.map { "${it.target}: ${it.detail}" }}")

        // Bootstrap tables should be created (they didn't exist before)
        assertTrue(report.created > 0, "Expected at least some CREATED results")

        // Summary should be parseable
        val summary = report.logSummary()
        assertTrue(summary.contains("SchemaReconciliation:"))
        assertTrue(summary.contains("ops"))
    }
}
