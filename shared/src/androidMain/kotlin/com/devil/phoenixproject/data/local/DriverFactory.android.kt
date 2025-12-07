package com.devil.phoenixproject.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        // For development: use destructive migration to handle schema changes
        // This will delete and recreate the database if the schema version changes
        // TODO: Add proper migration scripts for production
        return AndroidSqliteDriver(
            schema = VitruvianDatabase.Schema,
            context = context,
            name = "vitruvian.db",
            callback = object : AndroidSqliteDriver.Callback(VitruvianDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Enable foreign keys
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Destructive migration: drop all tables and recreate
                    // This is acceptable for development but should be replaced with
                    // proper migration scripts for production
                    println("Database schema changed from $oldVersion to $newVersion - recreating tables")

                    // Get list of all tables and drop them
                    val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'")
                    val tables = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        tables.add(cursor.getString(0))
                    }
                    cursor.close()

                    tables.forEach { table ->
                        db.execSQL("DROP TABLE IF EXISTS $table")
                    }

                    // Recreate schema using SQLDelight's Schema
                    val driver = AndroidSqliteDriver(db)
                    VitruvianDatabase.Schema.create(driver)
                }
            }
        )
    }
}
