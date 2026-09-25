package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileRecoveryDiscoveryTest {
    @Test
    fun `startup discovery never guesses default rows belong to an empty active profile`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)
        val database = PhoenixDatabase(driver)
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile("default", "Default", 0L, 1L, 0L)
        queries.insertProfile("active", "Athlete", 1L, 2L, 1L)
        driver.execute(
            null,
            "INSERT INTO WorkoutSession(id,timestamp,mode,targetReps,weightPerCableKg,profile_id) VALUES (?,?,?,?,?,?)",
            6,
        ) {
            bindString(0, "component-1")
            bindLong(1, 10L)
            bindString(2, "OldSchool")
            bindLong(3, 5L)
            bindDouble(4, 20.0)
            bindString(5, "default")
        }

        ProfileRecoveryDiscovery(
            database = database,
            driver = driver,
            now = { 100L },
            newId = { "recovery-default" },
        ).discoverProfileData(
            listOf(
                UserProfile("default", "Default", 0, 1L, false),
                UserProfile("active", "Athlete", 1, 2L, true),
            ),
        )

        assertEquals("default", queries.selectSessionById("component-1").executeAsOne().profile_id)
        val pending = queries.selectPendingProfileRecoveryBySourceKey("profile:default").executeAsOne()
        assertEquals("recovery-default", pending.recovery_id)
        assertNull(pending.resolved_at)
        assertEquals(1L, decodeProfileRecoveryCounts(pending.counts_json).tableCounts["WorkoutSession"])
    }
}
