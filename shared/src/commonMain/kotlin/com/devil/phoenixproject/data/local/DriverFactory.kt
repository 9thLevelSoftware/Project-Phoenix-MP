package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

internal data class SchemaHealOperation(
    val table: String,
    val column: String,
    val sql: String,
) {
    val target: String get() = "$table.$column"
}

internal enum class SchemaHealStatus {
    ADDED,
    ALREADY_PRESENT,
    TABLE_MISSING,
    FAILED,
}

internal data class SchemaHealResult(
    val operation: SchemaHealOperation,
    val status: SchemaHealStatus,
    val detail: String? = null,
)

/**
 * Reconcile known released schema drift using blind, replay-safe ALTER TABLE calls.
 *
 * We intentionally avoid PRAGMA table_info() pre-checks here. On iOS, NativeSqliteDriver
 * can serve schema reads from a different connection pool than DDL writes, which makes
 * reader-backed existence checks stale. Blind ALTER + duplicate-column handling is the
 * only cross-platform behavior that remains safe for all historical install states.
 *
 * Known released drift cohorts currently include:
 * - Migration 13: WorkoutSession.cableCount
 * - Migration 18: RoutineExercise.setEchoLevels / warmupSets
 * - Migration 20: RoutineExercise.stallDetectionEnabled / stopAtTop / repCountTiming
 */
private val knownLegacySchemaHeals = listOf(
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "cableCount",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN cableCount INTEGER",
    ),
    SchemaHealOperation(
        table = "RoutineExercise",
        column = "setEchoLevels",
        sql = "ALTER TABLE RoutineExercise ADD COLUMN setEchoLevels TEXT NOT NULL DEFAULT ''",
    ),
    SchemaHealOperation(
        table = "RoutineExercise",
        column = "warmupSets",
        sql = "ALTER TABLE RoutineExercise ADD COLUMN warmupSets TEXT NOT NULL DEFAULT ''",
    ),
    SchemaHealOperation(
        table = "RoutineExercise",
        column = "stallDetectionEnabled",
        sql = "ALTER TABLE RoutineExercise ADD COLUMN stallDetectionEnabled INTEGER NOT NULL DEFAULT 1",
    ),
    SchemaHealOperation(
        table = "RoutineExercise",
        column = "stopAtTop",
        sql = "ALTER TABLE RoutineExercise ADD COLUMN stopAtTop INTEGER NOT NULL DEFAULT 0",
    ),
    SchemaHealOperation(
        table = "RoutineExercise",
        column = "repCountTiming",
        sql = "ALTER TABLE RoutineExercise ADD COLUMN repCountTiming TEXT NOT NULL DEFAULT 'TOP'",
    ),
)

private fun applySchemaHeal(driver: SqlDriver, operation: SchemaHealOperation): SchemaHealResult {
    return try {
        driver.execute(
            identifier = null,
            sql = operation.sql,
            parameters = 0,
        )
        SchemaHealResult(operation, SchemaHealStatus.ADDED)
    } catch (e: Exception) {
        val message = e.message.orEmpty()
        val normalized = message.lowercase()
        when {
            normalized.contains("duplicate column") || normalized.contains("already exists") -> {
                SchemaHealResult(operation, SchemaHealStatus.ALREADY_PRESENT, message)
            }
            normalized.contains("no such table") -> {
                SchemaHealResult(operation, SchemaHealStatus.TABLE_MISSING, message)
            }
            else -> SchemaHealResult(operation, SchemaHealStatus.FAILED, message)
        }
    }
}

internal fun reconcileKnownLegacySchema(driver: SqlDriver): List<SchemaHealResult> =
    knownLegacySchemaHeals.map { applySchemaHeal(driver, it) }
