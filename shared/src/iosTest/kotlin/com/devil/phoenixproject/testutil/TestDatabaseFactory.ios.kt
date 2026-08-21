package com.devil.phoenixproject.testutil

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase

/**
 * iOS implementation of test database factory.
 * Uses in-memory SQLite via native driver for fast, isolated tests.
 */
actual fun createTestDatabase(): PhoenixDatabase {
    val driver = NativeSqliteDriver(PhoenixDatabase.Schema, ":memory:")
    return PhoenixDatabase(driver)
}
