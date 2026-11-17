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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.devil.phoenixproject.domain.model.percentLabel
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.powerloss.PowerLossConfigDraftState
import com.devil.phoenixproject.presentation.powerloss.PowerLossUiCopy
import com.devil.phoenixproject.ui.theme.Spacing
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
    // Issue #1392 SD4: dynamic Max-working-reps bound from the accepted execution plan
    // (ProtocolRepBounds.maxRepresentableWorkingReps — PROGRAM: 254 minus warm-ups,
    // Echo: its validator bound). This default is the un-warmed PROGRAM ceiling only;
    // callers with a derived plan MUST pass the real bound — never assume 254 for Echo.
    maxAllowedWorkingReps: Int = 254,
) {
    var config by remember { mutableStateOf(initialConfig) }

    // Issue #1392 — Set target edits run through the pure draft state machine
    // (validate-everything, atomic Apply; Cancel/discard restores the original).
    var powerDraft by remember {
        mutableStateOf(
            PowerLossConfigDraftState.seededFrom(
                config = initialConfig.powerLoss.copy(
                    savedNominalReps = initialConfig.powerLoss.savedNominalReps ?: templateReps,
                ),
                maxAllowedWorkingReps = maxAllowedWorkingReps,
                protocolFamily = PowerLossProtocolFamily.of(initialConfig.mode, isJustLiftExecutionStyle = false),
            ),
        )
    }
    // Program-mode switches move the capability gate (ui-spec §3).
    val currentProtocolFamily = PowerLossProtocolFamily.of(config.mode, isJustLiftExecutionStyle = false)
    LaunchedEffect(currentProtocolFamily) {
        if (powerDraft.protocolFamily != currentProtocolFamily) {
            powerDraft = powerDraft.copy(protocolFamily = currentProtocolFamily)
        }
    }

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
                                planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
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
                            )

                            ProgramMode.Pump -> PumpConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                prWeight = prWeight,
                                stepKg = weightStepKg,
                                planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
                            )

                            ProgramMode.EccentricOnly -> EccentricConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                eccentricPercent = config.eccentricLoadPercent,
                                onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) },
                                prWeight = prWeight,
                                stepKg = weightStepKg,
                                planningMaxWeightPerCableKg = planningMaxWeightPerCableKg,
                            )

                            ProgramMode.Echo -> EchoConfigPanel(
                                echoLevel = config.echoLevel,
                                onEchoLevelChange = { config = config.copy(echoLevel = it) },
                                eccentricPercent = config.eccentricLoadPercent,
                                onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) },
                            )

                            ProgramMode.TUTBeast -> Unit // Mapped to TUT at line 114; unreachable but required for exhaustiveness
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Issue #1392 — Set target (power-loss) section, ui-spec §3.
                PowerLossSetTargetSection(
                    draft = powerDraft,
                    onDraftChange = { powerDraft = it },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedButton(
                        onClick = {
                            // Cancel: discard the unapplied draft (ui-spec §3).
                            powerDraft = powerDraft.cancel()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }

                    Button(
                        onClick = {
                            // Apply validates every effective field and commits
                            // atomically to ExerciseConfig.powerLoss.
                            val committed = powerDraft.apply()
                            powerDraft = committed
                            onConfirm(config.copy(powerLoss = committed.committed))
                        },
                        enabled = powerDraft.canApply,
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
    // workout-setup-17: primaryContainer tint brings in brand colour and visually separates
    // the header from the surfaceContainer modal body. titleLarge aligns with other modal
    // headers (CompletedCard).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(Spacing.medium),
    ) {
        Text(
            text = exerciseName,
            style = MaterialTheme.typography.titleLarge,
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
private fun OldSchoolConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    planningMaxWeightPerCableKg: Float,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
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
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
    )

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
private fun PumpConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    planningMaxWeightPerCableKg: Float,
    prWeight: Float? = null,
    stepKg: Float = 2.5f,
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
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
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = stringResource(Res.string.starting_weight),
        prWeight = prWeight,
        step = stepKg,
        maxWeight = planningMaxWeightPerCableKg,
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

// ==================== SET TARGET (issue #1392 power-loss) ====================

/**
 * `Set target` section (ui-spec §3): Rep target | Power-loss target equal-width
 * choices, the POWER expanded config on the pure [PowerLossConfigDraftState]
 * machine, and the local-data disclosure. All copy comes from PowerLossUiCopy;
 * edits stay in the draft until Apply commits atomically.
 */
@Composable
private fun PowerLossSetTargetSection(
    draft: PowerLossConfigDraftState,
    onDraftChange: (PowerLossConfigDraftState) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(
            text = PowerLossUiCopy.SECTION_HEADING,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        // Two equal-width target choices (labels wrap, never truncate).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            TargetModeButton(
                label = PowerLossUiCopy.REP_TARGET_LABEL,
                selected = draft.effectiveTargetMode == TargetMode.REP_TARGET,
                onClick = { onDraftChange(draft.selectTargetMode(TargetMode.REP_TARGET)) },
                modifier = Modifier.weight(1f),
            )
            TargetModeButton(
                label = PowerLossUiCopy.POWER_TARGET_LABEL,
                selected = draft.effectiveTargetMode == TargetMode.POWER,
                onClick = { onDraftChange(draft.selectTargetMode(TargetMode.POWER)) },
                modifier = Modifier.weight(1f),
            )
        }

        if (!draft.capabilityAvailable) {
            SectionNote(PowerLossUiCopy.CAPABILITY_UNAVAILABLE)
            SectionNote(PowerLossUiCopy.CAPABILITY_UNAVAILABLE_SECONDARY)
        }
        if (draft.dormantPowerConfig) {
            SectionNote(PowerLossUiCopy.INCOMPATIBLE_SAVED_BANNER)
        }

        if (!draft.powerSectionExpanded) {
            SectionNote(PowerLossUiCopy.REP_HELPER)
            draft.committed.savedNominalReps?.let { saved ->
                SectionNote(PowerLossUiCopy.savedRepReadback(saved))
            }
        } else {
            Text(
                text = PowerLossUiCopy.POWER_LEAD,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SectionNote(PowerLossUiCopy.POWER_HELP)

            // Threshold presets — SD6 nonbreaking labels (`10%`, `Custom` never split).
            Text(
                text = PowerLossUiCopy.THRESHOLD_LABEL,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                draft.presetChips.forEach { chip ->
                    FilterChip(
                        selected = chip.selected,
                        onClick = { onDraftChange(draft.selectPreset(chip.preset)) },
                        label = { Text(chip.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (draft.customSelected) {
                OutlinedTextField(
                    value = draft.customThresholdInput,
                    onValueChange = { onDraftChange(draft.updateCustomThreshold(it)) },
                    label = { Text(PowerLossUiCopy.CUSTOM_THRESHOLD_LABEL) },
                    isError = draft.customThresholdError != null,
                    supportingText = {
                        Text(draft.customThresholdError ?: PowerLossUiCopy.CUSTOM_THRESHOLD_HELPER)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (draft.highThresholdCautionVisible) {
                // Warning treatment only; never disables Apply (ui-spec §3.4).
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Spacing.medium),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(modifier = Modifier.padding(Spacing.medium)) {
                        Text(
                            text = PowerLossUiCopy.HIGH_THRESHOLD_TITLE,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(Spacing.extraSmall))
                        Text(
                            text = PowerLossUiCopy.HIGH_THRESHOLD_BODY,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.maxRepsInput,
                onValueChange = { onDraftChange(draft.updateMaxReps(it)) },
                label = { Text(PowerLossUiCopy.MAX_WORKING_REPS_LABEL) },
                isError = draft.maxRepsError != null,
                supportingText = {
                    // SD4: the error bound is dynamic (`Enter 2–{maxAllowed} working reps.`).
                    Text(draft.maxRepsError ?: PowerLossUiCopy.MAX_WORKING_REPS_HELPER)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = PowerLossUiCopy.TWO_CONSECUTIVE_LABEL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = draft.draft.requireTwoConsecutive,
                    onCheckedChange = { onDraftChange(draft.setRequireTwoConsecutive(it)) },
                )
            }
            // SD1 helper copy; on/off text shown as well as the switch state.
            SectionNote(
                if (draft.draft.requireTwoConsecutive) {
                    PowerLossUiCopy.TWO_CONSECUTIVE_ON_HELPER
                } else {
                    PowerLossUiCopy.TWO_CONSECUTIVE_OFF_HELPER
                },
            )

            // Non-toggleable safeguard — never an optional ROM switch (ui-spec §3.7).
            SectionNote(PowerLossUiCopy.SHORT_ROM_SAFEGUARD)
            SectionNote(PowerLossUiCopy.SHORT_ROM_SAFEGUARD_HELP)

            if (draft.protocolFamily == PowerLossProtocolFamily.ECHO) {
                SectionNote(PowerLossUiCopy.ECHO_NOTE)
            }

            Text(
                text = draft.nearLimitCueReadback,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionNote(PowerLossUiCopy.NEAR_LIMIT_CUE_HELP)
        }

        SectionNote(PowerLossUiCopy.LOCAL_DATA_DISCLOSURE)
    }

    // Unsupported-mode confirmation (ui-spec §3); POWER settings stay dormant.
    draft.pendingModeConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { onDraftChange(draft.dismissModeConfirmation()) },
            title = { Text(confirmation.title) },
            text = { Text(confirmation.body) },
            confirmButton = {
                TextButton(onClick = { onDraftChange(draft.confirmUseRepTarget()) }) {
                    Text(confirmation.confirmAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { onDraftChange(draft.dismissModeConfirmation()) }) {
                    Text(confirmation.cancelAction)
                }
            },
        )
    }
}

@Composable
private fun TargetModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
