package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// TODO: Import Spacing from theme when it's migrated
private val Spacing = object {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}

/**
 * Reusable empty state component for displaying when lists/screens have no data.
 *
 * Follows Material Design 3 principles and uses theme colors for consistent styling.
 *
 * @param icon The icon to display (defaults to FitnessCenter)
 * @param title The title text to show
 * @param message The descriptive message text
 * @param actionText Optional action button text. If null, no button is shown.
 * @param onAction Optional callback for action button. Required if actionText is provided.
 * @param modifier Optional modifier for the component
 */
@Composable
fun EmptyState(
    icon: ImageVector = Icons.Default.FitnessCenter,
    title: String,
    message: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            modifier = Modifier.padding(Spacing.large)
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = "Empty state",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Message
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.large)
            )

            // Optional Action Button
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(Spacing.small))
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = Spacing.medium),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(actionText)
                }
            }
        }
    }
}
