package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import org.junit.Test
import kotlin.test.fail

/**
 * SchemaParityTest -- the definitive CI safety net for schema convergence.
 *
 * Two tests prove that every path to the current schema version produces an
 * identical result:
 *
 * 1. A fresh install (Schema.create) must match an upgrade-from-v1 path
 *    (manual v1 + migrate 1->26 + reconcileFullSchema).
 *
 * 2. Every intermediate version (1..25) must upgrade cleanly to 26 with all
 *    manifest columns and indexes present after reconciliation.
 */
class SchemaParityTest {

    // ==================== TEST 1 ====================

    @Test
    fun `fresh install and full upgrade produce identical schemas`() {
        // Database A: canonical fresh install
        val freshDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VitruvianDatabase.Schema.create(freshDriver)

        // Database B: v1 base -> migrate 1..26 with resilient fallback -> reconcile
        val upgradeDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        buildMinimalV1Schema(upgradeDriver)
        migrateWithResilience(upgradeDriver, 1, CURRENT_VERSION)
        reconcileFullSchema(upgradeDriver)

        // Compare tables (filter out migration temp/rebuild artifacts)
        val freshTables = getTables(freshDriver).filterNot { isTransientTable(it) }.sorted()
        val upgradeTables = getTables(upgradeDriver).filterNot { isTransientTable(it) }.sorted()

        val missingTables = freshTables - upgradeTables.toSet()
        val extraTables = upgradeTables - freshTables.toSet()

        if (missingTables.isNotEmpty() || extraTables.isNotEmpty()) {
            fail(
                buildString {
                    append("Table mismatch between fresh install and upgrade path.\n")
                    if (missingTables.isNotEmpty()) {
                        append("  Tables in fresh but MISSING from upgrade: $missingTables\n")
                    }
                    if (extraTables.isNotEmpty()) {
                        append("  Tables in upgrade but NOT in fresh: $extraTables\n")
                    }
                },
            )
        }

        // Compare columns for every table
        val columnDiffs = mutableListOf<String>()
        for (table in freshTables) {
            val freshCols = getColumns(freshDriver, table)
            val upgradeCols = getColumns(upgradeDriver, table)

            val missingCols = freshCols.keys - upgradeCols.keys
            val extraCols = upgradeCols.keys - freshCols.keys

            if (missingCols.isNotEmpty()) {
                columnDiffs += "  $table: columns in fresh but MISSING from upgrade: $missingCols"
            }
            if (extraCols.isNotEmpty()) {
                columnDiffs += "  $table: columns in upgrade but NOT in fresh: $extraCols"
            }
        }

        if (columnDiffs.isNotEmpty()) {
            fail("Column mismatch between fresh install and upgrade path:\n${columnDiffs.joinToString("\n")}")
        }

        // Compare indexes
        val freshIndexes = getIndexes(freshDriver).sorted()
        val upgradeIndexes = getIndexes(upgradeDriver).sorted()

        val missingIndexes = freshIndexes - upgradeIndexes.toSet()
        val extraIndexes = upgradeIndexes - freshIndexes.toSet()

        if (missingIndexes.isNotEmpty() || extraIndexes.isNotEmpty()) {
            fail(
                buildString {
                    append("Index mismatch between fresh install and upgrade path.\n")
                    if (missingIndexes.isNotEmpty()) {
                        append("  Indexes in fresh but MISSING from upgrade: $missingIndexes\n")
                    }
                    if (extraIndexes.isNotEmpty()) {
                        append("  Indexes in upgrade but NOT in fresh: $extraIndexes\n")
                    }
                },
            )
        }
    }

    // ==================== TEST 2 ====================

    @Test
    fun `every intermediate version upgrades cleanly with all manifest entries`() {
        val failures = mutableListOf<String>()

        for (startVersion in 1L..CURRENT_VERSION - 1) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                buildSchemaAtVersion(driver, startVersion)
                if (startVersion < CURRENT_VERSION) {
                    migrateWithResilience(driver, startVersion, CURRENT_VERSION)
                }
                reconcileFullSchema(driver)

                // Verify all manifestColumns entries exist
                for (op in manifestColumns) {
                    if (!columnExistsInDriver(driver, op.table, op.column)) {
                        failures += "v$startVersion->v$CURRENT_VERSION: MISSING column ${op.table}.${op.column}"
                    }
                }

                // Verify all manifestIndexes entries exist
                for (op in manifestIndexes) {
                    if (!indexExistsInDriver(driver, op.name)) {
                        failures += "v$startVersion->v$CURRENT_VERSION: MISSING index ${op.name}"
                    }
                }
            } catch (e: Exception) {
                failures += "v$startVersion->v$CURRENT_VERSION: EXCEPTION ${e::class.simpleName}: ${e.message}"
            } finally {
                try { driver.close() } catch (_: Exception) {}
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    append("Schema upgrade failures (${failures.size} issues):\n")
                    for (f in failures) {
                        append("  - $f\n")
                    }
                },
            )
        }
    }

    // ==================== HELPERS ====================

    companion object {
        private const val CURRENT_VERSION = 26L

        /**
         * Transient tables are intermediate artifacts of table-rebuild migrations
         * (e.g., migration 24 creates EarnedBadge_rebuild then renames it).
         * These may survive in the upgrade path if the resilient fallback runs
         * but are never present in a fresh install.
         */
        private val TRANSIENT_SUFFIXES = listOf("_rebuild", "_new", "_temp", "_v10")
    }

    private fun isTransientTable(name: String): Boolean =
        TRANSIENT_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    /**
     * Build the original v1 schema -- the 7 tables that existed before any
     * migrations. No foreign keys on RoutineExercise (those came in migration 3).
     */
    private fun buildMinimalV1Schema(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE Exercise (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL DEFAULT 0,
                muscleGroup TEXT NOT NULL DEFAULT '',
                muscleGroups TEXT NOT NULL DEFAULT '[]',
                muscles TEXT NOT NULL DEFAULT '[]',
                equipment TEXT NOT NULL DEFAULT '',
                movement TEXT NOT NULL DEFAULT '',
                sidedness TEXT NOT NULL DEFAULT '',
                grip TEXT NOT NULL DEFAULT '',
                gripWidth TEXT NOT NULL DEFAULT '',
                minRepRange INTEGER NOT NULL DEFAULT 1,
                popularity INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                isCustom INTEGER NOT NULL DEFAULT 0,
                timesPerformed INTEGER NOT NULL DEFAULT 0,
                lastPerformed INTEGER,
                aliases TEXT NOT NULL DEFAULT '[]',
                defaultCableConfig TEXT NOT NULL DEFAULT 'DOUBLE'
            )
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TABLE ExerciseVideo (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                angle TEXT NOT NULL DEFAULT '',
                videoUrl TEXT NOT NULL DEFAULT '',
                thumbnailUrl TEXT NOT NULL DEFAULT '',
                isTutorial INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TABLE WorkoutSession (
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
                routineId TEXT
            )
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TABLE MetricSample (
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
            0,
        )

        driver.execute(
            null,
            """
            CREATE TABLE PersonalRecord (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                exerciseName TEXT NOT NULL DEFAULT '',
                weight REAL NOT NULL DEFAULT 0.0,
                reps INTEGER NOT NULL DEFAULT 0,
                oneRepMax REAL NOT NULL DEFAULT 0.0,
                achievedAt INTEGER NOT NULL DEFAULT 0,
                workoutMode TEXT NOT NULL DEFAULT '',
                prType TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
                volume REAL
            )
            """.trimIndent(),
            0,
        )

        driver.execute(
            null,
            """
            CREATE TABLE Routine (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0,
                lastUsed INTEGER,
                useCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )

        // No foreign keys in v1 RoutineExercise
        driver.execute(
            null,
            """
            CREATE TABLE RoutineExercise (
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
                isAMRAP INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )

        // Set user_version = 1
        driver.execute(null, "PRAGMA user_version = 1", 0)
    }

    /**
     * Build the schema at a specific version by starting at v1 and migrating
     * up with resilient fallback.
     */
    private fun buildSchemaAtVersion(driver: SqlDriver, targetVersion: Long) {
        buildMinimalV1Schema(driver)
        if (targetVersion > 1) {
            migrateWithResilience(driver, 1, targetVersion)
        }
    }

    /**
     * Migrate through each version step, falling back to applyMigrationResilient
     * when the standard SQLDelight migration throws (e.g. duplicate column).
     */
    private fun migrateWithResilience(driver: SqlDriver, from: Long, to: Long) {
        for (v in from until to) {
            try {
                VitruvianDatabase.Schema.migrate(driver, v, v + 1)
            } catch (_: Exception) {
                applyMigrationResilient(driver, (v + 1).toInt())
            }
        }
    }

    // ── SQLite introspection helpers ────────────────────────────────────

    private fun getTables(driver: SqlDriver): List<String> {
        val tables = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor ->
                while (cursor.next().value) {
                    tables += cursor.getString(0).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return tables
    }

    private fun getColumns(driver: SqlDriver, table: String): Map<String, String> {
        val columns = mutableMapOf<String, String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(1).orEmpty()
                    val type = cursor.getString(2).orEmpty()
                    columns[name] = type
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return columns
    }

    private fun getIndexes(driver: SqlDriver): List<String> {
        val indexes = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor ->
                while (cursor.next().value) {
                    indexes += cursor.getString(0).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return indexes
    }

    private fun columnExistsInDriver(driver: SqlDriver, table: String, column: String): Boolean {
        val columns = getColumns(driver, table)
        return columns.containsKey(column)
    }

    private fun indexExistsInDriver(driver: SqlDriver, indexName: String): Boolean {
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
}
