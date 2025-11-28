package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.ProgramDayEntity
import com.devil.phoenixproject.data.local.WeeklyProgramEntity
import com.devil.phoenixproject.data.local.WeeklyProgramWithDays
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.KmpUtils

/**
 * Day of week data for the builder
 */
private data class DaySchedule(
    val dayOfWeek: Int,
    val name: String,
    val routineId: String? = null
)

/**
 * Program Builder screen - create/edit weekly programs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramBuilderScreen(
    navController: NavController,
    viewModel: MainViewModel,
    programId: String,
    exerciseRepository: ExerciseRepository,
    themeMode: ThemeMode
) {
    val isNewProgram = programId == "new"

    // State
    var programName by remember { mutableStateOf("") }
    var programDescription by remember { mutableStateOf("") }
    var daySchedules by remember {
        mutableStateOf(
            listOf(
                DaySchedule(1, "Monday"),
                DaySchedule(2, "Tuesday"),
                DaySchedule(3, "Wednesday"),
                DaySchedule(4, "Thursday"),
                DaySchedule(5, "Friday"),
                DaySchedule(6, "Saturday"),
                DaySchedule(7, "Sunday")
            )
        )
    }
    var showRoutinePicker by remember { mutableStateOf(false) }
    var selectedDayForPicker by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(!isNewProgram) }

    val routines by viewModel.routines.collectAsState()

    // Load existing program if editing
    LaunchedEffect(programId) {
        if (!isNewProgram) {
            viewModel.getProgramById(programId).collect { programWithDays ->
                if (programWithDays != null) {
                    programName = programWithDays.program.name
                    programDescription = programWithDays.program.description

                    // Update day schedules with existing routine assignments
                    daySchedules = daySchedules.map { day ->
                        val existingDay = programWithDays.days.find { it.dayOfWeek == day.dayOfWeek }
                        day.copy(routineId = existingDay?.routineId)
                    }
                    isLoading = false
                }
            }
        }
    }

    // Set title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle(if (isNewProgram) "New Program" else "Edit Program")
    }

    val backgroundGradient = if (themeMode == ThemeMode.DARK) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A), // slate-900
                Color(0xFF1E1B4B), // indigo-950
                Color(0xFF172554)  // blue-950
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE0E7FF), // indigo-200
                Color(0xFFFCE7F3), // pink-100
                Color(0xFFDDD6FE)  // violet-200
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Program Name Input
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Program Name",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                OutlinedTextField(
                                    value = programName,
                                    onValueChange = { programName = it },
                                    placeholder = { Text("Enter program name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    // Weekly Schedule Header
                    item {
                        Text(
                            text = "Weekly Schedule",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tap a day to assign a routine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Day Cards
                    items(daySchedules, key = { it.dayOfWeek }) { day ->
                        val routine = day.routineId?.let { id -> routines.find { it.id == id } }

                        DayRoutineCard(
                            day = day,
                            routine = routine,
                            onSelectRoutine = {
                                selectedDayForPicker = day.dayOfWeek
                                showRoutinePicker = true
                            },
                            onClearRoutine = {
                                daySchedules = daySchedules.map {
                                    if (it.dayOfWeek == day.dayOfWeek) it.copy(routineId = null) else it
                                }
                            }
                        )
                    }

                    // Program Summary
                    item {
                        val workoutDays = daySchedules.count { it.routineId != null }
                        val restDays = 7 - workoutDays

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$workoutDays",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "Workout Days",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$restDays",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "Rest Days",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom spacer
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }

                // Bottom Action Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                // Save program
                                val newProgramId = if (isNewProgram) generateUUID() else programId
                                val now = KmpUtils.currentTimeMillis()

                                val programEntity = WeeklyProgramEntity(
                                    id = newProgramId,
                                    name = programName.ifBlank { "Unnamed Program" },
                                    description = programDescription,
                                    isActive = false,
                                    createdAt = now,
                                    updatedAt = now
                                )

                                val days = daySchedules
                                    .filter { it.routineId != null }
                                    .map { day ->
                                        ProgramDayEntity(
                                            id = generateUUID(),
                                            programId = newProgramId,
                                            dayOfWeek = day.dayOfWeek,
                                            routineId = day.routineId,
                                            orderIndex = day.dayOfWeek
                                        )
                                    }

                                val programWithDays = WeeklyProgramWithDays(
                                    program = programEntity,
                                    days = days
                                )

                                viewModel.saveProgram(programWithDays)
                                Logger.d { "Saved program: ${programEntity.name} with ${days.size} days" }
                                navController.popBackStack()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            enabled = programName.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Save Program")
                        }
                    }
                }
            }
        }

        // Routine Picker Dialog
        if (showRoutinePicker && selectedDayForPicker != null) {
            RoutinePickerDialog(
                routines = routines,
                onSelectRoutine = { routine ->
                    daySchedules = daySchedules.map { day ->
                        if (day.dayOfWeek == selectedDayForPicker) {
                            day.copy(routineId = routine.id)
                        } else {
                            day
                        }
                    }
                    showRoutinePicker = false
                    selectedDayForPicker = null
                },
                onDismiss = {
                    showRoutinePicker = false
                    selectedDayForPicker = null
                }
            )
        }
    }
}

/**
 * Card for each day showing assigned routine
 */
@Composable
private fun DayRoutineCard(
    day: DaySchedule,
    routine: Routine?,
    onSelectRoutine: () -> Unit,
    onClearRoutine: () -> Unit
) {
    val hasRoutine = routine != null

    Card(
        onClick = onSelectRoutine,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasRoutine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ),
        border = if (hasRoutine) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Day Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .background(
                        if (hasRoutine) {
                            Brush.linearGradient(
                                listOf(Color(0xFF10B981), Color(0xFF059669))
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color(0xFF64748B), Color(0xFF475569))
                            )
                        },
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = day.name.take(3).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = day.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasRoutine) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = if (hasRoutine) {
                        "${routine!!.name} • ${routine.exercises.size} exercises"
                    } else {
                        "Rest day - tap to add routine"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasRoutine) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Action Button
            if (hasRoutine) {
                IconButton(
                    onClick = onClearRoutine,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove routine",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add routine",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Dialog for selecting a routine
 */
@Composable
private fun RoutinePickerDialog(
    routines: List<Routine>,
    onSelectRoutine: (Routine) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Routine",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (routines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FitnessCenter,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No routines available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Create routines in Daily Routines first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(routines, key = { it.id }) { routine ->
                        Card(
                            onClick = { onSelectRoutine(routine) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FitnessCenter,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = routine.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${routine.exercises.size} exercises",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
