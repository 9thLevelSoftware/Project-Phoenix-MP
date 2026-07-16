package com.devil.phoenixproject.data.migration

import kotlinx.coroutines.flow.StateFlow

class RequiredMigrationFailedException(message: String) : IllegalStateException(message)

interface RequiredMigrationGate {
    val requiredMigrationState: StateFlow<RequiredMigrationState>

    suspend fun awaitRequiredMigrations()
}
