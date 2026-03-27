package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

internal enum class SchemaHealStatus {
    ADDED,
    ALREADY_PRESENT,
    TABLE_MISSING,
    FAILED,
}

internal data class SchemaHealResult(
    val status: SchemaHealStatus,
    val detail: String? = null,
)

/**
 * Heal WorkoutSession.cableCount using a replay-safe ALTER TABLE.
 *
 * We intentionally avoid PRAGMA table_info() pre-checks here. On iOS, NativeSqliteDriver
 * can serve schema reads from a different connection pool than DDL writes, which makes
 * reader-backed existence checks stale. Blind ALTER + duplicate-column handling is the
 * only cross-platform behavior that remains safe for all historical install states.
 */
internal fun ensureWorkoutSessionCableCountColumn(driver: SqlDriver): SchemaHealResult {
    return try {
        driver.execute(
            identifier = null,
            sql = "ALTER TABLE WorkoutSession ADD COLUMN cableCount INTEGER",
            parameters = 0,
        )
        SchemaHealResult(SchemaHealStatus.ADDED)
    } catch (e: Exception) {
        val message = e.message.orEmpty()
        val normalized = message.lowercase()
        when {
            normalized.contains("duplicate column") || normalized.contains("already exists") -> {
                SchemaHealResult(SchemaHealStatus.ALREADY_PRESENT, message)
            }
            normalized.contains("no such table") -> {
                SchemaHealResult(SchemaHealStatus.TABLE_MISSING, message)
            }
            else -> SchemaHealResult(SchemaHealStatus.FAILED, message)
        }
    }
}
