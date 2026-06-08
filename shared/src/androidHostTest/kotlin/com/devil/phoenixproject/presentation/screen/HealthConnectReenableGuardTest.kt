package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * Regression guard for issue #520: after a user disables/revokes Health Connect,
 * Android can move Phoenix to the Health Connect inactive apps list. The normal
 * permission contract may immediately return denied, so the app must offer a
 * direct Health Connect app-permissions settings path instead of dead-ending.
 */
class HealthConnectReenableGuardTest {
    private val projectRoot: File = findProjectRoot()

    private fun findProjectRoot(): File {
        val startDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var current = File(startDir)
        while (!File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: error("Project root not found from ${System.getProperty("user.dir")}")
        }
        return current
    }

    @Test
    fun deniedHealthPermissions_emitSettingsRecoveryEvent() {
        val viewModel = File(
            projectRoot,
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt",
        ).readText()

        assertTrue(
            viewModel.contains("OpenHealthPermissionSettings"),
            "IntegrationsViewModel must emit an OpenHealthPermissionSettings UI event when Health Connect permission re-request is denied.",
        )
    }

    @Test
    fun androidSettingsLauncher_targetsPhoenixHealthConnectPermissions() {
        val launcher = File(
            projectRoot,
            "shared/src/androidMain/kotlin/com/devil/phoenixproject/util/HealthPermissionSettingsLauncher.android.kt",
        ).takeIf { it.exists() }?.readText().orEmpty()

        assertTrue(
            launcher.contains("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"),
            "Android launcher must use HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS for API 34+ Health Connect permission recovery.",
        )
        assertTrue(
            launcher.contains("Intent.EXTRA_PACKAGE_NAME") && launcher.contains("context.packageName"),
            "Android launcher must pass Phoenix's package name so Health Connect opens this app's permission screen, not the generic app list.",
        )
    }
}
