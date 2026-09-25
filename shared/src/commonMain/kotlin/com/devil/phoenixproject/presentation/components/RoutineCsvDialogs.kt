package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.csv.RoutineCsvImportAction
import com.devil.phoenixproject.domain.csv.RoutineCsvImportMode
import com.devil.phoenixproject.domain.csv.RoutineCsvIssue
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvExportUiState
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvImportUiState
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_close
import projectphoenix.shared.generated.resources.action_ok
import projectphoenix.shared.generated.resources.routine_csv_action_copy
import projectphoenix.shared.generated.resources.routine_csv_action_create
import projectphoenix.shared.generated.resources.routine_csv_action_overwrite
import projectphoenix.shared.generated.resources.routine_csv_commit_failed
import projectphoenix.shared.generated.resources.routine_csv_confirm
import projectphoenix.shared.generated.resources.routine_csv_export_blocked_message
import projectphoenix.shared.generated.resources.routine_csv_export_blocked_title
import projectphoenix.shared.generated.resources.routine_csv_group
import projectphoenix.shared.generated.resources.routine_csv_import_title
import projectphoenix.shared.generated.resources.routine_csv_imported
import projectphoenix.shared.generated.resources.routine_csv_issues_title
import projectphoenix.shared.generated.resources.routine_csv_matches_found
import projectphoenix.shared.generated.resources.routine_csv_mode_copies
import projectphoenix.shared.generated.resources.routine_csv_mode_overwrite
import projectphoenix.shared.generated.resources.routine_csv_more_issues
import projectphoenix.shared.generated.resources.routine_csv_refreshed
import projectphoenix.shared.generated.resources.routine_csv_summary
import projectphoenix.shared.generated.resources.routine_csv_unreadable_title

private const val MAX_ISSUES_SHOWN = 20

/**
 * The routine CSV import dialog (#772): problems in the file, or a preview of what will be
 * created or replaced with the Overwrite / Copies choice when routines already exist.
 */
@Composable
fun RoutineCsvImportDialog(
    state: RoutineCsvImportUiState,
    onSelectMode: (RoutineCsvImportMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is RoutineCsvImportUiState.Unreadable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.routine_csv_unreadable_title)) },
            text = { IssueList(state.issues) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
        )

        is RoutineCsvImportUiState.Imported -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.routine_csv_import_title)) },
            text = { Text(stringResource(Res.string.routine_csv_imported, state.routineCount)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_ok)) } },
        )

        is RoutineCsvImportUiState.Preview -> {
            val plan = state.plan
            AlertDialog(
                onDismissRequest = { if (!state.committing) onDismiss() },
                title = { Text(stringResource(Res.string.routine_csv_import_title)) },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        if (state.refreshed) Notice(stringResource(Res.string.routine_csv_refreshed))
                        if (state.commitFailed) Notice(stringResource(Res.string.routine_csv_commit_failed))
                        plan.routines.forEach { routine ->
                            Column {
                                Text(routine.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = actionLabel(routine.action) + " · " +
                                        stringResource(
                                            Res.string.routine_csv_summary,
                                            routine.exerciseCount,
                                            routine.setCount,
                                            routine.modes.joinToString(", "),
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                routine.groupName?.let {
                                    Text(
                                        stringResource(Res.string.routine_csv_group, it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (plan.hasMatches) {
                            HorizontalDivider()
                            Text(stringResource(Res.string.routine_csv_matches_found), style = MaterialTheme.typography.bodyMedium)
                            // The picked mode shows at once; its plan follows.
                            val selectedMode = state.replanningTo ?: plan.mode
                            Column(Modifier.selectableGroup()) {
                                ModeOption(
                                    label = stringResource(Res.string.routine_csv_mode_copies),
                                    selected = selectedMode == RoutineCsvImportMode.CREATE_COPIES,
                                    enabled = !state.committing,
                                    onSelect = { onSelectMode(RoutineCsvImportMode.CREATE_COPIES) },
                                )
                                ModeOption(
                                    label = stringResource(Res.string.routine_csv_mode_overwrite),
                                    selected = selectedMode == RoutineCsvImportMode.OVERWRITE_MATCHING,
                                    enabled = !state.committing,
                                    onSelect = { onSelectMode(RoutineCsvImportMode.OVERWRITE_MATCHING) },
                                )
                            }
                        }
                        if (plan.issues.isNotEmpty()) {
                            HorizontalDivider()
                            IssueList(plan.issues)
                        }
                    }
                },
                confirmButton = {
                    if (state.committing) {
                        CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
                    } else {
                        TextButton(onClick = onConfirm, enabled = plan.canCommit && state.replanningTo == null) {
                            Text(stringResource(Res.string.routine_csv_confirm))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !state.committing) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
    }
}

/** Shown when a routine uses settings a v1 CSV cannot carry (#772). */
@Composable
fun RoutineCsvExportBlockedDialog(state: RoutineCsvExportUiState.Blocked, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.routine_csv_export_blocked_title, state.routineName)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(stringResource(Res.string.routine_csv_export_blocked_message))
                state.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_ok)) } },
    )
}

@Composable
private fun actionLabel(action: RoutineCsvImportAction): String = when (action) {
    RoutineCsvImportAction.CREATE -> stringResource(Res.string.routine_csv_action_create)
    RoutineCsvImportAction.OVERWRITE -> stringResource(Res.string.routine_csv_action_overwrite)
    RoutineCsvImportAction.COPY -> stringResource(Res.string.routine_csv_action_copy)
}

@Composable
private fun IssueList(issues: List<RoutineCsvIssue>) {
    Column(
        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(stringResource(Res.string.routine_csv_issues_title), style = MaterialTheme.typography.bodyMedium)
        issues.take(MAX_ISSUES_SHOWN).forEach { issue ->
            Text(
                "• $issue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (issues.size > MAX_ISSUES_SHOWN) {
            Text(
                stringResource(Res.string.routine_csv_more_issues, issues.size - MAX_ISSUES_SHOWN),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ModeOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
