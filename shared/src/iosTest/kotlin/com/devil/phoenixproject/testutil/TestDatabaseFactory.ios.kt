package com.devil.phoenixproject.testutil

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.database.PhoenixDatabase

/**
 * iOS implementation of test database factory.
 * Uses in-memory SQLite via native driver for fast, isolated tests.
 *
 * Foreign keys are ON, as in DriverFactory.ios.kt after reconciliation. SQLiter applies
 * the extended config to every pooled connection (a PRAGMA would only reach one).
 */
actual fun createTestDatabase(): PhoenixDatabase {
    val driver = NativeSqliteDriver(
        schema = PhoenixDatabase.Schema,
        name = ":memory:",
        onConfiguration = { config ->
            config.copy(extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true))
        },
    )
    val enabled = driver.executeQuery(
        null,
        "PRAGMA foreign_keys",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        0,
    ).value
    check(enabled == 1L) { "Test DB foreign_keys pragma read back as $enabled, expected 1" }
    return PhoenixDatabase(driver)
}
