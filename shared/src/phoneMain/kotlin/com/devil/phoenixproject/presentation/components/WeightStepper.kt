package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.presentation.util.currentEffectiveWeightTextScale
import com.devil.phoenixproject.presentation.util.scaledForWeightValue
import com.devil.phoenixproject.presentation.util.scaledSelectorButtonSizeDp
import com.devil.phoenixproject.presentation.util.shouldSeparateWeightStepperValueRow
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Weight input with +/- stepper buttons.
 * Accepts per-cable bounds and steps in kg and formats/displays in [weightUnit].
 *
 * @param weight Current weight value in kg
 * @param onWeightChange Callback when weight is changed (in kg)
 * @param modifier Modifier for the composable
 * @param minWeight Minimum allowed weight in kg (default 0)
 * @param maxWeight Maximum allowed weight per cable in kg (default 100kg)
 * @param step Weight increment/decrement step in kg (default 2.5kg).
 *   Note: WeightStepper does NOT apply [UnitConverter.roundToMachineIncrement] after stepping.
 *   Callers that transmit the result over BLE should round to the machine's 0.5kg increment
 *   before sending, to avoid sub-increment values reaching the hardware.
 * @param label Label text displayed above the control
 * @param prWeight Optional PR weight in kg to show percentage indicator
 */
@Composable
fun WeightStepper(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minWeight: Float = 0f,
    maxWeight: Float = 100f, // Per cable max (V-Form: 100kg, Trainer+: 110kg)
    step: Float = 2.5f,
    label: String = "Weight",
    prWeight: Float? = null,
    prPhaseLabel: String? = null,
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
    Column(modifier = modifier) {
        // Header row with label and PR indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            // PR Indicator shown when prWeight is provided
            if (prWeight != null) {
                PRIndicator(
                    currentWeight = weight,
                    prWeight = prWeight,
                    phaseLabel = prPhaseLabel,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        // Issue #1367 SD1/SD7/SD9: scale the numeric value only (unit label + total stay
        // supporting text); when the effective rendered scale demands it (E > 1.5) the
        // value moves to its own row and the +/- controls stay on one reachable row.
        val weightTextScale = LocalWeightTextScale.current
        val effectiveScale = currentEffectiveWeightTextScale(weightTextScale)
        val separateValueRow = shouldSeparateWeightStepperValueRow(effectiveScale)
        val valueStyle = MaterialTheme.typography.headlineMedium.scaledForWeightValue(weightTextScale)
        val stepperButtonSize = scaledSelectorButtonSizeDp(48f, effectiveScale).dp
        val displayWeight = kgToDisplay(weight, weightUnit)
        val displayStep = kgToDisplay(step, weightUnit)

        val valueText: @Composable () -> Unit = {
            Text(
                text = WeightDisplayFormatter.formatDisplayNumber(displayWeight),
                style = valueStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val unitLabel: @Composable () -> Unit = {
            val unitPerCableText = when (weightUnit) {
                WeightUnit.KG -> stringResource(Res.string.weight_kg_per_cable)
                WeightUnit.LB -> "${stringResource(Res.string.label_lbs)} · ${stringResource(Res.string.label_per_cable).lowercase()}"
            }
            Text(
                text = unitPerCableText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val minusButton: @Composable () -> Unit = {
            FilledTonalIconButton(
                onClick = {
                    val steppedDisplay = (displayWeight - displayStep).coerceAtLeast(kgToDisplay(minWeight, weightUnit))
                    val newWeight = displayToKg(steppedDisplay, weightUnit).coerceIn(minWeight, maxWeight)
                    onWeightChange(if (newWeight <= minWeight + 0.01f) minWeight else newWeight)
                },
                enabled = weight > minWeight,
                modifier = Modifier.size(stepperButtonSize),
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(Res.string.cd_decrease_weight),
                )
            }
        }
        val plusButton: @Composable () -> Unit = {
            FilledTonalIconButton(
                onClick = {
                    val steppedDisplay = (displayWeight + displayStep).coerceAtMost(kgToDisplay(maxWeight, weightUnit))
                    val newWeight = displayToKg(steppedDisplay, weightUnit).coerceIn(minWeight, maxWeight)
                    onWeightChange(if (newWeight >= maxWeight - 0.01f) maxWeight else newWeight)
                },
                enabled = weight < maxWeight,
                modifier = Modifier.size(stepperButtonSize),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(Res.string.cd_increase_weight),
                )
            }
        }
        val containerModifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.medium,
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                MaterialTheme.shapes.medium,
            )
            .padding(Spacing.extraSmall)

        if (separateValueRow) {
            Column(
                modifier = containerModifier,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    minusButton()
                    plusButton()
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    valueText()
                    unitLabel()
                }
            }
        } else {
            Row(
                modifier = containerModifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                minusButton()

                // Weight display
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    valueText()
                    unitLabel()
                }

                plusButton()
            }
        }

        // Total weight for 2 cables indicator
        Spacer(modifier = Modifier.height(Spacing.small))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                val totalText = when (weightUnit) {
                    WeightUnit.KG -> stringResource(
                        Res.string.weight_total_two_cables,
                        WeightDisplayFormatter.formatTwoCableTotalWeight(
                            weightPerCableKg = weight,
                            unit = WeightUnit.KG,
                        ),
                    )
                    WeightUnit.LB -> stringResource(
                        Res.string.weight_total_both_cables,
                        formatWeight(weight * 2f, weightUnit),
                    )
                }
                Text(
                    text = totalText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
