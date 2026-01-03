package com.devil.phoenixproject.data.local

import android.content.Context
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
                    // Enable foreign keys
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }

                // SQLDelight handles migrations automatically via .sqm files.
                // The parent Callback class applies migrations 1.sqm, 2.sqm, 3.sqm, 4.sqm etc.
                // We do NOT override onUpgrade - let SQLDelight handle it properly.

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "Database corruption detected")
                    // Let SQLite handle corruption - it will attempt recovery or recreate
                    super.onCorruption(db)
                }
            }
        )
    }
}
