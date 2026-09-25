package com.devil.phoenixproject.testutil

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase

/**
 * Android/JVM implementation of test database factory.
 * Uses in-memory SQLite via JDBC driver for fast, isolated tests.
 *
 * Foreign keys are ON, as in both production drivers, so tests see the same
 * ON DELETE CASCADE / SET NULL behaviour and reject fixtures that seed orphan rows.
 */
actual fun createTestDatabase(): PhoenixDatabase = PhoenixDatabase(createTestDriver())

/** In-memory JDBC driver with the current schema and foreign keys ON. */
fun createTestDriver(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)

/**
 * Creates the schema on [driver], then turns foreign keys ON and reads the pragma back.
 * Use directly for wrapped (fault-injecting / counting) drivers.
 */
fun createTestSchema(driver: SqlDriver) {
    PhoenixDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    val enabled = driver.executeQuery(
        null,
        "PRAGMA foreign_keys",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        0,
    ).value
    check(enabled == 1L) { "Test DB foreign_keys pragma read back as $enabled, expected 1" }
}

/** Inserts a minimal Exercise row (if absent) so FK-checked children (routine exercises, PRs, ...) can reference [id]. */
fun PhoenixDatabase.seedExercise(
    id: String,
    name: String = id,
    muscleGroup: String = "Chest",
    equipment: String = "",
    isCustom: Boolean = false,
    archived: Boolean = false,
) {
    if (phoenixDatabaseQueries.selectExerciseById(id).executeAsOneOrNull() != null) return
    phoenixDatabaseQueries.insertExercise(
        id = id,
        name = name,
        displayName = null,
        description = null,
        created = 0L,
        muscleGroup = muscleGroup,
        muscleGroups = muscleGroup,
        muscles = null,
        equipment = equipment,
        movement = null,
        sidedness = null,
        grip = null,
        gripWidth = null,
        minRepRange = null,
        popularity = 0.0,
        archived = if (archived) 1L else 0L,
        isFavorite = 0L,
        isCustom = if (isCustom) 1L else 0L,
        timesPerformed = 0L,
        lastPerformed = null,
        aliases = null,
        defaultCableConfig = "DOUBLE",
        one_rep_max_kg = null,
        mvtOverrideMs = null,
        isBodyweight = null,
    )
}
