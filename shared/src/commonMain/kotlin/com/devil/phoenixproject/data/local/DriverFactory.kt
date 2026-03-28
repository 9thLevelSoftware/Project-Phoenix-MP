package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

internal data class SchemaHealOperation(val table: String, val column: String, val sql: String) {
    val target: String get() = "$table.$column"
}

internal enum class SchemaHealStatus {
    ADDED,
    ALREADY_PRESENT,
    TABLE_MISSING,
    FAILED,
}

internal data class SchemaHealResult(val operation: SchemaHealOperation, val status: SchemaHealStatus, val detail: String? = null)

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
 * - Migration 21: profile_id on profile-scoped workout / routine / assessment tables
 * - Migration 22: profile_id on gamification tables
 */
private val knownLegacySchemaHeals = listOf(
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "cableCount",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN cableCount INTEGER",
    ),
    // Safety columns were added directly to VitruvianDatabase.sq ("parity with parent v23")
    // but never given a numbered migration or schema heal. Every upgrade user is missing them.
    // SQLDelight's generated mapper expects them — queries crash without them.
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "safetyFlags",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN safetyFlags INTEGER NOT NULL DEFAULT 0",
    ),
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "deloadWarningCount",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN deloadWarningCount INTEGER NOT NULL DEFAULT 0",
    ),
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "romViolationCount",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN romViolationCount INTEGER NOT NULL DEFAULT 0",
    ),
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "spotterActivations",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN spotterActivations INTEGER NOT NULL DEFAULT 0",
    ),
    SchemaHealOperation(
        table = "WorkoutSession",
        column = "profile_id",
        sql = "ALTER TABLE WorkoutSession ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "PersonalRecord",
        column = "profile_id",
        sql = "ALTER TABLE PersonalRecord ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "Routine",
        column = "profile_id",
        sql = "ALTER TABLE Routine ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "TrainingCycle",
        column = "profile_id",
        sql = "ALTER TABLE TrainingCycle ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "AssessmentResult",
        column = "profile_id",
        sql = "ALTER TABLE AssessmentResult ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "ProgressionEvent",
        column = "profile_id",
        sql = "ALTER TABLE ProgressionEvent ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
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
    SchemaHealOperation(
        table = "EarnedBadge",
        column = "profile_id",
        sql = "ALTER TABLE EarnedBadge ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "StreakHistory",
        column = "profile_id",
        sql = "ALTER TABLE StreakHistory ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "RpgAttributes",
        column = "profile_id",
        sql = "ALTER TABLE RpgAttributes ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
    SchemaHealOperation(
        table = "GamificationStats",
        column = "profile_id",
        sql = "ALTER TABLE GamificationStats ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default'",
    ),
)

private fun applySchemaHeal(driver: SqlDriver, operation: SchemaHealOperation): SchemaHealResult = try {
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

internal fun reconcileKnownLegacySchema(driver: SqlDriver): List<SchemaHealResult> = knownLegacySchemaHeals.map { applySchemaHeal(driver, it) }
