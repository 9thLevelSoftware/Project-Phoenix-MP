package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Profile color palette
val ProfileColors = listOf(
    Color(0xFF3B82F6), // Blue
    Color(0xFF10B981), // Green
    Color(0xFFF59E0B), // Amber
    Color(0xFFEF4444), // Red
    Color(0xFF8B5CF6), // Purple
    Color(0xFFEC4899), // Pink
    Color(0xFF06B6D4), // Cyan
    Color(0xFFF97316)  // Orange
)

// Constant for profile color count to avoid magic numbers
const val PROFILE_COLOR_COUNT = 8

@Composable
fun ProfileSpeedDial(
    profiles: List<UserProfile>,
    activeProfile: UserProfile?,
    onProfileSelected: (UserProfile) -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "rotation"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd
    ) {
        // Expanded menu items
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Column(
                modifier = Modifier.padding(bottom = 72.dp, end = 4.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Add profile button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            "Add Profile",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = {
                            expanded = false
                            onAddProfile()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add profile")
                    }
                }

                // Profile list
                profiles.forEach { profile ->
                    val isActive = profile.id == activeProfile?.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shadowElevation = 4.dp
                        ) {
                            Text(
                                profile.name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        ProfileAvatar(
                            profile = profile,
                            isActive = isActive,
                            onClick = {
                                expanded = false
                                onProfileSelected(profile)
                            }
                        )
                    }
                }
            }
        }

        // Main FAB showing active profile
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(56.dp),
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHighest
                            else ProfileColors.getOrElse(activeProfile?.colorIndex ?: 0) { ProfileColors[0] },
            contentColor = Color.White
        ) {
            if (expanded) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close menu",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = activeProfile?.name?.take(1)?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ProfileAvatar(
    profile: UserProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = ProfileColors.getOrElse(profile.colorIndex) { ProfileColors[0] }

    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.shadow(if (isActive) 8.dp else 4.dp, CircleShape),
        containerColor = color,
        contentColor = Color.White
    ) {
        Text(
            text = profile.name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Reusable dialog for adding a new user profile.
 */
@Composable
fun AddProfileDialog(
    profiles: List<UserProfile>,
    profileRepository: UserProfileRepository,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    var newProfileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Profile") },
        text = {
            OutlinedTextField(
                value = newProfileName,
                onValueChange = { newProfileName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newProfileName.isNotBlank()) {
                        scope.launch {
                            val colorIndex = profiles.size % PROFILE_COLOR_COUNT
                            profileRepository.createProfile(newProfileName.trim(), colorIndex)
                        }
                        onDismiss()
                    }
                },
                enabled = newProfileName.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
