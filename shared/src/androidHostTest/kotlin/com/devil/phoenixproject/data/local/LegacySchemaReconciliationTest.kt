package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegacySchemaReconciliationTest {

    @Test
    fun `migration 13 remains replay-safe when cableCount already exists`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
                updatedAt INTEGER,
                serverId TEXT,
                deletedAt INTEGER
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

        VitruvianDatabase.Schema.migrate(driver, 13, 14)

        assertTrue(indexExists(driver, "idx_metric_sample_session"))
        assertTrue(columnNames(driver, "WorkoutSession").contains("cableCount"))
    }

    @Test
    fun `migration 18 remains replay-safe when routine exercise programming columns already exist`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
                isAMRAP INTEGER NOT NULL DEFAULT 0,
                supersetId TEXT,
                orderInSuperset INTEGER NOT NULL DEFAULT 0,
                usePercentOfPR INTEGER NOT NULL DEFAULT 0,
                weightPercentOfPR INTEGER NOT NULL DEFAULT 80,
                prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
                setWeightsPercentOfPR TEXT,
                stallDetectionEnabled INTEGER NOT NULL DEFAULT 1,
                stopAtTop INTEGER NOT NULL DEFAULT 0,
                repCountTiming TEXT NOT NULL DEFAULT 'TOP',
                setEchoLevels TEXT NOT NULL DEFAULT '',
                warmupSets TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            0,
        )

        VitruvianDatabase.Schema.migrate(driver, 18, 19)

        val columns = columnNames(driver, "RoutineExercise")
        assertTrue(columns.contains("setEchoLevels"))
        assertTrue(columns.contains("warmupSets"))
    }

    @Test
    fun `migration 20 remains replay-safe when routine exercise behavior columns already exist`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
                isAMRAP INTEGER NOT NULL DEFAULT 0,
                supersetId TEXT,
                orderInSuperset INTEGER NOT NULL DEFAULT 0,
                usePercentOfPR INTEGER NOT NULL DEFAULT 0,
                weightPercentOfPR INTEGER NOT NULL DEFAULT 80,
                prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
                setWeightsPercentOfPR TEXT,
                stallDetectionEnabled INTEGER NOT NULL DEFAULT 1,
                stopAtTop INTEGER NOT NULL DEFAULT 0,
                repCountTiming TEXT NOT NULL DEFAULT 'TOP',
                setEchoLevels TEXT NOT NULL DEFAULT '',
                warmupSets TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE WorkoutSession (
                id TEXT NOT NULL PRIMARY KEY,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )

        VitruvianDatabase.Schema.migrate(driver, 20, 21)

        val columns = columnNames(driver, "RoutineExercise")
        assertTrue(columns.contains("stallDetectionEnabled"))
        assertTrue(columns.contains("stopAtTop"))
        assertTrue(columns.contains("repCountTiming"))
        assertTrue(indexExists(driver, "idx_workout_session_timestamp"))
    }

    @Test
    fun `legacy reconciliation heals mixed drift states idempotently`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE WorkoutSession (
                id TEXT NOT NULL PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                mode TEXT NOT NULL,
                targetReps INTEGER NOT NULL,
                weightPerCableKg REAL NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE RoutineExercise (
                id TEXT NOT NULL PRIMARY KEY,
                routineId TEXT NOT NULL,
                exerciseName TEXT NOT NULL,
                orderIndex INTEGER NOT NULL,
                weightPerCableKg REAL NOT NULL DEFAULT 0.0,
                stallDetectionEnabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
            0,
        )

        val firstPass = reconcileKnownLegacySchema(driver)
        val secondPass = reconcileKnownLegacySchema(driver)

        assertEquals(SchemaHealStatus.ADDED, healStatus(firstPass, "WorkoutSession", "cableCount"))
        assertEquals(SchemaHealStatus.ALREADY_PRESENT, healStatus(firstPass, "RoutineExercise", "stallDetectionEnabled"))
        assertEquals(SchemaHealStatus.ADDED, healStatus(firstPass, "RoutineExercise", "stopAtTop"))
        assertEquals(SchemaHealStatus.ADDED, healStatus(firstPass, "RoutineExercise", "repCountTiming"))
        assertEquals(SchemaHealStatus.ADDED, healStatus(firstPass, "RoutineExercise", "setEchoLevels"))
        assertEquals(SchemaHealStatus.ADDED, healStatus(firstPass, "RoutineExercise", "warmupSets"))

        assertFalse(secondPass.any { it.status == SchemaHealStatus.ADDED })
        assertTrue(columnNames(driver, "WorkoutSession").contains("cableCount"))
        assertTrue(columnNames(driver, "RoutineExercise").contains("stallDetectionEnabled"))
        assertTrue(columnNames(driver, "RoutineExercise").contains("stopAtTop"))
        assertTrue(columnNames(driver, "RoutineExercise").contains("repCountTiming"))
        assertTrue(columnNames(driver, "RoutineExercise").contains("setEchoLevels"))
        assertTrue(columnNames(driver, "RoutineExercise").contains("warmupSets"))
    }

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

    private fun healStatus(results: List<SchemaHealResult>, table: String, column: String): SchemaHealStatus {
        val match = results.firstOrNull {
            it.operation.table == table && it.operation.column == column
        }
        assertNotNull(match, "Missing heal result for $table.$column")
        return match.status
    }
}
