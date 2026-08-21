package com.devil.phoenixproject

import com.devil.phoenixproject.data.preferences.PreferenceFileMigrationException
import com.devil.phoenixproject.data.preferences.PreferenceMigrationFailureCode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class AndroidStartupDependencyResolutionTest {
    @Test
    fun preferenceMigrationFailureProducesRetryableSafeCode() {
        val result = resolveStartupDependencies {
            throw IllegalStateException(
                "Koin wrapper",
                PreferenceFileMigrationException(
                    PreferenceMigrationFailureCode.RECOVERY_WRITE_FAILED,
                    "preference value must not leak",
                ),
            )
        }

        val failure = assertIs<StartupDependencyResolution.Failed>(result)
        assertEquals("PREFERENCES_RECOVERY_WRITE_FAILED", failure.diagnosticCode)
        assertTrue(failure.retryAllowed)
    }
}
