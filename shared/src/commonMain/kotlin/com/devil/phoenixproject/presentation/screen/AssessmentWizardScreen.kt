package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.assessment.LoadVelocityPoint
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.viewmodel.AssessmentStep
import com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * Multi-step assessment wizard screen for guided VBT strength testing.
 *
 * Walks the user through: exercise selection -> video instruction ->
 * progressive weight loading with velocity tracking -> results with
 * 1RM estimate and override option -> save confirmation.
 *
 * @param viewModel AssessmentViewModel managing wizard state
 * @param exerciseId Optional pre-selected exercise ID (from exercise detail navigation)
 * @param themeMode Current theme mode
 * @param onNavigateBack Callback to navigate back when assessment is complete or cancelled
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentWizardScreen(
    viewModel: AssessmentViewModel,
    exerciseId: String? = null,
    themeMode: ThemeMode,
    onNavigateBack: () -> Unit
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val exercises by viewModel.exercises.collectAsState()

    // Auto-select exercise if ID is provided
    LaunchedEffect(exerciseId, exercises) {
        if (exerciseId != null && exercises.isNotEmpty()) {
            viewModel.selectExerciseById(exerciseId)
        }
    }

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        when (val step = currentStep) {
            is AssessmentStep.ExerciseSelection -> ExerciseSelectionContent(
                step = step,
                exercises = exercises,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onExerciseSelected = viewModel::selectExercise
            )
            is AssessmentStep.Instruction -> InstructionContent(
                step = step,
                onStartAssessment = viewModel::startAssessment,
                onBack = viewModel::reset
            )
            is AssessmentStep.ProgressiveLoading -> ProgressiveLoadingContent(
                step = step,
                onRecordSet = viewModel::recordSet,
                onCancel = viewModel::reset
            )
            is AssessmentStep.Results -> ResultsContent(
                step = step,
                onAccept = viewModel::acceptResult,
                onDiscard = viewModel::reset
            )
            is AssessmentStep.Saving -> SavingContent()
            is AssessmentStep.Complete -> CompleteContent(
                step = step,
                onDone = onNavigateBack
            )
        }
    }
}

// ---------- Step 1: Exercise Selection ----------

@Composable
private fun ExerciseSelectionContent(
    step: AssessmentStep.ExerciseSelection,
    exercises: List<com.devil.phoenixproject.domain.model.Exercise>,
    onSearchQueryChange: (String) -> Unit,
    onExerciseSelected: (com.devil.phoenixproject.domain.model.Exercise) -> Unit
) {
    val filteredExercises = remember(exercises, step.searchQuery) {
        if (step.searchQuery.isBlank()) exercises
        else exercises.filter { it.name.contains(step.searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.medium, vertical = Spacing.medium)
    ) {
        Text(
            text = "Strength Assessment",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(Spacing.extraSmall))

        Text(
            text = "Select an exercise to test your 1RM",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Search field
        OutlinedTextField(
            value = step.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search exercises...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        // Exercise list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            items(filteredExercises, key = { it.id ?: it.name }) { exercise ->
                Card(
                    onClick = { onExerciseSelected(exercise) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.medium))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = exercise.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = exercise.muscleGroup,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (exercise.oneRepMaxKg != null) {
                            Text(
                                text = "${exercise.oneRepMaxKg} kg",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Step 2: Instruction ----------

@Composable
private fun InstructionContent(
    step: AssessmentStep.Instruction,
    onStartAssessment: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(
            text = step.exercise.displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Video player - prefer tutorial video, else first available
        val tutorialVideo = step.videos.firstOrNull { it.isTutorial }
        val videoToShow = tutorialVideo ?: step.videos.firstOrNull()
        if (videoToShow != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                VideoPlayer(
                    videoUrl = videoToShow.videoUrl,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
        }

        // Instructions card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                Text(
                    text = "Assessment Protocol",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val instructions = listOf(
                    "You will perform 3-5 sets at progressively heavier weights.",
                    "Perform 3 reps per set with maximum intent on the concentric (lifting) phase.",
                    "The system will track your velocity to estimate your one-rep max.",
                    "Start light (~40% of estimated max) and increase weight each set."
                )

                instructions.forEachIndexed { index, instruction ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = instruction,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Buttons
        Button(
            onClick = onStartAssessment,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                "Begin Assessment",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

// ---------- Step 3: Progressive Loading ----------

@Composable
private fun ProgressiveLoadingContent(
    step: AssessmentStep.ProgressiveLoading,
    onRecordSet: (Float, Int, Float, Float) -> Unit,
    onCancel: () -> Unit
) {
    var weightInput by remember(step.currentSetNumber) { mutableStateOf(step.suggestedWeightKg.toString()) }
    var velocityInput by remember(step.currentSetNumber) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        // Step indicator
        Text(
            text = "Set ${step.currentSetNumber} of up to 5",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Suggested weight card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Suggested Weight",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "${step.suggestedWeightKg} kg",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Completed sets list
        if (step.recordedSets.isNotEmpty()) {
            Text(
                text = "Completed Sets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            step.recordedSets.forEach { set ->
                val velocityColor = when {
                    set.meanVelocityMs > 0.8f -> Color(0xFF4CAF50)  // Green - light
                    set.meanVelocityMs > 0.5f -> Color(0xFFFFC107)  // Yellow - moderate
                    set.meanVelocityMs > 0.3f -> Color(0xFFFF9800)  // Orange - heavy
                    else -> Color(0xFFF44336)                        // Red - at/past threshold
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Set ${set.setNumber}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp)
                        )
                        Text(
                            text = "${set.loadKg} kg",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${set.meanVelocityMs} m/s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = velocityColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.medium))
        }

        // Should-stop warning
        if (step.shouldStop) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Velocity threshold reached - assessment complete",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(Spacing.medium)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
        }

        // Input section (when not stopped)
        if (!step.shouldStop) {
            Text(
                text = "Log This Set",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            OutlinedTextField(
                value = weightInput,
                onValueChange = { weightInput = it },
                label = { Text("Actual Weight Used (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            OutlinedTextField(
                value = velocityInput,
                onValueChange = { velocityInput = it },
                label = { Text("Mean Velocity (m/s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            val weight = weightInput.toFloatOrNull()
            val velocity = velocityInput.toFloatOrNull()
            val canLog = weight != null && weight > 0f && velocity != null && velocity > 0f

            Button(
                onClick = {
                    if (weight != null && velocity != null) {
                        onRecordSet(weight, 3, velocity, velocity)
                    }
                },
                enabled = canLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Log Set",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel Assessment")
        }
    }
}

// ---------- Step 4: Results ----------

@Composable
private fun ResultsContent(
    step: AssessmentStep.Results,
    onAccept: (Float?) -> Unit,
    onDiscard: () -> Unit
) {
    var overrideText by remember { mutableStateOf(step.overrideValueKg) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium)
    ) {
        Text(
            text = "Assessment Complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Estimated 1RM card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Estimated 1RM",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "${"%.1f".format(step.estimatedOneRepMaxKg)} kg",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Confidence indicator
        val confidenceText = when {
            step.r2 > 0.9f -> "High confidence"
            step.r2 > 0.7f -> "Moderate confidence"
            else -> "Low confidence - consider retesting"
        }
        val confidenceColor = when {
            step.r2 > 0.9f -> Color(0xFF4CAF50)
            step.r2 > 0.7f -> Color(0xFFFFC107)
            else -> Color(0xFFF44336)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Regression Quality:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(
                    text = "$confidenceText (R² = ${"%.2f".format(step.r2)})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = confidenceColor
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Load-velocity data points
        Text(
            text = "Load-Velocity Profile",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(Spacing.small))

        step.loadVelocityPoints.forEach { point ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${point.loadKg} kg",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${point.meanVelocityMs} m/s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Override section
        Text(
            text = "Override 1RM",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(Spacing.extraSmall))

        OutlinedTextField(
            value = overrideText,
            onValueChange = { overrideText = it },
            label = { Text("Enter your own estimate (kg)") },
            supportingText = { Text("Leave empty to use the calculated value") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Accept button
        Button(
            onClick = {
                val override = overrideText.toFloatOrNull()
                onAccept(override)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                "Accept & Save",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        // Discard button
        OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Discard")
        }
    }
}

// ---------- Step 5: Saving ----------

@Composable
private fun SavingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Saving assessment...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

// ---------- Step 6: Complete ----------

@Composable
private fun CompleteContent(
    step: AssessmentStep.Complete,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            modifier = Modifier.padding(Spacing.large)
        ) {
            // Success icon
            Icon(
                Icons.Default.Check,
                contentDescription = "Success",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(72.dp)
            )

            Text(
                text = "1RM Updated!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "${step.exerciseName}: ${"%.1f".format(step.finalOneRepMaxKg)} kg",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.large))

            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Done",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
