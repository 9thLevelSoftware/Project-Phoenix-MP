package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.Spacing
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesScreen(
    onBack: () -> Unit,
    mainViewModel: com.devil.phoenixproject.presentation.viewmodel.MainViewModel,
    viewModel: GamificationViewModel = koinInject()
) {
    val badges by viewModel.filteredBadges.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val streakInfo by viewModel.streakInfo.collectAsState()
    val gamificationStats by viewModel.gamificationStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedBadge by remember { mutableStateOf<BadgeWithProgress?>(null) }

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        mainViewModel.updateTopBarTitle("")
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Streak Widget at top
            StreakWidget(
                streakInfo = streakInfo,
                totalWorkouts = gamificationStats.totalWorkouts,
                totalBadges = viewModel.earnedBadgeCount,
                modifier = Modifier.padding(Spacing.medium)
            )

            // Stats Row
            StatsRow(
                earnedCount = viewModel.earnedBadgeCount,
                totalCount = viewModel.totalBadgeCount,
                modifier = Modifier.padding(horizontal = Spacing.medium)
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Category Filter Chips
            CategoryFilterRow(
                selectedCategory = selectedCategory,
                onCategorySelected = viewModel::selectCategory,
                modifier = Modifier.padding(horizontal = Spacing.medium)
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Badges Grid
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val windowSizeClass = LocalWindowSizeClass.current
                val gridColumns = when (windowSizeClass.widthSizeClass) {
                    WindowWidthSizeClass.Expanded -> 6
                    WindowWidthSizeClass.Medium -> 4
                    WindowWidthSizeClass.Compact -> 3
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(badges, key = { it.badge.id }) { badgeWithProgress ->
                        BadgeCard(
                            badgeWithProgress = badgeWithProgress,
                            onClick = { selectedBadge = badgeWithProgress }
                        )
                    }
                }
            }
        }
    }

    // Badge Detail Dialog
    selectedBadge?.let { badge ->
        BadgeDetailDialog(
            badgeWithProgress = badge,
            onDismiss = { selectedBadge = null }
        )
    }
}

@Composable
fun StreakWidget(
    streakInfo: StreakInfo,
    totalWorkouts: Int,
    totalBadges: Int,
    modifier: Modifier = Modifier
) {
    val fireColor = when {
        streakInfo.currentStreak >= 30 -> Color(0xFFFF4500) // Orange-red for long streaks
        streakInfo.currentStreak >= 7 -> Color(0xFFFF8C00) // Dark orange
        streakInfo.currentStreak >= 3 -> Color(0xFFFFA500) // Orange
        else -> Color(0xFFFFD700) // Gold
    }

    val scale by animateFloatAsState(
        targetValue = if (streakInfo.currentStreak > 0) 1f else 0.95f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "streak_scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Current Streak
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (streakInfo.currentStreak > 0)
                                Brush.radialGradient(listOf(fireColor, fireColor.copy(alpha = 0.3f)))
                            else
                                Brush.radialGradient(listOf(Color.Gray, Color.Gray.copy(alpha = 0.3f)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${streakInfo.currentStreak}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Day Streak",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                if (streakInfo.isAtRisk) {
                    Text(
                        text = "At Risk!",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF4500),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(60.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            )

            // Total Workouts
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$totalWorkouts",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Workouts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(60.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            )

            // Badges Earned
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.MilitaryTech,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$totalBadges",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Badges",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatsRow(
    earnedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    val progress = if (totalCount > 0) earnedCount.toFloat() / totalCount else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Badge Progress",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$earnedCount / $totalCount",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun CategoryFilterRow(
    selectedCategory: BadgeCategory?,
    onCategorySelected: (BadgeCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        // All category
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onCategorySelected(null) },
            label = { Text("All") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        // Individual categories
        BadgeCategory.entries.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { Text(category.displayName) },
                leadingIcon = {
                    Icon(
                        imageVector = getCategoryIcon(category),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun BadgeCard(
    badgeWithProgress: BadgeWithProgress,
    onClick: () -> Unit
) {
    val badge = badgeWithProgress.badge
    val isEarned = badgeWithProgress.isEarned
    val isSecret = badge.isSecret && !isEarned

    val tierColor = Color(badge.tier.colorHex.toInt())
    val alpha = if (isEarned) 1f else 0.5f
    val scale by animateFloatAsState(
        targetValue = if (isEarned) 1f else 0.95f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "badge_scale"
    )

    Card(
        modifier = Modifier
            .aspectRatio(0.85f)
            .scale(scale)
            .alpha(alpha)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEarned)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isEarned) BorderStroke(2.dp, tierColor) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Badge Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEarned)
                            Brush.radialGradient(listOf(tierColor, tierColor.copy(alpha = 0.5f)))
                        else
                            Brush.radialGradient(listOf(Color.Gray, Color.Gray.copy(alpha = 0.3f)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSecret) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = getBadgeIcon(badge.iconResource),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Badge Name
            Text(
                text = if (isSecret) "???" else badge.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Progress (if not earned and not secret)
            if (!isEarned && !isSecret) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { badgeWithProgress.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = tierColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "${(badgeWithProgress.progressPercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Earned checkmark
            if (isEarned) {
                Spacer(modifier = Modifier.height(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Earned",
                    tint = tierColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun BadgeDetailDialog(
    badgeWithProgress: BadgeWithProgress,
    onDismiss: () -> Unit
) {
    val badge = badgeWithProgress.badge
    val isEarned = badgeWithProgress.isEarned
    val tierColor = Color(badge.tier.colorHex.toInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEarned)
                            Brush.radialGradient(listOf(tierColor, tierColor.copy(alpha = 0.5f)))
                        else
                            Brush.radialGradient(listOf(Color.Gray, Color.Gray.copy(alpha = 0.3f)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getBadgeIcon(badge.iconResource),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = badge.tier.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = tierColor,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Progress
                if (!isEarned) {
                    Text(
                        text = "Progress",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { badgeWithProgress.progressPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = tierColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = badge.getProgressDescription(badgeWithProgress.currentProgress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Earned!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
