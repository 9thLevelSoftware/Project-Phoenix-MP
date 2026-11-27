package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.database.PhoenixDatabase
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog

actual class DriverFactory {
    private val coordinator = DatabaseFileMigrationCoordinator(IosDatabaseFileOperations())

    actual fun createDriver(): SqlDriver {
        val targetVersion = PhoenixDatabase.Schema.version
        NSLog("iOS DB: Initializing database (schema version $targetVersion)")

        val preparation = coordinator.prepareTarget()
        val resilientSchema = ResilientMigratingSchema(PhoenixDatabase.Schema)

        val driver = NativeSqliteDriver(
            schema = resilientSchema,
            name = DatabaseFileNames.TARGET,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = DatabaseConfiguration.Extended(
                        foreignKeyConstraints = false,
                    ),
                )
            },
        )

        val validatedDriver = try {
            // Authoritative reconciliation -- ensures ALL tables, columns, indexes exist
            val report = reconcileFullSchema(driver)
            val summary = report.logSummary()
            NSLog("iOS DB: $summary")
            if (report.hasFailures) {
                for (failure in report.failures) {
                    NSLog("iOS DB: RECONCILIATION FAILURE: ${failure.target} -- ${failure.detail?.take(120)}")
                }
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database schema could not be reconciled.",
                )
            }

            val journalMode = driver.queryText("PRAGMA journal_mode = WAL")
            if (!journalMode.equals("wal", ignoreCase = true)) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database journal mode is invalid.",
                )
            }
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)

            val schemaVersion = driver.queryLong("PRAGMA user_version")
            if (schemaVersion != targetVersion) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database schema version is invalid.",
                )
            }

            coordinator.targetValidated(preparation)
            driver
        } catch (failure: Throwable) {
            runCatching { driver.close() }
            if (failure is DatabaseFileMigrationException) throw failure
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                "The Phoenix database could not be validated after migration.",
                failure,
            )
        }

        // Backup exclusion is advisory. A Foundation/iCloud attribute failure
        // must not turn an otherwise validated database into a launch failure.
        excludeDatabaseArtifactsFromBackup()

        NSLog("iOS DB: Initialization complete")
        return validatedDriver
    }

    private fun excludeDatabaseArtifactsFromBackup() {
        val filesToExclude = listOf(
            DatabaseFileNames.TARGET,
            DatabaseFileNames.STAGING,
            DatabaseFileNames.RECOVERY,
        ).flatMap { name ->
            val path = DatabaseFileContext.databasePath(name, null)
            listOf(path, "$path-wal", "$path-shm")
        }
        val fileManager = NSFileManager.defaultManager

        for (path in filesToExclude) {
            if (!fileManager.fileExistsAtPath(path)) continue
            runBestEffortBackupExclusion(path)
        }
    }

    private fun SqlDriver.queryLong(sql: String): Long {
        var value: Long? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getLong(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return value ?: throw DatabaseFileMigrationException(
            DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
            "The Phoenix database schema version could not be read.",
        )
    }

    private fun SqlDriver.queryText(sql: String): String {
        var value: String? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return value ?: throw DatabaseFileMigrationException(
            DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
            "The Phoenix database journal mode could not be read.",
        )
    }
}

/**
 * Wraps SQLDelight's schema to apply each migration step with per-statement
 * resilient error recovery. Replaces the old SavepointMigratingSchema which
 * used nuclear recovery (database deletion) on any failure.
 *
 * On failure: catches the error, applies the step's SQL one statement at a
 * time (skipping duplicates), and continues to the next step. The post-migration
 * reconcileFullSchema() catches any remaining gaps.
 */
internal class ResilientMigratingSchema(
    private val delegate: SqlSchema<QueryResult.Value<Unit>>,
) : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long get() = delegate.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = delegate.create(driver)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        if (oldVersion >= newVersion) return QueryResult.Value(Unit)

        NSLog("iOS DB: Migrating from version $oldVersion to $newVersion")

        for (version in oldVersion until newVersion) {
            val stepTo = version + 1
            migrateStepWithPreHeal(
                driver = driver,
                fromVersion = version,
                stepTo = stepTo,
                migrateStep = {
                    delegate.migrate(driver, version, stepTo, *callbacks)
                },
                onLog = { msg -> NSLog("iOS DB: $msg") },
            )
            driver.execute(null, "PRAGMA user_version = $stepTo", 0)
            NSLog("iOS DB: Migration to version $stepTo completed")
        }

        NSLog("iOS DB: All migrations completed (now at version $newVersion)")
        return QueryResult.Value(Unit)
    }
}
