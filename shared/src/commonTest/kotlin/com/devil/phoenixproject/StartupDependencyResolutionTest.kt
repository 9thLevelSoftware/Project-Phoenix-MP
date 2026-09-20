package com.devil.phoenixproject

import com.devil.phoenixproject.data.local.DatabaseDiagnosticReason
import com.devil.phoenixproject.data.local.DatabaseFileMigrationException
import com.devil.phoenixproject.data.local.DatabaseMigrationFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class StartupDependencyResolutionTest {
    @Test
    fun requiredStartupCompletesBeforeFeatureDependenciesAreResolved() = runTest {
        val events = mutableListOf<String>()

        val result = prepareStartupDependencies(
            resolveStartupOnly = {
                events += "startup-only"
                "database"
            },
            prepareRequired = { dependency ->
                assertEquals("database", dependency)
                events += "required"
            },
            resolveFeatures = { dependency ->
                assertEquals("database", dependency)
                events += "features"
                "ready"
            },
        )

        assertEquals(listOf("startup-only", "required", "features"), events)
        assertEquals("ready", assertIs<StartupDependencyResolution.Ready<String>>(result).dependencies)
    }

    @Test
    fun requiredStartupFailureNeverConstructsFeatureDependenciesAndRemainsRetriable() = runTest {
        var featureResolutions = 0

        val result = prepareStartupDependencies(
            resolveStartupOnly = { "database" },
            prepareRequired = { throw RequiredStartupProbeFailure() },
            resolveFeatures = {
                featureResolutions++
                "must-not-resolve"
            },
        )

        val failure = assertIs<StartupDependencyResolution.Failed>(result)
        assertEquals(0, featureResolutions)
        assertEquals("REQUIRED_STARTUP_PROBE", failure.diagnosticCode)
        assertTrue(failure.retryAllowed)
    }

    @Test
    fun retryConstructsFeaturesExactlyOnceAfterRequiredStartupRecovers() = runTest {
        var requiredAttempts = 0
        var featureConstructions = 0
        suspend fun attempt() = prepareAppHostDependencies(
            resolveStartupOnly = { "startup" },
            prepareRequired = {
                requiredAttempts++
                if (requiredAttempts == 1) throw RequiredStartupProbeFailure()
            },
            resolveFeatures = {
                featureConstructions++
                "features"
            },
        )

        assertIs<StartupDependencyResolution.Failed>(attempt())
        assertEquals(0, featureConstructions)
        assertIs<StartupDependencyResolution.Ready<String>>(attempt())
        assertEquals(1, featureConstructions)
    }

    @Test
    fun dualDatabaseFailureOffersExportSupportAndRetryButNoAutomaticRecovery() {
        val result = resolveStartupDependencies {
            throw IllegalStateException(
                "Koin wrapper",
                DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.DUAL_DATABASES,
                    "sensitive internal detail",
                    diagnosticReason = DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET,
                ),
            )
        }

        val failure = assertIs<StartupDependencyResolution.Failed>(result)
        assertEquals("DB_DUAL_DATABASES", failure.diagnosticCode)
        assertTrue(failure.retryAllowed)
        assertEquals(DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET.name, failure.supportCode)
        assertFalse(failure.diagnosticCode.contains("sensitive"))
        assertEquals(
            listOf(
                StartupFailureAction.EXPORT_DATABASE_FILES,
                StartupFailureAction.CONTACT_SUPPORT,
                StartupFailureAction.RETRY,
            ),
            startupFailureActions(failure),
        )
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
        assertEquals(listOf(StartupFailureAction.RETRY), startupFailureActions(failure))
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
        assertEquals(listOf(StartupFailureAction.RETRY), startupFailureActions(failure))
    }

    @Test
    fun nonRetryableFailureOffersNoActions() {
        val failure = StartupDependencyResolution.Failed(
            diagnosticCode = "PREFS_SOMETHING",
            retryAllowed = false,
            cause = IllegalStateException(),
        )

        assertEquals(emptyList(), startupFailureActions(failure))
    }

    @Test
    fun nonRetryableDualDatabasesStillOffersExportAndSupport() {
        val failure = StartupDependencyResolution.Failed(
            diagnosticCode = "DB_DUAL_DATABASES",
            retryAllowed = false,
            cause = IllegalStateException(),
        )

        assertEquals(
            listOf(StartupFailureAction.EXPORT_DATABASE_FILES, StartupFailureAction.CONTACT_SUPPORT),
            startupFailureActions(failure),
        )
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

    private class RequiredStartupProbeFailure : IllegalStateException(), StartupDiagnosticFailure {
        override val startupDiagnosticCode: String = "REQUIRED_STARTUP_PROBE"
        override val startupRetryAllowed: Boolean = true
    }
}
