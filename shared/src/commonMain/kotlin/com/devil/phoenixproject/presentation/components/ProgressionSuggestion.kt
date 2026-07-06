package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ProgressionEvent
import com.devil.phoenixproject.domain.model.ProgressionReason
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Banner component that shows a weight progression suggestion.
 * Displayed when starting an exercise that has a pending progression.
 */
@Composable
fun ProgressionSuggestionBanner(
    event: ProgressionEvent,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onAccept: () -> Unit,
    onModify: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showModifyDialog by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val isDeload = event.reason.isDeload
        // Semantic: deload → warning (amber); progression → success (lime)
        val cardColor = if (isDeload) AccessibilityTheme.colors.warning else AccessibilityTheme.colors.success

        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = cardColor.copy(alpha = 0.15f),
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Header with icon and reason
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isDeload) Icons.AutoMirrored.Filled.TrendingDown else Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = cardColor,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (isDeload) "Deload Suggested" else "Weight Increase Suggested",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = cardColor,
                        )
                        Text(
                            when (event.reason) {
                                ProgressionReason.REPS_ACHIEVED -> "You've hit your target reps consistently"
                                ProgressionReason.LOW_RPE -> "Your recent sets felt easier than target"
                                ProgressionReason.MISSED_REPS -> "You've missed your target reps recently"
                                ProgressionReason.HIGH_RPE -> "Your recent sets have been very difficult"
                                ProgressionReason.PLATEAU_DETECTED -> "A plateau has been detected in your progress"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cardColor,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Weight suggestion
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            cardColor.copy(alpha = 0.1f),
                            MaterialTheme.shapes.small,
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Current",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatWeight(event.previousWeightKg, weightUnit),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = cardColor,
                        modifier = Modifier.size(20.dp),
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Suggested",
                            style = MaterialTheme.typography.labelSmall,
                            color = cardColor,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatWeight(event.suggestedWeightKg, weightUnit),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = cardColor,
                            )
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                color = cardColor,
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                val incrementText = if (isDeload) {
                                    formatWeight(-event.increment(), weightUnit).let { "-$it" }
                                } else {
                                    "+${formatWeight(event.increment(), weightUnit)}"
                                }
                                Text(
                                    incrementText,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDeload) AccessibilityTheme.colors.onWarning else AccessibilityTheme.colors.onSuccess,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            isVisible = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.extraSmall,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(Res.string.action_skip))
                    }

                    OutlinedButton(
                        onClick = { showModifyDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.action_modify))
                    }

                    Button(
                        onClick = {
                            isVisible = false
                            onAccept()
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.extraSmall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cardColor,
                        ),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.action_accept))
                    }
                }
            }
        }
    }

    // Modify weight dialog
    if (showModifyDialog) {
        ModifyWeightDialog(
            currentWeight = event.suggestedWeightKg,
            previousWeight = event.previousWeightKg,
            weightUnit = weightUnit,
            formatWeight = formatWeight,
            onConfirm = { newWeight ->
                showModifyDialog = false
                isVisible = false
                onModify(newWeight)
            },
            onDismiss = { showModifyDialog = false },
        )
    }
}

/**
 * Dialog for modifying the suggested weight.
 */
@Composable
private fun ModifyWeightDialog(
    currentWeight: Float,
    previousWeight: Float,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var weight by remember { mutableStateOf(currentWeight) }
    val increment = 0.5f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(Res.string.adjust_weight), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Previous: ${formatWeight(previousWeight, weightUnit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                // Weight adjuster
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = { weight = (weight - increment).coerceAtLeast(0f) },
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(Res.string.cd_decrease))
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            formatWeight(weight, weightUnit),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    IconButton(
                        onClick = { weight += increment },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.cd_increase))
                    }
                }

                // Quick presets
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(-1f, -0.5f, 0.5f, 1f, 2.5f).forEach { delta ->
                        FilterChip(
                            selected = false,
                            onClick = { weight = (previousWeight + delta).coerceAtLeast(0f) },
                            label = {
                                Text(
                                    if (delta > 0) "+${delta}kg" else "${delta}kg",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(weight) },
                enabled = weight > 0f && weight != previousWeight,
            ) {
                Text(stringResource(Res.string.use_this_weight))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/**
 * Compact indicator showing a weight increase is available.
 * Can be shown inline with exercise name.
 */
@Composable
fun ProgressionIndicator(
    increment: Float,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = AccessibilityTheme.colors.success.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                tint = AccessibilityTheme.colors.success,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "+${formatWeight(increment, weightUnit)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = AccessibilityTheme.colors.success,
            )
        }
    }
}
