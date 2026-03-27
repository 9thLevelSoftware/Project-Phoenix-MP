package com.devil.phoenixproject.presentation.components

import android.Manifest
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * BLE and notification permissions required for the app.
 * Android 12+ (API 31+) requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT.
 * Android 13+ (API 33+) requires POST_NOTIFICATIONS for workout notifications.
 * On older versions, only location permission is needed.
 */
object BlePermissions {
    /**
     * Get the list of permissions required based on Android version.
     */
    fun getRequiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Older versions need location for BLE scanning
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires POST_NOTIFICATIONS for workout notifications
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Check if all required BLE permissions are granted.
     */
    fun arePermissionsGranted(context: Context): Boolean = getRequiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.values.all { it }
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
                        permissionLauncher.launch(BlePermissions.getRequiredPermissions().toTypedArray())
                    },
                )
            }
        }

        is BlePermissionState.Denied -> {
            val activity = context as? Activity

            // Detect permanent denial: shouldShowRequestPermissionRationale returns false
            // when the user checked "Don't ask again" or when the system won't show the dialog
            val canRetry = remember(permissionState) {
                activity != null && BlePermissions.getRequiredPermissions().any { permission ->
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
                    onRetry = {
                        permissionLauncher.launch(BlePermissions.getRequiredPermissions().toTypedArray())
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
                text = "Project Phoenix needs Bluetooth permission to scan for and connect to your Vitruvian Trainer machine.",
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
 * Screen shown when BLE permissions have been denied.
 *
 * @param canRetry true if the system permission dialog can still be shown (user has not
 *   permanently denied). false if the user selected "Don't ask again" or the OS won't
 *   show the dialog — in that case we direct them to app Settings instead.
 * @param onRetry Re-request permissions via the system dialog.
 * @param onOpenSettings Open the app's Settings page so the user can toggle permissions manually.
 */
@Composable
private fun BlePermissionDeniedScreen(canRetry: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
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
                text = if (canRetry) {
                    "Bluetooth permission is required to connect to your Vitruvian Trainer. Please grant the permission to continue."
                } else {
                    "Bluetooth permission has been permanently denied. Please enable it in your device's Settings to use Project Phoenix."
                },
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
                text = if (canRetry) {
                    "If the permission dialog doesn't appear, you may need to enable Bluetooth permissions in your device's Settings > Apps > Project Phoenix > Permissions."
                } else {
                    "Navigate to Permissions and enable Bluetooth access, then return here. The app will detect the change automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
