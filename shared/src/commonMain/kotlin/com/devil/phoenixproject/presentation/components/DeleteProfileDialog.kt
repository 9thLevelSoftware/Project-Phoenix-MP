package com.devil.phoenixproject.presentation.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.*

/**
 * Confirmation dialog for deleting a user profile.
 */
@Composable
fun DeleteProfileDialog(
    profile: UserProfile,
    profileRepository: UserProfileRepository,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.delete_profile)) },
        text = {
            Text(stringResource(Res.string.delete_profile_message, profile.name))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        profileRepository.deleteProfile(profile.id)
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(Res.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}
