package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.PhoenixModalDialogFrame
import com.devil.phoenixproject.presentation.components.PhoenixSectionCard
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_add
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_delete
import projectphoenix.shared.generated.resources.action_save
import projectphoenix.shared.generated.resources.equipment_rack_add_item
import projectphoenix.shared.generated.resources.equipment_rack_behavior_added
import projectphoenix.shared.generated.resources.equipment_rack_behavior_counterweight
import projectphoenix.shared.generated.resources.equipment_rack_behavior_display_only
import projectphoenix.shared.generated.resources.equipment_rack_behavior_label
import projectphoenix.shared.generated.resources.equipment_rack_category_assistance
import projectphoenix.shared.generated.resources.equipment_rack_category_attachment
import projectphoenix.shared.generated.resources.equipment_rack_category_band
import projectphoenix.shared.generated.resources.equipment_rack_category_chains
import projectphoenix.shared.generated.resources.equipment_rack_category_dip_belt
import projectphoenix.shared.generated.resources.equipment_rack_category_label
import projectphoenix.shared.generated.resources.equipment_rack_category_other
import projectphoenix.shared.generated.resources.equipment_rack_category_weighted_vest
import projectphoenix.shared.generated.resources.equipment_rack_description
import projectphoenix.shared.generated.resources.equipment_rack_edit_item
import projectphoenix.shared.generated.resources.equipment_rack_empty
import projectphoenix.shared.generated.resources.equipment_rack_enabled
import projectphoenix.shared.generated.resources.equipment_rack_invalid_name
import projectphoenix.shared.generated.resources.equipment_rack_invalid_weight
import projectphoenix.shared.generated.resources.equipment_rack_name_label
import projectphoenix.shared.generated.resources.equipment_rack_title
import projectphoenix.shared.generated.resources.equipment_rack_weight_label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentRackScreen(viewModel: MainViewModel) {
    val rackItems by viewModel.rackItems.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val title = stringResource(Res.string.equipment_rack_title)
    val sortedRackItems = remember(rackItems) {
        rackItems.sortedWith(compareBy<RackItem> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    var showEditor by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<RackItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RackItem?>(null) }

    LaunchedEffect(title) {
        viewModel.updateTopBarTitle(title)
    }

    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = stringResource(Res.string.equipment_rack_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    editingItem = null
                    showEditor = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.small))
                Text(stringResource(Res.string.equipment_rack_add_item))
            }

            if (rackItems.isEmpty()) {
                PhoenixSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.equipment_rack_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                sortedRackItems.forEach { item ->
                    EquipmentRackItemCard(
                        item = item,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                        onEdit = {
                            editingItem = item
                            showEditor = true
                        },
                        onDelete = { deleteTarget = item },
                        onEnabledChange = { enabled ->
                            viewModel.saveRackItem(item.copy(enabled = enabled, updatedAt = currentTimeMillis()))
                        },
                    )
                }
            }
        }
    }

    if (showEditor) {
        EquipmentRackEditorDialog(
            item = editingItem,
            weightUnit = weightUnit,
            kgToDisplay = viewModel::kgToDisplay,
            displayToKg = viewModel::displayToKg,
            onDismiss = { showEditor = false },
            onSave = { item ->
                viewModel.saveRackItem(item)
                showEditor = false
            },
        )
    }

    deleteTarget?.let { target ->
        DestructiveConfirmDialog(
            title = "Delete rack item?",
            message = "Delete \"${target.name}\"? This cannot be undone.",
            confirmText = stringResource(Res.string.action_delete),
            onConfirm = {
                viewModel.deleteRackItem(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            icon = Icons.Default.Delete,
        )
    }
}

@Composable
private fun EquipmentRackItemCard(
    item: RackItem,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    PhoenixSectionCard(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${rackCategoryLabel(item.category)} - ${rackBehaviorLabel(item.behavior)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatWeight(item.weightKg, weightUnit),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .toggleable(
                        value = item.enabled,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    )
                    .padding(end = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(Res.string.equipment_rack_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = item.enabled, onCheckedChange = null)
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.equipment_rack_edit_item))
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EquipmentRackEditorDialog(
    item: RackItem?,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    onDismiss: () -> Unit,
    onSave: (RackItem) -> Unit,
) {
    var name by remember(item?.id) { mutableStateOf(item?.name.orEmpty()) }
    var weightText by remember(item?.id, weightUnit) {
        mutableStateOf(
            item?.let {
                val displayWeight = kgToDisplay(it.weightKg, weightUnit)
                val rounded = (displayWeight * 100f).let { value ->
                    if (value.isFinite()) kotlin.math.round(value) / 100f else displayWeight
                }
                rounded.toString()
            }.orEmpty(),
        )
    }
    var category by remember(item?.id) { mutableStateOf(item?.category ?: RackItemCategory.OTHER) }
    var behavior by remember(item?.id) { mutableStateOf(item?.behavior ?: RackItemBehavior.ADDED_RESISTANCE) }
    var enabled by remember(item?.id) { mutableStateOf(item?.enabled ?: true) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var behaviorExpanded by remember { mutableStateOf(false) }

    val normalizedWeightText = weightText.trim().replace(',', '.')
    val parsedWeight = normalizedWeightText.toFloatOrNull()
    val hasValidName = name.isNotBlank()
    val hasValidWeight = parsedWeight != null && parsedWeight.isFinite() && parsedWeight >= 0f

    PhoenixModalDialogFrame(
        title = stringResource(
            if (item == null) Res.string.equipment_rack_add_item else Res.string.equipment_rack_edit_item,
        ),
        onDismiss = onDismiss,
        confirmLabel = stringResource(if (item == null) Res.string.action_add else Res.string.action_save),
        onConfirm = {
            val displayWeight = parsedWeight ?: return@PhoenixModalDialogFrame
            val now = currentTimeMillis()
            onSave(
                RackItem(
                    id = item?.id ?: generateUUID(),
                    name = name.trim(),
                    category = category,
                    weightKg = displayToKg(displayWeight, weightUnit),
                    behavior = behavior,
                    enabled = enabled,
                    sortOrder = item?.sortOrder ?: 0,
                    createdAt = item?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
        },
        dismissLabel = stringResource(Res.string.action_cancel),
        confirmEnabled = hasValidName && hasValidWeight,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.equipment_rack_name_label)) },
            isError = name.isNotEmpty() && !hasValidName,
            supportingText = if (name.isNotEmpty() && !hasValidName) {
                { Text(stringResource(Res.string.equipment_rack_invalid_name)) }
            } else {
                null
            },
        )

        OutlinedTextField(
            value = weightText,
            onValueChange = { weightText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text(
                    stringResource(
                        Res.string.equipment_rack_weight_label,
                        if (weightUnit == WeightUnit.LB) "lb" else "kg",
                    ),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = weightText.isNotEmpty() && !hasValidWeight,
            supportingText = if (weightText.isNotEmpty() && !hasValidWeight) {
                { Text(stringResource(Res.string.equipment_rack_invalid_weight)) }
            } else {
                null
            },
        )

        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = { categoryExpanded = it },
        ) {
            OutlinedTextField(
                value = rackCategoryLabel(category),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(Res.string.equipment_rack_category_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = categoryExpanded,
                onDismissRequest = { categoryExpanded = false },
            ) {
                RackItemCategory.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(rackCategoryLabel(option)) },
                        onClick = {
                            category = option
                            categoryExpanded = false
                        },
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = behaviorExpanded,
            onExpandedChange = { behaviorExpanded = it },
        ) {
            OutlinedTextField(
                value = rackBehaviorLabel(behavior),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(Res.string.equipment_rack_behavior_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = behaviorExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = behaviorExpanded,
                onDismissRequest = { behaviorExpanded = false },
            ) {
                RackItemBehavior.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(rackBehaviorLabel(option)) },
                        onClick = {
                            behavior = option
                            behaviorExpanded = false
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = { enabled = it },
                )
                .padding(horizontal = Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.equipment_rack_enabled),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
private fun rackCategoryLabel(category: RackItemCategory): String = when (category) {
    RackItemCategory.WEIGHTED_VEST -> stringResource(Res.string.equipment_rack_category_weighted_vest)
    RackItemCategory.DIP_BELT -> stringResource(Res.string.equipment_rack_category_dip_belt)
    RackItemCategory.CHAINS -> stringResource(Res.string.equipment_rack_category_chains)
    RackItemCategory.BAND -> stringResource(Res.string.equipment_rack_category_band)
    RackItemCategory.ASSISTANCE -> stringResource(Res.string.equipment_rack_category_assistance)
    RackItemCategory.ATTACHMENT -> stringResource(Res.string.equipment_rack_category_attachment)
    RackItemCategory.OTHER -> stringResource(Res.string.equipment_rack_category_other)
}

@Composable
private fun rackBehaviorLabel(behavior: RackItemBehavior): String = when (behavior) {
    RackItemBehavior.ADDED_RESISTANCE -> stringResource(Res.string.equipment_rack_behavior_added)
    RackItemBehavior.COUNTERWEIGHT -> stringResource(Res.string.equipment_rack_behavior_counterweight)
    RackItemBehavior.DISPLAY_ONLY -> stringResource(Res.string.equipment_rack_behavior_display_only)
}
