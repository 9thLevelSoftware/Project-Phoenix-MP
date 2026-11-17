package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.garmin.GarminAvailability
import com.devil.phoenixproject.presentation.garmin.GarminArmUiState
import com.devil.phoenixproject.presentation.garmin.GarminConnectionUiState
import com.devil.phoenixproject.presentation.garmin.GarminReadiness
import com.devil.phoenixproject.presentation.garmin.GarminSessionController
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_dismiss
import projectphoenix.shared.generated.resources.garmin_cancel_body
import projectphoenix.shared.generated.resources.garmin_cancel_confirm
import projectphoenix.shared.generated.resources.garmin_cancel_safety
import projectphoenix.shared.generated.resources.garmin_cancel_title
import projectphoenix.shared.generated.resources.garmin_cd_arm
import projectphoenix.shared.generated.resources.garmin_cd_disarm
import projectphoenix.shared.generated.resources.garmin_cd_status
import projectphoenix.shared.generated.resources.garmin_controls_generic_device
import projectphoenix.shared.generated.resources.garmin_strip_arm
import projectphoenix.shared.generated.resources.garmin_strip_armed_hint
import projectphoenix.shared.generated.resources.garmin_strip_armed_title
import projectphoenix.shared.generated.resources.garmin_strip_cancel_exercise
import projectphoenix.shared.generated.resources.garmin_strip_device_not_armed
import projectphoenix.shared.generated.resources.garmin_strip_disarm
import projectphoenix.shared.generated.resources.garmin_strip_error_hint
import projectphoenix.shared.generated.resources.garmin_strip_error_hint_plain
import projectphoenix.shared.generated.resources.garmin_strip_error_title
import projectphoenix.shared.generated.resources.garmin_strip_not_armed_hint
import projectphoenix.shared.generated.resources.garmin_strip_phone_input
import projectphoenix.shared.generated.resources.garmin_strip_recovery_hint
import projectphoenix.shared.generated.resources.garmin_strip_recovery_title

/**
 * Per-workout Garmin Arm strip (issue #1391 Phase 1).
 *
 * A slim band shown on the SetReady / ActiveWorkout / JustLift screens. States follow the
 * approved prototype frames: not-armed / armed / disarmed (back to not-armed) plus honest
 * error / recovery presentations. Hidden entirely while the feature is OFF or unavailable
 * (release boundary, signoff correction 7) — zero disruption to existing controls.
 *
 * Hard constraint (signoff correction 5): the strip is a compact row/band with no fixed
 * heights — it must never overlap or push the STOP button, rep counter or weight readout
 * off-screen at default or largest accessibility text sizes. The phone Prev/START/Next/✕
 * controls are untouched by this component.
 *
 * Copy honesty: "Watch Start = this START" when armed; stop is "Stop set" semantics (never
 * "Discard"/"End workout"); remote control is "Ergonomic remote, NOT an emergency stop".
 */
@Composable
fun GarminControlsArmStrip(
    modifier: Modifier = Modifier,
    garminController: GarminSessionController = koinInject(),
    onConfirmSkipExercise: (() -> Unit)? = null,
) {
    val connection by garminController.connectionState.collectAsState()
    val arm by garminController.armState.collectAsState()
    var showCancelSheet by remember { mutableStateOf(false) }

    val stripState = garminStripState(connection, arm)

    AnimatedVisibility(
        visible = stripState != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        GarminControlsArmStripContent(
            modifier = modifier,
            stripState = stripState ?: GarminStripState.NOT_ARMED,
            connection = connection,
            arm = arm,
            onArm = garminController::arm,
            onDisarm = garminController::disarm,
            onRequestCancelConfirmation = {
                if (arm.cancelConfirmationLabel != null && onConfirmSkipExercise != null) {
                    showCancelSheet = true
                }
            },
            onCancelAvailable = arm.cancelConfirmationLabel != null && onConfirmSkipExercise != null,
        )
    }

    val cancelLabel = arm.cancelConfirmationLabel
    if (showCancelSheet && cancelLabel != null) {
        GarminCancelExerciseConfirmation(
            exerciseLabel = cancelLabel,
            onConfirm = {
                showCancelSheet = false
                onConfirmSkipExercise?.invoke()
            },
            onDismiss = { showCancelSheet = false },
        )
    }
}

/** Strip presentation states (prototype frames: not-armed / armed / disarmed + error / recovery). */
enum class GarminStripState {
    NOT_ARMED,
    ARMED,
    ERROR,
    RECOVERY,
}

/**
 * Maps the pinned controller UI state to the strip state. Returns null when the strip must
 * not be shown at all: feature OFF, or no matched watch app (release boundary), or the
 * feature is still in setup where the Settings card owns the flow.
 */
internal fun garminStripState(
    connection: GarminConnectionUiState,
    arm: GarminArmUiState,
): GarminStripState? {
    if (!connection.enabled || !connection.watchAppMatched) return null
    return when (connection.availability) {
        GarminAvailability.CONNECTED ->
            if (arm.armed) GarminStripState.ARMED else GarminStripState.NOT_ARMED
        GarminAvailability.ARMED -> GarminStripState.ARMED
        GarminAvailability.ERROR -> GarminStripState.ERROR
        GarminAvailability.RECOVERY -> GarminStripState.RECOVERY
        GarminAvailability.OFF,
        GarminAvailability.UNAVAILABLE,
        GarminAvailability.SETUP,
        -> null
    }
}

@Composable
private fun GarminControlsArmStripContent(
    stripState: GarminStripState,
    connection: GarminConnectionUiState,
    arm: GarminArmUiState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRequestCancelConfirmation: () -> Unit,
    onCancelAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (stripState) {
        GarminStripState.NOT_ARMED -> MaterialTheme.colorScheme.surfaceContainerHigh
        GarminStripState.ARMED -> MaterialTheme.colorScheme.primaryContainer
        GarminStripState.ERROR -> MaterialTheme.colorScheme.errorContainer
        GarminStripState.RECOVERY -> MaterialTheme.colorScheme.errorContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            val title: String
            val hint: String
            when (stripState) {
                GarminStripState.NOT_ARMED -> {
                    val device = connection.deviceName?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.garmin_controls_generic_device)
                    title = stringResource(Res.string.garmin_strip_device_not_armed, device)
                    hint = stringResource(Res.string.garmin_strip_not_armed_hint)
                }
                GarminStripState.ARMED -> {
                    title = stringResource(Res.string.garmin_strip_armed_title)
                    hint = stringResource(Res.string.garmin_strip_armed_hint)
                }
                GarminStripState.ERROR -> {
                    title = stringResource(Res.string.garmin_strip_error_title)
                    val code = connection.statusDetailCode
                    hint = if (code != null) {
                        stringResource(Res.string.garmin_strip_error_hint, code)
                    } else {
                        stringResource(Res.string.garmin_strip_error_hint_plain)
                    }
                }
                GarminStripState.RECOVERY -> {
                    title = stringResource(Res.string.garmin_strip_recovery_title)
                    hint = stringResource(Res.string.garmin_strip_recovery_hint)
                }
            }

            Icon(
                imageVector = when (stripState) {
                    GarminStripState.NOT_ARMED -> Icons.Default.Settings
                    GarminStripState.ARMED -> Icons.Default.CheckCircle
                    GarminStripState.ERROR -> Icons.Default.Warning
                    GarminStripState.RECOVERY -> Icons.Default.Warning
                },
                contentDescription = title,
                modifier = Modifier.size(20.dp),
                tint = when (stripState) {
                    GarminStripState.ARMED -> MaterialTheme.colorScheme.primary
                    GarminStripState.ERROR, GarminStripState.RECOVERY -> MaterialTheme.colorScheme.error
                    GarminStripState.NOT_ARMED -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (stripState == GarminStripState.ARMED) {
                    // Live GarminArmUiState labels — display-only context, never authority.
                    val liveLine = listOf(arm.displayLabel, arm.actionLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (liveLine.isNotBlank()) {
                        Text(
                            liveLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                    }
                    if (arm.readiness == GarminReadiness.PHONE_INPUT_REQUIRED) {
                        Text(
                            stringResource(Res.string.garmin_strip_phone_input),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            when (stripState) {
                GarminStripState.NOT_ARMED -> {
                    val armCd = stringResource(Res.string.garmin_cd_arm)
                    TextButton(
                        onClick = onArm,
                        modifier = Modifier
                            .padding(start = Spacing.extraSmall)
                            .semantics { contentDescription = armCd },
                    ) {
                        Text(
                            stringResource(Res.string.garmin_strip_arm),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                GarminStripState.ARMED -> {
                    Column(horizontalAlignment = Alignment.End) {
                        val disarmCd = stringResource(Res.string.garmin_cd_disarm)
                        TextButton(
                            onClick = onDisarm,
                            modifier = Modifier.semantics { contentDescription = disarmCd },
                        ) {
                            Text(
                                stringResource(Res.string.garmin_strip_disarm),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (onCancelAvailable) {
                            TextButton(onClick = onRequestCancelConfirmation) {
                                Text(stringResource(Res.string.garmin_strip_cancel_exercise))
                            }
                        }
                    }
                }
                GarminStripState.ERROR, GarminStripState.RECOVERY -> {
                    // No Arm/Disarm while error/recovery — the Settings card owns the fix path.
                    Spacer(modifier = Modifier.width(Spacing.extraSmall))
                }
            }
        }
    }
}

/**
 * Cancel-confirmation sheet naming the exercise (issue #1391). Opened from the Arm strip.
 *
 * Copy honesty: skipping cancels only the named exercise — never the whole routine — and
 * previously saved sets stay saved. The watch remote is an ergonomic remote, NOT an
 * emergency stop: a lost link cannot guarantee Stop delivery.
 */
@Composable
fun GarminCancelExerciseConfirmation(
    exerciseLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(Res.string.garmin_cancel_title, exerciseLabel))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(stringResource(Res.string.garmin_cancel_body, exerciseLabel))
                Text(
                    stringResource(Res.string.garmin_cancel_safety),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(Res.string.garmin_cancel_confirm, exerciseLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_dismiss))
            }
        },
    )
}
