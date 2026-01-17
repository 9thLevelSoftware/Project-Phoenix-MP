package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit

/**
 * Simplified exercise row for display inside a superset container.
 * Shows exercise info with inherited rest time indicator.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseRowInSuperset(
    exercise: RoutineExercise,
    supersetRestSeconds: Int,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSelectionToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (isSelectionMode) onSelectionToggle()
                    else onClick()
                },
                onLongClick = {
                    if (!isSelectionMode) onLongPress()
                    else onSelectionToggle()
                }
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectionToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    exercise.exercise.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                // Display format depends on whether this is a timed exercise
                // Check if bodyweight (equipment empty or "bodyweight") vs timed cable exercise
                val isBodyweight = exercise.exercise.equipment.isEmpty() ||
                    exercise.exercise.equipment.equals("bodyweight", ignoreCase = true)

                val exerciseText = if (exercise.duration != null && isBodyweight) {
                    // Bodyweight timed exercise (no cable weight needed)
                    "${exercise.sets} sets x ${exercise.duration}s"
                } else if (exercise.duration != null) {
                    // Timed cable exercise - show duration AND weight/progression
                    val isEchoMode = exercise.programMode == ProgramMode.Echo
                    val weightText = if (isEchoMode) {
                        "Adaptive"
                    } else {
                        val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                        val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                        "${weight.toInt()} $unitLabel"
                    }
                    val progressionText = when {
                        exercise.progressionKg > 0 -> {
                            val progWeight = kgToDisplay(exercise.progressionKg, weightUnit)
                            val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                            " (+${progWeight}$unitLabel)"
                        }
                        exercise.progressionKg < 0 -> {
                            val regWeight = kgToDisplay(-exercise.progressionKg, weightUnit)
                            val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                            " (-${regWeight}$unitLabel)"
                        }
                        else -> ""
                    }
                    "${exercise.sets} sets x ${exercise.duration}s @ $weightText$progressionText"
                } else {
                    // Rep-based exercise with weight
                    val isEchoMode = exercise.programMode == ProgramMode.Echo
                    val weightText = if (isEchoMode) {
                        "Adaptive"
                    } else {
                        val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                        val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                        "${weight.toInt()} $unitLabel"
                    }
                    // Handle AMRAP vs fixed reps display
                    val repsText = if (exercise.isAMRAP) "AMRAP" else "${exercise.reps} reps"
                    // Build progression/regression suffix if configured
                    val progressionText = when {
                        exercise.progressionKg > 0 -> {
                            val progWeight = kgToDisplay(exercise.progressionKg, weightUnit)
                            val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                            " (+${progWeight}$unitLabel/rep)"
                        }
                        exercise.progressionKg < 0 -> {
                            val regWeight = kgToDisplay(-exercise.progressionKg, weightUnit)
                            val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lb"
                            " (-${regWeight}$unitLabel/rep)"
                        }
                        else -> ""
                    }
                    "${exercise.sets} sets x $repsText @ $weightText$progressionText"
                }

                Text(
                    exerciseText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Inherited rest time indicator
                Text(
                    "${supersetRestSeconds}s rest (superset)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
