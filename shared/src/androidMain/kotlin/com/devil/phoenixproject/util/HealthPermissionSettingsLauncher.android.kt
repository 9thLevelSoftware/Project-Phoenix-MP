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
        val packageName = context.packageName
        // Order is the fallback chain: app permissions, then Health Connect home, then app details.
        // Each start is guarded so a missing activity continues instead of aborting the chain.
        val attempts = listOf(
            buildHealthPermissionIntent(packageName),
            Intent(healthConnectSettingsAction),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            },
        )
        launchHealthConnectSettingsChain(attempts) { intent ->
            context.startActivity(intent)
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

/**
 * Starts the first settings target that resolves.
 *
 * [ActivityNotFoundException] and any other start failure continue to the next
 * fallback. The last attempt is guarded too, so a missing app-details activity
 * does not escape to the caller.
 *
 * @return the index of the attempt that started, or null when every attempt failed.
 */
internal fun <T> launchHealthConnectSettingsChain(
    attempts: List<T>,
    startActivity: (T) -> Unit,
): Int? {
    attempts.forEachIndexed { index, intent ->
        try {
            startActivity(intent)
            return index
        } catch (e: ActivityNotFoundException) {
            healthSettingsLog.w(e) { healthConnectSettingsFailureMessage(index) }
        } catch (e: Exception) {
            healthSettingsLog.w(e) { healthConnectSettingsFailureMessage(index) }
        }
    }
    return null
}

private fun healthConnectSettingsFailureMessage(index: Int): String = when (index) {
    0 -> "Health Connect app-permissions settings unavailable; opening Health Connect home settings"
    1 -> "Health Connect home settings unavailable; opening app details settings"
    else -> "Failed to open Health Connect settings"
}

@Composable
actual fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher {
    val context = LocalContext.current
    return remember(context) { HealthPermissionSettingsLauncher(context) }
}
