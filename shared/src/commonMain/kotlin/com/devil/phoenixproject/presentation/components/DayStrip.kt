package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Visual state for a day chip in the day strip.
 */
enum class DayState {
    /** Day was completed - Green with checkmark */
    COMPLETED,

    /** Day was missed - Red with X */
    MISSED,

    /** Current day in cycle - Blue/primary filled */
    CURRENT,

    /** Future day - Gray outline */
    UPCOMING,
}

/**
 * Horizontal scrollable day strip showing cycle day progression.
 *
 * Visual representation:
 * ```
 * [checkmark 1] [checkmark 2] [filled 3] [X 4] [5] [zzz] [7]
 *    green         green        blue      red   gray gray  gray
 * ```
 *
 * @param days List of cycle days to display
 * @param progress Current cycle progress tracking completed/missed days
 * @param currentSelection Currently selected day number (for highlighting)
 * @param onDaySelected Callback when a day chip is tapped
 * @param modifier Modifier for the LazyRow container
 */
@Composable
fun DayStrip(
    days: List<CycleDay>,
    progress: CycleProgress,
    currentSelection: Int,
    onDaySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to current day on initial display only (Unit key = one-shot; gap-1-3)
    LaunchedEffect(Unit) {
        val currentIndex = days.indexOfFirst { it.dayNumber == progress.currentDayNumber }
        if (currentIndex >= 0) {
            // Scroll to make the current day visible, centered if possible
            listState.animateScrollToItem(
                index = maxOf(0, currentIndex - 2),
                scrollOffset = 0,
            )
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        contentPadding = PaddingValues(horizontal = Spacing.medium),
    ) {
        items(
            items = days,
            key = { it.id },
        ) { day ->
            val state = determineDayState(
                dayNumber = day.dayNumber,
                currentDayNumber = progress.currentDayNumber,
                completedDays = progress.completedDays,
                missedDays = progress.missedDays,
            )

            DayChip(
                dayNumber = day.dayNumber,
                isRestDay = day.isRestDay,
                state = state,
                isSelected = day.dayNumber == currentSelection,
                onClick = { onDaySelected(day.dayNumber) },
            )
        }
    }
}

/**
 * Individual day chip showing day number with visual state.
 *
 * @param dayNumber The day number to display
 * @param isRestDay Whether this is a rest day (shows sleep emoji)
 * @param state Visual state determining colors and icons
 * @param isSelected Whether this chip is currently selected (thicker border)
 * @param onClick Callback when chip is tapped
 */
@Composable
fun DayChip(dayNumber: Int, isRestDay: Boolean, state: DayState, isSelected: Boolean, onClick: () -> Unit) {
    val chipSize = 48.dp

    // gap-1-17: CURRENT chip entrance spring — starts at 0f scale, overshoots to ~1.15f, settles
    // at 1.0f via SpringBouncy. Non-CURRENT chips and reduceMotion stay at scale 1.0 throughout.
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    // rememberSaveable so the entrance latch survives LazyRow item recycling —
    // remember(state) alone replays the spring every time a long cycle's CURRENT
    // chip scrolls back into view (5A.5 review finding).
    var scaledIn by rememberSaveable(state.name) { mutableStateOf(state != DayState.CURRENT || reduceMotion) }
    val chipScale by animateFloatAsState(
        targetValue = if (scaledIn) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringBouncy,
        label = "dayChipScale",
    )
    LaunchedEffect(state) {
        if (state == DayState.CURRENT && !reduceMotion) {
            scaledIn = true
        }
    }

    // Determine colors based on state
    val containerColor = when (state) {
        DayState.COMPLETED -> AccessibilityTheme.colors.success
        DayState.MISSED -> MaterialTheme.colorScheme.errorContainer
        DayState.CURRENT -> MaterialTheme.colorScheme.primary
        DayState.UPCOMING -> Color.Transparent
    }

    val contentColor = when (state) {
        DayState.COMPLETED -> AccessibilityTheme.colors.onSuccess
        DayState.MISSED -> MaterialTheme.colorScheme.onErrorContainer
        DayState.CURRENT -> MaterialTheme.colorScheme.onPrimary
        DayState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Border styling
    val borderStroke = when {
        isSelected && state != DayState.CURRENT -> BorderStroke(
            width = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )

        state == DayState.UPCOMING -> BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        else -> null
    }

    // Accessibility: merged description so TalkBack announces the chip as a single unit
    val chipDesc = when {
        isRestDay -> stringResource(Res.string.cd_day_chip_rest, dayNumber)
        state == DayState.COMPLETED -> stringResource(Res.string.cd_day_chip_completed, dayNumber)
        state == DayState.MISSED -> stringResource(Res.string.cd_day_chip_missed, dayNumber)
        state == DayState.CURRENT -> stringResource(Res.string.cd_day_chip_current, dayNumber)
        else -> stringResource(Res.string.cd_day_chip_upcoming, dayNumber)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.size(chipSize).scale(chipScale).semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = chipDesc
        },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = borderStroke,
        // gap-1-17: stronger elevation separates the active chip from the row
        shadowElevation = if (state == DayState.CURRENT) 8.dp else 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Rest days show sleep emoji
                isRestDay -> {
                    Text(
                        text = "\uD83D\uDCA4", // ZZZ/Sleep emoji
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Completed days show checkmark with number
                state == DayState.COMPLETED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(Res.string.cd_completed),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = dayNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Missed days show X with number
                state == DayState.MISSED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_missed),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = dayNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Current day: no inner dot (gap-1-8 — redundant, off-grid, crowds number);
                // labelLarge bold so the active chip commands the most visual weight in the strip (gap-1-17).
                state == DayState.CURRENT -> {
                    Text(
                        text = dayNumber.toString(),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                    )
                }

                // Upcoming days show just the number
                else -> {
                    Text(
                        text = dayNumber.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Determines the visual state of a day based on cycle progress.
 */
private fun determineDayState(dayNumber: Int, currentDayNumber: Int, completedDays: Set<Int>, missedDays: Set<Int>): DayState = when {
    dayNumber in completedDays -> DayState.COMPLETED
    dayNumber in missedDays -> DayState.MISSED
    dayNumber == currentDayNumber -> DayState.CURRENT
    else -> DayState.UPCOMING
}
