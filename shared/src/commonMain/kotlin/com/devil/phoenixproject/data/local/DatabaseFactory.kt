package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.database.PhoenixDatabase

class DatabaseFactory(private val driverFactory: DriverFactory) {
    fun createDatabase(): PhoenixDatabase = PhoenixDatabase(driverFactory.createDriver())
}
