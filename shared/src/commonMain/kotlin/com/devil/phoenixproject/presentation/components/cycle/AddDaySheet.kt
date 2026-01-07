package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Routine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDaySheet(
    routines: List<Routine>,
    recentRoutineIds: List<String>,
    onSelectRoutine: (Routine) -> Unit,
    onAddRestDay: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    val recentRoutines = recentRoutineIds.mapNotNull { id -> routines.find { it.id == id } }
    val otherRoutines = routines.filterNot { it.id in recentRoutineIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Add to Cycle",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quick action: Rest day button
            FilledTonalButton(
                onClick = {
                    onAddRestDay()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.NightsStay,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add Rest Day",
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // Section header for workouts
            Text(
                text = "Select a Workout",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Routine list
            BoxWithConstraints {
                val maxSheetHeight = (maxHeight * 0.8f).coerceIn(300.dp, 600.dp)

                LazyColumn(
                    modifier = Modifier.heightIn(max = maxSheetHeight),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                // Recent routines section
                if (recentRoutines.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recent Routines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(recentRoutines) { routine ->
                        RoutineListItem(
                            routine = routine,
                            onClick = {
                                onSelectRoutine(routine)
                                onDismiss()
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                // All routines section
                if (otherRoutines.isNotEmpty()) {
                    item {
                        Text(
                            text = if (recentRoutines.isNotEmpty()) "All Routines" else "Routines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(otherRoutines) { routine ->
                        RoutineListItem(
                            routine = routine,
                            onClick = {
                                onSelectRoutine(routine)
                                onDismiss()
                            }
                        )
                    }
                }

                // Empty state
                if (routines.isEmpty()) {
                    item {
                        Text(
                            text = "No routines created yet.\nCreate a routine first to add workout days.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun RoutineListItem(
    routine: Routine,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
