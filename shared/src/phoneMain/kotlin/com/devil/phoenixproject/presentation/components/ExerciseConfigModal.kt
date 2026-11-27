package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.percentLabel
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

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
 * @param planningMaxWeightPerCableKg Per-cable ceiling for the weight stepper, in kg.
 *   Callers supply [com.devil.phoenixproject.util.CommandLimits.planningMaxWeightPerCableKg]
 *   for the last connected trainer. Nothing is commanded from this modal.
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
    // No default: a caller that forgot it would silently get the widest (Trainer+) ceiling.
    planningMaxWeightPerCableKg: Float,
    weightUnit: WeightUnit = WeightUnit.KG,
    kgToDisplay: (Float, WeightUnit) -> Float = { kg, unit ->
        WeightDisplayFormatter.toPerCableDisplayWeight(kg, unit)
    },
    displayToKg: (Float, WeightUnit) -> Float = { display, unit ->
        when (unit) {
            WeightUnit.KG -> display
            WeightUnit.LB -> display / UnitConverter.KG_TO_LB
        }
    },
    formatWeight: (Float, WeightUnit) -> String = { kg, unit ->
        val display = kgToDisplay(kg, unit)
        "${WeightDisplayFormatter.formatDisplayNumber(display)} ${unit.name.lowercase()}"
    },
) {
    var config by remember { mutableStateOf(initialConfig) }
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    PhoenixModalDialogFrame(
        title = exerciseName,
        onDismiss = onDismiss,
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(Res.string.action_cancel),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Button(
                onClick = { onConfirm(config) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(Res.string.action_apply),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        // Template metadata banner
        ExerciseConfigMetadataRow(
            templateSets = templateSets,
            templateReps = templateReps,
            oneRepMaxKg = oneRepMaxKg,
            weightUnit = weightUnit,
            formatWeight = formatWeight,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Mode selector
        ModeSelector(
            selectedMode = config.mode,
            onModeSelected = { newMode ->
                config = config.copy(mode = newMode)
            },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Mode-specific config panel
        AnimatedContent(
            targetState = config.mode,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn() + slideInVertically { it / 4 } togetherWith
                        fadeOut() + slideOutVertically { -it / 4 }
                }
            },
            label = "mode_panel",
        ) { mode ->
            Column(
                modifier = Modifier.padding(top = Spacing.small),
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
                        planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
                        weightUnit = weightUnit,
                        kgToDisplay = kgToDisplay,
                        displayToKg = displayToKg,
                        formatWeight = formatWeight,
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
                        planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
                        weightUnit = weightUnit,
                        kgToDisplay = kgToDisplay,
                        displayToKg = displayToKg,
                        formatWeight = formatWeight,
                    )

                    ProgramMode.Pump -> PumpConfigPanel(
                        weight = config.weightPerCableKg,
                        onWeightChange = { config = config.copy(weightPerCableKg = it) },
                        prWeight = prWeight,
                        stepKg = weightStepKg,
                        planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
                        weightUnit = weightUnit,
                        kgToDisplay = kgToDisplay,
                        displayToKg = displayToKg,
                        formatWeight = formatWeight,
                    )

                    ProgramMode.EccentricOnly -> EccentricConfigPanel(
                        weight = config.weightPerCableKg,
                        onWeightChange = { config = config.copy(weightPerCableKg = it) },
                        eccentricPercent = config.eccentricLoadPercent,
                        onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) },
                        prWeight = prWeight,
                        stepKg = weightStepKg,
                        planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
                        weightUnit = weightUnit,
                        kgToDisplay = kgToDisplay,
                        displayToKg = displayToKg,
                        formatWeight = formatWeight,
                    )

                    ProgramMode.Echo -> EchoConfigPanel(
                        echoLevel = config.echoLevel,
                        onEchoLevelChange = { config = config.copy(echoLevel = it) },
                        eccentricPercent = config.eccentricLoadPercent,
                        onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) },
                    )

                    ProgramMode.TUTBeast -> Unit // Mapped to TUT above; unreachable but required for exhaustiveness
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseConfigMetadataRow(
    templateSets: Int,
    templateReps: Int?,
    oneRepMaxKg: Float?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.small),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.small, vertical = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            MetaChip(label = stringResource(Res.string.config_meta_sets), value = templateSets.toString())
            MetaChip(
                label = stringResource(Res.string.config_meta_reps),
                value = templateReps?.toString() ?: stringResource(Res.string.label_amrap),
            )
            oneRepMaxKg?.let {
                MetaChip(label = stringResource(Res.string.config_meta_1rm), value = formatWeight(it, weightUnit))
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
private fun OldSchoolConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    planningMaxWeightPerCableKg: Float,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
        weightUnit = weightUnit,
        kgToDisplay = kgToDisplay,
        displayToKg = displayToKg,
        formatWeight = formatWeight,
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
    planningMaxWeightPerCableKg: Float,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
        weightUnit = weightUnit,
        kgToDisplay = kgToDisplay,
        displayToKg = displayToKg,
        formatWeight = formatWeight,
    )

    // Beast Mode Toggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = isBeastMode,
                role = Role.Switch,
                onValueChange = onBeastModeChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.small),
        ) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isBeastMode,
            onCheckedChange = null,
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
private fun PumpConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    planningMaxWeightPerCableKg: Float,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
        weightUnit = weightUnit,
        kgToDisplay = kgToDisplay,
        displayToKg = displayToKg,
        formatWeight = formatWeight,
    )
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
    planningMaxWeightPerCableKg: Float,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
        weightUnit = weightUnit,
        kgToDisplay = kgToDisplay,
        displayToKg = displayToKg,
        formatWeight = formatWeight,
    )
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
    EchoLevelPillSelector(
        selectedLevel = echoLevel,
        onLevelChange = onEchoLevelChange,
    )

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
                text = percentLabel(percent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        ExpressiveSlider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 100f..150f,
            steps = 9,
            remoteStep = 1f,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = percentLabel(100), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = percentLabel(150), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
