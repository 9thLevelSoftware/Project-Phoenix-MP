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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 * Non-flexible dialog chrome reserved before budgeting the grid and details areas,
 * EXCLUDING the decorative trophy and the font-scaled header: 24dp+24dp Column
 * padding, 8dp trophy spacer, 20dp header spacer, 20dp pre-dismiss spacer and the
 * 48dp dismiss button.
 */
private val CHROME_FIXED = 144.dp

/** Decorative trophy size; shrinks first when a window is extremely short. */
private val TROPHY_SIZE = 80.dp

/** titleLarge line height in sp; the header is hard-capped at [MAX_HEADER_LINES]. */
private const val HEADER_LINE_HEIGHT_SP = 28f

/** Hard cap on header lines (ellipsized beyond) so chrome is bounded at any font scale. */
private const val MAX_HEADER_LINES = 2

/** Slack absorbing text line-height rounding when converting sp*fontScale to dp. */
private val HEADER_ROUNDING_SLACK = 4.dp

/** Vertical space the expanded details area wishes for; it scrolls locally when capped. */
private val DETAILS_RESERVE = 140.dp

/**
 * Finite layout budget for the batched badge dialog, computed from live window
 * metrics captured outside the unbounded dialog layer.
 *
 * `cardWidth` is the finite Card width (≈90% of inset-adjusted usable width, capped
 * at 580dp) that bounds the grid before measurement. `chromeUpperBound` is the
 * worst-case measured chrome (fixed chrome + trophy + the header's line cap at the
 * current font scale); `maxGridHeight` and `maxDetailsHeight` split exactly the
 * remainder of `maxContentHeight` after that chrome — no minimum floors — so
 * `chromeUpperBound + maxGridHeight + maxDetailsHeight <= maxContentHeight` holds
 * by construction and the dismiss button stays inside visible bounds in short
 * windows. Both areas may shrink to zero and scroll locally.
 */
internal data class BadgeCelebrationLayoutBudget(
    val cardWidth: Dp,
    val maxContentHeight: Dp,
    val gridContentHeight: Dp,
    val maxGridHeight: Dp,
    val maxDetailsHeight: Dp,
    val chromeUpperBound: Dp,
    val trophySize: Dp,
    val spacerSmall: Dp,
    val spacerLarge: Dp,
    val headerMaxLines: Int,
)

/**
 * Pure window-metric seam for [BatchedBadgeCelebrationDialog]. Returns null for
 * invalid or non-positive metrics so the caller can defer rendering rather than
 * measure a grid under unbounded constraints.
 *
 * Insets are subtracted exactly once from the raw window dimensions; callers pass
 * live, raw dimensions (e.g. `LocalWindowSizeClass` widthDp/heightDp) captured
 * outside the dialog layer, plus the current font scale so chrome is bounded for
 * real text sizes instead of a fixed constant.
 */
internal fun badgeCelebrationLayoutBudget(
    windowWidth: Dp,
    windowHeight: Dp,
    horizontalInsets: Dp,
    verticalInsets: Dp,
    badgeCount: Int,
    detailsExpanded: Boolean,
    fontScale: Float = 1f,
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
    if (!fontScale.isFinite() || fontScale <= 0f) return null

    val usableWidth = windowWidth - horizontalInsets
    val usableHeight = windowHeight - verticalInsets
    if (usableWidth <= 0.dp || usableHeight <= 0.dp) return null

    // Approximately 90% of the inset-adjusted usable width, capped at 580dp.
    // Modifier.width() then coerces this into any tighter parent constraint.
    val cardWidth = minOf(usableWidth * CARD_WIDTH_FRACTION, MAX_CARD_WIDTH)
    if (!cardWidth.value.isFinite() || cardWidth <= 0.dp) return null

    val maxContentHeight = usableHeight * CONTENT_HEIGHT_FRACTION

    // Chrome upper bound: fixed chrome + trophy + the header's worst case
    // (MAX_HEADER_LINES lines at the current font scale). The header is hard-capped
    // at MAX_HEADER_LINES in the layout, so this bound holds at any text wrap.
    // Shrink order when a window is extremely short: the decorative trophy first,
    // then decorative spacing — never the dismiss button and never text sizes.
    val lineHeightDp = (HEADER_LINE_HEIGHT_SP * fontScale).dp
    val worstHeader = lineHeightDp * MAX_HEADER_LINES + HEADER_ROUNDING_SLACK
    var trophySize = TROPHY_SIZE
    var spacerSmall = 8.dp
    var spacerLarge = 20.dp
    var chromeBase = CHROME_FIXED + trophySize + worstHeader
    if (chromeBase > maxContentHeight) {
        trophySize = (TROPHY_SIZE - (chromeBase - maxContentHeight)).coerceAtLeast(0.dp)
        chromeBase = CHROME_FIXED + trophySize + worstHeader
    }
    if (chromeBase > maxContentHeight) {
        // Spacers: 20dp+20dp -> 8dp+8dp, then 8dp -> 4dp (28dp of decorative slack).
        val deficit = chromeBase - maxContentHeight
        val largeCut = minOf(deficit, 12.dp)
        val smallCut = minOf(deficit - largeCut, 4.dp)
        spacerLarge = 20.dp - largeCut
        spacerSmall = 8.dp - smallCut
        chromeBase -= largeCut * 2 + smallCut
    }
    // Header lines are floored to what actually fits, so chrome never exceeds the
    // budget even in the tightest windows. The layout renders at least one line
    // (below ~120dp usable height at large font scale something must give; the
    // card's hard height bound keeps the dialog bounded and the button in place).
    val chromeBeforeHeader = CHROME_FIXED + trophySize + spacerSmall + spacerLarge * 2 -
        (8.dp + 20.dp + 20.dp) + HEADER_ROUNDING_SLACK
    val headerMaxLines = if (lineHeightDp <= 0.dp) {
        0
    } else {
        ((maxContentHeight - chromeBeforeHeader) / lineHeightDp).toInt().coerceIn(0, MAX_HEADER_LINES)
    }
    val chromeUpperBound = chromeBeforeHeader + lineHeightDp * headerMaxLines

    // Everything left after chrome is shared by the grid and the details area
    // with NO minimum floors (merge audit F1: floors previously pushed the total
    // past the declared budget). Each area may shrink to zero and scrolls locally,
    // so chrome + grid + details <= maxContentHeight always holds.
    val contentAvail = (maxContentHeight - chromeUpperBound).coerceAtLeast(0.dp)
    // Grid row height ~= 80dp (56dp icon + 16dp tier text + padding) + 12dp spacing.
    // 1 row (1-3 badges): 110dp, 2 rows (4-6 badges): 220dp, 3+ rows: 280dp with scroll.
    val gridContentHeight = when {
        badgeCount <= 3 -> 110.dp
        badgeCount <= 6 -> 220.dp
        else -> 280.dp
    }
    val maxGridHeight: Dp
    val maxDetailsHeight: Dp
    if (!detailsExpanded) {
        maxGridHeight = minOf(gridContentHeight, contentAvail)
        maxDetailsHeight = 0.dp
    } else {
        // The details block the user just opened gets up to half the remainder
        // (capped at its reserve); the grid keeps the rest up to its natural rows,
        // and any slack the grid does not use goes back to the details block.
        val detailsFirst = minOf(DETAILS_RESERVE, contentAvail * 0.5f)
        maxGridHeight = minOf(gridContentHeight, contentAvail - detailsFirst)
        maxDetailsHeight = minOf(DETAILS_RESERVE, contentAvail - maxGridHeight)
    }

    return BadgeCelebrationLayoutBudget(
        cardWidth = cardWidth,
        maxContentHeight = maxContentHeight,
        gridContentHeight = gridContentHeight,
        maxGridHeight = maxGridHeight,
        maxDetailsHeight = maxDetailsHeight,
        chromeUpperBound = chromeUpperBound,
        trophySize = trophySize,
        spacerSmall = spacerSmall,
        spacerLarge = spacerLarge,
        headerMaxLines = headerMaxLines,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Issue #1403 merge-audit R2-F1: observation-only measurement probe.
//
// The audit rejected test evidence that printed assumed constraints or measured
// content under a test-owned unbounded shim instead of the production Dialog.
// This seam lets an iOS fixture record the constraints the REAL dialog layer
// imposes (incoming to the content root and to the grid) and the rendered bounds
// actually laid out inside the real Dialog subtree — measured, never assumed.
// Production passes no probe (the CompositionLocal defaults to null and the
// modifier chain is untouched), so runtime behavior is unchanged.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Observation-only probe receiving measure-time constraints and post-layout
 * rendered bounds at named points of the production dialog content.
 */
internal interface BadgeDialogMeasurementProbe {
    /** Observed measure-time constraints at [point] ("card", "grid", "dismissButton"). */
    fun onConstraints(point: String, constraints: Constraints)

    /** Observed rendered bounds in window pixels at [point] after layout. */
    fun onBounds(point: String, left: Float, top: Float, width: Float, height: Float)
}

internal val LocalBadgeDialogMeasurementProbe: ProvidableCompositionLocal<BadgeDialogMeasurementProbe?> =
    compositionLocalOf { null }

/**
 * Attaches the probe at one observation point. With no probe provided this is
 * the identity modifier. The `layout` pass-through records the constraints the
 * parent actually imposes; `onGloballyPositioned` records what was rendered.
 */
@Composable
private fun Modifier.badgeDialogProbePoint(point: String): Modifier {
    val probe = LocalBadgeDialogMeasurementProbe.current ?: return this
    return this
        .layout { measurable, constraints ->
            probe.onConstraints(point, constraints)
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            probe.onBounds(point, bounds.left, bounds.top, bounds.width, bounds.height)
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
        fontScale = LocalDensity.current.fontScale,
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
            // R2-F1 observation point "card": constraints incoming from the real
            // dialog layer + rendered Card bounds (identity without a probe).
            .badgeDialogProbePoint("card")
            // Issue #1403: finite responsive width enforced before the grid is
            // measured (≈90% usable width, capped at 580dp). Modifier.width coerces
            // into tighter parent constraints; never requiredWidth.
            .width(budget.cardWidth)
            .testTag("badge-celebration-card")
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
                // Merge audit F1: hard total bound on the actual Column. Chrome +
                // grid + details + dismiss button can never exceed the visible
                // budget; the budget split has no floors that could push past it.
                .heightIn(max = budget.maxContentHeight)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

            // Lottie trophy animation in background (decorative size shrinks in
            // extremely short windows per the layout budget)
            Box(
                modifier = Modifier.size(budget.trophySize),
                contentAlignment = Alignment.Center,
            ) {
                if (reduceMotion) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = stringResource(Res.string.cd_trophy),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(budget.trophySize * 0.8f),
                    )
                } else {
                    LottieAnimation(
                        animationJson = CelebrationAnimations.trophy,
                        size = budget.trophySize,
                        contentDescription = stringResource(Res.string.cd_trophy),
                    )
                }
            }

            Spacer(modifier = Modifier.height(budget.spacerSmall))

            // Header with badge count
            Text(
                text = if (badges.size ==
                    1
                ) {
                    stringResource(Res.string.badge_dialog_single_title)
                } else {
                    stringResource(Res.string.badge_dialog_multi_title, badges.size)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                // Hard line cap: chrome's header allowance is bounded at any font
                // scale or wrap, so the dismiss button can never be pushed out.
                maxLines = budget.headerMaxLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(budget.spacerLarge))

            // Badge grid (3 columns). Height is capped by the short-window budget so
            // the dismiss button stays reachable; the cap is a no-op when the window
            // has room for the natural row height.
            val gridHeight = budget.maxGridHeight

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    // R2-F1 observation point "grid": constraints the Card/Column
                    // imposes on the grid + rendered grid bounds.
                    .badgeDialogProbePoint("grid")
                    .fillMaxWidth()
                    .heightIn(max = gridHeight)
                    .testTag("badge-celebration-grid"),
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

                        // Category pill (non-clickable)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = tierColor.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = getCategoryIcon(badge.category),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = badge.category.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(budget.spacerLarge))

            // Dismiss button
            Button(
                onClick = {
                    onMarkAllCelebrated(badges.map { it.id })
                    onDismiss()
                },
                modifier = Modifier
                    // R2-F1 observation point "dismissButton": rendered bounds.
                    .badgeDialogProbePoint("dismissButton")
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
                    text = stringResource(Res.string.badge_dialog_dismiss_awesome),
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

    // dialogs-overlays-10: HighBouncy + StiffnessLow for the badge grid pop-in (properly festive).
    // reduceMotion: OR with reduceMotion so target is immediately 1f (channel settles immediately).
    val reduceMotionItem = LocalPlatformAccessibilitySettings.current.reduceMotion

    // Stagger fade-in animation
    var isVisible by remember(reduceMotionItem) { mutableStateOf(reduceMotionItem) }
    LaunchedEffect(reduceMotionItem) {
        if (!reduceMotionItem) {
            delay(animationDelay.toLong())
        }
        isVisible = true
    }

    val itemAlpha by animateFloatAsState(
        targetValue = if (isVisible || reduceMotionItem) 1f else 0f,
        animationSpec = if (reduceMotionItem) snap() else tween(durationMillis = 300),
        label = "itemAlpha",
    )

    val itemScale by animateFloatAsState(
        targetValue = if (isVisible || reduceMotionItem) 1f else 0.5f,
        animationSpec = if (reduceMotionItem) snap() else ExpressiveMotion.SpringBouncy,
        label = "itemScale",
    )

    // Pulse animation only for selected badge when reduced motion is off
    val pulseScale = if (isSelected && !reduceMotionItem) {
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

    val badgeContentDescription = stringResource(
        Res.string.badge_dialog_item_cd,
        badge.name,
        badge.tier.displayName,
        badge.category.displayName,
    )
    Column(
        modifier = Modifier
            .alpha(itemAlpha)
            .scale(displayScale)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(4.dp)
            .semantics {
                contentDescription = badgeContentDescription
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
