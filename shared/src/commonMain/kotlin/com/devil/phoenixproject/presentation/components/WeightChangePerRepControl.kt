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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.abs

private const val MAX_PROGRESS_KG_DISPLAY = 3f
private const val MAX_PROGRESS_LB_DISPLAY = 6f

/**
 * Display-unit-aware signed per-rep progression/regression control.
 *
 * The slider operates in the user's display unit for predictable touch/remote steps,
 * then reports the selected value back in kilograms for WorkoutParameters storage.
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
    val maxProgression = if (weightUnit == WeightUnit.LB) MAX_PROGRESS_LB_DISPLAY else MAX_PROGRESS_KG_DISPLAY
    val clampedDisplay = kgToDisplay(valueKg, weightUnit).coerceIn(-maxProgression, maxProgression)
    val clampedValueKg = displayToKg(clampedDisplay, weightUnit)
    val valueText = formatProgressionPerRep(clampedDisplay, weightUnit)

    // Notify the parent if the incoming valueKg was out of the clamp range.
    // SideEffect runs after every successful composition — using it instead of
    // LaunchedEffect avoids the extra coroutine launch and makes the semantics
    // explicit: this is a synchronous side-effect, not async work.
    SideEffect {
        if (abs(valueKg - clampedValueKg) > 0.0001f) {
            onValueChangeKg(clampedValueKg)
        }
    }

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
