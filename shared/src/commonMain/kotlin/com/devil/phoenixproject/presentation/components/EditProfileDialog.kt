package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Dialog for editing an existing user profile.
 * Allows changing name and color.
 */
@Composable
fun EditProfileDialog(profile: UserProfile, profileRepository: UserProfileRepository, scope: CoroutineScope, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var selectedColorIndex by remember { mutableStateOf(profile.colorIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_profile)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.label_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val colorNames = listOf(
                    stringResource(Res.string.color_blue),
                    stringResource(Res.string.color_green),
                    stringResource(Res.string.color_amber),
                    stringResource(Res.string.color_red),
                    stringResource(Res.string.color_purple),
                    stringResource(Res.string.color_pink),
                    stringResource(Res.string.color_cyan),
                    stringResource(Res.string.color_orange),
                )
                val selectColorTemplate = stringResource(Res.string.cd_select_profile_color)

                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ProfileColors.forEachIndexed { index, color ->
                        val colorName = colorNames.getOrElse(index) { "Color ${index + 1}" }
                        val colorDesc = selectColorTemplate.replace("%1\$s", colorName)
                        val isThisSelected = index == selectedColorIndex
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isThisSelected) {
                                        Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { selectedColorIndex = index }
                                .semantics {
                                    role = Role.RadioButton
                                    selected = isThisSelected
                                    contentDescription = colorDesc
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isThisSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(Color.White, CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        scope.launch {
                            profileRepository.updateProfile(
                                profile.id,
                                name.trim(),
                                selectedColorIndex,
                            )
                        }
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
