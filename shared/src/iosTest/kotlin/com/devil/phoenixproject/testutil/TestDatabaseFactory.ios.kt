package com.devil.phoenixproject.testutil

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase

/**
 * iOS implementation of test database factory.
 * Uses in-memory SQLite via native driver for fast, isolated tests.
 */
actual fun createTestDatabase(): VitruvianDatabase {
    val driver = NativeSqliteDriver(VitruvianDatabase.Schema, ":memory:")
    return VitruvianDatabase(driver)
}
