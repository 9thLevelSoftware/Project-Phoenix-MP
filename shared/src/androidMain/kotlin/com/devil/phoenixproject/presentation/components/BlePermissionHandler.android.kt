package com.devil.phoenixproject.presentation.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devil.phoenixproject.ui.theme.ApplyStatusBarAppearance

/**
 * Runtime permissions for the BLE connection gate.
 * Android 12+ (API 31+) requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT.
 * Older versions need location for BLE scanning.
 * POST_NOTIFICATIONS (API 33+) is optional: it may be requested with the
 * Bluetooth prompt, but denying it must not block the gate. Workout
 * notifications are posted best-effort.
 */
object BlePermissions {
    /**
     * Permissions that must be granted before the app can scan and connect.
     * Does not include POST_NOTIFICATIONS.
     */
    fun getRequiredPermissions(): List<String> =
        BlePermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT)

    /**
     * Required BLE permissions plus optional notification permission.
     */
    fun getPermissionsToRequest(): List<String> =
        BlePermissionPolicy.permissionsToRequest(Build.VERSION.SDK_INT)

    /**
     * True when every required BLE permission is granted. A denied
     * POST_NOTIFICATIONS permission does not fail this check.
     */
    fun arePermissionsGranted(context: Context): Boolean =
        missingRequiredPermissions(context).isEmpty()

    /**
     * Required BLE permissions that are not currently granted.
     */
    fun missingRequiredPermissions(context: Context): List<String> =
        getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
}

/**
 * State holder for BLE permission status.
 */
sealed class BlePermissionState {
    data object Granted : BlePermissionState()
    data object NotGranted : BlePermissionState()
    data object Denied : BlePermissionState()
}

/**
 * Composable that wraps content and ensures BLE permissions are granted before showing it.
 * Shows a permission request UI if permissions are not granted.
 *
 * @param content The composable content to show when permissions are granted
 */
@Composable
fun RequireBlePermissions(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var permissionState by remember {
        mutableStateOf(
            if (BlePermissions.arePermissionsGranted(context)) {
                BlePermissionState.Granted
            } else {
                BlePermissionState.NotGranted
            },
        )
    }

    // Re-check permissions after activity recreation (defense layer).
    // With android:configChanges the activity is NOT recreated on rotation,
    // but this guards against other recreation scenarios (e.g., system process death).
    LaunchedEffect(Unit) {
        if (permissionState !is BlePermissionState.Denied &&
            BlePermissions.arePermissionsGranted(context)
        ) {
            permissionState = BlePermissionState.Granted
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Re-check every required permission directly rather than trusting the
        // result map: Android may omit keys for permissions it didn't prompt
        // for, and `map.values.all { it }` would then pass on a partial subset.
        val allGranted = BlePermissions.arePermissionsGranted(context)
        permissionState = if (allGranted) {
            BlePermissionState.Granted
        } else {
            BlePermissionState.Denied
        }
    }

    when (permissionState) {
        is BlePermissionState.Granted -> {
            content()
        }

        is BlePermissionState.NotGranted -> {
            // Wrap permission screens in a basic theme
            PermissionScreenTheme {
                BlePermissionRequestScreen(
                    onRequestPermission = {
                        permissionLauncher.launch(BlePermissions.getPermissionsToRequest().toTypedArray())
                    },
                )
            }
        }

        is BlePermissionState.Denied -> {
            val activity = context as? Activity

            val missingPermissions = BlePermissions.missingRequiredPermissions(context)

            // Detect permanent denial: shouldShowRequestPermissionRationale returns false
            // when the user checked "Don't ask again" or when the system won't show the dialog.
            // Only required BLE permissions count; a denied notification permission must not
            // keep the user on this screen.
            val canRetry = remember(missingPermissions) {
                activity != null && missingPermissions.any { permission ->
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                }
            }

            // Re-check permissions when returning from Settings
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        if (BlePermissions.arePermissionsGranted(context)) {
                            permissionState = BlePermissionState.Granted
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            PermissionScreenTheme {
                BlePermissionDeniedScreen(
                    canRetry = canRetry,
                    missingPermissions = missingPermissions,
                    onRetry = {
                        permissionLauncher.launch(BlePermissions.getPermissionsToRequest().toTypedArray())
                    },
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                )
            }
        }
    }
}

/**
 * Simple theme wrapper for permission screens.
 */
@Composable
private fun PermissionScreenTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    ApplyStatusBarAppearance(isDark = isDark)
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Screen shown when BLE permissions need to be requested.
 */
@Composable
private fun BlePermissionRequestScreen(onRequestPermission: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Bluetooth Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Project Phoenix needs Bluetooth permission to scan for and connect to your smart fitness machine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = "Grant Permission",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Screen shown when a required BLE permission has been denied.
 *
 * @param canRetry true if the system permission dialog can still be shown (user has not
 *   permanently denied). false if the user selected "Don't ask again" or the OS won't
 *   show the dialog — in that case we direct them to app Settings instead.
 * @param missingPermissions Required permissions that are still not granted. Copy names these
 *   instead of always saying Bluetooth.
 * @param onRetry Re-request permissions via the system dialog.
 * @param onOpenSettings Open the app's Settings page so the user can toggle permissions manually.
 */
@Composable
private fun BlePermissionDeniedScreen(
    canRetry: Boolean,
    missingPermissions: List<String>,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val copy = BlePermissionPolicy.denialCopy(missingPermissions, canRetry)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (canRetry) Icons.Default.Warning else Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.error,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Permission Denied",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = copy.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (canRetry) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = "Try Again",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = copy.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
