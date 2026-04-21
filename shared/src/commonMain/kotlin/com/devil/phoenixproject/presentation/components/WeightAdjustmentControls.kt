package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

// Conversion constants
private const val KG_TO_LB = 2.20462f
private const val LB_TO_KG = 0.453592f
private const val MAX_WEIGHT_KG = 100f // Per cable max (V-Form: 100kg, Trainer+: 110kg)

/**
 * Weight adjustment controls for modifying weight during a workout.
 * Shows +/- buttons and the current weight display.
 */
@Composable
fun WeightAdjustmentControls(
    currentWeightKg: Float,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onWeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showPresets: Boolean = false,
    lastUsedWeight: Float? = null,
    prWeight: Float? = null,
    weightIncrementKg: Float = 0f, // Issue #266: 0 = use legacy default
) {
    var showWeightPicker by remember { mutableStateOf(false) }

    // Issue #266: Use configured increment if provided, otherwise legacy default
    val incrementKg = if (weightIncrementKg > 0f) {
        weightIncrementKg
    } else {
        when (weightUnit) {
            WeightUnit.KG -> 0.5f
            WeightUnit.LB -> LB_TO_KG // ~0.45kg = 1lb
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        // Main weight adjustment row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // Decrease button
            WeightButton(
                icon = Icons.Default.Remove,
                onClick = { onWeightChange((currentWeightKg - incrementKg).coerceAtLeast(0f)) },
                enabled = enabled && currentWeightKg > 0,
                contentDescription = stringResource(Res.string.cd_decrease_weight),
            )

            // Current weight display (tappable)
            val weightTapDescription = stringResource(Res.string.cd_current_weight_tap, formatWeight(currentWeightKg, weightUnit))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .semantics {
                        contentDescription = weightTapDescription
                        role = Role.Button
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { showWeightPicker = true }
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            ) {
                Text(
                    text = formatWeight(currentWeightKg, weightUnit),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                )
                Text(
                    text = "per cable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Increase button
            WeightButton(
                icon = Icons.Default.Add,
                onClick = { onWeightChange((currentWeightKg + incrementKg).coerceAtMost(MAX_WEIGHT_KG)) },
                enabled = enabled && currentWeightKg < MAX_WEIGHT_KG,
                contentDescription = stringResource(Res.string.cd_increase_weight),
            )
        }

        // Total weight for 2 cables indicator
        Surface(
            shape = RoundedCornerShape(Spacing.small),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(
                    text = "Total weight for 2 cables: ${formatWeight(currentWeightKg * 2, weightUnit)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Quick preset buttons
        if (showPresets && (lastUsedWeight != null || prWeight != null)) {
            Spacer(modifier = Modifier.height(Spacing.small))
            WeightPresets(
                currentWeightKg = currentWeightKg,
                lastUsedWeight = lastUsedWeight,
                prWeight = prWeight,
                formatWeight = { formatWeight(it, weightUnit) },
                onSelectPreset = onWeightChange,
                enabled = enabled,
            )
        }
    }

    // Weight picker dialog
    if (showWeightPicker) {
        WeightPickerDialog(
            currentWeightKg = currentWeightKg,
            weightUnit = weightUnit,
            formatWeight = formatWeight,
            onWeightSelected = { weight ->
                onWeightChange(weight)
                showWeightPicker = false
            },
            onDismiss = { showWeightPicker = false },
            weightIncrementKg = weightIncrementKg,
        )
    }
}

@Composable
private fun WeightButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
) {
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.9f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "button_scale",
    )

    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .scale(scale),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun WeightPresets(
    currentWeightKg: Float,
    lastUsedWeight: Float?,
    prWeight: Float?,
    formatWeight: (Float) -> String,
    onSelectPreset: (Float) -> Unit,
    enabled: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Last used weight preset
        lastUsedWeight?.let { weight ->
            if (weight != currentWeightKg) {
                PresetChip(
                    label = "Last: ${formatWeight(weight)}",
                    icon = Icons.Default.History,
                    onClick = { onSelectPreset(weight) },
                    enabled = enabled,
                )
            }
        }

        // PR weight preset
        prWeight?.let { weight ->
            if (weight != currentWeightKg && weight != lastUsedWeight) {
                PresetChip(
                    label = "PR: ${formatWeight(weight)}",
                    icon = Icons.Default.EmojiEvents,
                    onClick = { onSelectPreset(weight) },
                    enabled = enabled,
                    isHighlighted = true,
                )
            }
        }

        // Quick percentage adjustments — round to machine increment (0.5kg)
        if (currentWeightKg > 0) {
            PresetChip(
                label = "-5%",
                onClick = {
                    onSelectPreset(UnitConverter.roundToMachineIncrement(currentWeightKg * 0.95f))
                },
                enabled = enabled,
            )
            PresetChip(
                label = "+5%",
                onClick = {
                    onSelectPreset(
                        UnitConverter.roundToMachineIncrement(currentWeightKg * 1.05f)
                            .coerceAtMost(MAX_WEIGHT_KG),
                    )
                },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isHighlighted: Boolean = false,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        colors = if (isHighlighted) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeightPickerDialog(
    currentWeightKg: Float,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onWeightSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    weightIncrementKg: Float = 0f, // Issue #266: 0 = use legacy default
) {
    var selectedWeightKg by remember { mutableStateOf(currentWeightKg) }

    // Unit-aware configuration
    val isLbs = weightUnit == WeightUnit.LB
    val maxWeightDisplay = if (isLbs) (MAX_WEIGHT_KG * KG_TO_LB).toInt() else MAX_WEIGHT_KG.toInt() // 220 lbs or 100 kg

    // Issue #266: Cap slider steps at 200 to keep slider usable even with fine increments.
    // For very fine increments (e.g. 0.1lb), slider stays coarse — users rely on quick-adjust buttons.
    val rawSteps = if (isLbs) maxWeightDisplay - 1 else (MAX_WEIGHT_KG * 2 - 1).toInt()
    val sliderSteps = rawSteps.coerceAtMost(200)

    // Issue #266: Scale quick adjustments based on configured increment
    val displayIncrement = if (weightIncrementKg > 0f) {
        if (isLbs) UnitConverter.kgToLb(weightIncrementKg) else weightIncrementKg
    } else {
        if (isLbs) 1f else 0.5f
    }

    // Quick adjustment deltas (in display units), scaled to increment
    val quickAdjustments = if (isLbs) {
        listOf(
            -(displayIncrement * 10).toInt().coerceAtLeast(1),
            -(displayIncrement * 5).toInt().coerceAtLeast(1),
            -1,
            1,
            (displayIncrement * 5).toInt().coerceAtLeast(1),
            (displayIncrement * 10).toInt().coerceAtLeast(1),
        ).distinct()
    } else {
        listOf(-5, -2, -1, 1, 2, 5) // kg (keep standard increments for cleaner UI)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.set_weight)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Large weight display
                Text(
                    text = formatWeight(selectedWeightKg, weightUnit),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "per cable",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Total weight for 2 cables indicator
                Surface(
                    shape = RoundedCornerShape(Spacing.small),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            text = "Total weight for 2 cables: ${formatWeight(selectedWeightKg * 2, weightUnit)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Slider for weight selection (operates in display units for better UX)
                val displayWeight = if (isLbs) selectedWeightKg * KG_TO_LB else selectedWeightKg
                Slider(
                    value = displayWeight,
                    onValueChange = { displayValue ->
                        // Convert back to kg and round to machine increment (0.5kg)
                        val rawKg = if (isLbs) {
                            displayValue * LB_TO_KG
                        } else {
                            displayValue
                        }
                        selectedWeightKg = UnitConverter.roundToMachineIncrement(rawKg)
                            .coerceIn(0f, MAX_WEIGHT_KG)
                    },
                    valueRange = 0f..maxWeightDisplay.toFloat(),
                    steps = sliderSteps,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Quick adjustment buttons (in display units)
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    quickAdjustments.forEach { delta ->
                        val sign = if (delta > 0) "+" else ""
                        FilledTonalButton(
                            onClick = {
                                // Convert delta from display unit to kg, round to machine increment
                                val deltaKg = if (isLbs) delta * LB_TO_KG else delta.toFloat()
                                selectedWeightKg = UnitConverter.roundToMachineIncrement(
                                    selectedWeightKg + deltaKg,
                                ).coerceIn(0f, MAX_WEIGHT_KG)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "$sign$delta",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onWeightSelected(selectedWeightKg) }) {
                Text(stringResource(Res.string.label_set))
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
 * Compact weight adjustment controls for use in smaller spaces.
 * Shows a minimal +/- interface.
 */
@Composable
fun CompactWeightAdjustment(
    currentWeightKg: Float,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onWeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    weightIncrementKg: Float = 0f, // Issue #266: 0 = use legacy default
) {
    // Issue #266: Use configured increment if provided, otherwise legacy default
    val incrementKg = if (weightIncrementKg > 0f) {
        weightIncrementKg
    } else {
        when (weightUnit) {
            WeightUnit.KG -> 0.5f
            WeightUnit.LB -> LB_TO_KG // ~0.45kg = 1lb
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(
                onClick = { onWeightChange((currentWeightKg - incrementKg).coerceAtLeast(0f)) },
                enabled = enabled && currentWeightKg > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(Res.string.cd_decrease),
                    modifier = Modifier.size(16.dp),
                )
            }

            val weightDescription = stringResource(Res.string.cd_current_weight, formatWeight(currentWeightKg, weightUnit))
            Text(
                text = formatWeight(currentWeightKg, weightUnit),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics {
                        contentDescription = weightDescription
                    },
            )

            IconButton(
                onClick = { onWeightChange((currentWeightKg + incrementKg).coerceAtMost(MAX_WEIGHT_KG)) },
                enabled = enabled && currentWeightKg < MAX_WEIGHT_KG,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(Res.string.cd_increase),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
