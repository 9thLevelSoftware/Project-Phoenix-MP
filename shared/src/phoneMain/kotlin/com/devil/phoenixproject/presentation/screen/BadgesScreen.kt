package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import com.devil.phoenixproject.presentation.components.RpgAttributeCard
import com.devil.phoenixproject.presentation.components.getBadgeIcon
import com.devil.phoenixproject.presentation.components.getCategoryIcon
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.FlameOrange
import com.devil.phoenixproject.ui.theme.FlameRed
import com.devil.phoenixproject.ui.theme.FlameYellow
import com.devil.phoenixproject.ui.theme.Slate50
import com.devil.phoenixproject.ui.theme.Slate900
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesScreen(
    mainViewModel: com.devil.phoenixproject.presentation.viewmodel.MainViewModel,
    viewModel: GamificationViewModel = koinViewModel(),
) {
    val badges by viewModel.filteredBadges.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val streakInfo by viewModel.streakInfo.collectAsState()
    val gamificationStats by viewModel.gamificationStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val weightUnit by mainViewModel.weightUnit.collectAsState()
    val rpgProfile by viewModel.rpgProfile.collectAsState()

    var selectedBadge by remember { mutableStateOf<BadgeWithProgress?>(null) }

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        mainViewModel.updateTopBarTitle("")
        viewModel.loadRpgProfile()
    }

    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val constrainedModifier = if (contentMaxWidth != null) {
            Modifier.widthIn(max = contentMaxWidth).fillMaxSize()
        } else {
            Modifier.fillMaxSize()
        }

        if (isLoading) {
            Box(
                modifier = constrainedModifier,
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(LoadingIndicatorSize.Large)
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
                contentPadding = PaddingValues(
                    start = Spacing.medium,
                    end = Spacing.medium,
                    top = Spacing.medium,
                    bottom = Spacing.medium + navBarBottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                modifier = constrainedModifier,
            ) {
                rpgProfile?.let { profile ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "rpg_header") {
                        RpgAttributeCard(profile = profile)
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "streak_widget") {
                    StreakWidget(
                        streakInfo = streakInfo,
                        totalWorkouts = gamificationStats.totalWorkouts,
                        totalBadges = viewModel.earnedBadgeCount,
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "stats_row") {
                    StatsRow(
                        earnedCount = viewModel.earnedBadgeCount,
                        totalCount = viewModel.totalBadgeCount,
                        modifier = Modifier.padding(top = Spacing.extraSmall),
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "category_filter_row") {
                    CategoryFilterRow(
                        selectedCategory = selectedCategory,
                        onCategorySelected = viewModel::selectCategory,
                        modifier = Modifier.padding(vertical = Spacing.extraSmall),
                    )
                }

                items(badges, key = { it.badge.id }) { badgeWithProgress ->
                    val isSecretLocked = badgeWithProgress.badge.isSecret && !badgeWithProgress.isEarned
                    BadgeCard(
                        badgeWithProgress = badgeWithProgress,
                        onClick = if (isSecretLocked) {
                            null
                        } else {
                            { selectedBadge = badgeWithProgress }
                        },
                    )
                }
            }
        }
    }

    // Badge Detail Dialog
    selectedBadge?.let { badge ->
        BadgeDetailDialog(
            badgeWithProgress = badge,
            weightUnit = weightUnit,
            onDismiss = { selectedBadge = null },
        )
    }
}

@Composable
private fun StreakWidget(streakInfo: StreakInfo, totalWorkouts: Int, totalBadges: Int, modifier: Modifier = Modifier) {
    // lens-state-patterns-14: map streak tiers to Color.kt flame constants so the
    // widget stays aligned with other fire-gradient uses in the app.
    val fireColor = when {
        streakInfo.currentStreak >= 30 -> FlameRed        // most intense — outer flame
        streakInfo.currentStreak >= 7 -> FlameOrange      // sustained — core flame
        streakInfo.currentStreak >= 3 -> FlameYellow      // warming up — inner flame
        else -> MaterialTheme.colorScheme.secondary       // baseline glow
    }

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (streakInfo.currentStreak > 0 || reduceMotion) 1f else 0.95f,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessLow),
        label = "streak_scale",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Current Streak
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (streakInfo.currentStreak > 0) {
                                Brush.radialGradient(
                                    listOf(fireColor, fireColor.copy(alpha = 0.3f)),
                                )
                            } else {
                                Brush.radialGradient(
                                    listOf(Color.Gray, Color.Gray.copy(alpha = 0.3f)),
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Same luminance rule as the badge icons (analytics-history-18):
                    // light tiers (FlameYellow, secondary) need a dark glyph for AA contrast.
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = if (streakInfo.currentStreak > 0 && fireColor.luminance() > 0.4f) Slate900 else Slate50,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${streakInfo.currentStreak}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Day Streak",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                if (streakInfo.isAtRisk) {
                    Text(
                        text = "At Risk!",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlameRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(60.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
            )

            // Total Workouts
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$totalWorkouts",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Workouts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(60.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
            )

            // Badges Earned
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.MilitaryTech,
                    contentDescription = null,
                    tint = if (MaterialTheme.colorScheme.primaryContainer.luminance() > 0.5f) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        Color(0xFFFFD700)
                    },
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$totalBadges",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Badges",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun StatsRow(earnedCount: Int, totalCount: Int, modifier: Modifier = Modifier) {
    val progress = if (totalCount > 0) earnedCount.toFloat() / totalCount else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Badge Progress",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$earnedCount / $totalCount",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
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
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun CategoryFilterRow(
    selectedCategory: BadgeCategory?,
    onCategorySelected: (BadgeCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipItems: @Composable () -> Unit = {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onCategorySelected(null) },
            modifier = Modifier.heightIn(min = 48.dp),
            label = { Text(stringResource(Res.string.label_all)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )

        BadgeCategory.entries.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(category.displayName) },
                leadingIcon = {
                    Icon(
                        imageVector = getCategoryIcon(category),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }

    if (isCompactAccessibilityLayout()) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            chipItems()
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            chipItems()
        }
    }
}

@Composable
private fun BadgeCard(badgeWithProgress: BadgeWithProgress, onClick: (() -> Unit)?) {
    val badge = badgeWithProgress.badge
    val isEarned = badgeWithProgress.isEarned
    val isSecret = badge.isSecret && !isEarned
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    val tierColor = Color(badge.tier.colorHex.toInt())
    // analytics-history-18: choose icon tint by luminance so light-tier gradients
    // (bronze, silver) still pass WCAG AA contrast. Slate900 on light bg, Slate50 on dark.
    val badgeIconTint = if (tierColor.luminance() > 0.4f) Slate900 else Slate50
    val alpha = if (isEarned) 1f else 0.72f
    val scale by animateFloatAsState(
        targetValue = if (isEarned || reduceMotion) 1f else 0.95f,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessLow),
        label = "badge_scale",
    )

    val cardModifier = Modifier
        .aspectRatio(0.85f)
        .scale(scale)
        .alpha(alpha)
        .let { base ->
            if (isSecret) {
                base.semantics(mergeDescendants = true) {
                    contentDescription = "Secret badge, locked"
                }
            } else {
                base
            }
        }
    val cardColors = CardDefaults.cardColors(
        containerColor = if (isEarned) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    )
    val cardBorder = if (isEarned) {
        BorderStroke(2.dp, tierColor)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }

    val cardContent: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Badge Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEarned) {
                            Brush.radialGradient(listOf(tierColor, tierColor.copy(alpha = 0.5f)))
                        } else {
                            Brush.radialGradient(listOf(Color.Gray, Color.Gray.copy(alpha = 0.3f)))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSecret) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        // Lock icon is on a gray background (luminance ~0.22) → Slate50 gives safe contrast
                        tint = Slate50,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = getBadgeIcon(badge.iconResource),
                        contentDescription = null,
                        tint = badgeIconTint,
                        modifier = Modifier.size(24.dp),
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
                color = MaterialTheme.colorScheme.onSurface,
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
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = "${(badgeWithProgress.progressPercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Earned checkmark
            if (isEarned) {
                Spacer(modifier = Modifier.height(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(Res.string.cd_earned),
                    tint = tierColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }

    if (onClick != null) {
        ExpressiveCard(
            onClick = onClick,
            modifier = cardModifier,
            shape = MaterialTheme.shapes.small,
            colors = cardColors,
            border = cardBorder,
            content = cardContent,
        )
    } else {
        Card(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.small,
            colors = cardColors,
            border = cardBorder,
            content = cardContent,
        )
    }
}

@Composable
private fun BadgeDetailDialog(
    badgeWithProgress: BadgeWithProgress,
    weightUnit: WeightUnit = WeightUnit.KG,
    onDismiss: () -> Unit,
) {
    val badge = badgeWithProgress.badge
    val isEarned = badgeWithProgress.isEarned
    val isSecretLocked = badge.isSecret && !isEarned
    val tierColor = Color(badge.tier.colorHex.toInt())
    // analytics-history-18: luminance-based tint for dialog icon (same logic as BadgeCard).
    val dialogIconTint = if (tierColor.luminance() > 0.4f) Slate900 else Slate50

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEarned) {
                            Brush.radialGradient(listOf(tierColor, tierColor.copy(alpha = 0.5f)))
                        } else {
                            Brush.radialGradient(listOf(Color.Gray, Color.Gray.copy(alpha = 0.3f)))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isSecretLocked) Icons.Default.Lock else getBadgeIcon(badge.iconResource),
                    contentDescription = null,
                    tint = if (isEarned) dialogIconTint else Slate50,
                    modifier = Modifier.size(40.dp),
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isSecretLocked) "Secret Badge" else badge.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (isSecretLocked) "Locked" else badge.tier.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSecretLocked) MaterialTheme.colorScheme.onSurfaceVariant else tierColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = if (isSecretLocked) {
                        "Keep training to unlock this secret badge."
                    } else {
                        badge.description
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Progress (hidden for unearned secret badges to prevent leaking requirements)
                if (!isEarned && !isSecretLocked) {
                    Text(
                        text = "Progress",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { badgeWithProgress.progressPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = tierColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = badge.getProgressDescription(badgeWithProgress.currentProgress, weightUnit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                } else if (isEarned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccessibilityTheme.colors.success,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Earned!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccessibilityTheme.colors.success,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.small))
                }

                // Non-clickable category/secret pill
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (isSecretLocked) Icons.Default.Lock else getCategoryIcon(badge.category),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (isSecretLocked) "Secret" else badge.category.displayName,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.action_close))
            }
        },
    )
}
