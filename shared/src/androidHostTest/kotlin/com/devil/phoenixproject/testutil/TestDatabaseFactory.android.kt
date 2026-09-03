package com.devil.phoenixproject.testutil

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase

/**
 * Android/JVM implementation of test database factory.
 * Uses in-memory SQLite via JDBC driver for fast, isolated tests.
 */
actual fun createTestDatabase(): PhoenixDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    PhoenixDatabase.Schema.create(driver)
    return PhoenixDatabase(driver)
}
