package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compact Number Picker - Platform-specific number picker
 *
 * Platform implementations:
 * - Android: Native Android NumberPicker wheel
 * - iOS: Native UIPickerView wheel
 * - Desktop: Button-based with editable text field
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
    step: Float = 1.0f
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
    suffix: String = ""
)
