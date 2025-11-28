package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val dbFile = File(System.getProperty("user.home"), ".vitruvian/vitruvian.db")
        val parentDir = dbFile.parentFile
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        
        if (!dbFile.exists() || dbFile.length() == 0L) {
             VitruvianDatabase.Schema.create(driver)
        }
        
        return driver
    }
}
