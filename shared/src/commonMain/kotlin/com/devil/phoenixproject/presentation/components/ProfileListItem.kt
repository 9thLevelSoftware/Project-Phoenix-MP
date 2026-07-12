package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Individual profile row for the shared switcher and legacy side panel.
 * Shows avatar, name, and active indicator.
 * Supports tap to select and long-press for context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileListItem(
    profile: UserProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    switching: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interactionModifier = if (onLongClick == null) {
        Modifier.clickable(
            enabled = enabled && !isActive,
            onClick = onClick,
        )
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(interactionModifier)
            .semantics {
                selected = isActive
                role = Role.RadioButton
            },
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAvatar(
                profile = profile,
                isActive = isActive,
            )

            // Name
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            // Active indicator
            if (switching) {
                val switchingDescription = stringResource(Res.string.profile_switching)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = switchingDescription },
                    strokeWidth = 2.dp,
                )
            } else if (isActive) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(Res.string.cd_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
