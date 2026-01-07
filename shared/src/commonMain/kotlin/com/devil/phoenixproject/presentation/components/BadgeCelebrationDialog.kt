package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BadgeCategory
import kotlinx.coroutines.delay

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

/**
 * Batched celebration dialog that shows all earned badges in a single dialog.
 * Displays badges in a 3-column grid with expandable details when tapped.
 */
@Composable
fun BatchedBadgeCelebrationDialog(
    badges: List<Badge>,
    onDismiss: () -> Unit,
    onMarkAllCelebrated: (List<String>) -> Unit,
    onSoundTrigger: () -> Unit = {}
) {
    if (badges.isEmpty()) return

    // Animation states
    var showDialog by remember { mutableStateOf(false) }
    var selectedBadgeId by remember { mutableStateOf<String?>(null) }

    // Trigger sound once when dialog appears
    LaunchedEffect(Unit) {
        onSoundTrigger()
        showDialog = true
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

    // Selected badge for expanded details
    val selectedBadge = remember(selectedBadgeId, badges) {
        badges.find { it.id == selectedBadgeId }
    }

    Dialog(
        onDismissRequest = {
            onMarkAllCelebrated(badges.map { it.id })
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
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
                // Lottie trophy animation in background
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LottieAnimation(
                        animationJson = CelebrationAnimations.trophy,
                        size = 80.dp,
                        contentDescription = "Trophy celebration"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Header with badge count
                Text(
                    text = if (badges.size == 1) "Badge Earned!" else "${badges.size} Badges Earned!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD700),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Badge grid (3 columns)
                // Grid row height ~= 80dp (56dp icon + 16dp tier text + padding) + 12dp vertical spacing
                // 1 row (1-3 badges): 110dp, 2 rows (4-6 badges): 220dp, 3+ rows: 280dp with scroll
                val gridHeight = when {
                    badges.size <= 3 -> 110.dp
                    badges.size <= 6 -> 220.dp
                    else -> 280.dp
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = gridHeight),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = badges.size > 6
                ) {
                    itemsIndexed(
                        items = badges,
                        key = { _, badge -> badge.id }
                    ) { index, badge ->
                        BadgeGridItem(
                            badge = badge,
                            isSelected = badge.id == selectedBadgeId,
                            onClick = {
                                selectedBadgeId = if (selectedBadgeId == badge.id) null else badge.id
                            },
                            animationDelay = index * 50
                        )
                    }
                }

                // Expandable detail area
                AnimatedVisibility(
                    visible = selectedBadge != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    selectedBadge?.let { badge ->
                        val tierColor = Color(badge.tier.colorHex.toInt())

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Badge name
                            Text(
                                text = badge.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Description
                            Text(
                                text = badge.description,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Category chip
                            AssistChip(
                                onClick = { },
                                label = { Text(badge.category.displayName) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = getCategoryIcon(badge.category),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = tierColor.copy(alpha = 0.15f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Dismiss button
                Button(
                    onClick = {
                        onMarkAllCelebrated(badges.map { it.id })
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFD700)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Celebration,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Awesome!",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

/**
 * Individual badge item in the grid.
 * Shows a circular icon with tier-colored border and the tier name below.
 */
@Composable
private fun BadgeGridItem(
    badge: Badge,
    isSelected: Boolean,
    onClick: () -> Unit,
    animationDelay: Int
) {
    val tierColor = Color(badge.tier.colorHex.toInt())

    // Stagger fade-in animation
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(animationDelay.toLong())
        isVisible = true
    }

    val itemAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "itemAlpha"
    )

    val itemScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "itemScale"
    )

    // Pulse animation only for selected badge (avoids wasteful infinite transitions on unselected items)
    val pulseScale = if (isSelected) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        ).value
    } else {
        1f
    }

    val displayScale = itemScale * pulseScale

    Column(
        modifier = Modifier
            .alpha(itemAlpha)
            .scale(displayScale)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            .semantics {
                contentDescription = "${badge.name}, ${badge.tier.displayName} tier, ${badge.category.displayName} category"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular badge icon with tier border
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) tierColor else tierColor.copy(alpha = 0.7f),
                    shape = CircleShape
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            tierColor.copy(alpha = 0.3f),
                            tierColor.copy(alpha = 0.15f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getBadgeIcon(badge.iconResource),
                contentDescription = null,
                tint = tierColor,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Tier name below
        Text(
            text = badge.tier.displayName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tierColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
