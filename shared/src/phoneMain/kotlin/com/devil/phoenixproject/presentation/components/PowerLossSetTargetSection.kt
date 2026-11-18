package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.powerloss.PowerLossConfigDraftState
import com.devil.phoenixproject.presentation.powerloss.PowerLossTargetReadbackUi
import com.devil.phoenixproject.presentation.powerloss.PowerLossUiCopy
import com.devil.phoenixproject.ui.theme.Spacing

// ==================== SET TARGET (issue #1392 power-loss) ====================

/**
 * `Set target` section (ui-spec §3): Rep target | Power-loss target equal-width
 * choices, the POWER expanded config on the pure [PowerLossConfigDraftState]
 * machine, and the local-data disclosure. All copy comes from PowerLossUiCopy;
 * edits stay in the draft until Apply commits atomically.
 *
 * Shared by `ExerciseConfigModal` (template configuration), `ExerciseEditBottomSheet`
 * (routine/single-exercise editing) and any ad-hoc configuration surface — one
 * implementation so the SD6 copy can never drift between surfaces.
 */
@Composable
internal fun PowerLossSetTargetSection(
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

// ==================== TARGET READBACK (issue #1392 P5) ====================

/**
 * SD6 saved-target facts: `Saved reps: 12` with the adjacent
 * `Power-loss target active` badge. Renders nothing when there is nothing to say.
 */
@Composable
internal fun PowerLossSavedTargetFacts(
    savedNominalReps: Int?,
    powerActive: Boolean,
) {
    if (savedNominalReps == null && !powerActive) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        savedNominalReps?.let { saved ->
            Text(
                text = PowerLossUiCopy.savedRepsHeader(saved),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (powerActive) {
            Text(
                text = PowerLossUiCopy.POWER_ACTIVE_BADGE,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Compact set-ready / Just Lift target readback (ui-spec §2): `Power-loss target
 * · 20% · Max 20 working reps` plus the SD6 `Saved reps: 12` fact with the
 * adjacent `Power-loss target active` badge. Renders nothing for plain REP sets.
 * Active-set usage passes `locked` so the during-set copy reads
 * `Target locked for this set.` — editing is only available before the next set.
 */
@Composable
internal fun PowerLossTargetReadbackCard(readback: PowerLossTargetReadbackUi) {
    if (!readback.visible) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.medium),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            readback.targetLockedLine?.let { lockedLine ->
                Text(
                    text = lockedLine,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            readback.lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
