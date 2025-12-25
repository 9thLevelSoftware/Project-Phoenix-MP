package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.*
import platform.darwin.NSObject

/**
 * State holder for BLE permission status on iOS.
 */
sealed class BlePermissionState {
    data object Granted : BlePermissionState()
    data object NotGranted : BlePermissionState()
    data object Denied : BlePermissionState()
    data object Requesting : BlePermissionState()
}

/**
 * Helper class to manage CoreBluetooth permissions on iOS.
 * Creating a CBCentralManager triggers the permission dialog if not yet granted.
 */
@OptIn(ExperimentalForeignApi::class)
private class BluetoothPermissionManager(
    private val onStateChange: (BlePermissionState) -> Unit
) : NSObject(), CBCentralManagerDelegateProtocol {

    private val log = Logger.withTag("BluetoothPermissionManager")
    private var centralManager: CBCentralManager? = null

    /**
     * Check current Bluetooth authorization status without triggering permission request.
     */
    fun getCurrentAuthorizationState(): BlePermissionState {
        return when (CBCentralManager.authorization) {
            CBManagerAuthorizationAllowedAlways -> {
                log.d { "Bluetooth authorization: AllowedAlways" }
                BlePermissionState.Granted
            }
            CBManagerAuthorizationDenied -> {
                log.d { "Bluetooth authorization: Denied" }
                BlePermissionState.Denied
            }
            CBManagerAuthorizationRestricted -> {
                log.d { "Bluetooth authorization: Restricted" }
                BlePermissionState.Denied
            }
            CBManagerAuthorizationNotDetermined -> {
                log.d { "Bluetooth authorization: NotDetermined" }
                BlePermissionState.NotGranted
            }
            else -> {
                log.d { "Bluetooth authorization: Unknown" }
                BlePermissionState.NotGranted
            }
        }
    }

    /**
     * Request Bluetooth permission by creating a CBCentralManager.
     * This will trigger the iOS permission dialog if not yet determined.
     */
    fun requestPermission() {
        log.d { "Requesting Bluetooth permission..." }
        onStateChange(BlePermissionState.Requesting)

        // Creating CBCentralManager triggers the permission dialog
        // The delegate will be called with the updated state
        centralManager = CBCentralManager(delegate = this, queue = null)
    }

    /**
     * Clean up the central manager when no longer needed.
     */
    fun cleanup() {
        centralManager = null
    }

    // CBCentralManagerDelegate implementation
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        log.d { "Central manager state updated: ${central.state}" }

        val newState = when (central.state) {
            CBManagerStatePoweredOn -> {
                log.d { "Bluetooth is powered on and authorized" }
                BlePermissionState.Granted
            }
            CBManagerStatePoweredOff -> {
                // Bluetooth is off but may be authorized
                // Check authorization separately
                when (CBCentralManager.authorization) {
                    CBManagerAuthorizationAllowedAlways -> BlePermissionState.Granted
                    CBManagerAuthorizationDenied, CBManagerAuthorizationRestricted -> BlePermissionState.Denied
                    else -> BlePermissionState.NotGranted
                }
            }
            CBManagerStateUnauthorized -> {
                log.d { "Bluetooth is unauthorized" }
                BlePermissionState.Denied
            }
            CBManagerStateUnsupported -> {
                log.d { "Bluetooth is unsupported on this device" }
                BlePermissionState.Denied
            }
            CBManagerStateResetting -> {
                log.d { "Bluetooth is resetting" }
                BlePermissionState.Requesting
            }
            CBManagerStateUnknown -> {
                log.d { "Bluetooth state is unknown" }
                BlePermissionState.Requesting
            }
            else -> {
                log.d { "Bluetooth state: unknown value ${central.state}" }
                BlePermissionState.NotGranted
            }
        }

        onStateChange(newState)
    }
}

/**
 * Composable that wraps content and ensures BLE permissions are granted before showing it.
 * On iOS, this component checks the current authorization status and triggers the
 * permission dialog when the user taps Continue.
 *
 * @param content The composable content to show when permissions are granted
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun RequireBlePermissions(
    content: @Composable () -> Unit
) {
    val log = remember { Logger.withTag("RequireBlePermissions") }

    // Track permission state
    var permissionState by remember { mutableStateOf<BlePermissionState>(BlePermissionState.NotGranted) }

    // Create and remember the permission manager
    val permissionManager = remember {
        BluetoothPermissionManager { newState ->
            log.d { "Permission state changed to: $newState" }
            permissionState = newState
        }
    }

    // Check initial authorization status on first composition
    LaunchedEffect(Unit) {
        val initialState = permissionManager.getCurrentAuthorizationState()
        log.d { "Initial Bluetooth authorization state: $initialState" }
        permissionState = initialState
    }

    // Clean up when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            permissionManager.cleanup()
        }
    }

    when (permissionState) {
        is BlePermissionState.Granted -> {
            content()
        }
        is BlePermissionState.NotGranted -> {
            PermissionScreenTheme {
                BlePermissionRequestScreen(
                    onRequestPermission = {
                        permissionManager.requestPermission()
                    }
                )
            }
        }
        is BlePermissionState.Requesting -> {
            PermissionScreenTheme {
                BlePermissionRequestingScreen()
            }
        }
        is BlePermissionState.Denied -> {
            PermissionScreenTheme {
                BlePermissionDeniedScreen(
                    onRetry = {
                        // Re-check the authorization status
                        // User might have enabled it in Settings
                        val currentState = permissionManager.getCurrentAuthorizationState()
                        permissionState = currentState
                    }
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
private fun BlePermissionRequestScreen(
    onRequestPermission: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Bluetooth Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Project Phoenix needs Bluetooth permission to scan for and connect to your Vitruvian Trainer machine. When you tap Continue, iOS will ask for permission.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Continue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Screen shown while permission is being requested.
 */
@Composable
private fun BlePermissionRequestingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Requesting Permission...",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Please respond to the iOS permission dialog.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Screen shown when BLE permissions have been denied.
 */
@Composable
private fun BlePermissionDeniedScreen(
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Permission Denied",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Bluetooth permission is required to connect to your Vitruvian Trainer. Please enable Bluetooth permission in Settings > Project Phoenix > Bluetooth, then return to the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Check Again",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "If Bluetooth is disabled system-wide, please enable it in Control Center or Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
