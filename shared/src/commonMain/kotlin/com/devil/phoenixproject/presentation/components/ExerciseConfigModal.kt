package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Modal dialog for configuring a single exercise's mode and mode-specific settings.
 * Does NOT include reps/sets/rest - those come from the template.
 *
 * @param exerciseName Display name of the exercise being configured
 * @param templateSets Number of sets from the template
 * @param templateReps Number of reps from the template (null for AMRAP)
 * @param oneRepMaxKg User's estimated 1RM for this exercise (null if unknown)
 * @param prWeight User's Personal Record weight for this exercise/mode (null if no PR)
 * @param initialConfig Initial exercise configuration to edit
 * @param onConfirm Callback when user confirms the configuration
 * @param onDismiss Callback when user dismisses the modal
 */
@Composable
fun ExerciseConfigModal(
    exerciseName: String,
    templateSets: Int,
    templateReps: Int?,
    oneRepMaxKg: Float?,
    prWeight: Float? = null,
    initialConfig: ExerciseConfig,
    onConfirm: (ExerciseConfig) -> Unit,
    onDismiss: () -> Unit,
    weightStepKg: Float = 2.5f, // Issue #266: Configurable weight step in kg
) {
    var config by remember { mutableStateOf(initialConfig) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(Spacing.large),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                ExerciseConfigHeader(
                    exerciseName = exerciseName,
                    templateSets = templateSets,
                    templateReps = templateReps,
                    oneRepMaxKg = oneRepMaxKg,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Mode selector
                Column(modifier = Modifier.padding(Spacing.medium)) {
                    ModeSelector(
                        selectedMode = config.mode,
                        onModeSelected = { newMode ->
                            config = config.copy(mode = newMode)
                        },
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Mode-specific config panel
                AnimatedContent(
                    targetState = config.mode,
                    transitionSpec = {
                        fadeIn() + slideInVertically { it / 4 } togetherWith
                            fadeOut() + slideOutVertically { -it / 4 }
                    },
                    label = "mode_panel",
                ) { mode ->
                    Column(
                        modifier = Modifier.padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        // For TUT/TUTBeast, we show the same panel with Beast Mode toggle
                        val effectiveMode = if (mode == ProgramMode.TUTBeast) ProgramMode.TUT else mode

                        when (effectiveMode) {
                            ProgramMode.OldSchool -> OldSchoolConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                prWeight = prWeight,
                                stepKg = weightStepKg,
                            )

                            ProgramMode.TUT -> TutConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                isBeastMode = config.mode == ProgramMode.TUTBeast,
                                onBeastModeChange = { enabled ->
                                    config = config.copy(
                                        mode = if (enabled) ProgramMode.TUTBeast else ProgramMode.TUT,
                                    )
                                },
                                prWeight = prWeight,
                                stepKg = weightStepKg,
                            )

                            ProgramMode.TUTBeast -> { /* Handled by TUT case above */ }

                            ProgramMode.Pump -> PumpConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                prWeight = prWeight,
                                stepKg = weightStepKg,
                            )

                            ProgramMode.EccentricOnly -> EccentricConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                eccentricPercent = config.eccentricLoadPercent,
                                onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) },
                                prWeight = prWeight,
                                stepKg = weightStepKg,
                            )

                            ProgramMode.Echo -> EchoConfigPanel(
                                echoLevel = config.echoLevel,
                                onEchoLevelChange = { config = config.copy(echoLevel = it) },
                                eccentricPercent = config.eccentricLoadPercent,
                                onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }

                    Button(
                        onClick = { onConfirm(config) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.action_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseConfigHeader(exerciseName: String, templateSets: Int, templateReps: Int?, oneRepMaxKg: Float?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.medium),
    ) {
        Text(
            text = exerciseName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(Spacing.extraSmall))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            MetaChip(label = stringResource(Res.string.config_meta_sets), value = templateSets.toString())
            MetaChip(label = stringResource(Res.string.config_meta_reps), value = templateReps?.toString() ?: "AMRAP")
            oneRepMaxKg?.let {
                MetaChip(label = stringResource(Res.string.config_meta_1rm), value = "${it.toInt()}kg")
            }
        }
    }
}

@Composable
private fun MetaChip(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ==================== MODE-SPECIFIC PANELS ====================

@Composable
private fun OldSchoolConfigPanel(weight: Float, onWeightChange: (Float) -> Unit, prWeight: Float? = null, stepKg: Float = 2.5f) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
    )
    ModeInfoCard(
        title = stringResource(Res.string.mode_old_school),
        description = stringResource(Res.string.config_mode_old_school_desc),
    )
}

@Composable
private fun TutConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    isBeastMode: Boolean,
    onBeastModeChange: (Boolean) -> Unit,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
) {
    WeightStepper(weight = weight, onWeightChange = onWeightChange, label = stringResource(Res.string.starting_weight), prWeight = prWeight, step = stepKg)

    // Beast Mode Toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(Res.string.config_beast_mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
            Text(
                text = if (isBeastMode) {
                    stringResource(
                        Res.string.config_extended_difficulty,
                    )
                } else {
                    stringResource(Res.string.config_standard_tut)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Switch(
            checked = isBeastMode,
            onCheckedChange = onBeastModeChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }

    ModeInfoCard(
        title = if (isBeastMode) {
            stringResource(
                Res.string.config_mode_tut_beast_title,
            )
        } else {
            stringResource(Res.string.config_mode_tut_title)
        },
        description = if (isBeastMode) {
            stringResource(Res.string.config_mode_tut_beast_desc)
        } else {
            stringResource(Res.string.config_mode_tut_desc)
        },
    )
}

@Composable
private fun PumpConfigPanel(weight: Float, onWeightChange: (Float) -> Unit, prWeight: Float? = null, stepKg: Float = 2.5f) {
    WeightStepper(weight = weight, onWeightChange = onWeightChange, label = stringResource(Res.string.starting_weight), prWeight = prWeight, step = stepKg)
    ModeInfoCard(
        title = stringResource(Res.string.config_mode_pump_title),
        description = stringResource(Res.string.config_mode_pump_desc),
    )
}

@Composable
private fun EccentricConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    eccentricPercent: Int,
    onEccentricPercentChange: (Int) -> Unit,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
) {
    WeightStepper(weight = weight, onWeightChange = onWeightChange, label = stringResource(Res.string.starting_weight), prWeight = prWeight, step = stepKg)
    EccentricSlider(
        percent = eccentricPercent,
        onPercentChange = onEccentricPercentChange,
        label = stringResource(Res.string.eccentric_overload),
    )
}

@Composable
private fun EchoConfigPanel(
    echoLevel: EchoLevel,
    onEchoLevelChange: (EchoLevel) -> Unit,
    eccentricPercent: Int,
    onEccentricPercentChange: (Int) -> Unit,
) {
    // Echo Level selector
    Column {
        Text(
            text = stringResource(Res.string.config_echo_level),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium),
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            listOf(EchoLevel.HARD, EchoLevel.HARDER, EchoLevel.HARDEST, EchoLevel.EPIC).forEach { level ->
                val isSelected = level == echoLevel

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Spacing.small),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    onClick = { onEchoLevelChange(level) },
                ) {
                    Text(
                        text = level.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    EccentricSlider(
        percent = eccentricPercent,
        onPercentChange = onEccentricPercentChange,
        label = stringResource(Res.string.eccentric_load),
    )
}

@Composable
private fun EccentricSlider(percent: Int, onPercentChange: (Int) -> Unit, label: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 100f..150f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "100%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "150%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeInfoCard(title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
