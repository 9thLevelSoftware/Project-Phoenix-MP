package com.devil.phoenixproject.data.migration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class VendorMigrationTest {

    @Test
    fun `migration 13 assigns phoenix defaults to legacy rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createLegacyTables(driver)

        driver.execute(null, "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg) VALUES ('s1', 1, 'Old School', 10, 20.0)", 0)
        driver.execute(null, "INSERT INTO Routine (id, name, createdAt) VALUES ('r1', 'Legacy', 1)", 0)
        driver.execute(null, "INSERT INTO ConnectionLog (timestamp, eventType, level, message) VALUES (1, 'CONNECT', 'INFO', 'ok')", 0)

        VitruvianDatabase.Schema.migrate(driver, 13, 14)

        assertEquals("phoenix", stringValue(driver, "SELECT vendorId FROM WorkoutSession WHERE id = 's1'"))
        assertEquals("v1", stringValue(driver, "SELECT protocolVersion FROM WorkoutSession WHERE id = 's1'"))
        assertEquals("phoenix", stringValue(driver, "SELECT vendorId FROM Routine WHERE id = 'r1'"))
        assertEquals("v1", stringValue(driver, "SELECT protocolVersion FROM ConnectionLog LIMIT 1"))
    }


    @Test
    fun `fixture upgrade path migrates legacy rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val fixture = checkNotNull(javaClass.classLoader?.getResource("migrations/vendor-upgrade-fixture.sql"))
            .readText()

        fixture.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }

        VitruvianDatabase.Schema.migrate(driver, 13, 14)

        assertEquals("phoenix", stringValue(driver, "SELECT vendorId FROM WorkoutSession WHERE id = 'fixture-session'"))
        assertEquals("phoenix", stringValue(driver, "SELECT vendorId FROM Routine WHERE id = 'fixture-routine'"))
    }

    private fun createLegacyTables(driver: JdbcSqliteDriver) {
        driver.execute(null, """
            CREATE TABLE WorkoutSession (
                id TEXT NOT NULL PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                mode TEXT NOT NULL,
                targetReps INTEGER NOT NULL,
                weightPerCableKg REAL NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(null, """
            CREATE TABLE Routine (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(null, """
            CREATE TABLE ConnectionLog (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                eventType TEXT NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
            )
        """.trimIndent(), 0)
    }

    private fun stringValue(driver: JdbcSqliteDriver, sql: String): String {
        return driver.executeQuery(null, sql, { cursor ->
            cursor.next()
            cursor.getString(0)!!
        }, 0)
    }
}
