package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Reusable empty state component for displaying when lists/screens have no data.
 *
 * Follows Material Design 3 principles and uses theme colors for consistent styling.
 * The icon animates in with a SpringBouncy scale entrance; reduceMotion skips animation.
 *
 * @param icon The icon to display (defaults to FitnessCenter)
 * @param title The title text to show
 * @param message The descriptive message text
 * @param actionText Optional action button text. If null, no button is shown.
 * @param onAction Optional callback for action button. Required if actionText is provided.
 * @param iconTint Tint for the icon. Defaults to [Color.Unspecified], which resolves to
 *   primary (brand treatment) inside the composable. Pass
 *   [MaterialTheme.colorScheme.onSurfaceVariant] explicitly for subordinate empty states.
 * @param modifier Optional modifier for the component
 */
@Composable
fun EmptyState(
    icon: ImageVector = Icons.Default.FitnessCenter,
    title: String,
    message: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    iconTint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    // Resolve tint: Unspecified sentinel → primary brand color.
    // Primary is deep energetic orange (#E65100 light / #FF9149 dark) — legible on both
    // white surface (light) and Slate-800 surface (dark) at 0.8 alpha without alpha-stacking.
    val resolvedTint = if (iconTint == Color.Unspecified) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    } else {
        iconTint
    }

    // Entrance animation: icon first, button staggered 150 ms later.
    // When reduceMotion is true both start as already-visible so no frame is skipped.
    var iconVisible by remember { mutableStateOf(reduceMotion) }
    var buttonVisible by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            iconVisible = true
            delay(150L)
            buttonVisible = true
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            modifier = Modifier.padding(Spacing.large),
        ) {
            // Icon — Spacing.huge (48 dp) token; SpringBouncy scale-in entrance
            AnimatedVisibility(
                visible = iconVisible,
                enter = if (reduceMotion) EnterTransition.None
                        else scaleIn(animationSpec = ExpressiveMotion.SpringBouncy),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(Res.string.cd_empty_state),
                    modifier = Modifier.size(Spacing.huge),
                    tint = resolvedTint,
                )
            }

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Message
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.large),
            )

            // Optional Action Button — staggered entrance after icon.
            // Redundant Spacer + padding(top) removed; Column spacedBy(Spacing.medium)
            // already provides the 16 dp gap uniformly.
            if (actionText != null && onAction != null) {
                AnimatedVisibility(
                    visible = buttonVisible,
                    enter = if (reduceMotion) EnterTransition.None
                            else scaleIn(animationSpec = ExpressiveMotion.SpringBouncy),
                ) {
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(actionText)
                    }
                }
            }
        }
    }
}
