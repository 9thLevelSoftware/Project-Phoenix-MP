package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleProgression

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionSettingsSheet(
    progression: CycleProgression,
    currentRotation: Int,
    onSave: (CycleProgression) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    var frequency by remember { mutableStateOf(progression.frequencyCycles) }
    var weightEnabled by remember { mutableStateOf(progression.weightIncreasePercent != null) }
    var weightPercent by remember { mutableStateOf(progression.weightIncreasePercent ?: 2.5f) }
    var echoEnabled by remember { mutableStateOf(progression.echoLevelIncrease) }
    var eccentricEnabled by remember { mutableStateOf(progression.eccentricLoadIncreasePercent != null) }
    var eccentricPercent by remember { mutableStateOf(progression.eccentricLoadIncreasePercent ?: 5) }

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
                text = "Progression Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Frequency selector
            Text(
                text = "Apply progression every:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { if (frequency > 1) frequency-- },
                    enabled = frequency > 1
                ) {
                    Text("◄", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = "$frequency cycle completions",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                IconButton(
                    onClick = { if (frequency < 10) frequency++ },
                    enabled = frequency < 10
                ) {
                    Text("►", style = MaterialTheme.typography.titleLarge)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Weight increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = weightEnabled,
                    onCheckedChange = { weightEnabled = it }
                )
                Text(
                    text = "Increase weight by",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            if (weightEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = weightPercent,
                        onValueChange = { weightPercent = it },
                        valueRange = 0.5f..10f,
                        steps = 18,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(kotlin.math.round(weightPercent * 10) / 10)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(50.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Echo level increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = echoEnabled,
                    onCheckedChange = { echoEnabled = it }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Increase Echo level by 1",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Applies to Echo-mode exercises",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Eccentric load increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = eccentricEnabled,
                    onCheckedChange = { eccentricEnabled = it }
                )
                Text(
                    text = "Increase eccentric load by",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            if (eccentricEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = eccentricPercent.toFloat(),
                        onValueChange = { eccentricPercent = it.toInt() },
                        valueRange = 1f..20f,
                        steps = 18,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${eccentricPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(50.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Progress indicator
            val cyclesUntilNext = frequency - (currentRotation % frequency)
            Text(
                text = "Current rotation: $currentRotation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Next progression applies after $cyclesUntilNext more cycle${if (cyclesUntilNext != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Apply button
            Button(
                onClick = {
                    onSave(
                        CycleProgression(
                            cycleId = progression.cycleId,
                            frequencyCycles = frequency,
                            weightIncreasePercent = if (weightEnabled) weightPercent else null,
                            echoLevelIncrease = echoEnabled,
                            eccentricLoadIncreasePercent = if (eccentricEnabled) eccentricPercent else null
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Apply")
            }
        }
    }
}
