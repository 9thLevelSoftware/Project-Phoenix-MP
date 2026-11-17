package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.garmin.GarminAvailability
import com.devil.phoenixproject.presentation.garmin.GarminArmUiState
import com.devil.phoenixproject.presentation.garmin.GarminConnectionUiState
import com.devil.phoenixproject.presentation.garmin.GarminControlEvent
import com.devil.phoenixproject.presentation.garmin.GarminSessionController
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.garmin_cd_status
import projectphoenix.shared.generated.resources.garmin_controls_arm_action
import projectphoenix.shared.generated.resources.garmin_controls_armed_body
import projectphoenix.shared.generated.resources.garmin_controls_choose_device
import projectphoenix.shared.generated.resources.garmin_controls_connected_body
import projectphoenix.shared.generated.resources.garmin_controls_default_off
import projectphoenix.shared.generated.resources.garmin_controls_device_label
import projectphoenix.shared.generated.resources.garmin_controls_disarm_action
import projectphoenix.shared.generated.resources.garmin_controls_error_body
import projectphoenix.shared.generated.resources.garmin_controls_error_code
import projectphoenix.shared.generated.resources.garmin_controls_foreground
import projectphoenix.shared.generated.resources.garmin_controls_forget
import projectphoenix.shared.generated.resources.garmin_controls_forget_desc
import projectphoenix.shared.generated.resources.garmin_controls_install_action
import projectphoenix.shared.generated.resources.garmin_controls_install_hint
import projectphoenix.shared.generated.resources.garmin_controls_intro
import projectphoenix.shared.generated.resources.garmin_controls_last_command
import projectphoenix.shared.generated.resources.garmin_controls_local_opt_in
import projectphoenix.shared.generated.resources.garmin_controls_local_opt_in_desc
import projectphoenix.shared.generated.resources.garmin_controls_recovery_body
import projectphoenix.shared.generated.resources.garmin_controls_setup_body
import projectphoenix.shared.generated.resources.garmin_controls_status_armed
import projectphoenix.shared.generated.resources.garmin_controls_status_connected
import projectphoenix.shared.generated.resources.garmin_controls_status_error
import projectphoenix.shared.generated.resources.garmin_controls_status_off
import projectphoenix.shared.generated.resources.garmin_controls_status_recovery
import projectphoenix.shared.generated.resources.garmin_controls_status_setup
import projectphoenix.shared.generated.resources.garmin_controls_status_unavailable
import projectphoenix.shared.generated.resources.garmin_controls_title
import projectphoenix.shared.generated.resources.garmin_controls_unavailable_body
import projectphoenix.shared.generated.resources.garmin_event_applied
import projectphoenix.shared.generated.resources.garmin_event_pending
import projectphoenix.shared.generated.resources.garmin_event_rejected
import projectphoenix.shared.generated.resources.garmin_event_stale
import projectphoenix.shared.generated.resources.garmin_event_unknown

/**
 * Garmin watch-controls settings card (issue #1391 Phase 1).
 *
 * Placed beside the HRM [HrmSensorSettingsCard] in SettingsTab. Garmin CONTROL (Connect IQ
 * messaging) copy is kept strictly separate from Heart-rate sensor (BLE) copy — this card says
 * "Garmin controls", never "Heart-rate sensor", except to state that the two are separate.
 *
 * Opt-in default is OFF. States map from [GarminConnectionUiState] + [GarminArmUiState].
 * Release boundary (signoff correction 7): when [GarminConnectionUiState.watchAppMatched] is
 * false the card renders the UNAVAILABLE state with NO Install/Arm actions — the feature stays
 * hidden until a supported, matched watch app is detected.
 */
@Composable
fun GarminControlsSettingsCard(
    modifier: Modifier = Modifier,
    garminControllerOrNull: GarminSessionController? = null,
) {
    // Best-effort resolution (issue #1391): without the Garmin Koin bindings (unit
    // tests, early startup) the card stays hidden instead of failing composition.
    val garminController = garminControllerOrNull
        ?: rememberGarminSessionControllerOrNull()
        ?: return
    val connection by garminController.connectionState.collectAsState()
    val arm by garminController.armState.collectAsState()
    val lastEvent by garminController.lastEvent.collectAsState()

    val cardState = garminControlsCardState(connection, arm)
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            // Header row: icon + title + state label (state is text, never colour alone).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF455A64), Color(0xFF263238)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(Res.string.garmin_cd_status),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Column {
                        Text(
                            stringResource(Res.string.garmin_controls_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            garminControlsStateLabel(cardState),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = when (cardState) {
                                GarminControlsCardState.ERROR -> MaterialTheme.colorScheme.error
                                GarminControlsCardState.CONNECTED -> Color(0xFF00C853)
                                GarminControlsCardState.ARMED -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // Shared explanatory copy — strictly separate from the Heart-rate sensor (BLE) card.
            Text(
                stringResource(Res.string.garmin_controls_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.garmin_controls_foreground),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.garmin_controls_default_off),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // Master opt-in toggle (default OFF). Disabling revokes all authority immediately.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.garmin_controls_local_opt_in),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.garmin_controls_local_opt_in_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = connection.enabled,
                    onCheckedChange = garminController::setEnabled,
                )
            }

            if (connection.deviceName != null && connection.deviceName!!.isNotBlank()) {
                Text(
                    stringResource(Res.string.garmin_controls_device_label, connection.deviceName!!),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // State-specific body + actions.
            when (cardState) {
                GarminControlsCardState.OFF -> {
                    Text(
                        stringResource(Res.string.garmin_controls_install_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    OutlinedButton(onClick = { uriHandler.openUri(GARMIN_CONNECT_IQ_STORE_URL) }) {
                        Text(stringResource(Res.string.garmin_controls_install_action))
                    }
                }

                // Release boundary (signoff correction 7): NO Install/Arm actions here.
                GarminControlsCardState.UNAVAILABLE -> {
                    Text(
                        stringResource(Res.string.garmin_controls_unavailable_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        OutlinedButton(onClick = garminController::beginDeviceSelection) {
                            Text(stringResource(Res.string.garmin_controls_choose_device))
                        }
                        if (connection.deviceName != null) {
                            OutlinedButton(onClick = garminController::forgetDevice) {
                                Text(
                                    stringResource(Res.string.garmin_controls_forget),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                GarminControlsCardState.SETUP -> {
                    Text(
                        stringResource(Res.string.garmin_controls_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(Res.string.garmin_controls_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Button(onClick = { uriHandler.openUri(GARMIN_CONNECT_IQ_STORE_URL) }) {
                            Text(stringResource(Res.string.garmin_controls_install_action))
                        }
                        OutlinedButton(onClick = garminController::beginDeviceSelection) {
                            Text(stringResource(Res.string.garmin_controls_choose_device))
                        }
                        if (connection.deviceName != null) {
                            OutlinedButton(onClick = garminController::forgetDevice) {
                                Text(
                                    stringResource(Res.string.garmin_controls_forget),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                GarminControlsCardState.CONNECTED -> {
                    // Connected is NOT armed — say so explicitly.
                    Text(
                        stringResource(Res.string.garmin_controls_connected_body),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Button(onClick = garminController::arm) {
                            Text(stringResource(Res.string.garmin_controls_arm_action))
                        }
                        OutlinedButton(onClick = garminController::forgetDevice) {
                            Text(
                                stringResource(Res.string.garmin_controls_forget),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                GarminControlsCardState.ARMED -> {
                    Text(
                        stringResource(Res.string.garmin_controls_armed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // Live GarminArmUiState labels; display-only, never authority.
                    if (arm.displayLabel.isNotBlank()) {
                        Text(
                            arm.displayLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (arm.actionLabel.isNotBlank()) {
                        Text(
                            arm.actionLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    OutlinedButton(onClick = garminController::disarm) {
                        Text(stringResource(Res.string.garmin_controls_disarm_action))
                    }
                }

                GarminControlsCardState.ERROR -> {
                    // Errors are named in text (never colour alone) with a bounded detail code.
                    Text(
                        stringResource(Res.string.garmin_controls_error_body),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    connection.statusDetailCode?.let { code ->
                        Text(
                            stringResource(Res.string.garmin_controls_error_code, code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        OutlinedButton(onClick = garminController::beginDeviceSelection) {
                            Text(stringResource(Res.string.garmin_controls_choose_device))
                        }
                        OutlinedButton(onClick = garminController::forgetDevice) {
                            Text(
                                stringResource(Res.string.garmin_controls_forget),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                GarminControlsCardState.RECOVERY -> {
                    Text(
                        stringResource(Res.string.garmin_controls_recovery_body),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        OutlinedButton(onClick = garminController::beginDeviceSelection) {
                            Text(stringResource(Res.string.garmin_controls_choose_device))
                        }
                        OutlinedButton(onClick = garminController::forgetDevice) {
                            Text(
                                stringResource(Res.string.garmin_controls_forget),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (cardState != GarminControlsCardState.OFF) {
                Text(
                    stringResource(Res.string.garmin_controls_forget_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Observable outcome of the most recent watch command (bounded, honest).
            lastEvent?.let { event ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        stringResource(
                            Res.string.garmin_controls_last_command,
                            garminLastEventLabel(event),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.small),
                    )
                }
            }
        }
    }
}

/** Card presentation states, derived from the pinned GarminSessionController UI contract. */
enum class GarminControlsCardState {
    OFF,
    UNAVAILABLE,
    SETUP,
    CONNECTED,
    ARMED,
    ERROR,
    RECOVERY,
}

private const val GARMIN_CONNECT_IQ_STORE_URL = "https://apps.garmin.com/apps"

/**
 * Maps [GarminConnectionUiState] + [GarminArmUiState] to the seven card states.
 *
 * Release boundary (signoff correction 7): when [GarminConnectionUiState.watchAppMatched] is
 * false the feature is hidden behind the UNAVAILABLE presentation — no Install/Arm actions —
 * until a supported, matched watch app is detected.
 */
internal fun garminControlsCardState(
    connection: GarminConnectionUiState,
    arm: GarminArmUiState,
): GarminControlsCardState = when {
    !connection.enabled -> GarminControlsCardState.OFF
    !connection.watchAppMatched -> GarminControlsCardState.UNAVAILABLE
    else -> when (connection.availability) {
        GarminAvailability.OFF -> GarminControlsCardState.OFF
        GarminAvailability.UNAVAILABLE -> GarminControlsCardState.UNAVAILABLE
        GarminAvailability.SETUP -> GarminControlsCardState.SETUP
        GarminAvailability.CONNECTED ->
            if (arm.armed) GarminControlsCardState.ARMED else GarminControlsCardState.CONNECTED
        GarminAvailability.ARMED -> GarminControlsCardState.ARMED
        GarminAvailability.ERROR -> GarminControlsCardState.ERROR
        GarminAvailability.RECOVERY -> GarminControlsCardState.RECOVERY
    }
}

@Composable
internal fun garminControlsStateLabel(state: GarminControlsCardState): String = when (state) {
    GarminControlsCardState.OFF -> stringResource(Res.string.garmin_controls_status_off)
    GarminControlsCardState.UNAVAILABLE -> stringResource(Res.string.garmin_controls_status_unavailable)
    GarminControlsCardState.SETUP -> stringResource(Res.string.garmin_controls_status_setup)
    GarminControlsCardState.CONNECTED -> stringResource(Res.string.garmin_controls_status_connected)
    GarminControlsCardState.ARMED -> stringResource(Res.string.garmin_controls_status_armed)
    GarminControlsCardState.ERROR -> stringResource(Res.string.garmin_controls_status_error)
    GarminControlsCardState.RECOVERY -> stringResource(Res.string.garmin_controls_status_recovery)
}

@Composable
internal fun garminLastEventLabel(event: GarminControlEvent): String = when (event) {
    is GarminControlEvent.Pending -> stringResource(Res.string.garmin_event_pending, event.action)
    is GarminControlEvent.Applied -> stringResource(Res.string.garmin_event_applied, event.action)
    is GarminControlEvent.Stale -> stringResource(Res.string.garmin_event_stale, event.action)
    is GarminControlEvent.Rejected -> stringResource(Res.string.garmin_event_rejected, event.action, event.reasonCode)
    is GarminControlEvent.UnknownResult -> stringResource(Res.string.garmin_event_unknown, event.action)
}
