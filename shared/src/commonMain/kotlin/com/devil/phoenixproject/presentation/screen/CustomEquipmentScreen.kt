package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.data.preferences.CustomEquipmentContract
import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.data.repository.StaleProfileContextException
import com.devil.phoenixproject.domain.model.CustomEquipmentItem
import com.devil.phoenixproject.domain.model.CustomEquipmentPreferences
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.presentation.components.customCableEquipmentOptions
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_add
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_delete
import projectphoenix.shared.generated.resources.action_save
import projectphoenix.shared.generated.resources.cd_custom_equipment
import projectphoenix.shared.generated.resources.custom_equipment_add_hint
import projectphoenix.shared.generated.resources.custom_equipment_description
import projectphoenix.shared.generated.resources.custom_equipment_empty
import projectphoenix.shared.generated.resources.custom_equipment_error_comma
import projectphoenix.shared.generated.resources.custom_equipment_error_control
import projectphoenix.shared.generated.resources.custom_equipment_error_duplicate
import projectphoenix.shared.generated.resources.custom_equipment_error_empty
import projectphoenix.shared.generated.resources.custom_equipment_error_generic
import projectphoenix.shared.generated.resources.custom_equipment_error_limit
import projectphoenix.shared.generated.resources.custom_equipment_error_reserved
import projectphoenix.shared.generated.resources.custom_equipment_error_too_long
import projectphoenix.shared.generated.resources.custom_equipment_manage
import projectphoenix.shared.generated.resources.custom_equipment_official_body
import projectphoenix.shared.generated.resources.custom_equipment_official_title
import projectphoenix.shared.generated.resources.custom_equipment_remove
import projectphoenix.shared.generated.resources.custom_equipment_remove_message
import projectphoenix.shared.generated.resources.custom_equipment_remove_title
import projectphoenix.shared.generated.resources.custom_equipment_rename
import projectphoenix.shared.generated.resources.custom_equipment_rename_message
import projectphoenix.shared.generated.resources.custom_equipment_rename_title
import projectphoenix.shared.generated.resources.custom_equipment_title

/**
 * Issue #1227: profile-scoped custom equipment management (prototype images 2-5, 8).
 *
 * Contract:
 * - Every add/rename/delete runs the shared [ProfilePreferencesValidator] rule set and writes
 *   through [MainViewModel.updateCustomEquipment]; this composable never touches SQL.
 * - Identity is mint-once (signoff B1): rename changes the label only, never the token or
 *   createdAt; remove is non-destructive (signoff B2) — exercises keep a readable humanized
 *   name and the entry simply stops being a filter/dropdown option.
 * - An in-flight edit is bound to the profile that was active when it began (signoff B3):
 *   after a profile switch the edit is cancelled, never applied to the new profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEquipmentScreen(viewModel: MainViewModel) {
    val customEquipment by viewModel.customEquipment.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val title = stringResource(Res.string.custom_equipment_title)
    val scope = rememberCoroutineScope()

    LaunchedEffect(title) {
        viewModel.updateTopBarTitle(title)
    }

    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<CustomEquipmentItem?>(null) }
    var removeTarget by remember { mutableStateOf<CustomEquipmentItem?>(null) }
    // Signoff B3: the edit is bound to this profile and cancelled if it changes mid-edit.
    var editOriginProfileId by remember { mutableStateOf<String?>(null) }

    val items = customEquipment.items

    fun applyEdit(candidate: CustomEquipmentPreferences) {
        val profileId = editOriginProfileId ?: activeProfileId
        if (profileId != activeProfileId) return
        scope.launch {
            try {
                viewModel.updateCustomEquipment(profileId, candidate)
            } catch (_: StaleProfileContextException) {
                // Profile switched between edit start and write: reject, never re-target.
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = stringResource(Res.string.custom_equipment_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Add row: the Add button stays disabled until the shared validator accepts the
            // candidate document, with a plain-language rejection reason underneath.
            val trimmedName = newName.trim()
            val trialItem = CustomEquipmentContract.tokenFor(trimmedName)?.let { token ->
                CustomEquipmentItem(
                    token = token,
                    label = trimmedName,
                    createdAt = currentTimeMillis(),
                )
            }
            val trialDocument = trialItem
                ?.let { customEquipment.copy(items = items + it) }
                ?: customEquipment
            val addBlockedReason = if (trimmedName.isEmpty()) {
                null
            } else {
                rejectionMessage(trialDocument)
            }

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.custom_equipment_add_hint)) },
                singleLine = true,
                isError = trimmedName.isNotEmpty() && addBlockedReason != null,
            )
            if (trimmedName.isNotEmpty() && addBlockedReason != null) {
                Text(
                    text = addBlockedReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val candidate = trialDocument
                    if (trialItem != null && ProfilePreferencesValidator.customEquipment(candidate).isEmpty()) {
                        editOriginProfileId = activeProfileId
                        applyEdit(candidate)
                        newName = ""
                    }
                },
                enabled = trialItem != null && addBlockedReason == null && items.size < CustomEquipmentContract.MAX_ITEMS,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(Spacing.small))
                Text(stringResource(Res.string.action_add))
            }

            Text(
                text = "${items.size}/${CustomEquipmentContract.MAX_ITEMS}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (items.isEmpty()) {
                Text(
                    text = stringResource(Res.string.custom_equipment_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        IconButton(
                            onClick = {
                                editOriginProfileId = activeProfileId
                                renameTarget = item
                            },
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(Res.string.custom_equipment_rename),
                            )
                        }
                        IconButton(
                            onClick = {
                                editOriginProfileId = activeProfileId
                                removeTarget = item
                            },
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(Res.string.custom_equipment_remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Read-only explanatory card: the official vocabulary is frozen (signoff B9).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = stringResource(Res.string.custom_equipment_official_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = customCableEquipmentOptions().joinToString(" • ") { (_, label) -> label },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(Res.string.custom_equipment_official_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        var editedLabel by remember(target) { mutableStateOf(target.label) }
        val trialDocument = customEquipment.copy(
            items = items.map { item ->
                if (item.token == target.token) item.copy(label = editedLabel.trim()) else item
            },
        )
        val blockedReason = rejectionMessage(trialDocument)
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(Res.string.custom_equipment_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    OutlinedTextField(
                        value = editedLabel,
                        onValueChange = { editedLabel = it },
                        singleLine = true,
                        isError = blockedReason != null,
                    )
                    if (blockedReason != null) {
                        Text(
                            text = blockedReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.custom_equipment_rename_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = blockedReason == null,
                    onClick = {
                        // Mint-once identity (B1): only the label moves; token and createdAt
                        // are preserved exactly as stored.
                        applyEdit(trialDocument)
                        renameTarget = null
                    },
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(Res.string.custom_equipment_remove_title)) },
            text = { Text(stringResource(Res.string.custom_equipment_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Non-destructive removal (B2): no exercise row is touched; the
                        // leftover U_ token humanizes its slug for display.
                        applyEdit(customEquipment.copy(items = items.filterNot { it.token == target.token }))
                        removeTarget = null
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Issue #1227: the shared validator's first reason key rendered as a plain-language message,
 * or null when the candidate document is valid. One rule set, one message mapping.
 */
@Composable
private fun rejectionMessage(candidate: CustomEquipmentPreferences): String? {
    val first = ProfilePreferencesValidator.customEquipment(candidate).firstOrNull() ?: return null
    val resource = when (first) {
        "emptySlug" -> Res.string.custom_equipment_error_empty
        "labelTooLong" -> Res.string.custom_equipment_error_too_long
        "labelHasComma" -> Res.string.custom_equipment_error_comma
        "labelHasControlCharacter" -> Res.string.custom_equipment_error_control
        "labelReserved" -> Res.string.custom_equipment_error_reserved
        "labelDuplicate" -> Res.string.custom_equipment_error_duplicate
        "tokenDuplicate" -> Res.string.custom_equipment_error_duplicate
        "tooManyItems" -> Res.string.custom_equipment_error_limit
        else -> Res.string.custom_equipment_error_generic
    }
    return stringResource(resource)
}
