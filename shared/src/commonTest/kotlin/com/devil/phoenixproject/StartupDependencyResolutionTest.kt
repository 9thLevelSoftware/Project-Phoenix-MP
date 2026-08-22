package com.devil.phoenixproject

import com.devil.phoenixproject.data.local.DatabaseFileMigrationException
import com.devil.phoenixproject.data.local.DatabaseMigrationFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class StartupDependencyResolutionTest {
    @Test
    fun dualDatabaseFailureDisablesAutomaticRecovery() {
        val result = resolveStartupDependencies {
            throw IllegalStateException(
                "Koin wrapper",
                DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.DUAL_DATABASES,
                    "sensitive internal detail",
                ),
            )
        }

        val failure = assertIs<StartupDependencyResolution.Failed>(result)
        assertEquals("DB_DUAL_DATABASES", failure.diagnosticCode)
        assertFalse(failure.retryAllowed)
        assertFalse(failure.diagnosticCode.contains("sensitive"))
    }

    @Test
    fun recoverableDatabaseFailureAllowsRetry() {
        val result = resolveStartupDependencies {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.CHECKPOINT_FAILED,
                "internal detail",
            )
        }

        val failure = assertIs<StartupDependencyResolution.Failed>(result)
        assertEquals("DB_CHECKPOINT_FAILED", failure.diagnosticCode)
        assertTrue(failure.retryAllowed)
    }

    @Test
    fun unknownFailureUsesNonSensitiveGenericCode() {
        val result = resolveStartupDependencies {
            error("token=must-not-leak")
        }

        val failure = assertIs<StartupDependencyResolution.Failed>(result)
        assertEquals("STARTUP_INITIALIZATION_FAILED", failure.diagnosticCode)
        assertTrue(failure.retryAllowed)
        assertFalse(failure.diagnosticCode.contains("token"))
    }

    @Test
    fun failedKoinSingletonIsRetriedAndNotCached() {
        var attempts = 0
        val application = koinApplication {
            modules(
                module {
                    single<RetryProbe> {
                        attempts++
                        if (attempts == 1) error("first attempt fails")
                        RetryProbe.Ready
                    }
                },
            )
        }

        try {
            assertIs<StartupDependencyResolution.Failed>(
                resolveStartupDependencies { application.koin.get<RetryProbe>() },
            )
            assertIs<StartupDependencyResolution.Ready<RetryProbe>>(
                resolveStartupDependencies { application.koin.get<RetryProbe>() },
            )
            assertEquals(2, attempts)
        } finally {
            application.close()
        }
    }

    private sealed interface RetryProbe {
        data object Ready : RetryProbe
    }
}
