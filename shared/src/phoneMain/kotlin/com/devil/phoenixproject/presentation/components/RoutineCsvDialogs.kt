package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        is RoutineCsvImportUiState.Unreadable -> PhoenixModalDialogFrame(
            title = stringResource(Res.string.routine_csv_unreadable_title),
            onDismiss = onDismiss,
            confirmLabel = stringResource(Res.string.action_close),
            onConfirm = onDismiss,
        ) {
            IssueList(state.issues)
        }

        is RoutineCsvImportUiState.Imported -> PhoenixModalDialogFrame(
            title = stringResource(Res.string.routine_csv_import_title),
            onDismiss = onDismiss,
            confirmLabel = stringResource(Res.string.action_ok),
            onConfirm = onDismiss,
        ) {
            Text(
                text = stringResource(Res.string.routine_csv_imported, state.routineCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is RoutineCsvImportUiState.Preview -> {
            val plan = state.plan
            PhoenixModalDialogFrame(
                title = stringResource(Res.string.routine_csv_import_title),
                onDismiss = { if (!state.committing) onDismiss() },
                dismissOnClickOutside = !state.committing,
                actions = {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !state.committing,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            text = stringResource(Res.string.action_cancel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !state.committing && plan.canCommit && state.replanningTo == null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        if (state.committing) {
                            LoadingIndicator(LoadingIndicatorSize.Medium)
                        } else {
                            Text(
                                text = stringResource(Res.string.routine_csv_confirm),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            ) {
                if (state.refreshed) Notice(stringResource(Res.string.routine_csv_refreshed))
                if (state.commitFailed) Notice(stringResource(Res.string.routine_csv_commit_failed))

                plan.routines.forEach { routine ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.small),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = routine.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = actionLabel(routine.action),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = stringResource(
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
                }

                if (plan.hasMatches) {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.routine_csv_matches_found),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
        }
    }
}

/** Shown when a routine uses settings a v1 CSV cannot carry (#772). */
@Composable
fun RoutineCsvExportBlockedDialog(state: RoutineCsvExportUiState.Blocked, onDismiss: () -> Unit) {
    PhoenixModalDialogFrame(
        title = stringResource(Res.string.routine_csv_export_blocked_title, state.routineName),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.action_ok),
        onConfirm = onDismiss,
    ) {
        Text(
            text = stringResource(Res.string.routine_csv_export_blocked_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.reasons.forEach { reason ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "• $reason",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                )
            }
        }
    }
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            text = "${stringResource(Res.string.routine_csv_issues_title)} (${issues.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        issues.take(MAX_ISSUES_SHOWN).forEach { issue ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.Top,
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = if (issue.line != null) "Line ${issue.line}" else "File",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (issues.size > MAX_ISSUES_SHOWN) {
            Text(
                stringResource(Res.string.routine_csv_more_issues, issues.size - MAX_ISSUES_SHOWN),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(Spacing.small),
        )
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = Spacing.extraSmall, vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
