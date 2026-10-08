package com.devil.phoenixproject.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

private val healthSettingsLog = Logger.withTag("HealthPermissionSettingsLauncher")

private const val MANAGE_HEALTH_PERMISSIONS_ACTION =
    "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
private const val HEALTH_HOME_SETTINGS_ACTION =
    "android.health.connect.action.HEALTH_HOME_SETTINGS"
private const val HEALTH_CONNECT_SETTINGS_ACTION =
    "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"

actual class HealthPermissionSettingsLauncher(private val context: Context) {
    actual fun openSettings() {
        val attempts = healthConnectSettingsAttempts(context.packageName, Build.VERSION.SDK_INT)
        launchHealthConnectSettingsChain(attempts) { intent ->
            context.startActivity(intent)
        }
    }
}

/**
 * Settings targets for this API level, each action at most once.
 *
 * API 34+ opens this app's Health Connect permission screen, then Health Connect
 * home, then app details. Below API 34 the permission screen and the generic
 * Health Connect settings action are the same intent, so only that action is
 * tried before app details.
 */
internal fun healthConnectSettingsActions(sdkInt: Int): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(MANAGE_HEALTH_PERMISSIONS_ACTION)
        add(HEALTH_HOME_SETTINGS_ACTION)
    } else {
        add(HEALTH_CONNECT_SETTINGS_ACTION)
    }
    add(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
}

internal fun healthConnectSettingsAttempts(packageName: String, sdkInt: Int): List<Intent> {
    return healthConnectSettingsActions(sdkInt).map { action ->
        Intent(action).apply {
            if (action == MANAGE_HEALTH_PERMISSIONS_ACTION) {
                putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            }
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                data = Uri.fromParts("package", packageName, null)
            }
        }
    }
}

/**
 * Starts the first settings target that resolves.
 *
 * One catch covers [android.content.ActivityNotFoundException] and any other
 * start failure, then the chain continues. The last attempt is guarded too, so
 * a missing app-details activity does not escape to the caller.
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
        } catch (e: Exception) {
            healthSettingsLog.w(e) {
                healthConnectSettingsFailureMessage(index, attempts.lastIndex)
            }
        }
    }
    return null
}

private fun healthConnectSettingsFailureMessage(index: Int, lastIndex: Int): String = when {
    index >= lastIndex -> "Failed to open Health Connect settings"
    index == 0 && lastIndex >= 2 ->
        "Health Connect app-permissions settings unavailable; opening Health Connect home settings"
    else -> "Health Connect home settings unavailable; opening app details settings"
}

@Composable
actual fun rememberHealthPermissionSettingsLauncher(): HealthPermissionSettingsLauncher {
    val context = LocalContext.current
    return remember(context) { HealthPermissionSettingsLauncher(context) }
}
