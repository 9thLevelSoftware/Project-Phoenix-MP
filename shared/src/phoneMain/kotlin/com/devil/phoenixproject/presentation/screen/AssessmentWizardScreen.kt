package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.presentation.components.ExerciseDemoImage
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.viewmodel.AssessmentStep
import com.devil.phoenixproject.presentation.viewmodel.AssessmentUiEvent
import com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.celebrationBackgroundBrush
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Multi-step assessment wizard screen for guided VBT strength testing.
 *
 * Walks the user through: exercise selection -> image instruction ->
 * progressive weight loading with velocity tracking -> results with
 * 1RM estimate and override option -> save confirmation.
 *
 * @param viewModel AssessmentViewModel managing wizard state
 * @param exerciseId Optional pre-selected exercise ID (from exercise detail navigation)
 * @param onNavigateBack Callback to navigate back when assessment is complete or cancelled
 */
/**
 * @param metricsFlow Live BLE metrics flow for velocity capture during assessment sets.
 *        When null, the wizard falls back to manual velocity entry (e.g., when not connected).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentWizardScreen(
    viewModel: AssessmentViewModel,
    profileId: String,
    exerciseId: String? = null,
    onNavigateBack: () -> Unit,
    metricsFlow: StateFlow<WorkoutMetric?>? = null,
    weightUnit: WeightUnit = WeightUnit.KG,
) {
    require(profileId.isNotBlank()) { "Assessment profileId must not be blank" }
    val currentStep by viewModel.currentStep.collectAsState()
    val exercises by viewModel.exercises.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailureMessage = stringResource(Res.string.assessment_save_failed)
    LaunchedEffect(viewModel, saveFailureMessage) {
        viewModel.events.collect { event ->
            when (event) {
                AssessmentUiEvent.SaveFailed ->
                    snackbarHostState.showSnackbar(saveFailureMessage)
            }
        }
    }

    // Auto-select exercise if ID is provided
    LaunchedEffect(exerciseId, exercises) {
        if (exerciseId != null && exercises.isNotEmpty()) {
            viewModel.selectExerciseById(exerciseId)
        }
    }

    val backgroundGradient = screenBackgroundBrush()
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val widthModifier = if (contentMaxWidth != null) {
        Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()
    } else {
        Modifier.fillMaxWidth()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient),
    ) {
        val stepNumber = when (currentStep) {
            is AssessmentStep.ExerciseSelection -> 1
            is AssessmentStep.Instruction -> 2
            is AssessmentStep.ProgressiveLoading -> 3
            is AssessmentStep.Results -> 4
            else -> null
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (stepNumber != null) {
                Box(modifier = widthModifier) {
                    WizardStepIndicator(currentStep = stepNumber, totalSteps = 4)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = widthModifier.fillMaxHeight()) {
                    when (val step = currentStep) {
                        is AssessmentStep.ExerciseSelection -> ExerciseSelectionContent(
                            step = step,
                            exercises = exercises,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onExerciseSelected = viewModel::selectExercise,
                        )

                        is AssessmentStep.Instruction -> InstructionContent(
                            step = step,
                            onStartAssessment = viewModel::startAssessment,
                            onBack = viewModel::reset,
                        )

                        is AssessmentStep.ProgressiveLoading -> ProgressiveLoadingContent(
                            step = step,
                            weightUnit = weightUnit,
                            onRecordSet = viewModel::recordSet,
                            onStartCapture = { metricsFlow?.let { viewModel.startVelocityCapture(it) } },
                            onStopCapture = { viewModel.stopVelocityCapture() },
                            hasBleMetrics = metricsFlow != null,
                            onCancel = viewModel::reset,
                        )

                        is AssessmentStep.Results -> ResultsContent(
                            step = step,
                            weightUnit = weightUnit,
                            onAccept = { overrideKg ->
                                viewModel.acceptResult(
                                    profileId = profileId,
                                    overrideKg = overrideKg,
                                )
                            },
                            onDiscard = viewModel::reset,
                        )

                        is AssessmentStep.Saving -> SavingContent()

                        is AssessmentStep.Complete -> CompleteContent(
                            step = step,
                            weightUnit = weightUnit,
                            onDone = onNavigateBack,
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ---------- Wizard Step Indicator ----------

/**
 * Compact step-progress indicator for the 4 user-visible assessment steps.
 *
 * Shows a localized "Step N of 4" label and a row of 4 dots.
 * Active dot = primary, completed = primary @ 0.6 alpha, upcoming = outlineVariant.
 * Hidden on Saving/Complete (transient/terminal steps).
 */
@Composable
private fun WizardStepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    val stepLabel = stringResource(Res.string.wizard_step_of, currentStep, totalSteps)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .semantics(mergeDescendants = true) { contentDescription = stepLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            text = stepLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(totalSteps) { index ->
                val dotStep = index + 1
                val dotColor = when {
                    dotStep < currentStep -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    dotStep == currentStep -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
            }
        }
    }
}

// ---------- Step 1: Exercise Selection ----------

@Composable
private fun ExerciseSelectionContent(
    step: AssessmentStep.ExerciseSelection,
    exercises: List<com.devil.phoenixproject.domain.model.Exercise>,
    onSearchQueryChange: (String) -> Unit,
    onExerciseSelected: (com.devil.phoenixproject.domain.model.Exercise) -> Unit,
) {
    val filteredExercises = remember(exercises, step.searchQuery) {
        if (step.searchQuery.isBlank()) {
            exercises
        } else {
            exercises.filter { it.name.contains(step.searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
    ) {
        Text(
            text = stringResource(Res.string.strength_assessment_cta_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Spacing.extraSmall))

        Text(
            text = stringResource(Res.string.assessment_select_exercise_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Search field
        OutlinedTextField(
            value = step.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(Res.string.search_exercises)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.cd_search)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        // Exercise list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            items(filteredExercises, key = { it.id ?: it.name }) { exercise ->
                ExpressiveCard(
                    onClick = { onExerciseSelected(exercise) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.medium))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = exercise.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = exercise.muscleGroup,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun InstructionContent(step: AssessmentStep.Instruction, onStartAssessment: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.medium),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = step.exercise.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            if (step.images.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    ExerciseDemoImage(
                        imageUrls = step.images.map { it.url },
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = step.exercise.displayName,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
            }

            // Instructions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = stringResource(Res.string.assessment_protocol_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val instructions = listOf(
                        stringResource(Res.string.assessment_instruction_1),
                        stringResource(Res.string.assessment_instruction_2),
                        stringResource(Res.string.assessment_instruction_3),
                        stringResource(Res.string.assessment_instruction_4),
                    )

                    instructions.forEachIndexed { index, instruction ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                text = instruction,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Buttons anchored cleanly outside the scroll viewport
        Button(
            onClick = onStartAssessment,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                stringResource(Res.string.assessment_begin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.action_back))
        }
    }
}

// ---------- Step 3: Progressive Loading ----------

@Composable
private fun ProgressiveLoadingContent(
    step: AssessmentStep.ProgressiveLoading,
    weightUnit: WeightUnit,
    onRecordSet: (Float, Float) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    hasBleMetrics: Boolean,
    onCancel: () -> Unit,
) {
    var weightInput by remember(step.currentSetNumber, weightUnit) {
        mutableStateOf(formatDisplayNumber(step.suggestedWeightKg, weightUnit))
    }
    // Manual fallback fields only used when BLE metrics are unavailable
    var velocityInput by remember(step.currentSetNumber) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
    ) {
        // Step indicator
        Text(
            text = stringResource(Res.string.assessment_set_progress, step.currentSetNumber),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Suggested weight card (total weight = both cables combined)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.assessment_suggested_weight_total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = formatAssessmentWeight(step.suggestedWeightKg, weightUnit),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Completed sets list
        if (step.recordedSets.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.assessment_completed_sets),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            step.recordedSets.forEach { set ->
                val assessColors = AccessibilityTheme.colors
                val velocityColor = when {
                    set.meanVelocityMs > 0.8f -> assessColors.success
                    set.meanVelocityMs > 0.5f -> assessColors.warning
                    set.meanVelocityMs > 0.3f -> assessColors.qualityBelowAverage
                    else -> assessColors.error
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.set_ready_set_numbered, set.setNumber),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp),
                        )
                        Text(
                            text = formatAssessmentWeight(set.loadKg, weightUnit),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                Res.string.assessment_velocity_ms,
                                KmpUtils.formatFloat(set.meanVelocityMs, 2),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = velocityColor,
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
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = stringResource(Res.string.assessment_velocity_threshold_stop),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
        }

        // Input section (when not stopped)
        if (!step.shouldStop) {
            if (hasBleMetrics) {
                // BLE velocity capture mode: user performs a set while we capture velocity
                BleVelocityCaptureSection(
                    isCapturing = step.isCapturing,
                    liveVelocityMs = step.liveVelocityMs,
                    suggestedWeight = step.suggestedWeightKg,
                    weightUnit = weightUnit,
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                )
            } else {
                // Manual fallback mode: user types weight and velocity
                ManualInputSection(
                    weightInput = weightInput,
                    velocityInput = velocityInput,
                    weightUnit = weightUnit,
                    onWeightChange = { weightInput = it },
                    onVelocityChange = { velocityInput = it },
                    onRecordSet = onRecordSet,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            enabled = !step.isCapturing,
        ) {
            Text(stringResource(Res.string.cancel_assessment))
        }
    }
}

/**
 * BLE velocity capture UI: shows instructions, live velocity, and start/stop buttons.
 */
@Composable
private fun BleVelocityCaptureSection(
    isCapturing: Boolean,
    liveVelocityMs: Float?,
    suggestedWeight: Float,
    weightUnit: WeightUnit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isCapturing) {
                // Pre-set instructions
                Text(
                    text = stringResource(
                        Res.string.assessment_set_machine_to,
                        formatAssessmentWeight(suggestedWeight, weightUnit),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                Text(
                    text = stringResource(Res.string.assessment_capture_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.medium))

                Button(
                    onClick = onStartCapture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        stringResource(Res.string.assessment_start_set),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                // Capturing: show live velocity
                Text(
                    text = stringResource(Res.string.assessment_capturing_velocity),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Live velocity display
                val displayVelocity = liveVelocityMs?.let { KmpUtils.formatFloat(it, 2) } ?: "--"
                Text(
                    text = stringResource(Res.string.assessment_velocity_ms, displayVelocity),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = stringResource(Res.string.assessment_mean_concentric_velocity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                Button(
                    onClick = onStopCapture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ),
                ) {
                    Text(
                        stringResource(Res.string.assessment_end_set),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Manual velocity entry fallback when BLE is not connected.
 */
@Composable
private fun ManualInputSection(
    weightInput: String,
    velocityInput: String,
    weightUnit: WeightUnit,
    onWeightChange: (String) -> Unit,
    onVelocityChange: (String) -> Unit,
    onRecordSet: (Float, Float) -> Unit,
) {
    val unitSuffix = stringResource(if (weightUnit == WeightUnit.LB) Res.string.label_lbs else Res.string.label_kg)
    Text(
        text = stringResource(Res.string.assessment_log_this_set),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(modifier = Modifier.height(Spacing.small))

    OutlinedTextField(
        value = weightInput,
        onValueChange = onWeightChange,
        label = { Text(stringResource(Res.string.actual_weight_label)) },
        suffix = { Text(unitSuffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
    )

    Spacer(modifier = Modifier.height(Spacing.small))

    OutlinedTextField(
        value = velocityInput,
        onValueChange = onVelocityChange,
        label = { Text(stringResource(Res.string.mean_velocity_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
    )

    Spacer(modifier = Modifier.height(Spacing.medium))

    val enteredWeight = weightInput.toFloatOrNull()
    val velocity = velocityInput.toFloatOrNull()
    val canLog = enteredWeight != null && enteredWeight > 0f && velocity != null && velocity > 0f

    Button(
        onClick = {
            if (enteredWeight != null && velocity != null) {
                onRecordSet(parseDisplayWeightToKg(enteredWeight, weightUnit), velocity)
            }
        },
        enabled = canLog,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            stringResource(Res.string.assessment_log_set),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------- Step 4: Results ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsContent(
    step: AssessmentStep.Results,
    weightUnit: WeightUnit,
    onAccept: (Float?) -> Unit,
    onDiscard: () -> Unit,
) {
    var overrideText by remember(step.overrideValueKg, weightUnit) {
        mutableStateOf(
            step.overrideValueKg.toFloatOrNull()
                ?.let { formatDisplayNumber(it, weightUnit) }
                ?: step.overrideValueKg,
        )
    }
    val unitSuffix = stringResource(if (weightUnit == WeightUnit.LB) Res.string.label_lbs else Res.string.label_kg)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
    ) {
        Text(
            text = stringResource(Res.string.assessment_complete_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Estimated 1RM card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.assessment_estimated_1rm),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = formatAssessmentWeight(step.estimatedOneRepMaxKg, weightUnit),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Confidence indicator
        val confidenceText = stringResource(
            when {
                step.r2 > 0.9f -> Res.string.assessment_confidence_high
                step.r2 > 0.7f -> Res.string.assessment_confidence_moderate
                else -> Res.string.assessment_confidence_low
            },
        )
        val confidenceColor = when {
            step.r2 > 0.9f -> AccessibilityTheme.colors.success
            step.r2 > 0.7f -> AccessibilityTheme.colors.warning
            else -> AccessibilityTheme.colors.error
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.assessment_regression_quality),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        Res.string.assessment_confidence_r2,
                        confidenceText,
                        KmpUtils.formatFloat(step.r2, 2),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = confidenceColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Load-velocity data points
        Text(
            text = stringResource(Res.string.assessment_load_velocity_profile),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(Spacing.small))

        step.loadVelocityPoints.forEach { point ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                shape = MaterialTheme.shapes.extraSmall,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatAssessmentWeight(point.loadKg, weightUnit),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            Res.string.assessment_velocity_ms,
                            point.meanVelocityMs.toString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Override section
        Text(
            text = stringResource(Res.string.assessment_override_1rm),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(Spacing.extraSmall))

        OutlinedTextField(
            value = overrideText,
            onValueChange = { overrideText = it },
            label = { Text(stringResource(Res.string.enter_estimate_label)) },
            supportingText = { Text(stringResource(Res.string.leave_empty_hint)) },
            suffix = { Text(unitSuffix) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
        )

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Accept button
        Button(
            onClick = {
                val overrideKg = overrideText.toFloatOrNull()?.let {
                    parseDisplayWeightToKg(it, weightUnit)
                }
                onAccept(overrideKg)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                stringResource(Res.string.assessment_accept_and_save),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        // Discard button
        OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.action_discard))
        }
    }
}

// ---------- Step 5: Saving ----------

@Composable
private fun SavingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            LoadingIndicator(
                size = LoadingIndicatorSize.Large,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.assessment_saving),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

// ---------- Step 6: Complete ----------

@Composable
private fun CompleteContent(step: AssessmentStep.Complete, weightUnit: WeightUnit, onDone: () -> Unit) {
    // exercise-library-13: SpringBouncy entrance on icon + celebrationBackgroundBrush +
    // hero metric at displayMedium. reduceMotion: iconReady OR reduceMotion = 1f immediately.
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    var iconReady by remember { mutableStateOf(false) }
    val iconScale by animateFloatAsState(
        targetValue = if (iconReady || reduceMotion) 1f else 0f,
        animationSpec = ExpressiveMotion.SpringBouncy,
        label = "iconScale",
    )
    LaunchedEffect(Unit) { iconReady = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(celebrationBackgroundBrush()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            modifier = Modifier.padding(Spacing.large),
        ) {
            // Success icon with SpringBouncy scale entrance
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(Res.string.cd_success),
                tint = AccessibilityTheme.colors.success,
                modifier = Modifier
                    .size(72.dp)
                    .scale(iconScale),
            )

            Text(
                text = stringResource(Res.string.assessment_1rm_updated),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            // Exercise name: headlineLarge with overflow guard for 360dp screens
            Text(
                text = step.exerciseName,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Hero metric: displayMedium per charter (was titleLarge) — 1RM value on its own line
            Text(
                text = formatAssessmentWeight(step.finalOneRepMaxKg, weightUnit),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(Spacing.large))

            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    stringResource(Res.string.assessment_done),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun formatDisplayNumber(kg: Float, weightUnit: WeightUnit): String {
    val display = if (weightUnit == WeightUnit.LB) UnitConverter.kgToLb(kg) else kg
    return KmpUtils.formatFloat(display, 1)
}

@Composable
private fun formatAssessmentWeight(kg: Float, weightUnit: WeightUnit): String {
    val suffix = stringResource(if (weightUnit == WeightUnit.LB) Res.string.label_lbs else Res.string.label_kg)
    return "${formatDisplayNumber(kg, weightUnit)} $suffix"
}

private fun parseDisplayWeightToKg(displayWeight: Float, weightUnit: WeightUnit): Float =
    if (weightUnit == WeightUnit.LB) UnitConverter.lbToKg(displayWeight) else displayWeight
