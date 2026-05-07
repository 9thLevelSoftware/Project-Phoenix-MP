package com.devil.phoenixproject.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.requiredHealthPermissions
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import org.koin.compose.koinInject

private val log = Logger.withTag("OptionalPermissionsHandler")

/**
 * Composable that prompts for optional permissions (Health Connect + Microphone)
 * on first launch. Unlike [RequireBlePermissions], this is non-blocking:
 * users can skip and still access the app.
 *
 * Shows only once per install (tracked via [SettingsPreferencesManager]).
 *
 * @param content The composable content to show after permissions are handled
 */
@Composable
fun RequireOptionalPermissions(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefsManager = koinInject<SettingsPreferencesManager>()

    // Check if we've already shown onboarding
    var onboardingShown by remember { mutableStateOf(prefsManager.isPermissionsOnboardingShown()) }

    // Health Connect availability
    val healthAvailable = remember {
        try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    var allAlreadyGranted by remember { mutableStateOf(false) }

    LaunchedEffect(context, healthAvailable) {
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val healthGranted = if (healthAvailable) {
            try {
                HealthConnectClient.getOrCreate(context)
                    .permissionController
                    .getGrantedPermissions()
                    .containsAll(requiredHealthPermissions)
            } catch (e: Exception) {
                log.w(e) { "Unable to check existing Health Connect permissions" }
                false
            }
        } else {
            true
        }

        allAlreadyGranted = micGranted && healthGranted
    }

    if (onboardingShown || allAlreadyGranted) {
        content()
        return
    }

    // ── Permission launchers (sequential: mic first, then health) ────────
    //
    // Android only allows one Activity Result contract in flight at a time.
    // Launching two simultaneously causes the second to be silently dropped.
    // Flow: user taps Grant → mic dialog → mic callback → health dialog → health callback → done

    // Health Connect permission launcher (registered first, launched second)
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions ->
        val granted = grantedPermissions.containsAll(requiredHealthPermissions)
        log.d {
            "Health Connect permission result: $granted (got ${grantedPermissions.size} permissions)"
        }
        // Both permissions handled, mark done
        prefsManager.setPermissionsOnboardingShown(true)
        onboardingShown = true
    }

    // Standard Android permission launcher (RECORD_AUDIO) — launched first
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        log.d { "Microphone permission result: $granted" }
        // Mic done, now launch health if available
        if (healthAvailable) {
            healthPermissionLauncher.launch(requiredHealthPermissions)
        } else {
            // No health to request, we're done
            prefsManager.setPermissionsOnboardingShown(true)
            onboardingShown = true
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    val isDark = isSystemInDarkTheme()
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        OptionalPermissionsScreen(
            healthAvailable = healthAvailable,
            onGrantPermissions = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onSkip = {
                log.d { "User skipped optional permissions" }
                prefsManager.setPermissionsOnboardingShown(true)
                onboardingShown = true
            },
        )
    }
}

/**
 * Explanation screen for optional permissions.
 */
@Composable
private fun OptionalPermissionsScreen(healthAvailable: Boolean, onGrantPermissions: () -> Unit, onSkip: () -> Unit) {
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
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Enhance Your Experience",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = buildString {
                    append("Project Phoenix works best with a few additional permissions:")
                    append("\n\n")
                    if (healthAvailable) {
                        append(
                            "Health Connect -- Automatically sync your workouts to Google Health so all your fitness data stays in one place.",
                        )
                        append("\n\n")
                    }
                    append(
                        "Microphone -- Enable voice-activated emergency stop (\"safe word\") for hands-free workout safety.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onGrantPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = "Grant Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Skip for Now",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "You can enable these permissions later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
