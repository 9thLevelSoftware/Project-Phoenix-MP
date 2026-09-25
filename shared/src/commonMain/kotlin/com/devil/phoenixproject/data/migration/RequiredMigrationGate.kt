package com.devil.phoenixproject.data.migration

import com.devil.phoenixproject.StartupDiagnosticFailure
import kotlinx.coroutines.flow.StateFlow

class RequiredMigrationFailedException(message: String) : IllegalStateException(message), StartupDiagnosticFailure {
    override val startupDiagnosticCode: String = "REQUIRED_DATA_REPAIR_FAILED"
    override val startupRetryAllowed: Boolean = true
}

interface RequiredMigrationGate {
    val requiredMigrationState: StateFlow<RequiredMigrationState>

    suspend fun awaitRequiredMigrations()
}
