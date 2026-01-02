package com.devil.phoenixproject.presentation.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.ui.theme.Spacing

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
    UPCOMING
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
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to current day on initial display
    LaunchedEffect(progress.currentDayNumber) {
        val currentIndex = days.indexOfFirst { it.dayNumber == progress.currentDayNumber }
        if (currentIndex >= 0) {
            // Scroll to make the current day visible, centered if possible
            listState.animateScrollToItem(
                index = maxOf(0, currentIndex - 2),
                scrollOffset = 0
            )
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        contentPadding = PaddingValues(horizontal = Spacing.medium)
    ) {
        items(
            items = days,
            key = { it.id }
        ) { day ->
            val state = determineDayState(
                dayNumber = day.dayNumber,
                currentDayNumber = progress.currentDayNumber,
                completedDays = progress.completedDays,
                missedDays = progress.missedDays
            )

            DayChip(
                dayNumber = day.dayNumber,
                isRestDay = day.isRestDay,
                state = state,
                isSelected = day.dayNumber == currentSelection,
                onClick = { onDaySelected(day.dayNumber) }
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
fun DayChip(
    dayNumber: Int,
    isRestDay: Boolean,
    state: DayState,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val chipSize = 48.dp

    // Determine colors based on state
    val containerColor = when (state) {
        DayState.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
        DayState.MISSED -> MaterialTheme.colorScheme.errorContainer
        DayState.CURRENT -> MaterialTheme.colorScheme.primary
        DayState.UPCOMING -> Color.Transparent
    }

    val contentColor = when (state) {
        DayState.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
        DayState.MISSED -> MaterialTheme.colorScheme.onErrorContainer
        DayState.CURRENT -> MaterialTheme.colorScheme.onPrimary
        DayState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Border styling
    val borderStroke = when {
        isSelected && state != DayState.CURRENT -> BorderStroke(
            width = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
        state == DayState.UPCOMING -> BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        else -> null
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.size(chipSize),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = borderStroke,
        shadowElevation = if (state == DayState.CURRENT) 4.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Rest days show sleep emoji
                isRestDay -> {
                    Text(
                        text = "\uD83D\uDCA4", // ZZZ/Sleep emoji
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Completed days show checkmark with number
                state == DayState.COMPLETED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = dayNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // Missed days show X with number
                state == DayState.MISSED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Missed",
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = dayNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // Current day shows filled circle with number
                state == DayState.CURRENT -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Filled circle indicator
                        Surface(
                            modifier = Modifier.size(6.dp),
                            shape = CircleShape,
                            color = contentColor
                        ) {}
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dayNumber.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                // Upcoming days show just the number
                else -> {
                    Text(
                        text = dayNumber.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Determines the visual state of a day based on cycle progress.
 */
private fun determineDayState(
    dayNumber: Int,
    currentDayNumber: Int,
    completedDays: Set<Int>,
    missedDays: Set<Int>
): DayState {
    return when {
        dayNumber in completedDays -> DayState.COMPLETED
        dayNumber in missedDays -> DayState.MISSED
        dayNumber == currentDayNumber -> DayState.CURRENT
        else -> DayState.UPCOMING
    }
}
