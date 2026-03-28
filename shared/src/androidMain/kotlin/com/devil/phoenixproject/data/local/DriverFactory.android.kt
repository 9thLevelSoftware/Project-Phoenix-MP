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
                    val report = reconcileFullSchema(createTempDriver(db))
                    val summary = report.logSummary()
                    Log.i(TAG, summary)
                    if (report.hasFailures) {
                        for (failure in report.failures) {
                            Log.w(TAG, "Reconciliation failure: ${failure.target} — ${failure.detail}")
                        }
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    Log.i(TAG, "Upgrading database from version $oldVersion to $newVersion")
                    for (version in oldVersion until newVersion) {
                        try {
                            VitruvianDatabase.Schema.migrate(
                                driver = createTempDriver(db),
                                oldVersion = version.toLong(),
                                newVersion = (version + 1).toLong(),
                            )
                            Log.i(TAG, "Migration ${version + 1} succeeded")
                        } catch (e: SQLiteException) {
                            Log.w(TAG, "Migration ${version + 1} failed, applying resilient fallback: ${e.message}")
                            val results = applyMigrationResilient(createTempDriver(db), version + 1)
                            val failures = results.count { result -> !result.success && !result.recoverable }
                            if (failures > 0) {
                                Log.w(TAG, "Migration ${version + 1} had $failures non-recoverable statements")
                            }
                        }
                    }
                }

                private fun createTempDriver(db: SupportSQLiteDatabase): SqlDriver {
                    return object : SqlDriver {
                        override fun close() {}
                        override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {}
                        override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {}
                        override fun notifyListeners(vararg queryKeys: String) {}
                        override fun currentTransaction(): app.cash.sqldelight.Transacter.Transaction? = null
                        override fun newTransaction(): app.cash.sqldelight.db.QueryResult<app.cash.sqldelight.Transacter.Transaction> =
                            throw UnsupportedOperationException()
                        override fun execute(
                            identifier: Int?,
                            sql: String,
                            parameters: Int,
                            binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
                        ): app.cash.sqldelight.db.QueryResult<Long> {
                            db.execSQL(sql)
                            return app.cash.sqldelight.db.QueryResult.Value(0L)
                        }
                        override fun <R> executeQuery(
                            identifier: Int?,
                            sql: String,
                            mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
                            parameters: Int,
                            binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
                        ): app.cash.sqldelight.db.QueryResult<R> = throw UnsupportedOperationException()
                    }
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "Database corruption detected")
                    super.onCorruption(db)
                }
            },
        )
    }
}
