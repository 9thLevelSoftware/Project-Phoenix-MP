package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.viewmodel.IntervalsCheckState
import com.devil.phoenixproject.presentation.viewmodel.IntervalsFieldError
import com.devil.phoenixproject.presentation.viewmodel.IntervalsOperationError
import com.devil.phoenixproject.presentation.viewmodel.IntervalsPhase
import com.devil.phoenixproject.presentation.viewmodel.IntervalsSettingsUiState
import com.devil.phoenixproject.presentation.viewmodel.IntervalsSettingsViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.intervals_action_check_upload
import projectphoenix.shared.generated.resources.intervals_action_disconnect
import projectphoenix.shared.generated.resources.intervals_action_enable
import projectphoenix.shared.generated.resources.intervals_action_pause
import projectphoenix.shared.generated.resources.intervals_action_resume
import projectphoenix.shared.generated.resources.intervals_action_retry_check
import projectphoenix.shared.generated.resources.intervals_action_save
import projectphoenix.shared.generated.resources.intervals_action_test
import projectphoenix.shared.generated.resources.intervals_action_testing
import projectphoenix.shared.generated.resources.intervals_api_key_hide_cd
import projectphoenix.shared.generated.resources.intervals_api_key_hint
import projectphoenix.shared.generated.resources.intervals_api_key_label
import projectphoenix.shared.generated.resources.intervals_api_key_show_cd
import projectphoenix.shared.generated.resources.intervals_athlete_id_hint
import projectphoenix.shared.generated.resources.intervals_athlete_id_label
import projectphoenix.shared.generated.resources.intervals_back_cd
import projectphoenix.shared.generated.resources.intervals_counters_failed
import projectphoenix.shared.generated.resources.intervals_counters_last_sent
import projectphoenix.shared.generated.resources.intervals_counters_never
import projectphoenix.shared.generated.resources.intervals_counters_queued
import projectphoenix.shared.generated.resources.intervals_counters_sent
import projectphoenix.shared.generated.resources.intervals_counters_title
import projectphoenix.shared.generated.resources.intervals_counters_uncertain
import projectphoenix.shared.generated.resources.intervals_disclosure_backup_tradeoff
import projectphoenix.shared.generated.resources.intervals_disclosure_credentials
import projectphoenix.shared.generated.resources.intervals_disclosure_future_only
import projectphoenix.shared.generated.resources.intervals_disclosure_hr
import projectphoenix.shared.generated.resources.intervals_disclosure_no_background
import projectphoenix.shared.generated.resources.intervals_disclosure_no_edit_sync
import projectphoenix.shared.generated.resources.intervals_disclosure_no_onward
import projectphoenix.shared.generated.resources.intervals_disclosure_strength
import projectphoenix.shared.generated.resources.intervals_disclosure_title
import projectphoenix.shared.generated.resources.intervals_disconnect_confirm
import projectphoenix.shared.generated.resources.intervals_disconnect_message
import projectphoenix.shared.generated.resources.intervals_disconnect_title
import projectphoenix.shared.generated.resources.intervals_error_api_key_required
import projectphoenix.shared.generated.resources.intervals_error_athlete_id_invalid
import projectphoenix.shared.generated.resources.intervals_error_athlete_id_required
import projectphoenix.shared.generated.resources.intervals_error_credentials_rejected
import projectphoenix.shared.generated.resources.intervals_error_enable_failed
import projectphoenix.shared.generated.resources.intervals_error_network
import projectphoenix.shared.generated.resources.intervals_error_validation_rejected
import projectphoenix.shared.generated.resources.intervals_message_auth_required
import projectphoenix.shared.generated.resources.intervals_message_check_done
import projectphoenix.shared.generated.resources.intervals_message_check_failed
import projectphoenix.shared.generated.resources.intervals_message_checking
import projectphoenix.shared.generated.resources.intervals_message_draft
import projectphoenix.shared.generated.resources.intervals_message_enabled
import projectphoenix.shared.generated.resources.intervals_message_off
import projectphoenix.shared.generated.resources.intervals_message_offline_queued
import projectphoenix.shared.generated.resources.intervals_message_paused
import projectphoenix.shared.generated.resources.intervals_message_retry_exhausted
import projectphoenix.shared.generated.resources.intervals_message_saved
import projectphoenix.shared.generated.resources.intervals_message_secure_store_unavailable
import projectphoenix.shared.generated.resources.intervals_message_tested_disabled
import projectphoenix.shared.generated.resources.intervals_message_uncertain
import projectphoenix.shared.generated.resources.intervals_reason_ambiguous_outcome
import projectphoenix.shared.generated.resources.intervals_reason_auth_required
import projectphoenix.shared.generated.resources.intervals_reason_incomplete_reconciliation
import projectphoenix.shared.generated.resources.intervals_reason_label
import projectphoenix.shared.generated.resources.intervals_reason_lease_expired
import projectphoenix.shared.generated.resources.intervals_reason_multiple_remote_matches
import projectphoenix.shared.generated.resources.intervals_reason_no_remote_match
import projectphoenix.shared.generated.resources.intervals_reason_payload_too_large
import projectphoenix.shared.generated.resources.intervals_reason_retry_exhausted
import projectphoenix.shared.generated.resources.intervals_reason_safe_retryable
import projectphoenix.shared.generated.resources.intervals_reason_suppressed_by_pause
import projectphoenix.shared.generated.resources.intervals_reason_validation_rejected
import projectphoenix.shared.generated.resources.intervals_screen_title
import projectphoenix.shared.generated.resources.intervals_status_auth_required
import projectphoenix.shared.generated.resources.intervals_status_disconnect_confirm
import projectphoenix.shared.generated.resources.intervals_status_draft
import projectphoenix.shared.generated.resources.intervals_status_enabled
import projectphoenix.shared.generated.resources.intervals_status_off
import projectphoenix.shared.generated.resources.intervals_status_offline_queued
import projectphoenix.shared.generated.resources.intervals_status_paused
import projectphoenix.shared.generated.resources.intervals_status_retry_exhausted
import projectphoenix.shared.generated.resources.intervals_status_secure_store_unavailable
import projectphoenix.shared.generated.resources.intervals_status_tested_disabled
import projectphoenix.shared.generated.resources.intervals_status_testing
import projectphoenix.shared.generated.resources.intervals_status_uncertain
import projectphoenix.shared.generated.resources.intervals_three_way_queued
import projectphoenix.shared.generated.resources.intervals_three_way_saved
import projectphoenix.shared.generated.resources.intervals_three_way_sent
import projectphoenix.shared.generated.resources.intervals_three_way_title

/**
 * Settings surface for the opt-in Intervals.icu outbound export (issue #1384, packet P5).
 *
 * Everything renders from [IntervalsSettingsUiState] — no mock or inert controls. All copy
 * comes from `intervals_*` Compose resources. The API-key input is masked and its draft is
 * ephemeral (cleared on navigation-away, profile transition and after save). The screen
 * lives in phoneMain only: no Intervals code may sit in commonMain where a non-phone target
 * could pick it up.
 */
@Composable
fun IntervalsSettingsScreen(
    viewModel: IntervalsSettingsViewModel,
    onNavigateBack: () -> Unit = {},
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val apiKeyDraft by viewModel.apiKeyDraft.collectAsState()
    val title = stringResource(Res.string.intervals_screen_title)

    LaunchedEffect(title) { onSetTitle(title) }

    // Leaving the screen (back button, system back, dispose) always drops the drafts.
    DisposableEffect(Unit) {
        onDispose { viewModel.onNavigatedAway() }
    }

    if (state.phase == IntervalsPhase.DISCONNECT_CONFIRM) {
        DestructiveConfirmDialog(
            title = stringResource(Res.string.intervals_disconnect_title),
            message = stringResource(Res.string.intervals_disconnect_message),
            confirmText = stringResource(Res.string.intervals_disconnect_confirm),
            onConfirm = { viewModel.confirmDisconnect() },
            onDismiss = { viewModel.cancelDisconnect() },
            icon = Icons.Default.LinkOff,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // ── Back + title ─────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        viewModel.onNavigatedAway()
                        onNavigateBack()
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.intervals_back_cd),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(Spacing.small))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ── Status card ──────────────────────────────────────────────────
            IntervalsStatusCard(state = state)

            // ── Uncertain outcome: reconciliation check only ─────────────────
            if (state.uncertainCount > 0L || state.phase == IntervalsPhase.UNCERTAIN) {
                IntervalsCheckCard(state = state, viewModel = viewModel)
            }

            // ── Permanent failure with visible reason ────────────────────────
            if (state.permanentFailureCount > 0L) {
                IntervalsPermanentFailureCard(state = state)
            }

            // ── Credential form (pre-connection or credential repair) ────────
            if (!state.connected || state.phase == IntervalsPhase.AUTH_REQUIRED) {
                IntervalsCredentialsCard(
                    state = state,
                    apiKeyDraft = apiKeyDraft,
                    viewModel = viewModel,
                )
            }

            // ── Connected actions ────────────────────────────────────────────
            if (state.connected && state.phase != IntervalsPhase.DISCONNECT_CONFIRM) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (state.paused) viewModel.resume() else viewModel.pause()
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (state.paused) {
                                    Res.string.intervals_action_resume
                                } else {
                                    Res.string.intervals_action_pause
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(
                        onClick = { viewModel.disconnect() },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(Res.string.intervals_action_disconnect),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── Three-way distinction: saved vs queued vs sent ────────────────
            IntervalsThreeWayCard()

            // ── Disclosure ───────────────────────────────────────────────────
            IntervalsDisclosureCard()
        }
    }
}

// ─── Status ──────────────────────────────────────────────────────────────────

@Composable
private fun IntervalsStatusCard(state: IntervalsSettingsUiState) {
    val colors = AccessibilityTheme.colors
    val (chipColor, chipContentColor) = when (state.phase) {
        IntervalsPhase.ENABLED, IntervalsPhase.TESTED_DISABLED ->
            colors.connectedGreen.copy(alpha = 0.15f) to colors.connectedGreen
        IntervalsPhase.PAUSED, IntervalsPhase.OFFLINE_QUEUED, IntervalsPhase.UNCERTAIN ->
            colors.warning.copy(alpha = 0.15f) to colors.warning
        IntervalsPhase.AUTH_REQUIRED, IntervalsPhase.RETRY_EXHAUSTED, IntervalsPhase.SECURE_STORE_UNAVAILABLE ->
            colors.error.copy(alpha = 0.15f) to colors.error
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    StaticSettingsCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            StatusIcon(state.phase)
            Surface(shape = MaterialTheme.shapes.small, color = chipColor) {
                Text(
                    phaseLabel(state.phase),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = chipContentColor,
                    modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                )
            }
        }
        Text(
            phaseMessage(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.saveSucceeded) {
            Text(
                stringResource(Res.string.intervals_message_saved),
                style = MaterialTheme.typography.bodySmall,
                color = colors.connectedGreen,
            )
        }

        // Profile/destination-scoped counters — never global.
        if (state.connected) {
            Spacer(Modifier.height(Spacing.small))
            Text(
                stringResource(Res.string.intervals_counters_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            state.resolvedAthleteId?.let { athleteId ->
                Text(
                    athleteId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CounterRow(
                label = stringResource(Res.string.intervals_counters_last_sent),
                value = state.lastSentAt?.let { KmpUtils.formatTimestamp(it) }
                    ?: stringResource(Res.string.intervals_counters_never),
            )
            CounterRow(
                label = stringResource(Res.string.intervals_counters_queued),
                value = state.queuedCount.toString(),
            )
            CounterRow(
                label = stringResource(Res.string.intervals_counters_sent),
                value = state.sentCount.toString(),
            )
            CounterRow(
                label = stringResource(Res.string.intervals_counters_uncertain),
                value = state.uncertainCount.toString(),
            )
            CounterRow(
                label = stringResource(Res.string.intervals_counters_failed),
                value = state.permanentFailureCount.toString(),
            )
        }
    }
}

@Composable
private fun StatusIcon(phase: IntervalsPhase) {
    val icon: ImageVector
    val tint: Color
    when (phase) {
        IntervalsPhase.ENABLED, IntervalsPhase.TESTED_DISABLED -> {
            icon = Icons.Default.CheckCircle
            tint = AccessibilityTheme.colors.connectedGreen
        }
        IntervalsPhase.AUTH_REQUIRED,
        IntervalsPhase.RETRY_EXHAUSTED,
        IntervalsPhase.SECURE_STORE_UNAVAILABLE,
        -> {
            icon = Icons.Default.ErrorOutline
            tint = MaterialTheme.colorScheme.error
        }
        IntervalsPhase.PAUSED,
        IntervalsPhase.OFFLINE_QUEUED,
        IntervalsPhase.UNCERTAIN,
        -> {
            icon = Icons.Default.Info
            tint = AccessibilityTheme.colors.warning
        }
        else -> {
            icon = Icons.Default.CloudUpload
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

@Composable
private fun CounterRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

// ─── Uncertain outcome ───────────────────────────────────────────────────────

@Composable
private fun IntervalsCheckCard(state: IntervalsSettingsUiState, viewModel: IntervalsSettingsViewModel) {
    val colors = AccessibilityTheme.colors
    StaticSettingsCard {
        Text(
            stringResource(Res.string.intervals_status_uncertain),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colors.warning,
        )
        Text(
            stringResource(Res.string.intervals_message_uncertain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.checkState) {
            IntervalsCheckState.CHECKING -> Text(
                stringResource(Res.string.intervals_message_checking),
                style = MaterialTheme.typography.bodySmall,
            )
            IntervalsCheckState.CHECKED -> Text(
                stringResource(Res.string.intervals_message_check_done),
                style = MaterialTheme.typography.bodySmall,
                color = colors.connectedGreen,
            )
            IntervalsCheckState.FAILED -> {
                Text(
                    stringResource(Res.string.intervals_message_check_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { viewModel.retryCheck() },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        stringResource(Res.string.intervals_action_retry_check),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            IntervalsCheckState.IDLE -> Unit
        }
        // The only recovery path for an ambiguous outcome is reconciliation —
        // a blind "upload again" is forbidden by contract (spec §4.4).
        if (state.checkState != IntervalsCheckState.CHECKING) {
            OutlinedButton(
                onClick = { viewModel.checkUpload() },
                enabled = state.checkState != IntervalsCheckState.CHECKING,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    stringResource(Res.string.intervals_action_check_upload),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ─── Permanent failure ───────────────────────────────────────────────────────

@Composable
private fun IntervalsPermanentFailureCard(state: IntervalsSettingsUiState) {
    StaticSettingsCard {
        Text(
            stringResource(Res.string.intervals_status_retry_exhausted),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(Res.string.intervals_message_retry_exhausted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.permanentFailureReason?.let { reason ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    stringResource(Res.string.intervals_reason_label),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    reasonLabel(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ─── Credential form ─────────────────────────────────────────────────────────

@Composable
private fun IntervalsCredentialsCard(
    state: IntervalsSettingsUiState,
    apiKeyDraft: String,
    viewModel: IntervalsSettingsViewModel,
) {
    var showApiKey by remember { mutableStateOf(false) }

    StaticSettingsCard {
        Text(
            stringResource(Res.string.intervals_athlete_id_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = state.athleteIdDraft,
            onValueChange = viewModel::onAthleteIdChange,
            label = { Text(stringResource(Res.string.intervals_athlete_id_label)) },
            placeholder = { Text(stringResource(Res.string.intervals_athlete_id_hint)) },
            singleLine = true,
            isError = state.athleteIdError != null,
            supportingText = {
                when (state.athleteIdError) {
                    IntervalsFieldError.ATHLETE_ID_REQUIRED ->
                        Text(stringResource(Res.string.intervals_error_athlete_id_required))
                    IntervalsFieldError.ATHLETE_ID_INVALID ->
                        Text(stringResource(Res.string.intervals_error_athlete_id_invalid))
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        val apiKeyLabel = stringResource(Res.string.intervals_api_key_label)
        OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text(apiKeyLabel) },
            placeholder = { Text(stringResource(Res.string.intervals_api_key_hint)) },
            singleLine = true,
            // Secret hygiene: the password keyboard type stops IME autocorrect/learning
            // from collecting the key (repo convention: LinkAccountScreen).
            // PasswordVisualTransformation masks both the rendering and the semantics
            // text accessibility services announce (EditableText = masked dots, with the
            // [Password] semantics flag) — pinned by the masked-input UI test. The raw
            // value remains only in InputText, the IME/test edit plumbing.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showApiKey = !showApiKey }) {
                    Icon(
                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (showApiKey) {
                                Res.string.intervals_api_key_hide_cd
                            } else {
                                Res.string.intervals_api_key_show_cd
                            },
                        ),
                    )
                }
            },
            isError = state.apiKeyError != null,
            supportingText = {
                if (state.apiKeyError == IntervalsFieldError.API_KEY_REQUIRED) {
                    Text(stringResource(Res.string.intervals_error_api_key_required))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )

        when (state.operationError) {
            IntervalsOperationError.NETWORK ->
                FormErrorText(stringResource(Res.string.intervals_error_network))
            IntervalsOperationError.CREDENTIALS_REJECTED ->
                FormErrorText(stringResource(Res.string.intervals_error_credentials_rejected))
            IntervalsOperationError.VALIDATION_REJECTED ->
                FormErrorText(stringResource(Res.string.intervals_error_validation_rejected))
            IntervalsOperationError.ENABLE_FAILED ->
                FormErrorText(stringResource(Res.string.intervals_error_enable_failed))
            null -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            OutlinedButton(
                onClick = { viewModel.saveCredentials() },
                enabled = !state.isSaving,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(Res.string.intervals_action_save), style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { viewModel.testConnection() },
                enabled = !state.isTesting,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    stringResource(
                        if (state.isTesting) {
                            Res.string.intervals_action_testing
                        } else {
                            Res.string.intervals_action_test
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // Explicit opt-in. Testing never enables; only this action does. Gated on a
        // SAVED credential (a stored connection or a successful save), not on a test of
        // the draft key: opt-in before anything was saved must be impossible.
        Button(
            onClick = { viewModel.enable() },
            enabled = (state.saveSucceeded || state.connected) && !state.isTesting && !state.isSaving,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.intervals_action_enable), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FormErrorText(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

// ─── Three-way distinction + disclosure ──────────────────────────────────────

@Composable
private fun IntervalsThreeWayCard() {
    StaticSettingsCard {
        Text(
            stringResource(Res.string.intervals_three_way_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(Res.string.intervals_three_way_saved), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.intervals_three_way_queued), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.intervals_three_way_sent), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IntervalsDisclosureCard() {
    StaticSettingsCard {
        Text(
            stringResource(Res.string.intervals_disclosure_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_strength))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_hr))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_credentials))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_future_only))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_no_edit_sync))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_no_onward))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_no_background))
        DisclosureBullet(stringResource(Res.string.intervals_disclosure_backup_tradeoff))
    }
}

@Composable
private fun DisclosureBullet(text: String) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ─── Shared building blocks ──────────────────────────────────────────────────

/** Static informational card per the repo card convention (no tap action). */
@Composable
private fun StaticSettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            content = content,
        )
    }
}

// ─── Label / message mappings (all copy from intervals_* resources) ──────────

@Composable
private fun phaseLabel(phase: IntervalsPhase): String = when (phase) {
    IntervalsPhase.OFF -> stringResource(Res.string.intervals_status_off)
    IntervalsPhase.DRAFT -> stringResource(Res.string.intervals_status_draft)
    IntervalsPhase.TESTING -> stringResource(Res.string.intervals_status_testing)
    IntervalsPhase.TESTED_DISABLED -> stringResource(Res.string.intervals_status_tested_disabled)
    IntervalsPhase.ENABLED -> stringResource(Res.string.intervals_status_enabled)
    IntervalsPhase.PAUSED -> stringResource(Res.string.intervals_status_paused)
    IntervalsPhase.AUTH_REQUIRED -> stringResource(Res.string.intervals_status_auth_required)
    IntervalsPhase.SECURE_STORE_UNAVAILABLE -> stringResource(Res.string.intervals_status_secure_store_unavailable)
    IntervalsPhase.OFFLINE_QUEUED -> stringResource(Res.string.intervals_status_offline_queued)
    IntervalsPhase.RETRY_EXHAUSTED -> stringResource(Res.string.intervals_status_retry_exhausted)
    IntervalsPhase.UNCERTAIN -> stringResource(Res.string.intervals_status_uncertain)
    IntervalsPhase.DISCONNECT_CONFIRM -> stringResource(Res.string.intervals_status_disconnect_confirm)
}

@Composable
private fun phaseMessage(state: IntervalsSettingsUiState): String = when (state.phase) {
    IntervalsPhase.OFF -> stringResource(Res.string.intervals_message_off)
    IntervalsPhase.DRAFT -> stringResource(Res.string.intervals_message_draft)
    IntervalsPhase.TESTING -> stringResource(Res.string.intervals_action_testing)
    IntervalsPhase.TESTED_DISABLED -> stringResource(Res.string.intervals_message_tested_disabled)
    IntervalsPhase.ENABLED -> stringResource(Res.string.intervals_message_enabled)
    IntervalsPhase.PAUSED -> stringResource(Res.string.intervals_message_paused)
    IntervalsPhase.AUTH_REQUIRED -> stringResource(Res.string.intervals_message_auth_required)
    IntervalsPhase.SECURE_STORE_UNAVAILABLE -> stringResource(Res.string.intervals_message_secure_store_unavailable)
    IntervalsPhase.OFFLINE_QUEUED -> stringResource(Res.string.intervals_message_offline_queued)
    IntervalsPhase.RETRY_EXHAUSTED -> stringResource(Res.string.intervals_message_retry_exhausted)
    IntervalsPhase.UNCERTAIN -> stringResource(Res.string.intervals_message_uncertain)
    IntervalsPhase.DISCONNECT_CONFIRM -> stringResource(Res.string.intervals_disconnect_message)
}

@Composable
private fun reasonLabel(reason: IntervalsReasonCode): String = when (reason) {
    IntervalsReasonCode.RETRY_EXHAUSTED -> stringResource(Res.string.intervals_reason_retry_exhausted)
    IntervalsReasonCode.LEASE_EXPIRED -> stringResource(Res.string.intervals_reason_lease_expired)
    IntervalsReasonCode.AUTH_REQUIRED -> stringResource(Res.string.intervals_reason_auth_required)
    IntervalsReasonCode.VALIDATION_REJECTED -> stringResource(Res.string.intervals_reason_validation_rejected)
    IntervalsReasonCode.PAYLOAD_TOO_LARGE -> stringResource(Res.string.intervals_reason_payload_too_large)
    IntervalsReasonCode.SAFE_RETRYABLE -> stringResource(Res.string.intervals_reason_safe_retryable)
    IntervalsReasonCode.AMBIGUOUS_OUTCOME -> stringResource(Res.string.intervals_reason_ambiguous_outcome)
    IntervalsReasonCode.INCOMPLETE_RECONCILIATION -> stringResource(Res.string.intervals_reason_incomplete_reconciliation)
    IntervalsReasonCode.NO_REMOTE_MATCH -> stringResource(Res.string.intervals_reason_no_remote_match)
    IntervalsReasonCode.MULTIPLE_REMOTE_MATCHES -> stringResource(Res.string.intervals_reason_multiple_remote_matches)
    IntervalsReasonCode.SUPPRESSED_BY_PAUSE -> stringResource(Res.string.intervals_reason_suppressed_by_pause)
}
