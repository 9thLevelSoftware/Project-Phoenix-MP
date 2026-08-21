package com.devil.phoenixproject.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase

actual class DriverFactory(private val context: Context) {

    companion object {
        private const val TAG = "DriverFactory"
    }

    actual fun createDriver(): SqlDriver {
        val operations = AndroidDatabaseFileOperations(context)
        val coordinator = DatabaseFileMigrationCoordinator(operations)
        val preparation = coordinator.prepareTarget()
        var reconciliationReport: SchemaReconciliationReport? = null

        val driver = AndroidSqliteDriver(
            schema = PhoenixDatabase.Schema,
            context = context,
            name = DatabaseFileNames.TARGET,
            callback = object : AndroidSqliteDriver.Callback(PhoenixDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                    val report = reconcileFullSchema(callbackDriver(db))
                    reconciliationReport = report
                    val summary = report.logSummary()
                    Log.i(TAG, summary)
                    if (report.hasFailures) {
                        for (failure in report.failures) {
                            Log.w(TAG, "Reconciliation failure: ${failure.target} — ${failure.detail}")
                        }
                        throw DatabaseFileMigrationException(
                            DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                            "The Phoenix database schema could not be reconciled.",
                        )
                    }
                    // Diagnostic: log Routine table state so we can debug #324
                    try {
                        db.query("SELECT COUNT(*) AS cnt FROM Routine").use { cursor ->
                            if (cursor.moveToFirst()) {
                                val count = cursor.getInt(0)
                                Log.i(TAG, "ROUTINE_DIAG: Routine table has $count rows")
                            }
                        }
                        db.query(
                            "SELECT profile_id, COUNT(*) AS cnt FROM Routine GROUP BY profile_id",
                        ).use { profileCursor ->
                            while (profileCursor.moveToNext()) {
                                val pid = profileCursor.getString(0)
                                val cnt = profileCursor.getInt(1)
                                Log.i(TAG, "ROUTINE_DIAG: profile_id='$pid' → $cnt routines")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ROUTINE_DIAG: Failed to query Routine table — ${e.message}")
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    Log.i(TAG, "Upgrading database from version $oldVersion to $newVersion")
                    for (version in oldVersion until newVersion) {
                        try {
                            PhoenixDatabase.Schema.migrate(
                                driver = callbackDriver(db),
                                oldVersion = version.toLong(),
                                newVersion = (version + 1).toLong(),
                            )
                            Log.i(TAG, "Migration ${version + 1} succeeded")
                        } catch (e: SQLiteException) {
                            Log.w(TAG, "Migration ${version + 1} failed, applying resilient fallback: ${e.message}")
                            // getMigrationStatements is keyed by the .sqm file number — the
                            // version being migrated FROM (version), not the target. Passing
                            // version + 1 replayed the NEXT migration's statements and skipped
                            // the failed one's data fixes entirely (#636 review).
                            val results = applyMigrationResilient(callbackDriver(db), version)
                            val failures = results.count { result -> !result.success && !result.recoverable }
                            if (failures > 0) {
                                Log.w(TAG, "Migration ${version + 1} had $failures non-recoverable statements")
                            }
                        }
                    }
                }

                /**
                 * Wraps a [SupportSQLiteDatabase] from a callback as a full [SqlDriver].
                 * Uses [AndroidSqliteDriver]'s public database constructor so that
                 * both execute() and executeQuery() work correctly (needed by
                 * [reconcileFullSchema] and [applyMigrationResilient]).
                 *
                 * cacheSize = 1 because callback-scoped statements are one-shot DDL/DML.
                 * The returned driver must NOT be closed — the underlying database
                 * lifecycle is owned by the outer [AndroidSqliteDriver]'s open helper.
                 */
                private fun callbackDriver(db: SupportSQLiteDatabase): SqlDriver = AndroidSqliteDriver(database = db, cacheSize = 1)

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "Database corruption detected")
                    super.onCorruption(db)
                }
            },
        )

        return try {
            val schemaVersion = driver.queryLong("PRAGMA user_version")
            if (schemaVersion != PhoenixDatabase.Schema.version) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database schema version is invalid.",
                )
            }
            if (reconciliationReport == null) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database did not complete schema reconciliation.",
                )
            }
            coordinator.targetValidated(preparation)
            driver
        } catch (failure: Throwable) {
            runCatching { driver.close() }
            throw failure
        }
    }

    private fun SqlDriver.queryLong(sql: String): Long {
        var value: Long? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) {
                    value = cursor.getLong(0)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return value ?: throw DatabaseFileMigrationException(
            DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
            "The Phoenix database schema version could not be read.",
        )
    }
}
