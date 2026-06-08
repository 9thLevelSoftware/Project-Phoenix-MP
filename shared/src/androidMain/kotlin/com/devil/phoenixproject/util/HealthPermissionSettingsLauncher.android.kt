package com.devil.phoenixproject.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

private val healthSettingsLog = Logger.withTag("HealthPermissionSettingsLauncher")

private val healthConnectSettingsAction: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        "android.health.connect.action.HEALTH_HOME_SETTINGS"
    } else {
        "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
    }

actual class HealthPermissionSettingsLauncher(private val context: Context) {
    actual fun openSettings() {
        val intent = buildHealthPermissionIntent(context.packageName)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            healthSettingsLog.w(e) { "Health Connect app-permissions settings unavailable; opening Health Connect home settings" }
            context.startActivity(Intent(healthConnectSettingsAction))
        } catch (e: Exception) {
            healthSettingsLog.w(e) { "Failed to open Health Connect settings; opening app details settings" }
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                },
            )
        }
    }

    private fun buildHealthPermissionIntent(packageName: String): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            }
        } else {
            Intent(healthConnectSettingsAction)
        }
    }
}

@Composable
actual fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher {
    val context = LocalContext.current
    return remember(context) { HealthPermissionSettingsLauncher(context) }
}
