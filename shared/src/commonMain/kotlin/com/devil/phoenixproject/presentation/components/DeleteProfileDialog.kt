package com.devil.phoenixproject.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_delete
import vitruvianprojectphoenix.shared.generated.resources.delete_profile
import vitruvianprojectphoenix.shared.generated.resources.delete_profile_message

/**
 * Confirmation dialog for deleting a user profile.
 */
@Composable
fun DeleteProfileDialog(profile: UserProfile, profileRepository: UserProfileRepository, scope: CoroutineScope, onDismiss: () -> Unit) {
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
                        // Issue #393: Unhandled exceptions in profile deletion caused
                        // SIGABRT on iOS (Kotlin/Native abort() on uncaught exception).
                        try {
                            profileRepository.deleteProfile(profile.id)
                        } catch (e: Exception) {
                            Logger.e(e) { "PROFILE_DELETE: Failed to delete profile '${profile.name}' (id=${profile.id})" }
                        }
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(Res.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
