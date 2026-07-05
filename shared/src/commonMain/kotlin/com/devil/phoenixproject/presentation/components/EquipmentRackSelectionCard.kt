package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_active_selection
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_behavior_added_short
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_behavior_counterweight_short
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_behavior_display_only_short
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_manage
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_no_enabled_items
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_none_selected

/**
 * Collapsible equipment rack summary row with optional per-item behavior overrides.
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
    showBehaviorOverrides: Boolean = false,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.equipment_rack_active_selection),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onManageRack != null) {
                    TextButton(onClick = onManageRack) {
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
                enter = expandVertically(),
                exit = shrinkVertically(),
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
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (selected && showBehaviorOverrides) {
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

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, behavior ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onBehaviorChange(behavior) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    val label = when (behavior) {
                        RackItemBehavior.ADDED_RESISTANCE -> stringResource(Res.string.equipment_rack_behavior_added_short)
                        RackItemBehavior.COUNTERWEIGHT -> stringResource(Res.string.equipment_rack_behavior_counterweight_short)
                        RackItemBehavior.DISPLAY_ONLY -> stringResource(Res.string.equipment_rack_behavior_display_only_short)
                    }
                    Text(
                        text = if (behavior == globalBehavior) "$label *" else label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

private fun buildCollapsedSummary(
    selectedItems: List<RackItem>,
    behaviorOverrides: Map<String, RackItemBehavior>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String = selectedItems.joinToString(", ") { item ->
    val effectiveBehavior = behaviorOverrides[item.id] ?: item.behavior
    val behaviorSuffix = when (effectiveBehavior) {
        RackItemBehavior.ADDED_RESISTANCE -> "+"
        RackItemBehavior.COUNTERWEIGHT -> "-"
        RackItemBehavior.DISPLAY_ONLY -> "display"
    }
    "${item.name} ${formatWeight(item.weightKg, weightUnit)} $behaviorSuffix"
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
