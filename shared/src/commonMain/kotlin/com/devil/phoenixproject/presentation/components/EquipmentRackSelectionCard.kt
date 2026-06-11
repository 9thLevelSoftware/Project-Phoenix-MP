package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_display_only_count
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_manage
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_no_enabled_items
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_none_selected
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_selected_count
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_selected_summary

@Composable
fun EquipmentRackSelectionCard(
    rackItems: List<RackItem>,
    activeRackItemIds: List<String>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onSelectionChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onManageRack: (() -> Unit)? = null,
) {
    val enabledItems = remember(rackItems) {
        rackItems
            .filter { it.enabled }
            .sortedWith(compareBy<RackItem> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val activeIdSet = remember(activeRackItemIds) { activeRackItemIds.toSet() }
    val selectedItems = remember(enabledItems, activeIdSet) { enabledItems.filter { it.id in activeIdSet } }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.equipment_rack_active_selection),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
                if (onManageRack != null) {
                    TextButton(onClick = onManageRack) {
                        Text(stringResource(Res.string.equipment_rack_manage))
                    }
                }
            }

            if (enabledItems.isEmpty()) {
                Text(
                    stringResource(Res.string.equipment_rack_no_enabled_items),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                enabledItems.forEach { item ->
                    val selected = item.id in activeIdSet
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
                                "${item.name} - ${formatRackChipDetail(item, weightUnit, formatWeight)}",
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    if (selectedItems.isEmpty()) {
                        stringResource(Res.string.equipment_rack_none_selected)
                    } else {
                        stringResource(
                            Res.string.equipment_rack_selected_summary,
                            rackSelectionSummary(selectedItems, weightUnit, formatWeight),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRackChipDetail(
    item: RackItem,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String = when (item.behavior) {
    RackItemBehavior.ADDED_RESISTANCE -> "+${formatWeight(item.weightKg, weightUnit)}"
    RackItemBehavior.COUNTERWEIGHT -> "-${formatWeight(item.weightKg, weightUnit)}"
    RackItemBehavior.DISPLAY_ONLY -> formatWeight(item.weightKg, weightUnit)
}

@Composable
private fun rackSelectionSummary(
    items: List<RackItem>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String {
    val addedKg = items.filter { it.behavior == RackItemBehavior.ADDED_RESISTANCE }.sumOf { it.weightKg.toDouble() }.toFloat()
    val counterweightKg = items.filter { it.behavior == RackItemBehavior.COUNTERWEIGHT }.sumOf { it.weightKg.toDouble() }.toFloat()
    val displayOnlyCount = items.count { it.behavior == RackItemBehavior.DISPLAY_ONLY }
    val displayOnlyLabel = if (displayOnlyCount > 0) {
        stringResource(Res.string.equipment_rack_display_only_count, displayOnlyCount)
    } else {
        null
    }
    val selectedCountLabel = stringResource(Res.string.equipment_rack_selected_count, items.size)
    val parts = buildList {
        if (addedKg > 0f) add("+${formatWeight(addedKg, weightUnit)}")
        if (counterweightKg > 0f) add("-${formatWeight(counterweightKg, weightUnit)}")
        displayOnlyLabel?.let(::add)
    }
    return parts.ifEmpty { listOf(selectedCountLabel) }.joinToString(" / ")
}
