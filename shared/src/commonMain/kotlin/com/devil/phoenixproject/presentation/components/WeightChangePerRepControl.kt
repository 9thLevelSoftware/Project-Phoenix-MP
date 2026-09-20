package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.CommandLimits
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.floor

/**
 * Display-unit-aware signed per-rep progression/regression control.
 *
 * The slider operates in the user's display unit for predictable touch/remote steps,
 * then reports the selected value back in kilograms for WorkoutParameters storage.
 *
 * The range is derived from [CommandLimits.MAX_PROGRESSION_KG] rather than being a third
 * copy of the bound, and is floored to the slider's 0.1 step so the display unit can never
 * round *up* past the kg bound (6.6 lb is 2.99 kg).
 *
 * The control is **display-only for out-of-range input**: an incoming value beyond the
 * range is rendered clamped but is NOT written back to the caller. A programmatic
 * coercion used to be reported through [onValueChangeKg], which is the user-edit handler:
 * it latched "user adjusted during rest" (so the next set inherited the previous set's
 * weight and reps) and pre-empted the capped notice by rewriting the parameters before
 * the command resolved. [CommandLimits.resolve] is the only thing that changes what is
 * commanded; [onValueChangeKg] now only ever reports a real user interaction.
 */
@Composable
fun WeightChangePerRepControl(
    valueKg: Float,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    onValueChangeKg: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Weight Change / Rep",
) {
    val maxProgression = floor(kgToDisplay(CommandLimits.MAX_PROGRESSION_KG, weightUnit) * 10f) / 10f
    val clampedDisplay = kgToDisplay(valueKg, weightUnit).coerceIn(-maxProgression, maxProgression)
    val valueText = formatProgressionPerRep(clampedDisplay, weightUnit)

    val accentColor = when {
        clampedDisplay > 0f -> MaterialTheme.colorScheme.primary
        clampedDisplay < 0f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.semantics {
            contentDescription = "$label, $valueText"
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        ExpressiveSlider(
            value = clampedDisplay,
            onValueChange = { displayValue ->
                val selectedDisplay = displayValue.coerceIn(-maxProgression, maxProgression)
                onValueChangeKg(displayToKg(selectedDisplay, weightUnit))
            },
            valueRange = -maxProgression..maxProgression,
            remoteStep = 0.1f,
            trackColor = accentColor,
            thumbColor = accentColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun formatProgressionPerRep(displayValue: Float, weightUnit: WeightUnit): String {
    val sign = when {
        displayValue > 0f -> "+"
        else -> ""
    }
    val unit = weightUnit.name.lowercase()
    return "$sign${UnitConverter.formatDecimal(displayValue)} $unit/rep"
}
