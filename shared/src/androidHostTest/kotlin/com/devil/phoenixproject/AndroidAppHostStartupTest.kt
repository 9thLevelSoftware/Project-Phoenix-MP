package com.devil.phoenixproject

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class AndroidAppHostStartupTest {
    @Test
    fun `android host failure never constructs features and retry constructs them once`() = runTest {
        var requiredAttempts = 0
        var featureConstructions = 0
        suspend fun launchAttempt() = prepareAndroidHostGraph(
            resolveStartupOnly = { "migration-manager" },
            prepareRequired = {
                requiredAttempts++
                if (requiredAttempts == 1) error("injected required migration failure")
            },
            resolveFeatures = {
                featureConstructions++
                "main-view-model-graph"
            },
        )

        assertIs<StartupDependencyResolution.Failed>(launchAttempt())
        assertEquals(0, featureConstructions)
        assertIs<StartupDependencyResolution.Ready<String>>(launchAttempt())
        assertEquals(1, featureConstructions)
    }
}
