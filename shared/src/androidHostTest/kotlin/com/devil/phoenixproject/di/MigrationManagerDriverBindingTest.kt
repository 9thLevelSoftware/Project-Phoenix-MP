package com.devil.phoenixproject.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.util.OneRepMaxCalculator
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class MigrationManagerDriverBindingTest {
    @Test
    fun domainModulePassesTheSameSqlDriverSqlDelightUsesSoStartupRepairRuns() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhoenixDatabase.Schema.create(driver)
        val database = PhoenixDatabase(driver)
        val application = koinApplication {
            allowOverride(true)
            modules(
                dataModule,
                domainModule,
                module {
                    single<PhoenixDatabase> { database }
                    single<SqlDriver> { driver }
                    single<Settings> { MapSettings() }
                },
            )
        }

        try {
            val koin = application.koin
            val resolvedDatabase = koin.get<PhoenixDatabase>()
            val resolvedDriver = koin.get<SqlDriver>()
            val manager = koin.get<MigrationManager>()

            assertSame(database, resolvedDatabase)
            assertSame(driver, resolvedDriver)

            val queries = resolvedDatabase.phoenixDatabaseQueries
            queries.insertProfile(
                id = "active-profile",
                name = "Active",
                colorIndex = 0L,
                createdAt = 1L,
                isActive = 1L,
            )
            queries.insertRecord(
                exerciseId = "deadlift",
                exerciseName = "Deadlift",
                weight = 60.0,
                reps = 5,
                oneRepMax = OneRepMaxCalculator.epley(60f, 5).toDouble(),
                achievedAt = 1L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT.name,
                volume = 300.0,
                phase = "COMBINED",
                profile_id = "deleted-profile",
                cable_count = null,
                uuid = null,
            )

            assertEquals(mapOf("deleted-profile" to 1), manager.scanForOrphanedPRRecords())

            manager.checkAndRepairOrphanedData()

            assertEquals(emptyMap(), manager.scanForOrphanedPRRecords())
            assertEquals(
                1,
                queries.selectAllRecords("active-profile").executeAsList()
                    .count { it.exerciseId == "deadlift" },
            )
        } finally {
            application.close()
        }
    }
}
