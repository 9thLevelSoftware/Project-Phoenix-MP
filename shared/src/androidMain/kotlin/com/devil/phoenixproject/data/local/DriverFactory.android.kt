package com.devil.phoenixproject.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase

actual class DriverFactory(private val context: Context) {

    companion object {
        private const val TAG = "DriverFactory"
        private const val DATABASE_NAME = "vitruvian.db"
    }

    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = VitruvianDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
            callback = object : AndroidSqliteDriver.Callback(VitruvianDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                    val report = reconcileFullSchema(callbackDriver(db))
                    val summary = report.logSummary()
                    Log.i(TAG, summary)
                    if (report.hasFailures) {
                        for (failure in report.failures) {
                            Log.w(TAG, "Reconciliation failure: ${failure.target} — ${failure.detail}")
                        }
                    }
                    // Diagnostic: log Routine table state so we can debug #324
                    try {
                        val cursor = db.query("SELECT COUNT(*) AS cnt FROM Routine")
                        if (cursor.moveToFirst()) {
                            val count = cursor.getInt(0)
                            Log.i(TAG, "ROUTINE_DIAG: Routine table has $count rows")
                        }
                        cursor.close()
                        val profileCursor = db.query(
                            "SELECT profile_id, COUNT(*) AS cnt FROM Routine GROUP BY profile_id"
                        )
                        while (profileCursor.moveToNext()) {
                            val pid = profileCursor.getString(0)
                            val cnt = profileCursor.getInt(1)
                            Log.i(TAG, "ROUTINE_DIAG: profile_id='$pid' → $cnt routines")
                        }
                        profileCursor.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "ROUTINE_DIAG: Failed to query Routine table — ${e.message}")
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    Log.i(TAG, "Upgrading database from version $oldVersion to $newVersion")
                    for (version in oldVersion until newVersion) {
                        try {
                            VitruvianDatabase.Schema.migrate(
                                driver = callbackDriver(db),
                                oldVersion = version.toLong(),
                                newVersion = (version + 1).toLong(),
                            )
                            Log.i(TAG, "Migration ${version + 1} succeeded")
                        } catch (e: SQLiteException) {
                            Log.w(TAG, "Migration ${version + 1} failed, applying resilient fallback: ${e.message}")
                            val results = applyMigrationResilient(callbackDriver(db), version + 1)
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
                private fun callbackDriver(db: SupportSQLiteDatabase): SqlDriver =
                    AndroidSqliteDriver(database = db, cacheSize = 1)

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "Database corruption detected")
                    super.onCorruption(db)
                }
            },
        )
    }
}
