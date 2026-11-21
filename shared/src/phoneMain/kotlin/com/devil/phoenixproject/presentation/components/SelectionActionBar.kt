package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Bottom action bar shown during selection mode in RoutineEditorScreen.
 * Uses FlowRow so actions never overflow horizontally on compact widths or large fonts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionActionBar(
    visible: Boolean,
    selectedCount: Int,
    canAddToSuperset: Boolean,
    canRemoveFromSuperset: Boolean,
    hasExistingSupersets: Boolean,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onAddToSuperset: () -> Unit,
    onRemoveFromSuperset: () -> Unit,
    onBulkWeightAdjust: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val showAddToSuperset = selectedCount > 0 && (hasExistingSupersets || canAddToSuperset)
        val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = (
                        if (contentMaxWidth != null) {
                            Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        }
                        ).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cancel button (48dp target)
                    SmallFloatingActionButton(
                        onClick = onCancel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_cancel_selection),
                        )
                    }

                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Remove from Superset (if applicable)
                        if (canRemoveFromSuperset) {
                            SmallFloatingActionButton(
                                onClick = onRemoveFromSuperset,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Icon(
                                    Icons.Default.LinkOff,
                                    contentDescription = stringResource(Res.string.cd_remove_superset),
                                )
                            }
                        }

                        // Bulk weight adjust
                        SmallFloatingActionButton(
                            onClick = onBulkWeightAdjust,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = stringResource(Res.string.cd_adjust_weights),
                            )
                        }

                        // Copy selected exercises
                        SmallFloatingActionButton(
                            onClick = onCopy,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(Res.string.cd_copy_selected),
                            )
                        }

                        // Add to Superset (if 2+ selected or existing supersets)
                        if (showAddToSuperset) {
                            FilledTonalButton(
                                onClick = onAddToSuperset,
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.add_to_superset), maxLines = 1)
                            }
                        }

                        // Delete button
                        SmallFloatingActionButton(
                            onClick = onDelete,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            BadgedBox(
                                badge = {
                                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                                        Text("$selectedCount")
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(Res.string.cd_delete_selected),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
