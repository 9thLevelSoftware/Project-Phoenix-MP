package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.presentation.manager.BlockedReason
import com.devil.phoenixproject.presentation.manager.TelemetryCleanupUiState
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_ok
import projectphoenix.shared.generated.resources.telemetry_cleanup_blocked_backup
import projectphoenix.shared.generated.resources.telemetry_cleanup_blocked_workout
import projectphoenix.shared.generated.resources.telemetry_cleanup_checking
import projectphoenix.shared.generated.resources.telemetry_cleanup_checking_progress
import projectphoenix.shared.generated.resources.telemetry_cleanup_clean_action
import projectphoenix.shared.generated.resources.telemetry_cleanup_cleaning_progress
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_db
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_noop
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_removed_one
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_removed_other
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_restart
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_reusable
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_sets_one
import projectphoenix.shared.generated.resources.telemetry_cleanup_done_sets_other
import projectphoenix.shared.generated.resources.telemetry_cleanup_estimate_backup
import projectphoenix.shared.generated.resources.telemetry_cleanup_estimate_backup_value
import projectphoenix.shared.generated.resources.telemetry_cleanup_estimate_db
import projectphoenix.shared.generated.resources.telemetry_cleanup_backup_unaffected
import projectphoenix.shared.generated.resources.telemetry_cleanup_failed
import projectphoenix.shared.generated.resources.telemetry_cleanup_kept_bodyweight
import projectphoenix.shared.generated.resources.telemetry_cleanup_kept_clean
import projectphoenix.shared.generated.resources.telemetry_cleanup_kept_legacy
import projectphoenix.shared.generated.resources.telemetry_cleanup_kept_no_onset
import projectphoenix.shared.generated.resources.telemetry_cleanup_reassurance
import projectphoenix.shared.generated.resources.telemetry_cleanup_safety_note
import projectphoenix.shared.generated.resources.telemetry_cleanup_sheet_title
import projectphoenix.shared.generated.resources.telemetry_cleanup_stop_after_set
import projectphoenix.shared.generated.resources.telemetry_cleanup_summary_checked_one
import projectphoenix.shared.generated.resources.telemetry_cleanup_summary_checked_other
import projectphoenix.shared.generated.resources.telemetry_cleanup_summary_cleanable_one
import projectphoenix.shared.generated.resources.telemetry_cleanup_summary_cleanable_other
import projectphoenix.shared.generated.resources.telemetry_cleanup_summary_kept_one
import projectphoenix.shared.generated.resources.telemetry_cleanup_summary_kept_other
import projectphoenix.shared.generated.resources.telemetry_cleanup_why_kept
import kotlin.math.roundToInt

/**
 * "Telemetry storage cleanup" modal sheet (issue #1425 P4). Rendered in [PhoenixModalDialogFrame]
 * per prototype D1; a single "Stop after this set" action while cleaning (D2); no per-set list
 * (D3); measured-only numbers with a plain one-line fallback (signoff #6 / D4).
 */
@Composable
fun TelemetryCleanupSheet(
    state: TelemetryCleanupUiState,
    includeRawTelemetryInBackups: Boolean,
    onCleanUp: () -> Unit,
    onStopAfterSet: () -> Unit,
    onClose: () -> Unit,
) {
    val cleaning = state is TelemetryCleanupUiState.Cleaning
    PhoenixModalDialogFrame(
        title = stringResource(Res.string.telemetry_cleanup_sheet_title),
        // D2: no outside-tap dismiss while cleaning; the single stop action is the only way out.
        onDismiss = { if (!cleaning) onClose() },
        dismissOnClickOutside = !cleaning,
        actions = {
            when (state) {
                is TelemetryCleanupUiState.Ready -> {
                    OutlinedButton(onClick = onCleanUp, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.telemetry_cleanup_clean_action))
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
                is TelemetryCleanupUiState.Cleaning -> {
                    OutlinedButton(onClick = onStopAfterSet, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.telemetry_cleanup_stop_after_set))
                    }
                }
                else -> {
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.action_ok))
                    }
                }
            }
        },
    ) {
        when (state) {
            is TelemetryCleanupUiState.Idle -> Unit
            is TelemetryCleanupUiState.Checking -> CheckingContent(state.checked, state.total)
            is TelemetryCleanupUiState.Ready -> ReadyContent(state, includeRawTelemetryInBackups)
            is TelemetryCleanupUiState.Cleaning -> CleaningContent(state.checked, state.total)
            is TelemetryCleanupUiState.Done -> DoneContent(state)
            is TelemetryCleanupUiState.Blocked -> BlockedContent(state.reason)
            is TelemetryCleanupUiState.Failed -> Text(stringResource(Res.string.telemetry_cleanup_failed))
        }
        Spacer(modifier = Modifier.height(Spacing.small))
        ReassuranceLine()
    }
}

@Composable
private fun CheckingContent(checked: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(Spacing.large))
        Spacer(modifier = Modifier.width(Spacing.medium))
        Text(stringResource(Res.string.telemetry_cleanup_checking), style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(modifier = Modifier.height(Spacing.small))
    Text(
        stringResource(Res.string.telemetry_cleanup_checking_progress, checked, total),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadyContent(
    state: TelemetryCleanupUiState.Ready,
    includeRawTelemetryInBackups: Boolean,
) {
    val report = state.report
    var whyKeptExpanded by remember { mutableStateOf(false) }

    val summary = pluralChecked(report.setsScanned) + " · " +
        pluralCleanable(report.setsCleanable) + " · " +
        pluralKept(report.setsKept)
    Text(
        summary,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
    report.estimatedDatabaseBytesReclaimable?.let { bytes ->
        Text(
            stringResource(Res.string.telemetry_cleanup_estimate_db, mbString(bytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Frame-07 fix: the savings label takes the free width and a minimum gap keeps the value
    // from touching it at 402-pt width.
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (includeRawTelemetryInBackups) {
            Text(
                stringResource(Res.string.telemetry_cleanup_estimate_backup),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(Spacing.small))
            Text(
                report.estimatedBackupBytesReclaimable?.let {
                    stringResource(Res.string.telemetry_cleanup_estimate_backup_value, mbString(it))
                } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(Res.string.telemetry_cleanup_backup_unaffected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(Spacing.small))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { whyKeptExpanded = !whyKeptExpanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.telemetry_cleanup_why_kept),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (whyKeptExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (whyKeptExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(Res.string.telemetry_cleanup_kept_bodyweight), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.telemetry_cleanup_kept_no_onset), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.telemetry_cleanup_kept_legacy), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.telemetry_cleanup_kept_clean), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(Res.string.telemetry_cleanup_safety_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CleaningContent(checked: Int, total: Int) {
    Text(
        stringResource(Res.string.telemetry_cleanup_cleaning_progress, checked + 1, total),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun DoneContent(state: TelemetryCleanupUiState.Done) {
    val result = state.result
    val vacuumRan = result.vacuum as? com.devil.phoenixproject.data.maintenance.VacuumOutcome.Ran
    when {
        result.rowsDeleted == 0 -> {
            Text(stringResource(Res.string.telemetry_cleanup_done_noop), style = MaterialTheme.typography.bodyLarge)
        }
        vacuumRan != null && !vacuumRan.checkpointBusy &&
            result.databaseBytesBefore != null && result.databaseBytesAfter != null -> {
            // Measured sizes only (signoff #6): never a fabricated before/after.
            Text(
                stringResource(
                    if (result.rowsDeleted == 1) {
                        Res.string.telemetry_cleanup_done_removed_one
                    } else {
                        Res.string.telemetry_cleanup_done_removed_other
                    },
                    result.rowsDeleted,
                ) + " " + stringResource(
                    if (result.setsCleaned == 1) {
                        Res.string.telemetry_cleanup_done_sets_one
                    } else {
                        Res.string.telemetry_cleanup_done_sets_other
                    },
                    result.setsCleaned,
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(
                    Res.string.telemetry_cleanup_done_db,
                    mbString(result.databaseBytesBefore),
                    mbString(result.databaseBytesAfter),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val checkpointBusy = vacuumRan?.checkpointBusy == true
            Text(
                stringResource(
                    if (result.rowsDeleted == 1) {
                        Res.string.telemetry_cleanup_done_removed_one
                    } else {
                        Res.string.telemetry_cleanup_done_removed_other
                    },
                    result.rowsDeleted,
                ) + " " + stringResource(
                    if (result.setsCleaned == 1) {
                        Res.string.telemetry_cleanup_done_sets_one
                    } else {
                        Res.string.telemetry_cleanup_done_sets_other
                    },
                    result.setsCleaned,
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (checkpointBusy) {
                    stringResource(Res.string.telemetry_cleanup_done_restart)
                } else {
                    stringResource(Res.string.telemetry_cleanup_done_reusable)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BlockedContent(reason: BlockedReason) {
    Text(
        stringResource(
            when (reason) {
                BlockedReason.ACTIVE_WORKOUT -> Res.string.telemetry_cleanup_blocked_workout
                BlockedReason.BACKUP_RUNNING -> Res.string.telemetry_cleanup_blocked_backup
            },
        ),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ReassuranceLine() {
    Text(
        stringResource(Res.string.telemetry_cleanup_reassurance),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One-decimal MB rendering for measured/estimated byte counts. */
internal fun mbString(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return ((mb * 10.0).roundToInt() / 10.0).toString()
}

/** Local helpers selecting `_one`/`_other` string pairs like the existing backup-file copy. */
@Composable
private fun pluralChecked(count: Int): String = stringResource(
    if (count == 1) Res.string.telemetry_cleanup_summary_checked_one else Res.string.telemetry_cleanup_summary_checked_other,
    count,
)

@Composable
private fun pluralCleanable(count: Int): String = stringResource(
    if (count == 1) Res.string.telemetry_cleanup_summary_cleanable_one else Res.string.telemetry_cleanup_summary_cleanable_other,
    count,
)

@Composable
private fun pluralKept(count: Int): String = stringResource(
    if (count == 1) Res.string.telemetry_cleanup_summary_kept_one else Res.string.telemetry_cleanup_summary_kept_other,
    count,
)
