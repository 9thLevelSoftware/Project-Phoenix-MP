package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.SupersetTheme

/**
 * Exercise item displayed inside a superset with tree connector visuals.
 *
 * Shows:
 * - Indentation (16.dp start padding) for visual nesting
 * - Tree connector lines showing hierarchy
 * - Colored border matching the superset's color theme
 * - Drag handle slot for reordering
 * - Exercise name and set/rep/weight details
 * - Menu button for edit/delete actions
 *
 * @param exercise The routine exercise data to display
 * @param colorIndex The superset's color index for visual theming
 * @param isFirst Whether this is the first exercise in the superset
 * @param isLast Whether this is the last exercise in the superset
 * @param isDragging Whether this item is currently being dragged
 * @param weightUnit The user's preferred weight unit for display
 * @param kgToDisplay Conversion function from kg to the display unit
 * @param onMenuClick Callback when the menu button is clicked
 * @param onDragHandle Composable slot for the drag handle
 * @param onClick Callback when the exercise item is clicked
 * @param modifier Optional modifier for the component
 */
@Composable
fun SupersetExerciseItem(
    exercise: RoutineExercise,
    colorIndex: Int,
    isFirst: Boolean,
    isLast: Boolean,
    isDragging: Boolean,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onMenuClick: () -> Unit,
    onDragHandle: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp), // Indent for nesting
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tree connector
        Column(
            modifier = Modifier.width(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Vertical line (top portion)
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(if (isFirst) 0.dp else 20.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )

            // Horizontal connector
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            }

            // Vertical line (bottom portion) - only if not last
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            }
        }

        // Exercise card
        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = if (isDragging) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = BorderStroke(
                width = 2.dp,
                color = color.copy(alpha = 0.3f)
            ),
            tonalElevation = if (isDragging) 8.dp else 1.dp
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                onDragHandle()

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exercise.exercise.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                    val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                    Text(
                        text = "${exercise.sets} sets x ${exercise.reps} reps @ ${weight.toInt()} $unitLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
            }
        }
    }
}
