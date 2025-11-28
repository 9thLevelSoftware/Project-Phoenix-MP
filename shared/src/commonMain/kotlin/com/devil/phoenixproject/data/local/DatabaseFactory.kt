package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.database.VitruvianDatabase

class DatabaseFactory(private val driverFactory: DriverFactory) {
    fun createDatabase(): VitruvianDatabase {
        return VitruvianDatabase(driverFactory.createDriver())
    }
}
