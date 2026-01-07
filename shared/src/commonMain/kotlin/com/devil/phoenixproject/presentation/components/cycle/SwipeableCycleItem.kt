package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.ui.theme.SignalError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableCycleItem(
    item: CycleItem,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onTap: () -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false // Don't actually dismiss, we handle removal ourselves
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onDuplicate()
                    false // Don't dismiss
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.4f }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> SignalError
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiary
                    else -> Color.Transparent
                },
                label = "swipe_color"
            )
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.ContentCopy
                else -> null
            }
            val label = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> "DELETE"
                SwipeToDismissBoxValue.StartToEnd -> "COPY"
                else -> ""
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (icon != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (direction == SwipeToDismissBoxValue.StartToEnd) {
                            Icon(icon, contentDescription = label, tint = Color.White)
                            Text(label, color = Color.White)
                        } else {
                            Text(label, color = Color.White)
                            Icon(icon, contentDescription = label, tint = Color.White)
                        }
                    }
                }
            }
        },
        content = {
            when (item) {
                is CycleItem.Workout -> WorkoutDayRow(
                    workout = item,
                    onTap = onTap,
                    dragModifier = dragModifier
                )
                is CycleItem.Rest -> RestDayRow(
                    rest = item,
                    dragModifier = dragModifier
                )
            }
        }
    )
}
