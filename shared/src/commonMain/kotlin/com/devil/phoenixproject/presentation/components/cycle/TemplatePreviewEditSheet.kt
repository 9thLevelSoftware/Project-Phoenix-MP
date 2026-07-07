package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.CycleDayTemplate
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.presentation.components.MiniExercisePickerDialog
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Editable preview of a cycle template shown between template selection and the
 * 1RM input step. Lets the user customize the prefilled structure — add, remove,
 * and reorder exercises, and adjust sets/reps — before the cycle is created.
 *
 * Percentage-based 5/3/1 main lifts are structural (sets/reps come from the active
 * week's percentage scheme) and can be removed but not rep-edited here.
 */
@Composable
fun TemplatePreviewEditSheet(
    template: CycleTemplate,
    exerciseRepository: ExerciseRepository,
    onContinue: (CycleTemplate) -> Unit,
    onCancel: () -> Unit,
) {
    var editedDays by remember(template) { mutableStateOf(template.days) }
    // Day number currently choosing an exercise to add, or null
    var pickingForDay by remember { mutableStateOf<Int?>(null) }

    fun updateDay(dayNumber: Int, transform: (List<TemplateExercise>) -> List<TemplateExercise>) {
        editedDays = editedDays.map { day ->
            if (day.dayNumber == dayNumber && day.routine != null) {
                day.copy(routine = day.routine.copy(exercises = transform(day.routine.exercises)))
            } else {
                day
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = 140.dp, // space for bottom bar
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Customize the exercises below, or continue with the defaults. " +
                        "Weights are set automatically from your 1RM and PR data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.small))
            }

            items(count = editedDays.size, key = { editedDays[it].dayNumber }) { index ->
                val day = editedDays[index]
                if (day.isRestDay) {
                    RestDayRow(day)
                } else {
                    TrainingDayCard(
                        day = day,
                        onMoveExercise = { exerciseIndex, delta ->
                            updateDay(day.dayNumber) { exercises ->
                                val target = exerciseIndex + delta
                                if (target !in exercises.indices) return@updateDay exercises
                                exercises.toMutableList().apply {
                                    val item = removeAt(exerciseIndex)
                                    add(target, item)
                                }
                            }
                        },
                        onRemoveExercise = { exerciseIndex ->
                            updateDay(day.dayNumber) { exercises ->
                                exercises.filterIndexed { i, _ -> i != exerciseIndex }
                            }
                        },
                        onAdjustSets = { exerciseIndex, delta ->
                            updateDay(day.dayNumber) { exercises ->
                                exercises.mapIndexed { i, ex ->
                                    if (i == exerciseIndex) {
                                        ex.copy(sets = (ex.sets + delta).coerceIn(1, 10))
                                    } else {
                                        ex
                                    }
                                }
                            }
                        },
                        onAdjustReps = { exerciseIndex, delta ->
                            updateDay(day.dayNumber) { exercises ->
                                exercises.mapIndexed { i, ex ->
                                    if (i == exerciseIndex && ex.reps != null) {
                                        ex.copy(reps = (ex.reps + delta).coerceIn(1, 50))
                                    } else {
                                        ex
                                    }
                                }
                            }
                        },
                        onAddExercise = { pickingForDay = day.dayNumber },
                    )
                }
            }
        }

        // Bottom action bar
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("Back")
                }
                Button(
                    onClick = { onContinue(template.copy(days = editedDays)) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("Continue")
                }
            }
        }
    }

    // Exercise picker for adding to a day
    pickingForDay?.let { dayNumber ->
        MiniExercisePickerDialog(
            exerciseRepository = exerciseRepository,
            onDismiss = { pickingForDay = null },
            onExerciseSelected = { exercise ->
                updateDay(dayNumber) { exercises ->
                    exercises + TemplateExercise(
                        exerciseName = exercise.name,
                        sets = 3,
                        reps = 10,
                        suggestedMode = ProgramMode.OldSchool,
                        exerciseId = exercise.id,
                    )
                }
                pickingForDay = null
            },
        )
    }
}

@Composable
private fun RestDayRow(day: CycleDayTemplate) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.SelfImprovement,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Day ${day.dayNumber} — Rest",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrainingDayCard(
    day: CycleDayTemplate,
    onMoveExercise: (exerciseIndex: Int, delta: Int) -> Unit,
    onRemoveExercise: (exerciseIndex: Int) -> Unit,
    onAdjustSets: (exerciseIndex: Int, delta: Int) -> Unit,
    onAdjustReps: (exerciseIndex: Int, delta: Int) -> Unit,
    onAddExercise: () -> Unit,
) {
    val exercises = day.routine?.exercises ?: emptyList()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                "Day ${day.dayNumber} — ${day.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.small))

            exercises.forEachIndexed { index, exercise ->
                ExerciseEditRow(
                    exercise = exercise,
                    canMoveUp = index > 0,
                    canMoveDown = index < exercises.lastIndex,
                    onMoveUp = { onMoveExercise(index, -1) },
                    onMoveDown = { onMoveExercise(index, +1) },
                    onRemove = { onRemoveExercise(index) },
                    onAdjustSets = { delta -> onAdjustSets(index, delta) },
                    onAdjustReps = { delta -> onAdjustReps(index, delta) },
                )
                if (index < exercises.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }

            if (exercises.isEmpty()) {
                Text(
                    "No exercises",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onAddExercise) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add exercise")
            }
        }
    }
}

@Composable
private fun ExerciseEditRow(
    exercise: TemplateExercise,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onAdjustSets: (Int) -> Unit,
    onAdjustReps: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        exercise.exerciseName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (exercise.isPercentageBased) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                "5/3/1",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                Text(
                    if (exercise.isPercentageBased) {
                        "Sets from weekly % scheme"
                    } else {
                        "${exercise.sets} sets × ${exercise.reps?.toString() ?: "timed"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove exercise",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Sets/reps steppers — not applicable to percentage-based lifts
        if (!exercise.isPercentageBased) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                StepperControl(
                    label = "Sets",
                    value = exercise.sets.toString(),
                    onDecrement = { onAdjustSets(-1) },
                    onIncrement = { onAdjustSets(+1) },
                )
                if (exercise.reps != null) {
                    StepperControl(
                        label = "Reps",
                        value = exercise.reps.toString(),
                        onDecrement = { onAdjustReps(-1) },
                        onIncrement = { onAdjustReps(+1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperControl(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onDecrement, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "$label -1", modifier = Modifier.size(16.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onIncrement, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, contentDescription = "$label +1", modifier = Modifier.size(16.dp))
        }
    }
}
