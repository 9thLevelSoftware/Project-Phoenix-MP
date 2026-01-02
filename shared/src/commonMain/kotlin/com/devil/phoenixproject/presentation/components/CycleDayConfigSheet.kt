package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutType
import com.devil.phoenixproject.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Bottom sheet for configuring per-day modifiers in a training cycle.
 * Shows context-aware modifiers based on the routine's exercise types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleDayConfigSheet(
    day: CycleDay,
    routine: Routine?,
    onDismiss: () -> Unit,
    onApply: (CycleDay) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Determine which modifiers to show based on routine exercise types
    val hasEchoMode = routine?.exercises?.any { it.workoutType is WorkoutType.Echo } ?: false
    val hasOldSchool = routine?.exercises?.any { it.workoutType is WorkoutType.Program } ?: true
    val allSetsAmrap = routine?.exercises?.all { it.isAMRAP } ?: false

    // State for each modifier, initialized from day properties
    var echoLevel by remember { mutableStateOf(day.echoLevel ?: EchoLevel.HARDER) }
    var eccentricLoadPercent by remember { mutableStateOf(day.eccentricLoadPercent ?: 100) }
    var weightProgressionPercent by remember { mutableStateOf(day.weightProgressionPercent ?: 0f) }
    var repModifier by remember { mutableStateOf(day.repModifier ?: 0) }
    var restTimeSeconds by remember { mutableStateOf(day.restTimeOverrideSeconds ?: 60) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium)
                .padding(bottom = Spacing.medium)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Day ${day.dayNumber}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    routine?.name?.let { routineName ->
                        Text(
                            text = routineName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium)
            ) {
                // Echo Level - if hasEchoMode
                if (hasEchoMode) {
                    EchoLevelSection(
                        level = echoLevel,
                        onLevelChange = { echoLevel = it }
                    )

                    // Eccentric Load - if hasEchoMode
                    EccentricLoadSection(
                        loadPercent = eccentricLoadPercent,
                        onLoadChange = { eccentricLoadPercent = it }
                    )
                }

                // Weight Progression - if hasOldSchool
                if (hasOldSchool) {
                    WeightProgressionSection(
                        progressionPercent = weightProgressionPercent,
                        onProgressionChange = { weightProgressionPercent = it }
                    )
                }

                // Rep Modifier - if NOT allSetsAmrap
                if (!allSetsAmrap) {
                    RepModifierSection(
                        modifier = repModifier,
                        onModifierChange = { repModifier = it }
                    )
                }

                // Rest Time - always shown
                RestTimeSection(
                    restSeconds = restTimeSeconds,
                    onRestChange = { restTimeSeconds = it }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Apply Button
            Button(
                onClick = {
                    // Create updated CycleDay with non-default values (null out defaults to save space)
                    val updatedDay = day.copy(
                        echoLevel = if (hasEchoMode && echoLevel != EchoLevel.HARDER) echoLevel else null,
                        eccentricLoadPercent = if (hasEchoMode && eccentricLoadPercent != 100) eccentricLoadPercent else null,
                        weightProgressionPercent = if (hasOldSchool && weightProgressionPercent != 0f) weightProgressionPercent else null,
                        repModifier = if (!allSetsAmrap && repModifier != 0) repModifier else null,
                        restTimeOverrideSeconds = if (restTimeSeconds != 60) restTimeSeconds else null
                    )
                    onApply(updatedDay)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Text(
                    "Apply",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EchoLevelSection(
    level: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Text(
            text = "Echo Level",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            val levels = EchoLevel.entries
            levels.forEachIndexed { index, echoLevel ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                    onClick = { onLevelChange(echoLevel) },
                    selected = level == echoLevel
                ) {
                    Text(echoLevel.displayName, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EccentricLoadSection(
    loadPercent: Int,
    onLoadChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Text(
            text = "Eccentric Load: $loadPercent%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = loadPercent.toFloat(),
            onValueChange = { onLoadChange(it.roundToInt()) },
            valueRange = 0f..150f,
            steps = 14, // 0, 10, 20, ... 150 (15 steps = 14 intervals)
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Load percentage applied during eccentric (lowering) phase",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeightProgressionSection(
    progressionPercent: Float,
    onProgressionChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        val sign = if (progressionPercent >= 0) "+" else ""
        Text(
            text = "Weight Progression: $sign${progressionPercent.roundToInt()}%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = progressionPercent,
            onValueChange = { onProgressionChange(it) },
            valueRange = -20f..20f,
            steps = 39, // -20 to +20 in 1% increments (41 values = 40 intervals)
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Percentage adjustment applied to all weights for this day",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RepModifierSection(
    modifier: Int,
    onModifierChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Text(
            text = "Rep Modifier",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            val options = listOf(-2, -1, 0, 1, 2)
            options.forEach { value ->
                val label = when {
                    value > 0 -> "+$value"
                    else -> "$value"
                }
                FilterChip(
                    selected = modifier == value,
                    onClick = { onModifierChange(value) },
                    label = {
                        Text(
                            text = label,
                            fontWeight = if (modifier == value) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "Adds or removes reps from all sets for this day",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RestTimeSection(
    restSeconds: Int,
    onRestChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        val minutes = restSeconds / 60
        val seconds = restSeconds % 60
        val displayTime = if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
        Text(
            text = "Rest Time Override: $displayTime",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = restSeconds.toFloat(),
            onValueChange = { onRestChange(it.roundToInt()) },
            valueRange = 0f..300f,
            steps = 59, // 0 to 300 in 5s increments (60 values = 59 intervals)
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Override rest time between sets for this day only",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
