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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

// ─────────────────────────────────────────────────────────────────────────────
// Issue #1403: finite window-metric seam for the badge dialog.
//
// The crash: BatchedBadgeCelebrationDialog uses Dialog(usePlatformDefaultWidth = false),
// so on iOS the wrap-content dialog layer measures its content with
// maxWidth == Constraints.Infinity. The grid's fillMaxWidth() is a no-op under
// infinite constraints, and CMP 1.12.2 rememberColumnWidthSums (LazyGridDsl.kt:223)
// then throws IllegalArgumentException "LazyVerticalGrid's width should be bound by
// parent." which aborts the process (EXC_CRASH SIGABRT).
//
// The remediation (RCA fix direction + architecture review): compute a finite
// responsive Card width BEFORE the grid is measured, from live window metrics
// captured outside the unbounded dialog layer — approximately 90% of the
// inset-adjusted usable width, capped at 580dp — honoring tighter parent
// constraints. Invalid/zero metrics defer rendering instead of measuring an
// unbounded grid, without dismissing or marking queued badges celebrated.
// ─────────────────────────────────────────────────────────────────────────────

private const val CARD_WIDTH_FRACTION = 0.9f
private val MAX_CARD_WIDTH = 580.dp
private const val CONTENT_HEIGHT_FRACTION = 0.92f

/**
 * Non-flexible dialog chrome reserved before budgeting the grid and details areas:
 * 24dp+24dp Column padding, 80dp trophy, 8dp+20dp+20dp spacers, 48dp dismiss button
 * and a font-scaled header allowance. Conservative so the dismiss button stays
 * inside visible bounds in short windows.
 */
private val CHROME_RESERVE = 300.dp

/** Vertical space reserved for the expanded details area in short windows. */
private val DETAILS_RESERVE = 140.dp

/** One grid row height — the floor the grid never shrinks below. */
private val MIN_GRID_HEIGHT = 110.dp

/** Floor for the details area; it scrolls locally when capped. */
private val MIN_DETAILS_HEIGHT = 96.dp

/**
 * Finite layout budget for the batched badge dialog, computed from live window
 * metrics captured outside the unbounded dialog layer.
 *
 * `cardWidth` is the finite Card width (≈90% of inset-adjusted usable width, capped
 * at 580dp) that bounds the grid before measurement. `maxGridHeight` and
 * `maxDetailsHeight` keep the dismiss button reachable in short windows; they are
 * no-ops when the window has room (caps sit at or above the content's natural size).
 */
internal data class BadgeCelebrationLayoutBudget(
    val cardWidth: Dp,
    val maxContentHeight: Dp,
    val gridContentHeight: Dp,
    val maxGridHeight: Dp,
    val maxDetailsHeight: Dp,
)

/**
 * Pure window-metric seam for [BatchedBadgeCelebrationDialog]. Returns null for
 * invalid or non-positive metrics so the caller can defer rendering rather than
 * measure a grid under unbounded constraints.
 *
 * Insets are subtracted exactly once from the raw window dimensions; callers pass
 * live, raw dimensions (e.g. `LocalWindowSizeClass` widthDp/heightDp) captured
 * outside the dialog layer.
 */
internal fun badgeCelebrationLayoutBudget(
    windowWidth: Dp,
    windowHeight: Dp,
    horizontalInsets: Dp,
    verticalInsets: Dp,
    badgeCount: Int,
    detailsExpanded: Boolean,
): BadgeCelebrationLayoutBudget? {
    // Invalid metrics defer rendering. Non-finite and NaN values must never reach
    // the grid: a NaN/Infinity bound is not a bound.
    if (!windowWidth.value.isFinite() || !windowHeight.value.isFinite() ||
        !horizontalInsets.value.isFinite() || !verticalInsets.value.isFinite()
    ) {
        return null
    }
    if (windowWidth <= 0.dp || windowHeight <= 0.dp) return null
    if (horizontalInsets < 0.dp || verticalInsets < 0.dp) return null

    val usableWidth = windowWidth - horizontalInsets
    val usableHeight = windowHeight - verticalInsets
    if (usableWidth <= 0.dp || usableHeight <= 0.dp) return null

    // Approximately 90% of the inset-adjusted usable width, capped at 580dp.
    // Modifier.width() then coerces this into any tighter parent constraint.
    val cardWidth = minOf(usableWidth * CARD_WIDTH_FRACTION, MAX_CARD_WIDTH)
    if (!cardWidth.value.isFinite() || cardWidth <= 0.dp) return null

    val maxContentHeight = usableHeight * CONTENT_HEIGHT_FRACTION
    // Grid row height ~= 80dp (56dp icon + 16dp tier text + padding) + 12dp spacing.
    // 1 row (1-3 badges): 110dp, 2 rows (4-6 badges): 220dp, 3+ rows: 280dp with scroll.
    val gridContentHeight = when {
        badgeCount <= 3 -> 110.dp
        badgeCount <= 6 -> 220.dp
        else -> 280.dp
    }
    val detailsReserve = if (detailsExpanded) DETAILS_RESERVE else 0.dp
    val gridBudget = (maxContentHeight - CHROME_RESERVE - detailsReserve)
        .coerceAtLeast(MIN_GRID_HEIGHT)
    val maxGridHeight = minOf(gridContentHeight, gridBudget)
    val maxDetailsHeight = (maxContentHeight - CHROME_RESERVE - maxGridHeight)
        .coerceAtLeast(MIN_DETAILS_HEIGHT)

    return BadgeCelebrationLayoutBudget(
        cardWidth = cardWidth,
        maxContentHeight = maxContentHeight,
        gridContentHeight = gridContentHeight,
        maxGridHeight = maxGridHeight,
        maxDetailsHeight = maxDetailsHeight,
    )
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
    onSoundTrigger: () -> Unit = {},
) {
    if (badges.isEmpty()) return

    // Animation states
    var showDialog by remember { mutableStateOf(false) }
    var selectedBadgeId by remember { mutableStateOf<String?>(null) }

    // Issue #1403: live window metrics captured OUTSIDE the unbounded dialog layer.
    // LocalWindowSizeClass is provided from the root BoxWithConstraints in
    // EnhancedMainScreen (NavGraph -> ActiveWorkoutScreen -> here), so widthDp and
    // heightDp are live (recomputed on resize) and raw; subtract the horizontal and
    // vertical safe-drawing insets exactly once (converted with the current density)
    // to get inset-correct usable dimensions.
    val windowSizeClass = LocalWindowSizeClass.current
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val layoutBudget = badgeCelebrationLayoutBudget(
        windowWidth = windowSizeClass.widthDp,
        windowHeight = windowSizeClass.heightDp,
        horizontalInsets = safeDrawing.calculateLeftPadding(layoutDirection) +
            safeDrawing.calculateRightPadding(layoutDirection),
        verticalInsets = safeDrawing.calculateTopPadding() +
            safeDrawing.calculateBottomPadding(),
        badgeCount = badges.size,
        detailsExpanded = selectedBadgeId != null,
    )
    // Invalid/zero metrics defer rendering: no dialog, no sound, and no dismissal or
    // celebration callbacks — queued badges stay queued until metrics become valid.
    if (layoutBudget == null) return

    // Trigger sound once when dialog appears
    LaunchedEffect(Unit) {
        onSoundTrigger()
        showDialog = true
    }

    // dialogs-overlays-10: HighBouncy for the batched dialog entrance (same upgrade as single dialog).
    // reduceMotion: OR with reduceMotion so targetValue is immediately 1f (channel settles at open).
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (showDialog || reduceMotion) 1f else 0f,
        animationSpec = ExpressiveMotion.SpringBouncy,
        label = "scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (showDialog || reduceMotion) 1f else 0f,
        animationSpec = tween(300),
        label = "alpha",
    )

    Dialog(
        onDismissRequest = {
            onMarkAllCelebrated(badges.map { it.id })
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BatchedBadgeCelebrationDialogContent(
            badges = badges,
            budget = layoutBudget,
            selectedBadgeId = selectedBadgeId,
            onBadgeClick = { badgeId ->
                selectedBadgeId = if (selectedBadgeId == badgeId) null else badgeId
            },
            onMarkAllCelebrated = onMarkAllCelebrated,
            onDismiss = onDismiss,
            scale = scale,
            alpha = alpha,
        )
    }
}

/**
 * The dialog's Card and content. Extracted from [BatchedBadgeCelebrationDialog] so
 * tests can compose the actual production content under an unbounded outer width
 * (the iOS dialog-layer constraint that triggered the #1403 crash).
 */
@Composable
internal fun BatchedBadgeCelebrationDialogContent(
    badges: List<Badge>,
    budget: BadgeCelebrationLayoutBudget,
    selectedBadgeId: String?,
    onBadgeClick: (String) -> Unit,
    onMarkAllCelebrated: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    scale: Float,
    alpha: Float,
) {
    // Selected badge for expanded details
    val selectedBadge = remember(selectedBadgeId, badges) {
        badges.find { it.id == selectedBadgeId }
    }

    Card(
        modifier = Modifier
            // Issue #1403: finite responsive width enforced before the grid is
            // measured (≈90% usable width, capped at 580dp). Modifier.width coerces
            // into tighter parent constraints; never requiredWidth.
            .width(budget.cardWidth)
            .scale(scale)
            .alpha(alpha),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Lottie trophy animation in background
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                LottieAnimation(
                    animationJson = CelebrationAnimations.trophy,
                    size = 80.dp,
                    contentDescription = stringResource(Res.string.cd_trophy),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Header with badge count
            Text(
                text = if (badges.size ==
                    1
                ) {
                    "Badge Earned!"
                } else {
                    "${badges.size} Badges Earned!"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Badge grid (3 columns). Height is capped by the short-window budget so
            // the dismiss button stays reachable; the cap is a no-op when the window
            // has room for the natural row height.
            val gridHeight = budget.maxGridHeight

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = gridHeight),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // Rows scroll whenever the grid is capped below its natural row
                // height, so clipped rows stay reachable in short windows.
                userScrollEnabled = badges.size > 6 || gridHeight < budget.gridContentHeight,
            ) {
                itemsIndexed(
                    items = badges,
                    key = { _, badge -> badge.id },
                ) { index, badge ->
                    BadgeGridItem(
                        badge = badge,
                        isSelected = badge.id == selectedBadgeId,
                        onClick = { onBadgeClick(badge.id) },
                        animationDelay = index * 50,
                    )
                }
            }

            // Expandable detail area
            AnimatedVisibility(
                visible = selectedBadge != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                selectedBadge?.let { badge ->
                    val tierColor = Color(badge.tier.colorHex.toInt())

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            // Short-window budget: the details block scrolls locally
                            // when capped (the grid is never nested in a scroller).
                            .heightIn(max = budget.maxDetailsHeight)
                            .verticalScroll(rememberScrollState())
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Badge name
                        Text(
                            text = badge.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Description
                        Text(
                            text = badge.description,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = tierColor.copy(alpha = 0.15f),
                            ),
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
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Celebration,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Awesome!",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Individual badge item in the grid.
 * Shows a circular icon with tier-colored border and the tier name below.
 */
@Composable
private fun BadgeGridItem(badge: Badge, isSelected: Boolean, onClick: () -> Unit, animationDelay: Int) {
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
        label = "itemAlpha",
    )

    // dialogs-overlays-10: HighBouncy + StiffnessLow for the badge grid pop-in (properly festive).
    // reduceMotion: OR with reduceMotion so target is immediately 1f (channel settles immediately).
    val reduceMotionItem = LocalPlatformAccessibilitySettings.current.reduceMotion
    val itemScale by animateFloatAsState(
        targetValue = if (isVisible || reduceMotionItem) 1f else 0.5f,
        animationSpec = ExpressiveMotion.SpringBouncy,
        label = "itemScale",
    )

    // Pulse animation only for selected badge (avoids wasteful infinite transitions on unselected items)
    val pulseScale = if (isSelected) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        ).value
    } else {
        1f
    }

    val displayScale = itemScale * pulseScale

    Column(
        modifier = Modifier
            .alpha(itemAlpha)
            .scale(displayScale)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(4.dp)
            .semantics {
                contentDescription =
                    "${badge.name}, ${badge.tier.displayName} tier, ${badge.category.displayName} category"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Circular badge icon with tier border
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) tierColor else tierColor.copy(alpha = 0.7f),
                    shape = CircleShape,
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            tierColor.copy(alpha = 0.3f),
                            tierColor.copy(alpha = 0.15f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = getBadgeIcon(badge.iconResource),
                contentDescription = null,
                tint = tierColor,
                modifier = Modifier.size(28.dp),
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
            overflow = TextOverflow.Ellipsis,
        )
    }
}
