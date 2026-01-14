package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * One Rep Max Input Screen
 *
 * Displayed when user selects the 5/3/1 (Wendler) template to collect
 * their 1RM values for the main lifts.
 *
 * @param mainLiftNames List of exercise names that need 1RM input (e.g., ["Bench Press", "Squat", "Shoulder Press", "Deadlift"])
 * @param existingOneRepMaxValues Pre-fill values from stored data (exercise name to 1RM in kg)
 * @param weightUnit User's preferred weight unit (KG or LB)
 * @param kgToDisplay Conversion function from kg to display unit
 * @param displayToKg Conversion function from display unit back to kg
 * @param onConfirm Callback with exercise name to 1RM mapping (always in kg) when user continues
 * @param onCancel Callback when user cancels
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneRepMaxInputScreen(
    mainLiftNames: List<String>,
    existingOneRepMaxValues: Map<String, Float> = emptyMap(),
    weightUnit: WeightUnit = WeightUnit.KG,
    kgToDisplay: (Float, WeightUnit) -> Float = { kg, unit -> if (unit == WeightUnit.LB) kg * 2.205f else kg },
    displayToKg: (Float, WeightUnit) -> Float = { display, unit -> if (unit == WeightUnit.LB) display / 2.205f else display },
    onConfirm: (Map<String, Float>) -> Unit,
    onCancel: () -> Unit
) {
    // Unit label based on user preference
    val unitLabel = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
    // State: Map of exercise name to text input value (displayed in user's preferred unit)
    val inputValues = remember(existingOneRepMaxValues, weightUnit) {
        mutableStateMapOf<String, String>().apply {
            // Initialize with existing values converted to display unit
            mainLiftNames.forEach { name ->
                val existingValueKg = existingOneRepMaxValues[name]
                if (existingValueKg != null && existingValueKg > 0f) {
                    // Convert from kg to display unit and format nicely
                    val displayValue = kgToDisplay(existingValueKg, weightUnit)
                    put(name, displayValue.toInt().toString())
                } else {
                    put(name, "")
                }
            }
        }
    }

    // Track validation errors
    val validationErrors = remember { mutableStateMapOf<String, Boolean>() }

    // Check if at least one value is entered and all non-empty values are valid
    val hasAtLeastOneValue = inputValues.values.any { it.isNotBlank() }
    val allValidOrEmpty = inputValues.all { (name, value) ->
        value.isBlank() || value.toFloatOrNull() != null
    }
    val canContinue = hasAtLeastOneValue && allValidOrEmpty

    // Use Box with bottom bar instead of Scaffold to avoid double TopAppBar (Unified Bar spec)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 160.dp) // Space for bottom bar
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium)
        ) {
            // Title
            Text(
                text = "Enter Your 1RM Values",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(top = Spacing.medium)
            )
            // Header with instructions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(top = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                Text(
                    text = "Enter your one rep max (or recent heavy single) for each lift. These values are used to calculate your working weights.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "You can skip exercises and add their 1RM values later in the Exercise Library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Card containing all input fields
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium)
                ) {
                    mainLiftNames.forEach { exerciseName ->
                        OneRepMaxInputField(
                            exerciseName = exerciseName,
                            value = inputValues[exerciseName] ?: "",
                            onValueChange = { newValue ->
                                inputValues[exerciseName] = newValue
                                // Validate input
                                val isValid = newValue.isBlank() || newValue.toFloatOrNull() != null
                                validationErrors[exerciseName] = !isValid
                            },
                            isError = validationErrors[exerciseName] == true,
                            unitLabel = unitLabel
                        )
                    }
                }
            }

            // Help text
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                ) {
                    Text(
                        text = "What is a 1RM?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Your one rep max is the maximum weight you can lift for a single repetition with proper form. If you don't know your exact 1RM, use a recent heavy single or estimate based on your typical working weights.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Bottom spacer for better scroll experience
            Spacer(modifier = Modifier.height(Spacing.small))
        }

        // Bottom action bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Skip button - styled as a distinct outlined button
                OutlinedButton(
                    onClick = {
                        // Continue with zeros for all lifts
                        val emptyMap = mainLiftNames.associateWith { 0f }
                        onConfirm(emptyMap)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.tertiary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                        )
                    )
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Skip for now",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // Bottom action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            // Parse valid inputs and convert back to kg, use 0 for empty
                            val oneRepMaxValues = inputValues.mapNotNull { (name, value) ->
                                val displayValue = value.toFloatOrNull()
                                if (displayValue != null && displayValue > 0) {
                                    // Convert display value back to kg for storage
                                    val kgValue = displayToKg(displayValue, weightUnit)
                                    name to kgValue
                                } else if (value.isBlank()) {
                                    name to 0f
                                } else {
                                    null
                                }
                            }.toMap()
                            onConfirm(oneRepMaxValues)
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        enabled = canContinue,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "Continue",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    } // End Box
}

/**
 * Input field for a single exercise's 1RM
 */
@Composable
private fun OneRepMaxInputField(
    exerciseName: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    unitLabel: String = "kg"
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
    ) {
        // Exercise name label
        Text(
            text = exerciseName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Weight input field with "kg" suffix
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("e.g., 100") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                singleLine = true,
                isError = isError,
                supportingText = if (isError) {
                    { Text("Please enter a valid number") }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    errorBorderColor = MaterialTheme.colorScheme.error
                )
            )

            // Units indicator (dynamic based on user preference)
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.small)
            )
        }
    }
}
