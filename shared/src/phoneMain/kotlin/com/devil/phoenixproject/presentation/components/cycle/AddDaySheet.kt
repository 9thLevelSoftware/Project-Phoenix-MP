package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.presentation.components.PhoenixSheetHeader
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDaySheet(
    routines: List<Routine>,
    recentRoutineIds: List<String>,
    onSelectRoutine: (Routine) -> Unit,
    onAddRestDay: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    // When editing an existing workout day: opens the routine editor for its exercises.
    onEditExercises: (() -> Unit)? = null,
) {
    val recentRoutines = recentRoutineIds.mapNotNull { id -> routines.find { it.id == id } }
    val otherRoutines = routines.filterNot { it.id in recentRoutineIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            PhoenixSheetHeader(
                title = stringResource(Res.string.add_day_title),
                onClose = onDismiss,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Edit the current day's routine exercises (only when editing an existing workout day)
            if (onEditExercises != null) {
                FilledTonalButton(
                    onClick = {
                        onEditExercises()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.add_day_edit_exercises),
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Quick action: Rest day button
            FilledTonalButton(
                onClick = {
                    onAddRestDay()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.NightsStay,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.add_day_rest_day),
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // Section header for workouts
            Text(
                text = stringResource(Res.string.add_day_select_workout),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Routine list
            BoxWithConstraints {
                val maxSheetHeight = (maxHeight * 0.8f).coerceAtMost(600.dp).coerceAtLeast(160.dp)

                LazyColumn(
                    modifier = Modifier.heightIn(max = maxSheetHeight),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Recent routines section
                    if (recentRoutines.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.add_day_recent_routines),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(recentRoutines) { routine ->
                            RoutineListItem(
                                routine = routine,
                                onClick = {
                                    onSelectRoutine(routine)
                                    onDismiss()
                                },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // All routines section
                    if (otherRoutines.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(
                                    if (recentRoutines.isNotEmpty()) {
                                        Res.string.add_day_all_routines
                                    } else {
                                        Res.string.home_action_routines
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(otherRoutines) { routine ->
                            RoutineListItem(
                                routine = routine,
                                onClick = {
                                    onSelectRoutine(routine)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    // Empty state
                    if (routines.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.add_day_empty_routines),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineListItem(routine: Routine, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = routine.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (routine.exercises.isNotEmpty()) {
                val countText = if (routine.exercises.size == 1) {
                    stringResource(Res.string.exercise_count_one)
                } else {
                    stringResource(Res.string.exercise_count_other, routine.exercises.size)
                }
                Text(
                    text = stringResource(
                        Res.string.add_day_routine_exercises,
                        countText,
                        routine.exercises.joinToString(", ") { it.exercise.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
