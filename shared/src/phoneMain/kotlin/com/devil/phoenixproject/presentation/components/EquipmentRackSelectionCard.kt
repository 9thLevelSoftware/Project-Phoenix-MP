package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.labelAllCaps
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.cd_collapse
import projectphoenix.shared.generated.resources.cd_expand
import projectphoenix.shared.generated.resources.equipment_rack_active_selection
import projectphoenix.shared.generated.resources.equipment_rack_behavior_added_short
import projectphoenix.shared.generated.resources.equipment_rack_behavior_counterweight_short
import projectphoenix.shared.generated.resources.equipment_rack_behavior_display_only_short
import projectphoenix.shared.generated.resources.equipment_rack_manage
import projectphoenix.shared.generated.resources.equipment_rack_no_enabled_items
import projectphoenix.shared.generated.resources.equipment_rack_none_selected
import projectphoenix.shared.generated.resources.external_cd_collapsed
import projectphoenix.shared.generated.resources.external_cd_expanded
import projectphoenix.shared.generated.resources.rack_selection_default_suffix
import projectphoenix.shared.generated.resources.rack_selection_display_suffix

/**
 * Collapsible equipment rack summary row with per-item behavior overrides.
 */
@Composable
fun EquipmentRackSelectionCard(
    rackItems: List<RackItem>,
    activeRackItemIds: List<String>,
    behaviorOverrides: Map<String, RackItemBehavior> = emptyMap(),
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onSelectionChange: (List<String>) -> Unit,
    onBehaviorOverrideChange: (Map<String, RackItemBehavior>) -> Unit = {},
    modifier: Modifier = Modifier,
    onManageRack: (() -> Unit)? = null,
) {
    val enabledItems = remember(rackItems) {
        rackItems
            .filter { it.enabled }
            .sortedWith(compareBy<RackItem> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val activeIdSet = remember(activeRackItemIds) { activeRackItemIds.toSet() }
    val selectedItems = remember(enabledItems, activeIdSet) {
        enabledItems.filter { it.id in activeIdSet }
    }

    var expanded by remember { mutableStateOf(false) }
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            val expandLabel = stringResource(Res.string.cd_expand)
            val collapseLabel = stringResource(Res.string.cd_collapse)
            val expandedState = stringResource(Res.string.external_cd_expanded)
            val collapsedState = stringResource(Res.string.external_cd_collapsed)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (expanded) collapseLabel else expandLabel,
                        ) { expanded = !expanded }
                        .semantics {
                            stateDescription = if (expanded) expandedState else collapsedState
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.equipment_rack_active_selection),
                        style = labelAllCaps.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onManageRack != null) {
                    TextButton(
                        onClick = onManageRack,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(stringResource(Res.string.equipment_rack_manage))
                    }
                }
            }

            when {
                enabledItems.isEmpty() -> {
                    Text(
                        stringResource(Res.string.equipment_rack_no_enabled_items),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                selectedItems.isEmpty() -> {
                    Text(
                        stringResource(Res.string.equipment_rack_none_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                !expanded -> {
                    Text(
                        text = buildCollapsedSummary(
                            selectedItems = selectedItems,
                            behaviorOverrides = behaviorOverrides,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && enabledItems.isNotEmpty(),
                enter = if (reduceMotion) EnterTransition.None else expandVertically(),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    enabledItems.forEach { item ->
                        val selected = item.id in activeIdSet
                        val effectiveBehavior = behaviorOverrides[item.id] ?: item.behavior

                        Column {
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val updated = if (selected) {
                                        activeRackItemIds.filterNot { it == item.id }
                                    } else {
                                        activeRackItemIds + item.id
                                    }
                                    onSelectionChange(updated)
                                },
                                label = {
                                    Text(
                                        "${item.name} - ${formatRackChipDetail(item, effectiveBehavior, weightUnit, formatWeight)}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            )

                            if (selected) {
                                Spacer(Modifier.height(4.dp))
                                RackItemBehaviorPicker(
                                    currentBehavior = effectiveBehavior,
                                    globalBehavior = item.behavior,
                                    onBehaviorChange = { newBehavior ->
                                        val updatedOverrides = if (newBehavior == item.behavior) {
                                            behaviorOverrides - item.id
                                        } else {
                                            behaviorOverrides + (item.id to newBehavior)
                                        }
                                        onBehaviorOverrideChange(updatedOverrides)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RackItemBehaviorPicker(
    currentBehavior: RackItemBehavior,
    globalBehavior: RackItemBehavior,
    onBehaviorChange: (RackItemBehavior) -> Unit,
) {
    val options = RackItemBehavior.entries
    val selectedIndex = options.indexOf(currentBehavior)
    val useFlowRow = isCompactAccessibilityLayout() ||
        LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact

    if (useFlowRow) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            options.forEach { behavior ->
                val label = behaviorShortLabel(behavior)
                val displayLabel = if (behavior == globalBehavior) {
                    stringResource(Res.string.rack_selection_default_suffix, label)
                } else {
                    label
                }
                FilterChip(
                    selected = behavior == currentBehavior,
                    onClick = { onBehaviorChange(behavior) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, behavior ->
                val label = behaviorShortLabel(behavior)
                val displayLabel = if (behavior == globalBehavior) {
                    stringResource(Res.string.rack_selection_default_suffix, label)
                } else {
                    label
                }
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onBehaviorChange(behavior) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun behaviorShortLabel(behavior: RackItemBehavior): String = when (behavior) {
    RackItemBehavior.ADDED_RESISTANCE -> stringResource(Res.string.equipment_rack_behavior_added_short)
    RackItemBehavior.COUNTERWEIGHT -> stringResource(Res.string.equipment_rack_behavior_counterweight_short)
    RackItemBehavior.DISPLAY_ONLY -> stringResource(Res.string.equipment_rack_behavior_display_only_short)
}

@Composable
private fun buildCollapsedSummary(
    selectedItems: List<RackItem>,
    behaviorOverrides: Map<String, RackItemBehavior>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String {
    val displaySuffix = stringResource(Res.string.rack_selection_display_suffix)
    return selectedItems.joinToString(", ") { item ->
        val effectiveBehavior = behaviorOverrides[item.id] ?: item.behavior
        val behaviorSuffix = when (effectiveBehavior) {
            RackItemBehavior.ADDED_RESISTANCE -> "+"
            RackItemBehavior.COUNTERWEIGHT -> "-"
            RackItemBehavior.DISPLAY_ONLY -> displaySuffix
        }
        "${item.name} ${formatWeight(item.weightKg, weightUnit)} $behaviorSuffix"
    }
}

private fun formatRackChipDetail(
    item: RackItem,
    effectiveBehavior: RackItemBehavior,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String = when (effectiveBehavior) {
    RackItemBehavior.ADDED_RESISTANCE -> "+${formatWeight(item.weightKg, weightUnit)}"
    RackItemBehavior.COUNTERWEIGHT -> "-${formatWeight(item.weightKg, weightUnit)}"
    RackItemBehavior.DISPLAY_ONLY -> formatWeight(item.weightKg, weightUnit)
}
