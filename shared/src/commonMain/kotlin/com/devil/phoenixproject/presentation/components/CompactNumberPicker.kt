package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.devil.phoenixproject.util.KmpUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact Number Picker - Platform-specific number picker
 *
 * Platform implementations:
 * - Android: Native Android NumberPicker wheel
 * - iOS: Native UIPickerView wheel
 *
 * Supports both integer and fractional values with configurable step size.
 *
 * @param value Current value
 * @param onValueChange Callback when value changes
 * @param range Valid range for the picker
 * @param modifier Compose modifier
 * @param label Label displayed above the picker
 * @param suffix Unit suffix (e.g., "kg", "lbs")
 * @param step Step increment between values
 */
@Composable
expect fun CompactNumberPicker(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    label: String = "",
    suffix: String = "",
    step: Float = 1.0f,
)

/**
 * Overload for backward compatibility with Int values
 */
@Composable
expect fun CompactNumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    label: String = "",
    suffix: String = "",
)

internal fun formatCompactNumberPickerValue(value: Float, step: Float): String {
    val decimals = decimalPlacesForStep(step)
    val formatted = if (decimals <= 0) {
        if (value % 1.0f == 0f) {
            value.toInt().toString()
        } else {
            KmpUtils.formatFloat(value, 2).trimEnd('0').trimEnd('.')
        }
    } else {
        KmpUtils.formatFloat(value, decimals).trimEnd('0').trimEnd('.')
    }

    return if (formatted == "-0") "0" else formatted
}

private fun decimalPlacesForStep(step: Float): Int {
    if (step <= 0f) return 0

    var scaled = step
    for (decimals in 0..4) {
        if (abs(scaled.roundToInt() - scaled) < 0.0001f) {
            return decimals
        }
        scaled *= 10f
    }

    return 4
}
