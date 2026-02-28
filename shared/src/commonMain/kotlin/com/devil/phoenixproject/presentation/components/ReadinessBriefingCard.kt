package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ReadinessResult
import com.devil.phoenixproject.domain.model.ReadinessStatus
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Readiness Briefing Card -- shown to Elite tier users before their first set.
 *
 * Renders differently for [ReadinessResult.Ready] (traffic-light status with score)
 * and [ReadinessResult.InsufficientData] (informational message).
 *
 * Always dismissible (BRIEF-03: advisory only, never blocks workout).
 * Includes Portal upsell link (BRIEF-04).
 * Uses AccessibilityTheme colors with text+icon secondary signals (WCAG 1.4.1).
 */
@Composable
fun ReadinessBriefingCard(
    result: ReadinessResult,
    onDismiss: () -> Unit,
    onPortalLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pre-compute AccessibilityTheme colors before any draw operations (v0.5.1 decision)
    val colors = AccessibilityTheme.colors

    when (result) {
        is ReadinessResult.Ready -> ReadyCard(
            result = result,
            statusColor = when (result.status) {
                ReadinessStatus.GREEN -> colors.statusGreen
                ReadinessStatus.YELLOW -> colors.statusYellow
                ReadinessStatus.RED -> colors.statusRed
            },
            onDismiss = onDismiss,
            onPortalLink = onPortalLink,
            modifier = modifier
        )
        is ReadinessResult.InsufficientData -> InsufficientDataCard(
            onDismiss = onDismiss,
            onPortalLink = onPortalLink,
            modifier = modifier
        )
    }
}

@Composable
private fun ReadyCard(
    result: ReadinessResult.Ready,
    statusColor: Color,
    onDismiss: () -> Unit,
    onPortalLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusIcon: ImageVector
    val statusLabel: String
    val advisoryMessage: String

    when (result.status) {
        ReadinessStatus.GREEN -> {
            statusIcon = Icons.Default.CheckCircle
            statusLabel = "Ready"
            advisoryMessage = "Your training load is well-balanced. Train normally."
        }
        ReadinessStatus.YELLOW -> {
            statusIcon = Icons.Default.Warning
            statusLabel = "Caution"
            advisoryMessage = "Your recent training load is elevated. Consider moderate intensity."
        }
        ReadinessStatus.RED -> {
            statusIcon = Icons.Default.Error
            statusLabel = "Overreaching"
            advisoryMessage = "Your training load suggests overreaching. Consider a lighter session or active recovery."
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
                    .padding(end = Spacing.extraLarge) // Space for dismiss button
            ) {
                // Header row: icon + label + score badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusLabel,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Score badge
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "${result.score}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Advisory message
                Text(
                    text = advisoryMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Spacing.extraSmall))

                // ACWR detail line
                Text(
                    text = "Load ratio: ${"%.1f".format(result.acwr)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Portal upsell (BRIEF-04)
                TextButton(
                    onClick = onPortalLink,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "Connect to Portal for full readiness model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Dismiss button in top-right corner
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss readiness briefing",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InsufficientDataCard(
    onDismiss: () -> Unit,
    onPortalLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
                    .padding(end = Spacing.extraLarge) // Space for dismiss button
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Information",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Readiness Tracking",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                Text(
                    text = "Not enough training data yet. Train for 28+ days to enable readiness tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Portal upsell (BRIEF-04)
                TextButton(
                    onClick = onPortalLink,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "Connect to Portal for full readiness model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Dismiss button in top-right corner
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss readiness briefing",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
