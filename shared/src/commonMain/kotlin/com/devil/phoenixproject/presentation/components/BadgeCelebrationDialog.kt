package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BadgeCategory

/**
 * Celebratory dialog shown when a user earns a new badge
 */
@Composable
fun BadgeCelebrationDialog(
    badge: Badge,
    onDismiss: () -> Unit,
    onMarkCelebrated: () -> Unit,
    onSoundTrigger: () -> Unit = {}
) {
    val tierColor = Color(badge.tier.colorHex.toInt())

    // Animation states
    var showDialog by remember { mutableStateOf(false) }

    // Trigger sound when dialog is shown
    LaunchedEffect(badge.id) {
        onSoundTrigger()
    }
    val scale by animateFloatAsState(
        targetValue = if (showDialog) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (showDialog) 1f else 0f,
        animationSpec = tween(300),
        label = "alpha"
    )

    // Badge icon animation
    val infiniteTransition = rememberInfiniteTransition(label = "celebration")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rotation"
    )

    // Start animation
    LaunchedEffect(Unit) {
        showDialog = true
    }

    Dialog(
        onDismissRequest = {
            onMarkCelebrated()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .alpha(alpha),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Celebration header
                Text(
                    text = "Achievement Unlocked!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD700)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Animated badge icon with Lottie trophy animation
                Box(
                    modifier = Modifier.size(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Lottie trophy animation in background
                    LottieAnimation(
                        animationJson = CelebrationAnimations.trophy,
                        size = 140.dp,
                        contentDescription = "Trophy celebration"
                    )

                    // Main badge icon overlay
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .rotate(rotation)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(tierColor, tierColor.copy(alpha = 0.7f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getBadgeIcon(badge.iconResource),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Badge name
                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Tier badge
                Surface(
                    color = tierColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = badge.tier.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = tierColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                // Description
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Category chip
                AssistChip(
                    onClick = { },
                    label = { Text(badge.category.displayName) },
                    leadingIcon = {
                        Icon(
                            imageVector = getCategoryIcon(badge.category),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Continue button
                Button(
                    onClick = {
                        onMarkCelebrated()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tierColor
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Celebration,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Awesome!",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Queue-based celebration dialog that shows multiple badges one at a time
 */
@Composable
fun BadgeCelebrationQueue(
    badges: List<Badge>,
    onAllCelebrated: () -> Unit,
    onMarkCelebrated: (String) -> Unit,
    onSoundTrigger: () -> Unit = {}
) {
    var currentIndex by remember { mutableStateOf(0) }

    if (badges.isNotEmpty() && currentIndex < badges.size) {
        BadgeCelebrationDialog(
            badge = badges[currentIndex],
            onDismiss = {
                if (currentIndex < badges.size - 1) {
                    currentIndex++
                } else {
                    onAllCelebrated()
                }
            },
            onMarkCelebrated = {
                onMarkCelebrated(badges[currentIndex].id)
            },
            onSoundTrigger = onSoundTrigger
        )
    }
}

// Helper functions
private fun getCategoryIcon(category: BadgeCategory): ImageVector {
    return when (category) {
        BadgeCategory.CONSISTENCY -> Icons.Default.LocalFireDepartment
        BadgeCategory.STRENGTH -> Icons.Default.EmojiEvents
        BadgeCategory.VOLUME -> Icons.Default.Repeat
        BadgeCategory.EXPLORER -> Icons.Default.Explore
        BadgeCategory.DEDICATION -> Icons.Default.FitnessCenter
    }
}

private fun getBadgeIcon(iconResource: String): ImageVector {
    return when (iconResource) {
        "fire" -> Icons.Default.LocalFireDepartment
        "trophy" -> Icons.Default.EmojiEvents
        "dumbbell" -> Icons.Default.FitnessCenter
        "repeat" -> Icons.Default.Repeat
        "compass" -> Icons.Default.Explore
        "calendar" -> Icons.Default.CalendarMonth
        "sun" -> Icons.Default.WbSunny
        "moon" -> Icons.Default.NightsStay
        "weight" -> Icons.Default.FitnessCenter
        "lightning" -> Icons.Default.Bolt
        "body" -> Icons.Default.Accessibility
        "phoenix" -> Icons.Default.LocalFireDepartment // Phoenix uses fire icon
        "shield" -> Icons.Default.Shield
        "list" -> Icons.Default.Checklist
        else -> Icons.Default.Star
    }
}
