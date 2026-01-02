package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.ui.theme.SupersetTheme

/**
 * Header component for displaying a superset in the routine editor.
 *
 * Shows:
 * - Colored left bar indicating the superset's color theme
 * - Drag handle (passed as composable slot for flexibility)
 * - Superset name and metadata (exercise count, rest time)
 * - Menu button for rename/edit/delete actions
 * - Expand/collapse toggle
 *
 * @param superset The superset data to display
 * @param isExpanded Whether the superset's exercises are currently visible
 * @param isDragging Whether this header is currently being dragged
 * @param onToggleExpand Callback when expand/collapse is toggled
 * @param onMenuClick Callback when the menu button is clicked
 * @param onDragHandle Composable slot for the drag handle
 * @param modifier Optional modifier for the component
 */
@Composable
fun SupersetHeader(
    superset: Superset,
    isExpanded: Boolean,
    isDragging: Boolean,
    onToggleExpand: () -> Unit,
    onMenuClick: () -> Unit,
    onDragHandle: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(superset.colorIndex)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = if (isDragging) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            SupersetTheme.backgroundTint(superset.colorIndex, false)
        },
        tonalElevation = if (isDragging) 8.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored left bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )

            Spacer(Modifier.width(8.dp))

            // Drag handle
            onDragHandle()

            Spacer(Modifier.width(8.dp))

            // Name and exercise count
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = superset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(
                    text = "${superset.exerciseCount} exercises \u2022 ${superset.restBetweenSeconds}s rest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Menu button
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }

            // Expand/collapse
            IconButton(onClick = onToggleExpand) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                )
            }
        }
    }
}
