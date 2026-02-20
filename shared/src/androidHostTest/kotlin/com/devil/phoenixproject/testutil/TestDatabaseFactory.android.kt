package com.devil.phoenixproject.testutil

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase

/**
 * Android/JVM implementation of test database factory.
 * Uses in-memory SQLite via JDBC driver for fast, isolated tests.
 */
actual fun createTestDatabase(): VitruvianDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    VitruvianDatabase.Schema.create(driver)
    return VitruvianDatabase(driver)
}
