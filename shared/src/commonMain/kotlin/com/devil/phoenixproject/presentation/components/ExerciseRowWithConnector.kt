package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * Unified exercise row that handles both standalone and superset exercises.
 * When in a superset, shows connector line on the left.
 *
 * This component provides:
 * - Optional superset connector on the left (when part of a superset)
 * - Drag handle for reordering
 * - Exercise card with name and set/rep/weight info
 * - Menu button for additional actions
 *
 * @param exercise The routine exercise to display
 * @param elevation Shadow elevation for drag feedback
 * @param weightUnit User's preferred weight unit (KG or LB)
 * @param kgToDisplay Function to convert kg to display unit
 * @param supersetColorIndex Color index for superset connector (null if standalone)
 * @param connectorPosition Position in superset (null if standalone)
 * @param onClick Called when the row is tapped
 * @param onMenuClick Called when the menu button is tapped
 * @param dragModifier Modifier for the drag handle (for drag-and-drop)
 * @param modifier Optional modifier for the component
 */
@Composable
fun ExerciseRowWithConnector(
    exercise: RoutineExercise,
    elevation: Dp,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    // Superset connector info
    supersetColorIndex: Int?,
    connectorPosition: ConnectorPosition?,
    // Callbacks
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .shadow(elevation, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connector column (if in superset)
        if (supersetColorIndex != null && connectorPosition != null) {
            SupersetConnector(
                colorIndex = supersetColorIndex,
                position = connectorPosition,
                modifier = Modifier
            )
        }

        // Drag handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag",
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = dragModifier
            )
        }

        // Card content
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        exercise.exercise.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val isEchoMode = exercise.programMode == ProgramMode.Echo
                    val weightText = if (isEchoMode) {
                        "Adaptive"
                    } else {
                        val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                        val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                        "${weight.toInt()} $unitLabel"
                    }
                    Text(
                        "${exercise.sets} sets x ${exercise.reps} reps @ $weightText",
                        style = MaterialTheme.typography.bodyMedium,
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
