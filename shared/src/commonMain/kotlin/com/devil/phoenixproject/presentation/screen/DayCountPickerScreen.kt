package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Screen for selecting the number of days in a training cycle.
 * This is the entry point for cycle creation where users first choose
 * how many days their cycle will have.
 *
 * @param onDayCountSelected Callback when a day count is selected (1-365)
 * @param onBack Callback for back navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayCountPickerScreen(
    onDayCountSelected: (Int) -> Unit,
    onBack: () -> Unit
) {
    // Preset day counts for common cycle lengths
    val presets = listOf(7, 14, 21, 28)

    // State for custom input dialog
    var showCustomDialog by remember { mutableStateOf(false) }
    var customInputValue by remember { mutableStateOf("") }
    var customInputError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Cycle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Heading
            Text(
                text = "How many days in your cycle?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Subtext
            Text(
                text = "This is a rolling schedule \u2014 Day 1 follows the last day automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Preset chips in a Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                presets.forEach { days ->
                    FilterChip(
                        selected = false,
                        onClick = { onDayCountSelected(days) },
                        label = {
                            Text(
                                text = days.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        modifier = Modifier.size(width = 64.dp, height = 48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Custom button
            OutlinedButton(
                onClick = {
                    customInputValue = ""
                    customInputError = null
                    showCustomDialog = true
                },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Custom")
            }
        }
    }

    // Custom input dialog
    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = {
                Text(
                    "Enter Day Count",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = customInputValue,
                        onValueChange = { newValue ->
                            // Only allow numeric input
                            if (newValue.all { it.isDigit() }) {
                                customInputValue = newValue
                                // Validate range
                                val number = newValue.toIntOrNull()
                                customInputError = when {
                                    newValue.isBlank() -> null
                                    number == null -> "Please enter a valid number"
                                    number < 1 -> "Minimum is 1 day"
                                    number > 365 -> "Maximum is 365 days"
                                    else -> null
                                }
                            }
                        },
                        label = { Text("Number of days") },
                        placeholder = { Text("e.g., 5") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        isError = customInputError != null,
                        supportingText = customInputError?.let { error ->
                            { Text(error, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val days = customInputValue.toIntOrNull()
                        if (days != null && days in 1..365) {
                            showCustomDialog = false
                            onDayCountSelected(days)
                        } else {
                            customInputError = when {
                                customInputValue.isBlank() -> "Please enter a number"
                                days == null -> "Please enter a valid number"
                                days < 1 -> "Minimum is 1 day"
                                days > 365 -> "Maximum is 365 days"
                                else -> "Invalid input"
                            }
                        }
                    },
                    enabled = customInputValue.isNotBlank() && customInputError == null
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
