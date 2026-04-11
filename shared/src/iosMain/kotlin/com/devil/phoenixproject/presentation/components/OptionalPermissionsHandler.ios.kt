package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.compose.koinInject
import platform.Foundation.NSError
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus

private val log = Logger.withTag("OptionalPermissionsHandler")

/**
 * iOS composable that prompts for optional permissions (HealthKit + Speech Recognition)
 * on first launch. Non-blocking: users can skip and still access the app.
 *
 * Shows only once per install (tracked via [SettingsPreferencesManager]).
 *
 * @param content The composable content to show after permissions are handled
 */
@Composable
fun RequireOptionalPermissions(content: @Composable () -> Unit) {
    val prefsManager = koinInject<SettingsPreferencesManager>()
    val scope = rememberCoroutineScope()

    var onboardingShown by remember { mutableStateOf(prefsManager.isPermissionsOnboardingShown()) }

    if (onboardingShown) {
        content()
        return
    }

    val healthAvailable = remember { HKHealthStore.isHealthDataAvailable() }

    val isDark = isSystemInDarkTheme()
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        OptionalPermissionsScreen(
            healthAvailable = healthAvailable,
            onGrantPermissions = {
                scope.launch {
                    requestIosPermissions(healthAvailable)
                    prefsManager.setPermissionsOnboardingShown(true)
                    onboardingShown = true
                }
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
 * Requests HealthKit and Speech Recognition permissions sequentially.
 * Each request shows the system dialog and waits for the user's response.
 */
private suspend fun requestIosPermissions(healthAvailable: Boolean) {
    // 1. Request HealthKit authorization
    if (healthAvailable) {
        try {
            val healthStore = HKHealthStore()
            val workoutType = HKObjectType.workoutType()
            val activeEnergyType = HKQuantityType.quantityTypeForIdentifier(
                HKQuantityTypeIdentifierActiveEnergyBurned,
            )
            val writeTypes = buildSet {
                add(workoutType)
                activeEnergyType?.let { add(it) }
            }

            suspendCancellableCoroutine<Unit> { continuation ->
                healthStore.requestAuthorizationToShareTypes(
                    typesToShare = writeTypes,
                    readTypes = null,
                    completion = { success: Boolean, error: NSError? ->
                        if (error != null) {
                            log.e { "HealthKit authorization error: ${error.localizedDescription}" }
                        }
                        log.d { "HealthKit authorization dialog shown: $success" }
                        continuation.resume(Unit)
                    },
                )
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to request HealthKit permissions" }
        }
    }

    // 2. Request Speech Recognition authorization
    try {
        val currentStatus = SFSpeechRecognizer.authorizationStatus()
        if (currentStatus ==
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined
        ) {
            suspendCancellableCoroutine { continuation ->
                SFSpeechRecognizer.requestAuthorization { status ->
                    log.d { "Speech recognition authorization result: $status" }
                    continuation.resume(Unit)
                }
            }
        } else {
            log.d { "Speech recognition already determined: $currentStatus" }
        }
    } catch (e: Exception) {
        log.e(e) { "Failed to request Speech Recognition permissions" }
    }
}

/**
 * Explanation screen for optional permissions (shared UI, iOS variant).
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
                            "Apple Health -- Automatically sync your workouts to Apple Health so all your fitness data stays in one place.",
                        )
                        append("\n\n")
                    }
                    append(
                        "Speech Recognition -- Enable voice-activated emergency stop (\"safe word\") for hands-free workout safety.",
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
