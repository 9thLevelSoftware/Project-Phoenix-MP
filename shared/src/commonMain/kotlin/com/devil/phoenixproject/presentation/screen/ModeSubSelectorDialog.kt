package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Mode Sub-Selector Dialog for hierarchical workout modes (TUT and Echo)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSubSelectorDialog(
    type: String,
    workoutParameters: WorkoutParameters,
    onDismiss: () -> Unit,
    onSelect: (WorkoutMode, EccentricLoad?) -> Unit
) {
    when (type) {
        "TUT" -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Select TUT Variant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(28.dp),
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        OutlinedButton(
                            onClick = { onSelect(WorkoutMode.TUT, null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("TUT")
                        }
                        OutlinedButton(
                            onClick = { onSelect(WorkoutMode.TUTBeast, null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("TUT Beast")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }
        "Echo" -> {
            var selectedEchoLevel by remember {
                mutableStateOf(
                    if (workoutParameters.isEchoMode) {
                        workoutParameters.echoLevel
                    } else {
                        EchoLevel.HARD
                    }
                )
            }
            var selectedEccentricLoad by remember {
                mutableStateOf(
                    if (workoutParameters.isEchoMode) {
                        workoutParameters.eccentricLoad
                    } else {
                        EccentricLoad.LOAD_100
                    }
                )
            }
            var showEchoLevelMenu by remember { mutableStateOf(false) }
            var showEccentricMenu by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Echo Mode Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(28.dp),
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Text(
                            "Echo adapts resistance to your output",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))

                        // Echo Level Dropdown
                        ExposedDropdownMenuBox(
                            expanded = showEchoLevelMenu,
                            onExpandedChange = { showEchoLevelMenu = !showEchoLevelMenu }
                        ) {
                            OutlinedTextField(
                                value = selectedEchoLevel.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Echo Level") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEchoLevelMenu)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = showEchoLevelMenu,
                                onDismissRequest = { showEchoLevelMenu = false }
                            ) {
                                listOf(EchoLevel.HARD, EchoLevel.HARDER, EchoLevel.HARDEST, EchoLevel.EPIC).forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(level.displayName) },
                                        onClick = {
                                            selectedEchoLevel = level
                                            showEchoLevelMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Eccentric Load Dropdown
                        ExposedDropdownMenuBox(
                            expanded = showEccentricMenu,
                            onExpandedChange = { showEccentricMenu = !showEccentricMenu }
                        ) {
                            OutlinedTextField(
                                value = selectedEccentricLoad.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Eccentric Load") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEccentricMenu)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = showEccentricMenu,
                                onDismissRequest = { showEccentricMenu = false }
                            ) {
                                listOf(
                                    EccentricLoad.LOAD_0,
                                    EccentricLoad.LOAD_50,
                                    EccentricLoad.LOAD_75,
                                    EccentricLoad.LOAD_100,
                                    EccentricLoad.LOAD_120,
                                    EccentricLoad.LOAD_150
                                ).forEach { load ->
                                    DropdownMenuItem(
                                        text = { Text(load.displayName) },
                                        onClick = {
                                            selectedEccentricLoad = load
                                            showEccentricMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onSelect(WorkoutMode.Echo(selectedEchoLevel), selectedEccentricLoad)
                        }
                    ) {
                        Text("Select")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
